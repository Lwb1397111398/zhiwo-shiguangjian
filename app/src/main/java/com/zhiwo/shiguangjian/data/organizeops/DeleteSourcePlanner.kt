package com.zhiwo.shiguangjian.data.organizeops

import com.zhiwo.shiguangjian.data.memory.MemorySimilarity

/**
 * 一条清理提案的候选源：AI 看到的第 index 项（1 基）对应哪条记录/评价。
 * [version]/[contentHash] 是提案生成时的现场快照，执行前用来判 STALE。
 */
data class CleanCandidate(
    val id: Long,
    val isReview: Boolean,
    val label: String,
    val version: String,
    val contentHash: String
)

/**
 * 「每条清理提案只携带它自己那一组源 id」的唯一算法（纯函数，零 Android 依赖）。
 *
 * 旧实现把**当前所选全部 id**塞进每一条 DELETE 提案，勾 1 条清理会删掉全部源数据。
 * 现在：优先用 AI 回传的条目序号；序号缺失时按文本归一化匹配（EXACT/CONTAINS）；
 * 两者都对不上就**不生成这条提案**——宁可少删，也绝不退化成"全删"。
 */
object DeleteSourcePlanner {

    fun plan(
        label: String,
        itemIndexes: List<Int>,
        candidates: List<CleanCandidate>
    ): DeleteSourcePayload? {
        val matched = select(label, itemIndexes, candidates)
        if (matched.isEmpty()) return null
        val recordIds = matched.filterNot { it.isReview }.map { it.id }
        val reviewIds = matched.filter { it.isReview }.map { it.id }
        return DeleteSourcePayload(
            label = label,
            recordIds = recordIds,
            reviewIds = reviewIds,
            recordVersions = recordIds.associateWith { id -> matched.first { it.id == id && !it.isReview }.version },
            reviewVersions = reviewIds.associateWith { id -> matched.first { it.id == id && it.isReview }.version },
            recordHashes = recordIds.associateWith { id -> matched.first { it.id == id && !it.isReview }.contentHash },
            reviewHashes = reviewIds.associateWith { id -> matched.first { it.id == id && it.isReview }.contentHash }
        )
    }

    /** 命中哪些源：序号优先，其次文本匹配；都不命中返回空（= 不生成提案） */
    fun select(
        label: String,
        itemIndexes: List<Int>,
        candidates: List<CleanCandidate>
    ): List<CleanCandidate> {
        val byIndex = itemIndexes.map { it - 1 }.distinct().filter { it in candidates.indices }.map { candidates[it] }
        if (byIndex.isNotEmpty()) return byIndex
        if (label.isBlank()) return emptyList()
        // 只认同一条清理提案覆盖哪些源：全等或完整包含；SIMILAR 不够格（宁可少删）
        return candidates.filter { MemorySimilarity.autoMergeable(label, it.label) }
    }
}
