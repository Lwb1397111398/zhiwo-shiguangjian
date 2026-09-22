package com.zhiwo.shiguangjian.data.memory

import com.google.gson.JsonArray
import com.google.gson.annotations.SerializedName
import com.zhiwo.shiguangjian.data.ai.AiJsonParser

/**
 * 记忆对账：用新信息修正旧记忆（如"准备法考"被"忘记报名法考"推翻）。
 * 本文件只做纯逻辑：解析 AI 输出 + 决定哪些自动执行、哪些进待确认队列。
 */
data class ReconcileSuggestion(
    @SerializedName("memoryId") val memoryId: Long,
    @SerializedName("relation") val relation: String = "none",  // conflict | update | support | none
    @SerializedName("action") val action: String = "keep",    // supersede | revise | keep
    @SerializedName("newText") val newText: String = "",
    @SerializedName("reason") val reason: String = "",
    @SerializedName("confidence") val confidence: String = "low", // high | medium | low
    /** 触发本次更正的来源记录 id（幂等键组成部分 + 溯源） */
    @SerializedName("sourceRecordId") val sourceRecordId: Long? = null
)

data class ReconcilePlan(
    /** 高置信、可直接执行的建议（supersede 停用旧记忆；revise 停用旧记忆并写入更正后的新记忆） */
    val autoApplies: List<ReconcileSuggestion>,
    /** 需要用户在记忆页确认的建议 */
    val pending: List<ReconcileSuggestion>
)

object MemoryReconciliation {

    const val SOURCE_RECONCILE = "reconcile"
    const val MAX_PENDING = 20

    /** 解析 AI 返回的更正建议 JSON 数组，容忍格式异常 */
    fun parseSuggestions(raw: String): List<ReconcileSuggestion> {
        val array: JsonArray = try {
            AiJsonParser.parseArray(raw)
        } catch (_: Throwable) {
            return emptyList()
        }
        val result = mutableListOf<ReconcileSuggestion>()
        for (element in array) {
            try {
                val obj = element.asJsonObject
                val memoryId = obj.get("memoryId")?.asLong
                    ?: obj.get("memory_id")?.asLong
                    ?: continue
                val suggestion = ReconcileSuggestion(
                    memoryId = memoryId,
                    relation = obj.get("relation")?.asString ?: "none",
                    action = obj.get("action")?.asString ?: "keep",
                    newText = obj.get("newText")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: "",
                    reason = obj.get("reason")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: "",
                    confidence = normalizeConfidence(obj.get("confidence")?.asString)
                )
                result.add(suggestion)
            } catch (_: Throwable) {
                // 单条损坏跳过，不影响其余
            }
        }
        return result
    }

    /**
     * 生成执行计划：
     * - action 为 supersede/revise 且 confidence=high → 自动执行
     * - 其余 supersede/revise → 进待确认队列
     * - keep/support/none、无效 memoryId → 丢弃
     */
    fun buildPlan(suggestions: List<ReconcileSuggestion>, validMemoryIds: Set<Long>): ReconcilePlan {
        val autoApplies = mutableListOf<ReconcileSuggestion>()
        val pending = mutableListOf<ReconcileSuggestion>()
        val seenAuto = HashSet<Long>()
        val seenPending = HashSet<Long>()
        for (s in suggestions) {
            if (s.memoryId !in validMemoryIds) continue
            if (s.action != "supersede" && s.action != "revise") continue
            // revise 必须有新文本，否则降级为 supersede
            val effective = if (s.action == "revise" && s.newText.isBlank()) s.copy(action = "supersede") else s
            if (effective.confidence == "high") {
                if (seenAuto.add(effective.memoryId)) autoApplies.add(effective)
            } else {
                if (seenPending.add(effective.memoryId)) pending.add(effective)
            }
        }
        return ReconcilePlan(autoApplies = autoApplies, pending = pending)
    }

    /**
     * 待确认队列合并：同一 memoryId 的新建议替换旧建议，保持先进先出，超过上限丢最旧。
     * 需要知道"丢了谁"的调用方请用 [mergePendingWithDropped]。
     */
    fun mergePending(existing: List<ReconcileSuggestion>, incoming: List<ReconcileSuggestion>): List<ReconcileSuggestion> =
        mergePendingWithDropped(existing, incoming).merged

    /** 合并结果：留在队列里的 + 被上限挤掉的（被挤掉的对应记忆必须从 under_review 打回 active，否则成卡死孤儿） */
    data class PendingMerge(val merged: List<ReconcileSuggestion>, val dropped: List<ReconcileSuggestion>)

    fun mergePendingWithDropped(
        existing: List<ReconcileSuggestion>,
        incoming: List<ReconcileSuggestion>
    ): PendingMerge {
        val byMemoryId = LinkedHashMap<Long, ReconcileSuggestion>()
        for (s in existing) byMemoryId[s.memoryId] = s
        for (s in incoming) byMemoryId[s.memoryId] = s
        val all = byMemoryId.values.toList()
        val overflow = all.size - MAX_PENDING
        if (overflow <= 0) return PendingMerge(merged = all, dropped = emptyList())
        return PendingMerge(merged = all.drop(overflow), dropped = all.take(overflow))
    }

    /**
     * 孤儿 under_review：记忆被置成了待确认，但队列里已经没有指向它的建议
     * （被上限挤掉、被 prune 清掉、进程被杀）——这些记忆既不注入 prompt 也不在主列表可见，
     * 用户永远看不到也删不掉，必须打回 active。
     */
    fun orphanUnderReviewIds(underReviewIds: List<Long>, pending: List<ReconcileSuggestion>): List<Long> {
        val tracked = pending.map { it.memoryId }.toHashSet()
        return underReviewIds.filterNot { it in tracked }
    }

    private fun normalizeConfidence(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "high", "高" -> "high"
        "medium", "中" -> "medium"
        else -> "low"
    }
}
