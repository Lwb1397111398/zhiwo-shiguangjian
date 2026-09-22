package com.zhiwo.shiguangjian.data.organizeops

import androidx.room.withTransaction
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.AppDatabase
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.db.entity.OrganizeOpEntity
import com.zhiwo.shiguangjian.data.repository.MemoryRepository
import com.zhiwo.shiguangjian.data.repository.RecordRepository
import com.zhiwo.shiguangjian.data.repository.ReviewRepository
import kotlinx.coroutines.flow.first

data class BatchSummary(
    val executed: Int,      // 实际执行成功（含幂等达成）
    val stale: Int,         // 源数据变化，需重新生成提案
    val failed: Int,        // 执行失败（可重试）
    val blocked: Int        // 前置依赖未成功，未执行
) {
    val total get() = executed + stale + failed + blocked
}

/** 事务内重读发现现场已不等于提案时 → 抛出，整笔回滚并把提案标 STALE（不是 FAILED） */
internal class OpStaleException(reason: String) : Exception(reason)

/**
 * Apply 阶段执行器：逐条独立事务执行，不包大事务；
 * 每条执行前先过 [OpValidator]（幂等 + STALE）与依赖检查（防"源删了记忆没存"），
 * 真正动手前**在事务内再重读校验一次**（重读不一致即回滚标 STALE，绝不静默执行旧提案），
 * 执行后立即回写状态，进程中断可恢复。
 *
 * 记忆侧语义定死：整理动作只置 `superseded`，唯一允许物理删记忆的是显式 DELETE_MEMORY。
 */
class OrganizeOpExecutor(
    private val database: AppDatabase,
    private val memoryRepo: MemoryRepository,
    private val recordRepo: RecordRepository,
    private val reviewRepo: ReviewRepository
) {

    private val opDao get() = database.organizeOpDao()

    /** 执行单条操作，返回终态（APPLIED / STALE / FAILED / BLOCKED） */
    suspend fun applyOp(op: OrganizeOpEntity): String {
        check(op.status == OpStatus.PENDING || op.status == OpStatus.BLOCKED) {
            "only PENDING/BLOCKED ops can be applied"
        }
        val now = System.currentTimeMillis()

        // 依赖检查：所有 dependsOn 必须已 APPLIED，否则 BLOCKED 且不执行
        // forceDelete（用户双重确认"只删不存"）跳过依赖检查，哈希/存在性校验照常
        val dependsOn = if (isForceDelete(op)) emptyList() else OpPayloads.decodeDependsOn(op.dependsOn)
        if (dependsOn.isNotEmpty()) {
            val statuses = dependsOn.mapNotNull { id ->
                opDao.getOpById(id)?.let { id to it.status }
            }.toMap()
            val unmet = DependencyChecker.evaluate(dependsOn, statuses)
            if (unmet != null) {
                return finish(op.copy(status = OpStatus.BLOCKED, error = "依赖的操作未成功：$unmet", updatedAt = now))
                    .let { OpStatus.BLOCKED }
            }
        }

        val snapshot = buildSnapshot()
        return when (val decision = OpValidator.decide(op.type, op.payloadJson, snapshot)) {
            is Decision.Execute -> try {
                executeInTransaction(op)
                finish(op.copy(status = OpStatus.APPLIED, appliedAt = now, error = null, updatedAt = now))
                OpStatus.APPLIED
            } catch (e: OpStaleException) {
                finish(op.copy(status = OpStatus.STALE, error = e.message, updatedAt = now))
                OpStatus.STALE
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                finish(op.copy(status = OpStatus.FAILED, error = e.message ?: "执行失败", updatedAt = now))
                OpStatus.FAILED
            }
            is Decision.AlreadyApplied ->
                finish(op.copy(status = OpStatus.APPLIED, appliedAt = now, error = null, updatedAt = now))
                    .let { OpStatus.APPLIED }
            is Decision.Stale ->
                finish(op.copy(status = OpStatus.STALE, error = decision.reason, updatedAt = now))
                    .let { OpStatus.STALE }
            is Decision.Invalid ->
                finish(op.copy(status = OpStatus.FAILED, error = decision.reason, updatedAt = now))
                    .let { OpStatus.FAILED }
        }
    }

    /**
     * 按 op id 集合执行（可跨批次）：非终态的项按
     * EVOLVE → MERGE → SPLIT → DELETE_MEMORY → SAVE → DELETE_SOURCE 顺序逐条独立执行。
     * [onlyChecked] = true 时只跑勾选的（「全部同意」）；单条「继续执行/重试」传 false，尊重用户点击。
     */
    suspend fun applyOps(opIds: Collection<String>, onlyChecked: Boolean = true): BatchSummary {
        if (opIds.isEmpty()) return BatchSummary(0, 0, 0, 0)
        val ops = opDao.getOpsByIds(opIds.toList())
        val plan = OpValidator.planBatch(ops.map {
            BatchOp(it.id, it.type, it.status, it.checked, it.payloadJson, OpPayloads.decodeDependsOn(it.dependsOn))
        }, onlyChecked = onlyChecked)
        var executed = 0; var stale = 0; var failed = 0; var blocked = 0
        for (item in plan) {
            // 双检：执行前重读状态，防止并发/重复触发已处理的 op（幂等保障之一）
            val fresh = opDao.getOpById(item.id) ?: continue
            if (fresh.status != OpStatus.PENDING && fresh.status != OpStatus.BLOCKED) continue
            when (applyOp(fresh)) {
                OpStatus.APPLIED -> executed++
                OpStatus.STALE -> stale++
                OpStatus.BLOCKED -> blocked++
                else -> failed++
            }
        }
        return BatchSummary(executed = executed, stale = stale, failed = failed, blocked = blocked)
    }

    /** 用户明示"只删不存"的标记：payload 内 forceDelete=true */
    private fun isForceDelete(op: OrganizeOpEntity): Boolean =
        op.type == OpType.DELETE_SOURCE &&
            OpPayloads.decodeDeleteSource(op.payloadJson)?.forceDelete == true

    private suspend fun executeInTransaction(op: OrganizeOpEntity) {
        val nowIso = DateFormats.nowDateTimeIso()
        database.withTransaction {
            // 事务内重读校验：现场与提案不一致就整笔回滚（不静默执行旧提案）
            revalidateInTransaction(op)
            when (op.type) {
                OpType.SAVE_MEMORY -> {
                    val payload = OpPayloads.decodeSaveMemory(op.payloadJson)
                        ?: throw IllegalStateException("载荷解析失败")
                    memoryRepo.insertMemory(
                        MemoryEntity(
                            content = payload.content.trim(),
                            source = "organize",
                            createdAt = nowIso,                 // 写库时间：排序与上限淘汰都看这个
                            updatedAt = nowIso,
                            occurredAt = sourceDate(payload.sourceDate, nowIso),  // 来源日期
                            status = "active"
                        )
                    )
                }
                OpType.EVOLVE_MEMORY -> {
                    val payload = OpPayloads.decodeEvolveMemory(op.payloadJson)
                        ?: throw IllegalStateException("载荷解析失败")
                    val old = requireActive(payload.memoryId, null)
                    if (old.content.trim() != payload.oldText.trim()) {
                        throw OpStaleException("源记忆内容与提案时不一致")
                    }
                    val newId = memoryRepo.insertMemory(
                        MemoryEntity(
                            content = payload.newText.trim(),
                            source = "organize",
                            createdAt = nowIso,                 // 写库时间：排序与上限淘汰都看这个
                            updatedAt = nowIso,
                            occurredAt = old.occurredOrCreated(),  // 来源日期跟着旧记忆走
                            status = "active"
                        )
                    )
                    memoryRepo.updateMemory(
                        old.copy(
                            status = "superseded",
                            supersededBy = newId,
                            note = "整理演化：${payload.oldText}",
                            updatedAt = nowIso
                        )
                    )
                }
                OpType.MERGE_MEMORY -> {
                    val payload = OpPayloads.decodeMergeMemory(op.payloadJson)
                        ?: throw IllegalStateException("载荷解析失败")
                    val sources = payload.memoryIds.map { requireActive(it, payload.contentHashes[it]) }
                    val occurredAt = sources.minOf { it.occurredOrCreated() }
                    val newId = memoryRepo.insertMemory(
                        MemoryEntity(
                            content = payload.mergedContent.trim(),
                            source = "organize",
                            createdAt = nowIso,
                            updatedAt = nowIso,
                            occurredAt = occurredAt,   // 合并取来源里最早的日期
                            status = "active"
                        )
                    )
                    for (old in sources) {
                        memoryRepo.updateMemory(
                            old.copy(
                                status = "superseded",
                                supersededBy = newId,
                                note = "整理合并：${payload.mergedContent.trim()}",
                                updatedAt = nowIso
                            )
                        )
                    }
                }
                OpType.SPLIT_MEMORY -> {
                    val payload = OpPayloads.decodeSplitMemory(op.payloadJson)
                        ?: throw IllegalStateException("载荷解析失败")
                    val old = requireActive(payload.memoryId, payload.contentHash)
                    val parts = payload.parts.map { it.trim() }.filter { it.isNotEmpty() }
                    var firstId = 0L
                    parts.forEachIndexed { index, part ->
                        val id = memoryRepo.insertMemory(
                            MemoryEntity(
                                content = part,
                                source = "organize",
                                createdAt = nowIso,
                                updatedAt = nowIso,
                                occurredAt = old.occurredOrCreated(),
                                status = "active"
                            )
                        )
                        if (index == 0) firstId = id
                    }
                    if (firstId == 0L) throw IllegalStateException("拆分未产生任何记忆")
                    memoryRepo.updateMemory(
                        old.copy(
                            status = "superseded",
                            supersededBy = firstId,
                            note = "整理拆分为 ${parts.size} 条",
                            updatedAt = nowIso
                        )
                    )
                }
                OpType.DELETE_MEMORY -> {
                    // 整理链里唯一的物理删除：内容哈希已在重读校验里比对过，对不上根本走不到这里
                    val payload = OpPayloads.decodeDeleteMemory(op.payloadJson)
                        ?: throw IllegalStateException("载荷解析失败")
                    memoryRepo.deleteMemory(payload.memoryId)
                }
                OpType.DELETE_SOURCE -> {
                    val payload = OpPayloads.decodeDeleteSource(op.payloadJson)
                        ?: throw IllegalStateException("载荷解析失败")
                    // 删记录默认保留其任务与打卡史（任务属于用户，不属于某条记录）
                    for (id in payload.recordIds) recordRepo.deleteRecordKeepingTasks(id)
                    for (id in payload.reviewIds) reviewRepo.deleteReview(id)
                }
                else -> throw IllegalStateException("未知操作类型: ${op.type}")
            }
        }
        if (op.type == OpType.SAVE_MEMORY || op.type == OpType.MERGE_MEMORY || op.type == OpType.SPLIT_MEMORY) {
            memoryRepo.enforceMemoryLimit(MEMORY_LIMIT)
        }
    }

    /** 事务内重读：任何一条 op 在现场已不可执行（非 Execute）→ 回滚标 STALE */
    private suspend fun revalidateInTransaction(op: OrganizeOpEntity) {
        val fresh = buildSnapshot()
        when (val decision = OpValidator.decide(op.type, op.payloadJson, fresh)) {
            is Decision.Execute -> Unit
            is Decision.AlreadyApplied -> throw OpStaleException("目标态已达成，无需重复执行")
            is Decision.Stale -> throw OpStaleException(decision.reason)
            is Decision.Invalid -> throw OpStaleException(decision.reason)
        }
    }

    /** 重读目标记忆：必须存在、必须 active、内容哈希必须一致 */
    private suspend fun requireActive(id: Long, expectedHash: String?): MemoryEntity {
        val memory = memoryRepo.getMemoryById(id) ?: throw OpStaleException("记忆#$id 已不存在")
        if (memory.status != "active") throw OpStaleException("记忆#$id 当前状态为 ${memory.status}，不是生效中")
        if (!expectedHash.isNullOrEmpty() && expectedHash != OpPayloads.contentHash(memory.content)) {
            throw OpStaleException("记忆#$id 内容与提案时不一致")
        }
        return memory
    }

    private fun MemoryEntity.occurredOrCreated(): String = occurredAt.ifBlank { createdAt }

    private suspend fun buildSnapshot(): OpSnapshot {
        val memories = memoryRepo.getAllMemories().first()
        val records = recordRepo.getAllRecords().first()
        val reviews = reviewRepo.getAllReviews().first()
        return OpSnapshot(
            memories = memories.map { MemoryRef(it.id, it.content, it.status) },
            records = records.associate {
                it.id to SourceState(OpPayloads.contentHash(it.title, it.content), it.updatedAt)
            },
            reviews = reviews.associate {
                it.id to SourceState(OpPayloads.contentHash(it.content), it.createdAt)
            }
        )
    }

    private suspend fun finish(op: OrganizeOpEntity): OrganizeOpEntity {
        opDao.updateOp(op)
        return op
    }

    /** 来源日期（yyyy-MM-dd）→ ISO 时间串；缺失就用写入时间兜底 */
    private fun sourceDate(date: String, fallback: String): String = try {
        if (date.length == 10) "${date}T00:00:00.000Z" else date.ifBlank { fallback }
    } catch (_: Throwable) {
        fallback
    }

    private companion object {
        const val MEMORY_LIMIT = 100
    }
}
