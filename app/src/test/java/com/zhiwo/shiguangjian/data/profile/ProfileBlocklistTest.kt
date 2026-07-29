package com.zhiwo.shiguangjian.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBlocklistTest {

    @Test
    fun `serialize roundtrip`() {
        val original = ProfileBlocklist(
            preferences = listOf("喜欢直接建议"),
            personalityTraits = listOf("容易自我反思")
        )
        val json = ProfileBlocklistCodec.toJson(original)
        val restored = ProfileBlocklistCodec.fromJson(json)
        assertEquals(listOf("喜欢直接建议"), restored.preferences)
        assertEquals(listOf("容易自我反思"), restored.personalityTraits)
    }

    @Test
    fun `damaged json returns empty`() {
        val empty = ProfileBlocklistCodec.fromJson("{not-json")
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `filterBlocked removes matching entries case insensitive`() {
        val profile = UserProfile(
            stableFacts = listOf("大学生"),
            preferences = listOf("喜欢直接建议", "讨厌说教"),
            supportStyle = listOf("先共情"),
            appearanceFacts = listOf("戴眼镜"),
            recentStates = listOf(RecentState("备考驾照", "2099-01-01")),
            personality = listOf(PersonalityTrait("容易自我反思", 0.6))
        )
        val blocklist = ProfileBlocklist(
            preferences = listOf("  喜欢直接建议  "),
            recentStates = listOf("备考驾照"),
            personalityTraits = listOf("容易自我反思")
        )
        val filtered = profile.filterBlocked(blocklist)
        assertEquals(listOf("大学生"), filtered.stableFacts)
        assertEquals(listOf("讨厌说教"), filtered.preferences)
        assertEquals(listOf("先共情"), filtered.supportStyle)
        assertEquals(listOf("戴眼镜"), filtered.appearanceFacts)
        assertTrue(filtered.recentStates.isEmpty())
        assertTrue(filtered.personality.isEmpty())
    }

    @Test
    fun `fifo max 50 and dedup`() {
        var bl = ProfileBlocklist()
        repeat(55) { i ->
            bl = ProfileBlocklistCodec.blocked(bl, BlocklistCategory.PREFERENCES, "item$i")
        }
        assertEquals(ProfileBlocklistCodec.MAX_PER_CATEGORY, bl.preferences.size)
        assertEquals("item5", bl.preferences.first())
        assertEquals("item54", bl.preferences.last())
        // duplicate ignored
        val same = ProfileBlocklistCodec.blocked(bl, BlocklistCategory.PREFERENCES, "ITEM10")
        assertEquals(bl.preferences.size, same.preferences.size)
    }

    @Test
    fun `unblocked removes by normalized key`() {
        val bl = ProfileBlocklist(preferences = listOf("A", "B"))
        val next = ProfileBlocklistCodec.unblocked(bl, BlocklistCategory.PREFERENCES, " a ")
        assertEquals(listOf("B"), next.preferences)
    }
}
