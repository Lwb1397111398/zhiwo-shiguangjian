package com.zhiwo.shiguangjian.data.organizeops

import com.zhiwo.shiguangjian.data.memory.MemorySimilarity

/**
 * 执行前校验：纯函数，输入操作 + 现场快照，输出执行决策。
 * 幂等、STALE、依赖判定全部在这里，保证可单测。
 */
data class OpSnapshot(
    /** 当前所有记忆（含状态） */
    val memories: List<MemoryRef>,
    /** 源记录现场：id -> (内容哈希, updatedAt) */
    val records: Map<Long, SourceState>,
    /** 源评价现场：id -> (内容哈希, createdAt) */
    val reviews: Map<Long, SourceState>
)

data class SourceState(
    val contentHash: String,
    val updatedAt: String
)

data class MemoryRef(
    val id: Long,
    val content: String,
    val status: String
) {
    /** 内容指纹：提案生成与执行前重读都用同一口径 */
    val hash: String get() = OpPayloads.contentHash(content)
}

sealed class Decision {
    /** 正常执行；[hint] 是给用户的提示（如"与现有记忆疑似重复"），不影响执行 */
    data class Execute(val hint: String = "") : Decision()
    /** 目标态已达成（幂等跳过，标记 APPLIED） */
    object AlreadyApplied : Decision()
    /** 源数据已变化，需重新生成提案 */
    data class Stale(val reason: String) : Decision()
    /** 载荷损坏等不可执行 */
    data class Invalid(val reason: String) : Decision()
}

/** 依赖检查结果（纯函数，输入依赖 id -> 其当前状态） */
object DependencyChecker {
    /**
     * 可执行当且仅当所有依赖均为 APPLIED。
     * 返回 null 表示依赖满足；否则返回未满足的依赖描述（用于 BLOCKED 原因）。
     */
    fun evaluate(dependsOn: List<String>, statuses: Map<String, String>): String? {
        val unmet = dependsOn.filter { statuses[it] != OpStatus.APPLIED }
        if (unmet.isEmpty()) return null
        return unmet.joinToString("、") { id ->
            "${id.take(8)}…${statuses[id]?.let { "($it)" } ?: "(不存在)"}"
        }
    }
}

object OpValidator {

    fun decide(type: String, payloadJson: String, snapshot: OpSnapshot): Decision {
        // 老提案可能缺字段（Gson 会把非空 Kotlin 字段注入 null），decide 必须总是返回决策而不是抛
        return try {
            when (type) {
                OpType.SAVE_MEMORY -> decideSaveMemory(payloadJson, snapshot)
                OpType.DELETE_SOURCE -> decideDeleteSource(payloadJson, snapshot)
                OpType.EVOLVE_MEMORY -> decideEvolveMemory(payloadJson, snapshot)
                OpType.MERGE_MEMORY -> decideMergeMemory(payloadJson, snapshot)
                OpType.SPLIT_MEMORY -> decideSplitMemory(payloadJson, snapshot)
                OpType.DELETE_MEMORY -> decideDeleteMemory(payloadJson, snapshot)
                else -> Decision.Invalid("未知操作类型: $type")
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Decision.Invalid("载荷字段缺失或损坏：${e.message ?: type}")
        }
    }

    // ========== 生成阶段的判重（供 ViewModel 用） ==========

    /** 提案预筛结果：保留哪些、跳过哪些、哪些只是"疑似重复"要人看 */
    data class SaveProposalFilter(
        val keep: List<String>,
        val skipped: List<String>,
        val suspicious: List<String>
    )

    /**
     * 存记忆提案预筛：与现有 active 记忆 EXACT/CONTAINS 的一律跳过（这就是"整理后残存"的正解），
     * SIMILAR 的**保留但标注**——绝不自动合并语义相近的两条（"在准备法考" ≠ "通过法考"）。
     */
    fun filterSaveProposals(contents: List<String>, existingActiveContents: List<String>): SaveProposalFilter {
        val keep = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val suspicious = mutableListOf<String>()
        for (raw in contents) {
            val content = raw.trim()
            if (content.isEmpty()) continue
            val exact = existingActiveContents.any { MemorySimilarity.autoMergeable(content, it) } ||
                keep.any { MemorySimilarity.autoMergeable(content, it) }
            if (exact) {
                skipped += content
                continue
            }
            keep += content
            if (existingActiveContents.any { MemorySimilarity.suspiciousDuplicate(content, it) }) suspicious += content
        }
        return SaveProposalFilter(keep = keep, skipped = skipped, suspicious = suspicious)
    }

    // ========== SAVE_MEMORY ==========

    private fun decideSaveMemory(payloadJson: String, snapshot: OpSnapshot): Decision {
        val payload = OpPayloads.decodeSaveMemory(payloadJson)
            ?: return Decision.Invalid("载荷解析失败")
        val content = payload.content.trim()
        if (content.isEmpty()) return Decision.Invalid("记忆内容为空")
        // 幂等 + 判重：归一化后全等或完整包含的 active 记忆已存在 → 目标态已达成，不再存第二条
        // （SIMILAR 不算重复，见 hasEquivalentActive：语义相近的两条必须留给人判断）
        val duplicated = snapshot.memories.any {
            it.status == STATUS_ACTIVE && MemorySimilarity.autoMergeable(content, it.content)
        }
        if (duplicated) return Decision.AlreadyApplied
        val near = snapshot.memories.any {
            it.status == STATUS_ACTIVE && MemorySimilarity.suspiciousDuplicate(content, it.content)
        }
        return if (near) Decision.Execute("与现有记忆表述相近，已按新记忆入库；如属重复请到「已更正的记忆」页清理旧条") else Decision.Execute()
    }

    // ========== DELETE_SOURCE ==========

    private fun decideDeleteSource(payloadJson: String, snapshot: OpSnapshot): Decision {
        val payload = OpPayloads.decodeDeleteSource(payloadJson)
            ?: return Decision.Invalid("载荷解析失败")
        // 载荷是空的（提案生成时没匹配到任何源）→ 不可执行，绝不退化成"删全部"
        if (payload.recordIds.isEmpty() && payload.reviewIds.isEmpty()) {
            return Decision.Invalid("该清理提案没有绑定任何源数据，已拒绝执行")
        }
        val missingRecords = payload.recordIds.filter { it !in snapshot.records }
        val missingReviews = payload.reviewIds.filter { it !in snapshot.reviews }
        // 幂等：全部源都已不存在 → 目标态达成
        if (missingRecords.size == payload.recordIds.size && missingReviews.size == payload.reviewIds.size) {
            return Decision.AlreadyApplied
        }
        // STALE：仍存在的源中，有任何一个内容与提案时不一致（哈希优先，版本降级）
        for (id in payload.recordIds) {
            val current = snapshot.records[id] ?: continue  // 已删除的不管
            if (isRecordChanged(id, payload, current)) {
                return Decision.Stale("源记录[$id]在提案生成后被修改过")
            }
        }
        for (id in payload.reviewIds) {
            val current = snapshot.reviews[id] ?: continue
            if (isReviewChanged(id, payload, current)) {
                return Decision.Stale("源评价[$id]在提案生成后被修改过")
            }
        }
        return Decision.Execute()
    }

    private fun isRecordChanged(id: Long, payload: DeleteSourcePayload, current: SourceState): Boolean {
        // 哈希优先：内容指纹一致即视为未变化（加标签等不触发误报）
        val expectedHash = payload.recordHashes[id]
        if (expectedHash != null) return expectedHash != current.contentHash
        // 降级：无哈希时按版本快照
        val expectedVersion = payload.recordVersions[id] ?: return false
        return expectedVersion != current.updatedAt
    }

    private fun isReviewChanged(id: Long, payload: DeleteSourcePayload, current: SourceState): Boolean {
        val expectedHash = payload.reviewHashes[id]
        if (expectedHash != null) return expectedHash != current.contentHash
        val expectedVersion = payload.reviewVersions[id] ?: return false
        return expectedVersion != current.updatedAt
    }

    // ========== EVOLVE_MEMORY ==========

    private fun decideEvolveMemory(payloadJson: String, snapshot: OpSnapshot): Decision {
        val payload = OpPayloads.decodeEvolveMemory(payloadJson)
            ?: return Decision.Invalid("载荷解析失败")
        if (payload.newText.isBlank()) return Decision.Invalid("演化后的记忆内容为空")
        val memory = snapshot.memories.find { it.id == payload.memoryId }
        return when {
            memory == null ->
                // 旧记忆已不存在：若新文本已入库则幂等达成，否则过期
                if (hasEquivalentActive(snapshot, payload.newText)) Decision.AlreadyApplied
                else Decision.Stale("源记忆#${payload.memoryId}已不存在")
            memory.status == STATUS_UNDER_REVIEW ->
                Decision.Stale("源记忆尚有待确认的更正，请先在记忆页处理")
            memory.status == "superseded" ->
                if (hasEquivalentActive(snapshot, payload.newText)) Decision.AlreadyApplied
                else Decision.Stale("源记忆已处于停用状态")
            memory.status != STATUS_ACTIVE ->
                Decision.Stale("源记忆状态为 ${memory.status}，不是生效中")
            memory.content.trim() != payload.oldText.trim() ->
                Decision.Stale("源记忆内容与提案时不一致")
            hasEquivalentActive(snapshot, payload.newText) -> Decision.AlreadyApplied
            else -> Decision.Execute()
        }
    }

    // ========== MERGE_MEMORY ==========

    private fun decideMergeMemory(payloadJson: String, snapshot: OpSnapshot): Decision {
        val payload = OpPayloads.decodeMergeMemory(payloadJson)
            ?: return Decision.Invalid("载荷解析失败")
        val ids = payload.memoryIds.distinct()
        if (ids.size < 2) return Decision.Invalid("合并至少需要 2 条记忆")
        if (ids.size != payload.memoryIds.size) return Decision.Invalid("合并的记忆 id 有重复")
        val merged = payload.mergedContent.trim()
        if (merged.isEmpty()) return Decision.Invalid("合并后的记忆内容为空")
        val targets = ids.mapNotNull { id -> snapshot.memories.find { it.id == id } }
        if (targets.size != ids.size) return Decision.Stale("部分源记忆已被删除，提案需重新生成")
        // 幂等：合并结果已入库且原条目都已停用 → 目标态已达成
        if (targets.all { it.status != STATUS_ACTIVE } && hasEquivalentActive(snapshot, merged)) {
            return Decision.AlreadyApplied
        }
        for (t in targets) {
            if (t.status == STATUS_UNDER_REVIEW) return Decision.Stale("记忆#${t.id} 尚有待确认的更正")
            if (t.status != STATUS_ACTIVE) return Decision.Stale("记忆#${t.id} 已不是生效状态")
            if (!hashMatches(payload.contentHashes[t.id], t)) return Decision.Stale("记忆#${t.id} 内容与提案时不一致")
        }
        return Decision.Execute()
    }

    // ========== SPLIT_MEMORY ==========

    private fun decideSplitMemory(payloadJson: String, snapshot: OpSnapshot): Decision {
        val payload = OpPayloads.decodeSplitMemory(payloadJson)
            ?: return Decision.Invalid("载荷解析失败")
        val parts = payload.parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return Decision.Invalid("拆分至少需要 2 条内容")
        val memory = snapshot.memories.find { it.id == payload.memoryId }
        if (memory == null) {
            // 原记忆已经不在了：拆出来的每条都已入库则视为幂等达成，否则过期
            return if (parts.all { hasEquivalentActive(snapshot, it) }) Decision.AlreadyApplied
            else Decision.Stale("源记忆#${payload.memoryId}已不存在")
        }
        if (memory.status == STATUS_UNDER_REVIEW) return Decision.Stale("源记忆尚有待确认的更正，请先在记忆页处理")
        if (memory.status != STATUS_ACTIVE) return Decision.Stale("源记忆已不是生效状态")
        if (!hashMatches(payload.contentHash, memory)) return Decision.Stale("源记忆内容与提案时不一致")
        return Decision.Execute()
    }

    // ========== DELETE_MEMORY ==========

    private fun decideDeleteMemory(payloadJson: String, snapshot: OpSnapshot): Decision {
        val payload = OpPayloads.decodeDeleteMemory(payloadJson)
            ?: return Decision.Invalid("载荷解析失败")
        // Gson 对缺失字段会塞 0：不能当成"这条记忆已经不在了"，否则损坏的提案会被标成已完成
        if (payload.memoryId <= 0L) return Decision.Invalid("缺少 memoryId")
        val memory = snapshot.memories.find { it.id == payload.memoryId }
            ?: return Decision.AlreadyApplied          // 已经不在了：目标态达成
        if (payload.contentHash.isNotEmpty() && payload.contentHash != memory.hash) {
            return Decision.Stale("记忆#${payload.memoryId} 内容与提案时不一致，已跳过物理删除")
        }
        return Decision.Execute("将永久删除记忆#${memory.id}，不可恢复")
    }

    /**
     * 现存 active 记忆的判重分组：归一化全等 / 完整包含的归为一组（SIMILAR 绝不归组）。
     * 每组 ≥2 条产出一个 MERGE_MEMORY 提案，合并文本取组内最长的那条（信息量最大的表述）。
     * 这是"整理后老有残存的记忆"的正面解法：以前判重只认整句全等，换个说法的重复条目谁也合不掉。
     */
    fun planDedupeMerges(memories: List<MemoryRef>): List<MergeMemoryPayload> {
        val active = memories.filter { it.status == STATUS_ACTIVE }
        if (active.size < 2) return emptyList()
        val groups = mutableListOf<MutableList<MemoryRef>>()
        for (m in active) {
            val group = groups.firstOrNull { g -> g.any { MemorySimilarity.autoMergeable(m.content, it.content) } }
            if (group != null) group.add(m) else groups.add(mutableListOf(m))
        }
        return groups.filter { it.size >= 2 }.map { group ->
            MergeMemoryPayload(
                memoryIds = group.map { it.id }.sorted(),
                mergedContent = group.maxByOrNull { it.content.trim().length }!!.content.trim(),
                contentHashes = group.associate { it.id to it.hash }
            )
        }
    }

    // ========== 编排 ==========

    /**
     * 批次执行编排（纯函数）：保留勾选且 待执行(PENDING) 或 被阻塞(BLOCKED，依赖可能已满足需重评) 的项，
     * 按类型优先级排序。BLOCKED 是否真正可执行由执行器实时依赖检查决定。
     */
    fun planBatch(
        ops: List<BatchOp>,
        order: List<String> = OpType.executionOrder(),
        onlyChecked: Boolean = true
    ): List<BatchOp> {
        val rank = order.withIndex().associate { (i, t) -> t to i }
        return ops
            .filter { (!onlyChecked || it.checked) && (it.status == OpStatus.PENDING || it.status == OpStatus.BLOCKED) }
            .sortedBy { rank[it.type] ?: order.size }
    }

    // ========== 工具 ==========

    /** active 记忆里是否已有等价表述（归一化全等 / 完整包含；SIMILAR 不算，交给人判断） */
    fun hasEquivalentActive(snapshot: OpSnapshot, text: String): Boolean =
        snapshot.memories.any { it.status == STATUS_ACTIVE && MemorySimilarity.autoMergeable(text, it.content) }

    /** 提案期没带哈希（旧数据）时放行；带了就必须一致 */
    private fun hashMatches(expected: String?, current: MemoryRef): Boolean =
        expected.isNullOrEmpty() || expected == current.hash

    private const val STATUS_ACTIVE = "active"
    private const val STATUS_UNDER_REVIEW = "under_review"
}

/** 编排输入的精简视图（避免直接依赖 Room 实体，保证可测） */
data class BatchOp(
    val id: String,
    val type: String,
    val status: String,
    val checked: Boolean,
    val payloadJson: String,
    val dependsOn: List<String> = emptyList()
)
