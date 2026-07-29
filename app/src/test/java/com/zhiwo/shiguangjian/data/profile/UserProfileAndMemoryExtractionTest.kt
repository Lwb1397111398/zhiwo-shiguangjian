package com.zhiwo.shiguangjian.data.profile

import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.ui.viewmodel.ReviewViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class UserProfileAndMemoryExtractionTest {

    @Test
    fun `parse new object format keeps at most two memories and profile`() {
        val raw = """
            {
              "newMemories": ["准备驾照考试", "喜欢直接建议", "多余第三条"],
              "updatedProfile": {
                "stableFacts": ["大学生"],
                "preferences": ["喜欢直接建议"],
                "supportStyle": ["先共情再给方案"],
                "personality": [{"trait": "容易自我反思", "confidence": 0.6}],
                "appearanceFacts": [],
                "recentStates": [{"content": "近期备考驾照", "expiresAt": "2026-12-31"}]
              }
            }
        """.trimIndent()

        val result = MemoryExtractionParser.parse(raw)
        assertEquals(listOf("准备驾照考试", "喜欢直接建议"), result.newMemories)
        val profile = result.updatedProfile
        requireNotNull(profile)
        assertEquals(listOf("大学生"), profile.stableFacts)
        assertEquals(1, profile.personality.size)
        assertEquals(0.6, profile.personality[0].confidence, 0.0001)
    }

    @Test
    fun `parse old array format falls back and caps at two`() {
        val raw = """["记忆A", "记忆B", "记忆C", "记忆D"]"""
        val result = MemoryExtractionParser.parse(raw)
        assertEquals(listOf("记忆A", "记忆B"), result.newMemories)
        assertNull(result.updatedProfile)
    }

    @Test
    fun `parse invalid json returns empty without throwing`() {
        val result = MemoryExtractionParser.parse("这不是JSON@@@")
        assertTrue(result.newMemories.isEmpty())
        assertNull(result.updatedProfile)
    }

    @Test
    fun `parse object with bad profile still keeps memories`() {
        val raw = """
            {
              "newMemories": ["只保留这条"],
              "updatedProfile": "not-an-object"
            }
        """.trimIndent()
        val result = MemoryExtractionParser.parse(raw)
        assertEquals(listOf("只保留这条"), result.newMemories)
        assertNull(result.updatedProfile)
    }

    @Test
    fun `parse object with bad memories still keeps profile`() {
        val raw = """
            {
              "newMemories": "错误类型",
              "updatedProfile": {
                "preferences": ["喜欢直接建议"]
              }
            }
        """.trimIndent()
        val result = MemoryExtractionParser.parse(raw)
        assertTrue(result.newMemories.isEmpty())
        val profile = result.updatedProfile
        requireNotNull(profile)
        assertEquals(listOf("喜欢直接建议"), profile.preferences)
    }

    @Test
    fun `damaged profile json returns empty profile`() {
        val profile = UserProfileCodec.fromJson("{not valid json")
        assertTrue(profile.isEmpty())
    }

    @Test
    fun `empty profile summary is placeholder`() {
        assertEquals("暂无可靠画像", UserProfileCodec.toPromptSummary(UserProfile()))
    }

    @Test
    fun `summary includes preferences and filters expired recent states`() {
        val profile = UserProfile(
            preferences = listOf("喜欢直接建议"),
            supportStyle = listOf("少说教"),
            personality = listOf(PersonalityTrait("容易自我反思", 0.5)),
            recentStates = listOf(
                RecentState("已经过期的状态", "2020-01-01"),
                RecentState("近期备考驾照", "2099-12-31")
            )
        )
        val summary = UserProfileCodec.toPromptSummary(profile)
        assertTrue(summary.contains("喜欢直接建议"))
        assertTrue(summary.contains("近期备考驾照"))
        assertTrue(!summary.contains("已经过期的状态"))
        assertTrue(summary.contains("中等置信度"))
    }

    @Test
    fun `sanitize clamps confidence and list sizes`() {
        val raw = UserProfile(
            stableFacts = (1..12).map { "事实$it" },
            personality = listOf(
                PersonalityTrait("  冷静  ", 1.5),
                PersonalityTrait("冷静", 0.2),
                PersonalityTrait("", 0.9)
            ),
            recentStates = (1..8).map { RecentState("状态$it", "2099-01-01") }
        )
        val cleaned = UserProfileCodec.sanitize(raw, updatedAt = "2026-07-29T00:00:00.000Z")
        assertEquals(8, cleaned.stableFacts.size)
        assertEquals(1, cleaned.personality.size)
        assertEquals(1.0, cleaned.personality[0].confidence, 0.0001)
        assertEquals(5, cleaned.recentStates.size)
        assertEquals("2026-07-29T00:00:00.000Z", cleaned.updatedAt)
    }

    @Test
    fun `mergeWith keeps old profile when AI returns empty`() {
        val old = UserProfile(
            preferences = listOf("喜欢直接建议"),
            supportStyle = listOf("先共情")
        )
        val empty = UserProfile()
        val merged = UserProfileCodec.mergeWith(old, empty)
        assertEquals(listOf("喜欢直接建议"), merged.preferences)
        assertEquals(listOf("先共情"), merged.supportStyle)
    }

    @Test
    fun `mergeWith merges partial profile without clearing`() {
        val old = UserProfile(
            stableFacts = listOf("大学生"),
            preferences = listOf("喜欢直接建议")
        )
        val update = UserProfile(
            preferences = listOf("喜欢直接建议", "讨厌说教"),
            recentStates = listOf(RecentState("近期备考驾照", "2099-12-31"))
        )
        val merged = UserProfileCodec.mergeWith(old, update)
        assertEquals(listOf("大学生"), merged.stableFacts)
        assertEquals(listOf("喜欢直接建议", "讨厌说教"), merged.preferences)
        assertEquals(1, merged.recentStates.size)
    }

    @Test
    fun `filterNewMemories dedups against existing and caps at two`() {
        val existing = listOf(
            MemoryEntity(id = 1, content = "已有记忆", source = "review", createdAt = "a", updatedAt = "a"),
            MemoryEntity(id = 2, content = "  Like Coffee  ", source = "review", createdAt = "a", updatedAt = "a")
        )
        val candidates = listOf(
            "  已有记忆  ",
            "like coffee",
            "新记忆一",
            "新记忆一",
            "新记忆二",
            "新记忆三",
            "   "
        )
        val result = ReviewViewModel.filterNewMemories(candidates, existing)
        assertEquals(listOf("新记忆一", "新记忆二"), result)
    }

    @Test
    fun `filterNewMemories uses Locale ROOT`() {
        val existing = listOf(
            MemoryEntity(id = 1, content = "I Like Coffee", source = "review", createdAt = "a", updatedAt = "a")
        )
        val candidates = listOf("i like coffee", "新记忆")
        val result = ReviewViewModel.filterNewMemories(candidates, existing)
        assertEquals(listOf("新记忆"), result)
    }

    @Test
    fun `illegal confidence does not throw`() {
        val raw = UserProfile(
            personality = listOf(
                PersonalityTrait("冷静", Double.NaN),
                PersonalityTrait("内向", Double.POSITIVE_INFINITY),
                PersonalityTrait("外向", 0.8)
            )
        )
        val cleaned = UserProfileCodec.sanitize(raw)
        assertEquals(1, cleaned.personality.size)
        assertEquals(0.8, cleaned.personality[0].confidence, 0.0001)
    }

    @Test
    fun `summary never exceeds 500 chars`() {
        val longPrefs = (1..50).map { "偏好内容很长很长很长很长很长很长很长很长$it" }
        val profile = UserProfile(preferences = longPrefs)
        val summary = UserProfileCodec.toPromptSummary(profile)
        assertTrue(summary.length <= 501) // 500 + ellipsis
    }
}