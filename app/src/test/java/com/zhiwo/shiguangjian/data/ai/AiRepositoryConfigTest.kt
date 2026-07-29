package com.zhiwo.shiguangjian.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRepositoryConfigTest {

    @Test
    fun `isConfigured is false before configure`() {
        val repository = AiRepository()

        assertFalse(repository.isConfigured)
    }

    @Test
    fun `isConfigured is false when base url has no scheme`() {
        val repository = AiRepository()

        repository.configure("api.example.com/v1", "key", "model")

        assertFalse(repository.isConfigured)
    }

    @Test
    fun `isConfigured is true when retrofit service initializes`() {
        val repository = AiRepository()

        repository.configure("https://api.example.com/v1", "key", "model")

        assertTrue(repository.isConfigured)
    }
}
