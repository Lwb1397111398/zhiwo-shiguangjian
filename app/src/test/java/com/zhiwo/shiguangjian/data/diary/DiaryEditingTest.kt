package com.zhiwo.shiguangjian.data.diary

import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
