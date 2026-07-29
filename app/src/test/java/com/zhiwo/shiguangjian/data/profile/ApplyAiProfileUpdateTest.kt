package com.zhiwo.shiguangjian.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyAiProfileUpdateTest {

    @Test
    fun `flag off returns current unchanged`() {
        val current = UserProfile(preferences = listOf("旧偏好"))
        val ai = UserProfile(preferences = listOf("新偏好"), stableFacts = listOf("新事实"))
        val result = applyAiProfileUpdate(
            current = current,
            aiUpdate = ai,
            blocklist = ProfileBlocklist(),
            autoUpdateEnabled = false
        )
        assertEquals(current, result)
    }

    @Test
    fun `flag on empty blocklist merges`() {
        val current = UserProfile(preferences = listOf("旧偏好"))
        val ai = UserProfile(preferences = listOf("新偏好"), stableFacts = listOf("大学生"))
        val result = applyAiProfileUpdate(current, ai, ProfileBlocklist(), true)
        assertTrue(result.preferences.contains("旧偏好"))
        assertTrue(result.preferences.contains("新偏好"))
        assertTrue(result.stableFacts.contains("大学生"))
    }

    @Test
    fun `flag on blocked items excluded from merge`() {
        val current = UserProfile(preferences = listOf("保留"))
        val ai = UserProfile(
            preferences = listOf("被阻止", "可合并"),
            personality = listOf(PersonalityTrait("被阻止性格", 0.7))
        )
        val blocklist = ProfileBlocklist(
            preferences = listOf("被阻止"),
            personalityTraits = listOf("被阻止性格")
        )
        val result = applyAiProfileUpdate(current, ai, blocklist, true)
        assertEquals(listOf("保留", "可合并"), result.preferences)
        assertTrue(result.personality.none { it.trait == "被阻止性格" })
    }
}
