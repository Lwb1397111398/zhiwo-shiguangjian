package com.zhiwo.shiguangjian.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiModelsTest {

    @Test
    fun `resolveCategory trims english values before matching`() {
        assertEquals("todo", resolveCategory(" todo "))
        assertEquals("goal", resolveCategory(" GOAL "))
        assertEquals("study", resolveCategory(" study "))
    }

    @Test
    fun `resolveCategory trims chinese values before fuzzy matching`() {
        assertEquals("todo", resolveCategory(" 待办 "))
        assertEquals("emotion", resolveCategory(" 心情 "))
    }

    @Test
    fun `resolveCategory falls back to other for blank or unknown values`() {
        assertEquals("other", resolveCategory(null))
        assertEquals("other", resolveCategory("   "))
        assertEquals("other", resolveCategory("无法识别的分类"))
    }
}
