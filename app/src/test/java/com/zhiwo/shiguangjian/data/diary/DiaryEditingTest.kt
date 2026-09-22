package com.zhiwo.shiguangjian.data.diary

import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryEditingTest {

    @Test
    fun `prepareEditedDiary resets exported flag after content changes`() {
        val diary = DiaryEntity(
            id = 1,
            date = "2026-07-26",
            content = "旧内容",
            mood = "平淡",
            createdAt = "2026-07-26T10:00:00.000Z",
            exported = true
        )

        val edited = prepareEditedDiary(diary, "新内容")

        assertEquals("新内容", edited.content)
        assertFalse(edited.exported)
    }

    @Test
    fun `user edited diary is protected from auto regeneration`() {
        val aiGenerated = DiaryEntity(
            id = 2,
            date = "2026-08-01",
            content = "AI 写的日记",
            mood = "开心",
            createdAt = "2026-08-01T10:00:00.000Z",
            sourceRecordIds = "[1, 2]",
            generationVersion = 1,
            isUserEdited = false
        )
        // AI 生成未编辑：允许重新生成覆盖
        assertFalse(aiGenerated.isUserEdited)

        val edited = prepareEditedDiary(aiGenerated, "我改过的日记")
        // 手动编辑后：不允许自动覆盖
        assertTrue(edited.isUserEdited)
        // 编辑不改变生成溯源字段
        assertEquals("[1, 2]", edited.sourceRecordIds)
        assertEquals(1, edited.generationVersion)
    }
}
