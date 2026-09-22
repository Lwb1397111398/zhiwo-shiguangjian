package com.zhiwo.shiguangjian.data.diary

import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.db.entity.ReviewEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCorrectionScannerTest {

    private fun memory(content: String, updatedAt: String) = MemoryEntity(
        id = 1, content = content, source = "reconcile",
        createdAt = "2026-01-01T00:00:00.000Z", updatedAt = updatedAt, status = "superseded"
    )

    private fun diary(date: String, content: String, createdAt: String, edited: Boolean = false) = DiaryEntity(
        id = 1, date = date, content = content, mood = "开心",
        createdAt = createdAt, isUserEdited = edited
    )

    @Test
    fun `law exam scenario - old diary referencing superseded memory is hit`() {
        // 法考场景：1月写日记提到"准备法考"；2月记忆被更正（superseded）
        val superseded = memory("准备法考", updatedAt = "2026-02-01T21:00:00.000Z")
        val oldDiary = diary("2026-01-15", "今天继续准备法考，看了三章民法。", createdAt = "2026-01-15T21:30:00.000Z")
        val newDiary = diary("2026-02-10", "法考已放弃，开始准备考研。", createdAt = "2026-02-10T21:30:00.000Z")

        val hits = HistoryCorrectionScanner.scan(listOf(oldDiary, newDiary), emptyList(), listOf(superseded))

        // 只有更正前生成、且命中旧记忆词元的日记被标出
        assertEquals(1, hits.size)
        assertEquals("diary", hits[0].type)
        assertEquals("2026-01-15", hits[0].date)
        assertTrue(hits[0].matchedFragment.isNotBlank())
    }

    @Test
    fun `content generated after correction is not hit`() {
        val superseded = memory("准备法考", updatedAt = "2026-02-01T21:00:00.000Z")
        val after = diary("2026-02-05", "今天提到法考的事", createdAt = "2026-02-05T21:00:00.000Z")
        assertTrue(HistoryCorrectionScanner.scan(listOf(after), emptyList(), listOf(superseded)).isEmpty())
    }

    @Test
    fun `review hits are reported as review type`() {
        val superseded = memory("准备法考", updatedAt = "2026-02-01T21:00:00.000Z")
        val review = ReviewEntity(
            id = 7, type = "daily", date = "2026-01-20",
            content = "准备法考的节奏不错，继续保持", createdAt = "2026-01-20T21:00:00.000Z"
        )
        val hits = HistoryCorrectionScanner.scan(emptyList(), listOf(review), listOf(superseded))
        assertEquals(1, hits.size)
        assertEquals("review", hits[0].type)
    }

    @Test
    fun `user edited diary is flagged but not silently regenerated`() {
        val superseded = memory("准备法考", updatedAt = "2026-02-01T21:00:00.000Z")
        val edited = diary("2026-01-15", "我改过的准备法考日记", createdAt = "2026-01-15T21:30:00.000Z", edited = true)
        val hits = HistoryCorrectionScanner.scan(listOf(edited), emptyList(), listOf(superseded))
        assertEquals(1, hits.size)
        assertTrue(hits[0].isUserEdited)
    }

    @Test
    fun `no superseded memories yields no hits`() {
        val d = diary("2026-01-15", "随便写写", createdAt = "2026-01-15T21:30:00.000Z")
        assertTrue(HistoryCorrectionScanner.scan(listOf(d), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `paraphrased reference is not matched - known heuristic limitation`() {
        val superseded = memory("准备法考", updatedAt = "2026-02-01T21:00:00.000Z")
        val paraphrased = diary("2026-01-15", "法考复习进展不错", createdAt = "2026-01-15T21:30:00.000Z")
        // 已知局限：改写后无连续词元命中的内容扫不出来——UI 文案已如实标注"启发式，无法穷尽"
        assertTrue(HistoryCorrectionScanner.scan(listOf(paraphrased), emptyList(), listOf(superseded)).isEmpty())
    }

    @Test
    fun `token extraction splits punctuation and windows long segments`() {
        val tokens = HistoryCorrectionScanner.extractTokens("准备法考，明年再战！")
        assertTrue(tokens.contains("准备法考"))
        assertTrue(tokens.contains("明年再战"))
        // 单字与空白段被过滤
        assertTrue(tokens.none { it.length < 2 })
    }
}
