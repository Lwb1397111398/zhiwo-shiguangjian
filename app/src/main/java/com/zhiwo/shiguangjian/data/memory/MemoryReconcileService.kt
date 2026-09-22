package com.zhiwo.shiguangjian.data.memory

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.zhiwo.shiguangjian.data.ai.AiRepository
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.repository.MemoryRepository
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 记忆对账执行器：
 * - 高置信更正自动应用（supersede 停用旧记忆，必要时写入更正后的新记忆，supersededBy 形成完整链）
 * - 中低置信进待确认队列（settings 表持久化），对应旧记忆置 under_review，不再注入生成 prompt
 * - 幂等：旧记忆非 active/under_review 即跳过；新记忆同文本已存在则不重复插入
 * - AI 失败入重试队列，下次进入记忆页时自动补跑
 */
class MemoryReconcileService(
    private val memoryRepo: MemoryRepository,
    private val settingsRepo: SettingsRepository,
    private val aiRepo: AiRepository
) {

    private val gson = Gson()

    /** 对账失败待重试的内容（进程被杀后下次补跑） */
    data class RetryItem(
        @SerializedName("content") val content: String,
        @SerializedName("sourceRecordId") val sourceRecordId: Long?,
        @SerializedName("sourceDate") val sourceDate: String = "",
        @SerializedName("queuedAt") val queuedAt: Long
    )

    /** 触发一次对账并应用，返回自动应用的条数；失败入重试队列 */
    suspend fun reconcileAndApply(
        newContent: String,
        now: String,
        sourceRecordId: Long? = null,
        sourceDate: String = ""
    ): Int {
        val content = newContent.trim()
        if (content.isBlank() || !aiRepo.isConfigured) return 0
        val allMemories = memoryRepo.getAllMemories().first()
        val active = allMemories.filter { it.status == STATUS_ACTIVE }
        if (active.isEmpty()) return 0
        return try {
            val raw = aiRepo.reconcileMemories(content, active)
            val suggestions = MemoryReconciliation.parseSuggestions(raw)
                .map { it.copy(sourceRecordId = sourceRecordId ?: it.sourceRecordId) }
            val dismissed = getDismissedIds()
            val validIds = active.map { it.id }.toSet()
            val plan = MemoryReconciliation.buildPlan(suggestions, validIds)
            val autoApplied = applySuggestions(plan.autoApplies, now, sourceDate)

            if (plan.pending.isNotEmpty()) {
                // 中低置信：对应旧记忆置 under_review（不再注入 prompt），建议入队待确认
                val pendingFiltered = plan.pending.filterNot { it.memoryId in dismissed }
                if (pendingFiltered.isNotEmpty()) {
                    markUnderReview(pendingFiltered.map { it.memoryId }, now)
                    val merge = MemoryReconciliation.mergePendingWithDropped(getPending(), pendingFiltered)
                    savePending(merge.merged)
                    // 被上限挤掉的建议对应的记忆不能留在 under_review：那是个看不见的死状态
                    restoreToActive(merge.dropped.map { it.memoryId }, now)
                }
            }
            autoApplied
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e(TAG, "对账失败，进入重试队列: ${e.message}")
            enqueueRetry(RetryItem(content, sourceRecordId, sourceDate, System.currentTimeMillis()))
            0
        }
    }

    /** 补跑重试队列：先清空再逐项重试，仍失败的项会由 reconcileAndApply 重新入队 */
    suspend fun flushRetries(now: String): Int {
        val queue = getRetryQueue()
        if (queue.isEmpty() || !aiRepo.isConfigured) return 0
        saveRetryQueue(emptyList())
        var applied = 0
        for (item in queue) {
            applied += reconcileAndApply(item.content, now, item.sourceRecordId, item.sourceDate)
        }
        return applied
    }

    /** 应用单条待确认建议，成功返回 true */
    suspend fun applyPending(suggestion: ReconcileSuggestion, now: String): Boolean {
        val memory = memoryRepo.getMemoryById(suggestion.memoryId) ?: run {
            removeFromPending(suggestion.memoryId); return false
        }
        if (memory.status != STATUS_ACTIVE && memory.status != STATUS_UNDER_REVIEW) {
            removeFromPending(suggestion.memoryId); return false
        }
        val applied = applySuggestions(listOf(suggestion), now)
        removeFromPending(suggestion.memoryId)
        return applied == 1
    }

    /** 应用全部待确认建议，返回成功条数 */
    suspend fun applyAllPending(now: String): Int {
        val pending = getPending()
        if (pending.isEmpty()) return 0
        var count = 0
        for (s in pending) {
            if (applyPending(s, now)) count++
        }
        savePending(emptyList())
        return count
    }

    /** 忽略单条待确认建议：旧记忆恢复 active，并记住该 memoryId 不再自动提示 */
    suspend fun dismissPendingSuspend(memoryId: Long, now: String) {
        val memory = memoryRepo.getMemoryById(memoryId)
        if (memory != null && memory.status == STATUS_UNDER_REVIEW) {
            memoryRepo.updateMemory(memory.copy(status = STATUS_ACTIVE, updatedAt = now))
        }
        removeFromPending(memoryId)
        rememberDismissed(memoryId)
    }

    fun observePending(): Flow<List<ReconcileSuggestion>> =
        settingsRepo.getAllSettings().map { list ->
            list.find { it.key == PENDING_KEY }?.value?.let(::decodePending) ?: emptyList()
        }

    /**
     * 作废孤儿建议 + 反向解锁：
     * - 目标记忆已不存在的建议移出队列
     * - 仍处于 under_review 但队列里已无对应建议的记忆（被上限挤掉 / 被 prune / 进程被杀）打回 active，
     *   否则会永久卡在"不注入 prompt、不计入数量、主列表看不见"的死角
     */
    suspend fun prunePending() {
        val pending = getPending()
        val liveIds = memoryRepo.getAllMemories().first().map { it.id }.toSet()
        val valid = pending.filter { it.memoryId in liveIds }
        if (valid.size != pending.size) savePending(valid)
        val now = com.zhiwo.shiguangjian.data.ai.DateFormats.nowDateTimeIso()
        val tracked = valid.map { it.memoryId }.toHashSet()
        val orphans = memoryRepo.getUnderReviewMemories().filterNot { it.id in tracked }
        for (memory in orphans) {
            memoryRepo.updateMemory(memory.copy(status = STATUS_ACTIVE, updatedAt = now))
        }
        if (orphans.isNotEmpty()) {
            android.util.Log.w(TAG, "待确认队列遗失导致 ${orphans.size} 条记忆卡在 under_review，已打回 active")
        }
    }

    suspend fun getPending(): List<ReconcileSuggestion> =
        settingsRepo.getSetting(PENDING_KEY)?.let(::decodePending) ?: emptyList()

    /**
     * 执行建议：supersede 停用旧记忆；带 newText 时停用旧记忆并写入更正后的新记忆。
     * 幂等：旧记忆必须仍是 active/under_review；新记忆与现有 active 记忆归一化后等价（EXACT/CONTAINS）时
     * 只停用旧的，不再插第二条——旧实现用"整句小写全等"判重，换个说法就会新旧同时 active。
     */
    private suspend fun applySuggestions(
        suggestions: List<ReconcileSuggestion>,
        now: String,
        sourceDate: String = ""
    ): Int {
        var applied = 0
        val existing = memoryRepo.getAllMemories().first()
        val activeContents = existing
            .filter { it.status == STATUS_ACTIVE }
            .map { it.content }
            .toMutableList()
        for (s in suggestions) {
            val memory = memoryRepo.getMemoryById(s.memoryId) ?: continue
            if (memory.status != STATUS_ACTIVE && memory.status != STATUS_UNDER_REVIEW) continue
            var supersededBy: Long? = null
            val newText = s.newText.trim()
            val duplicated = activeContents.any { MemorySimilarity.autoMergeable(newText, it) }
            if (newText.isNotBlank() && !duplicated) {
                val newId = memoryRepo.insertMemory(
                    MemoryEntity(
                        content = newText,
                        source = MemoryReconciliation.SOURCE_RECONCILE,
                        status = STATUS_ACTIVE,
                        createdAt = now,                       // 写库时间
                        updatedAt = now,
                        occurredAt = sourceDate.ifBlank { memory.occurredAt.ifBlank { now } },  // 来源日期
                        sourceRecordId = s.sourceRecordId
                    )
                )
                supersededBy = newId
                activeContents.add(newText)
            }
            memoryRepo.updateMemory(
                memory.copy(
                    status = STATUS_SUPERSEDED,
                    supersededBy = supersededBy,
                    note = s.reason.ifBlank { "与更新的信息冲突，已自动更正" },
                    updatedAt = now
                )
            )
            applied++
        }
        return applied
    }

    private suspend fun markUnderReview(memoryIds: List<Long>, now: String) {
        for (id in memoryIds) {
            val memory = memoryRepo.getMemoryById(id) ?: continue
            if (memory.status == STATUS_ACTIVE) {
                memoryRepo.updateMemory(memory.copy(status = STATUS_UNDER_REVIEW, updatedAt = now))
            }
        }
    }

    /** 打回 active：队列里已经没有这条建议了，不能让它继续隐形 */
    private suspend fun restoreToActive(memoryIds: List<Long>, now: String) {
        for (id in memoryIds.distinct()) {
            val memory = memoryRepo.getMemoryById(id) ?: continue
            if (memory.status == STATUS_UNDER_REVIEW) {
                memoryRepo.updateMemory(memory.copy(status = STATUS_ACTIVE, updatedAt = now))
            }
        }
    }

    private suspend fun removeFromPending(memoryId: Long) {
        val updated = getPending().filterNot { it.memoryId == memoryId }
        savePending(updated)
    }

    private suspend fun savePending(list: List<ReconcileSuggestion>) {
        try {
            settingsRepo.setSetting(PENDING_KEY, gson.toJson(list))
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "保存待确认更正失败", e)
        }
    }

    // ========== 忽略防抖 ==========

    private suspend fun rememberDismissed(memoryId: Long) {
        try {
            val current = getDismissedIds().toMutableList()
            if (memoryId !in current) {
                current.add(memoryId)
                while (current.size > 100) current.removeAt(0)
                settingsRepo.setSetting(DISMISSED_KEY, gson.toJson(current))
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "记录忽略防抖失败", e)
        }
    }

    private suspend fun getDismissedIds(): List<Long> =
        settingsRepo.getSetting(DISMISSED_KEY)?.let { json ->
            try {
                gson.fromJson(json, object : TypeToken<List<Long>>() {}.type) ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
        } ?: emptyList()

    // ========== 重试队列 ==========

    private suspend fun enqueueRetry(item: RetryItem) {
        try {
            val queue = getRetryQueue().toMutableList()
            if (queue.none { it.content == item.content }) {
                queue.add(item)
                while (queue.size > 5) {
                    val dropped = queue.removeAt(0)
                    android.util.Log.w(TAG, "对账重试队列已满，丢弃最旧项(queuedAt=${dropped.queuedAt})")
                }
                saveRetryQueue(queue)
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "写入重试队列失败", e)
        }
    }

    private suspend fun getRetryQueue(): List<RetryItem> =
        settingsRepo.getSetting(RETRY_KEY)?.let { json ->
            try {
                gson.fromJson(json, object : TypeToken<List<RetryItem>>() {}.type) ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
        } ?: emptyList()

    private suspend fun saveRetryQueue(queue: List<RetryItem>) {
        settingsRepo.setSetting(RETRY_KEY, gson.toJson(queue))
    }

    private fun decodePending(json: String): List<ReconcileSuggestion> = try {
        val type = object : TypeToken<List<ReconcileSuggestion>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    companion object {
        private const val TAG = "MemoryReconcile"
        const val PENDING_KEY = "memoryPendingCorrections"
        private const val DISMISSED_KEY = "memoryDismissedReconcileIds"
        private const val RETRY_KEY = "memoryReconcileRetryQueue"
        const val STATUS_ACTIVE = "active"
        const val STATUS_SUPERSEDED = "superseded"
        const val STATUS_UNDER_REVIEW = "under_review"
    }
}
