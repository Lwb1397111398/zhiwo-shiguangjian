package com.zhiwo.shiguangjian.data.organizeops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「每条清理提案只携带它自己那一组源 id」（MFDP）。
 * 旧实现把当前所选全部 id 塞进每一条 DELETE 提案 —— 勾 1 条清理会删掉全部源数据。
 */
class DeleteSourcePlannerTest {

    private fun rec(id: Long, title: String) =
        CleanCandidate(id, isReview = false, label = title, version = "v$id", contentHash = OpPayloads.contentHash(title))

    private fun rev(id: Long, title: String) =
        CleanCandidate(id, isReview = true, label = title, version = "r$id", contentHash = OpPayloads.contentHash(title))

    private val threeSelected = listOf(
        rec(1, "完成科目二考试"),
        rec(2, "买了新键盘"),
        rev(7, "每日评价·2026-01-05")
    )

    @Test fun MFDP01_按序号只绑定自己那一组() {
        val payload = DeleteSourcePlanner.plan("买了新键盘", listOf(2), threeSelected)!!
        assertEquals(listOf(2L), payload.recordIds)
        assertTrue(payload.reviewIds.isEmpty())
        assertEquals("v2", payload.recordVersions[2L])
        assertEquals(OpPayloads.contentHash("买了新键盘"), payload.recordHashes[2L])
    }

    @Test fun MFDP02_一条提案可覆盖多个序号但不越界() {
        val payload = DeleteSourcePlanner.plan("琐事", listOf(2, 3, 99), threeSelected)!!
        assertEquals(listOf(2L), payload.recordIds)
        assertEquals(listOf(7L), payload.reviewIds)
    }

    @Test fun MFDP03_没有序号时按归一化文本匹配() {
        // 老模型只回文本：标点半角全角、空格换行都不该影响命中
        val payload = DeleteSourcePlanner.plan("完成科目二考试。", emptyList(), threeSelected)!!
        assertEquals(listOf(1L), payload.recordIds)
    }

    @Test fun MFDP04_匹配不上就不生成提案绝不退化成全删() {
        // 这是"勾 1 条删全部"的正面锁：任何对不上的清理项只能被丢弃
        assertNull(DeleteSourcePlanner.plan("完全没选过的东西", emptyList(), threeSelected))
        assertNull(DeleteSourcePlanner.plan("", emptyList(), threeSelected))
        assertNull(DeleteSourcePlanner.plan("琐事", listOf(0, -1), threeSelected))
        assertNull(DeleteSourcePlanner.plan("琐事", emptyList(), emptyList()))
    }

    @Test fun MFDP05_重复序号去重且记录评价分桶() {
        val payload = DeleteSourcePlanner.plan("两件一起做", listOf(1, 1, 3), threeSelected)!!
        assertEquals(listOf(1L), payload.recordIds)
        assertEquals(listOf(7L), payload.reviewIds)
        // 记录 id 与评价 id 同号也必须各归各的桶
        val sameNumber = listOf(rec(5, "写周报"), rev(5, "每日评价·2026-02-02"))
        val split = DeleteSourcePlanner.plan("写周报", listOf(1), sameNumber)!!
        assertEquals(listOf(5L), split.recordIds)
        assertTrue(split.reviewIds.isEmpty())
    }

    @Test fun MFDP06_待清理列表为空时一条提案都不生成() {
        val cleanable = emptyList<Pair<String, List<Int>>>()
        val produced = cleanable.mapNotNull { DeleteSourcePlanner.plan(it.first, it.second, threeSelected) }
        assertTrue(produced.isEmpty())
    }
}
