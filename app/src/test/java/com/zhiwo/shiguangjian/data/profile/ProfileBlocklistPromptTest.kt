package com.zhiwo.shiguangjian.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBlocklistPromptTest {

    @Test
    fun `empty blocklist renders empty string`() {
        assertEquals("", ProfileBlocklistPrompt.render(ProfileBlocklist()))
    }

    @Test
    fun `non empty contains title and items`() {
        val bl = ProfileBlocklist(
            preferences = listOf("喜欢直接建议"),
            personalityTraits = listOf("容易自我反思")
        )
        val text = ProfileBlocklistPrompt.render(bl)
        assertTrue(text.contains("【禁止再次学习的内容】"))
        assertTrue(text.contains("喜欢直接建议"))
        assertTrue(text.contains("容易自我反思"))
        assertTrue(text.contains("- "))
    }

    @Test
    fun `more than 30 items keeps newest 30`() {
        val prefs = (1..40).map { "pref$it" }
        val bl = ProfileBlocklist(preferences = prefs)
        val text = ProfileBlocklistPrompt.render(bl)
        assertTrue(text.contains("pref40"))
        assertTrue(text.contains("pref11"))
        assertFalse(text.contains("pref10")) // 1..10 dropped if takeLast 30 of 40
        val bulletCount = text.lines().count { it.startsWith("- ") }
        assertEquals(30, bulletCount)
    }

    @Test
    fun `long item is clamped to 100 chars`() {
        val long = "x".repeat(150)
        val bl = ProfileBlocklist(stableFacts = listOf(long))
        val text = ProfileBlocklistPrompt.render(bl)
        assertFalse(text.contains("x".repeat(101)))
        assertTrue(text.contains("x".repeat(100)))
    }

    @Test
    fun `empty inject removes placeholder without leaving title or extra blank lines`() {
        val template = """
头部

{{blocked_items}}
规则：
1. 测试
""".trimIndent()
        val out = ProfileBlocklistPrompt.injectIntoTemplate(template, "{{blocked_items}}", "")
        assertFalse(out.contains("{{blocked_items}}"))
        assertFalse(out.contains("禁止再次学习"))
        assertFalse(out.contains("\n\n\n"))
        assertTrue(out.contains("规则："))
    }

    @Test
    fun `non empty inject keeps content at placeholder`() {
        val template = "前\n{{blocked_items}}\n后"
        val rendered = ProfileBlocklistPrompt.render(
            ProfileBlocklist(preferences = listOf("A"))
        )
        val out = ProfileBlocklistPrompt.injectIntoTemplate(template, "{{blocked_items}}", rendered)
        assertTrue(out.contains("【禁止再次学习的内容】"))
        assertTrue(out.contains("- A"))
        assertTrue(out.startsWith("前\n"))
        assertTrue(out.endsWith("\n后"))
    }
}
