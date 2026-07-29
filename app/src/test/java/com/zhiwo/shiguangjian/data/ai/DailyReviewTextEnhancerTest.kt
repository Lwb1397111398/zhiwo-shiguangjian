package com.zhiwo.shiguangjian.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyReviewTextEnhancerTest {

    @Test
    fun `enhanceDailyReviewText adds gentle symbol when main text has none`() {
        val text = "今天你完成了学习计划，也认真照顾了自己的节奏。\n\n🌟 今日寄语：慢慢来，也是在往前走。"

        val enhanced = enhanceDailyReviewText(text)

        assertTrue(enhanced.startsWith("🌿 "))
        assertTrue(enhanced.contains("🌟 今日寄语：慢慢来，也是在往前走。"))
    }

    @Test
    fun `enhanceDailyReviewText keeps existing emoji and blessing structure`() {
        val text = "✨ 今天很棒，你把任务一点点完成了。\n\n🌟 今日寄语：继续保持。"

        val enhanced = enhanceDailyReviewText(text)

        assertEquals(text, enhanced)
    }
}
