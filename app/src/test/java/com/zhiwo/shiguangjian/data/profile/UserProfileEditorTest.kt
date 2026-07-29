package com.zhiwo.shiguangjian.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UserProfileEditorTest {

    @Test
    fun `add text item rejects blank and duplicate`() {
        val base = listOf("喜欢直接建议")
        assertTrue(UserProfileEditor.addTextItem(base, "  ").isFailure)
        assertTrue(UserProfileEditor.addTextItem(base, "喜欢直接建议").isFailure)
        val added = UserProfileEditor.addTextItem(base, "讨厌说教").getOrThrow()
        assertEquals(listOf("喜欢直接建议", "讨厌说教"), added)
    }

    @Test
    fun `text list respects max size`() {
        var list = emptyList<String>()
        repeat(UserProfileEditor.MAX_LIST) { i ->
            list = UserProfileEditor.addTextItem(list, "item$i").getOrThrow()
        }
        assertTrue(UserProfileEditor.addTextItem(list, "overflow").isFailure)
    }

    @Test
    fun `update text item replaces at index`() {
        val list = listOf("A", "B", "C")
        val updated = UserProfileEditor.updateTextItem(list, 1, "B2").getOrThrow()
        assertEquals(listOf("A", "B2", "C"), updated)
    }

    @Test
    fun `remove text item`() {
        val list = listOf("A", "B", "C")
        assertEquals(listOf("A", "C"), UserProfileEditor.removeAt(list, 1))
    }

    @Test
    fun `personality confidence clamped and labeled`() {
        assertEquals(0.5, UserProfileEditor.sanitizeConfidence(Double.NaN), 0.0)
        assertEquals(1.0, UserProfileEditor.sanitizeConfidence(2.0), 0.0)
        assertEquals(0.0, UserProfileEditor.sanitizeConfidence(-1.0), 0.0)
        assertEquals("高", UserProfileEditor.confidenceLabel(0.8))
        assertEquals("中", UserProfileEditor.confidenceLabel(0.5))
        assertEquals("低", UserProfileEditor.confidenceLabel(0.2))
    }

    @Test
    fun `personality max and duplicate`() {
        var list = emptyList<PersonalityTrait>()
        repeat(UserProfileEditor.MAX_PERSONALITY) { i ->
            list = UserProfileEditor.addPersonality(list, "trait$i", 0.5).getOrThrow()
        }
        assertTrue(UserProfileEditor.addPersonality(list, "more", 0.5).isFailure)
        assertTrue(UserProfileEditor.addPersonality(listOf(PersonalityTrait("冷静", 0.4)), "冷静", 0.9).isFailure)
    }

    @Test
    fun `recent state days capped at one year`() {
        val today = LocalDate.of(2026, 7, 29)
        val expires = UserProfileEditor.expiresAtFromDays(9999, today)
        assertEquals("2027-07-29", expires)
    }

    @Test
    fun `prepareForSave drops expired recent states`() {
        val today = LocalDate.of(2026, 7, 29)
        val draft = UserProfile(
            preferences = listOf("直接建议"),
            recentStates = listOf(
                RecentState("过期", "2020-01-01"),
                RecentState("有效", "2099-01-01")
            )
        )
        val prepared = UserProfileEditor.prepareForSave(draft, today)
        assertEquals(1, prepared.recentStates.size)
        assertEquals("有效", prepared.recentStates[0].content)
        assertEquals(listOf("直接建议"), prepared.preferences)
    }

    @Test
    fun `empty profile after clear is empty`() {
        assertTrue(UserProfileEditor.emptyProfile().isEmpty())
    }

    @Test
    fun `user delete then save without merge keeps deletion`() {
        // 模拟：旧画像有两条偏好，用户删掉一条后直接 save（不 merge）
        val old = UserProfile(preferences = listOf("A", "B"))
        val afterDelete = old.copy(preferences = listOf("A"))
        val saved = UserProfileEditor.prepareForSave(afterDelete)
        // 若错误调用 mergeWith(old, saved) 会把 B 加回来——这里验证不 merge 时 B 不在
        assertFalse(saved.preferences.contains("B"))
        assertEquals(listOf("A"), saved.preferences)
    }

    @Test
    fun `isExpired handles blank and invalid`() {
        assertFalse(UserProfileEditor.isExpired(""))
        assertFalse(UserProfileEditor.isExpired("not-a-date"))
        assertTrue(UserProfileEditor.isExpired("2020-01-01", LocalDate.of(2026, 1, 1)))
    }
}
