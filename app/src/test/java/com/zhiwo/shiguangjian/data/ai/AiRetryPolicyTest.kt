package com.zhiwo.shiguangjian.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRetryPolicyTest {

    @Test
    fun `transient server and network errors are retryable`() {
        assertTrue(AiRepository.isRetryableMessage("Request timed out"))
        assertTrue(AiRepository.isRetryableMessage("network connect failed"))
        assertTrue(AiRepository.isRetryableMessage("Connection reset"))
        assertTrue(AiRepository.isRetryableMessage("请求失败(429)"))
        assertTrue(AiRepository.isRetryableMessage("请求失败(503)：Service Unavailable"))
        assertTrue(AiRepository.isRetryableMessage("请求过于频繁，请稍后重试"))
        assertTrue(AiRepository.isRetryableMessage("API服务器暂时不可用"))
        assertTrue(AiRepository.isRetryableMessage("网络连接失败，请检查API配置和网络"))
    }

    @Test
    fun `client errors are not retryable`() {
        assertFalse(AiRepository.isRetryableMessage("API Key无效或已过期"))        // 401
        assertFalse(AiRepository.isRetryableMessage("没有权限访问此API"))          // 403
        assertFalse(AiRepository.isRetryableMessage("API地址不正确"))              // 404
        assertFalse(AiRepository.isRetryableMessage("API 响应格式异常：缺少 choices 字段"))
        assertFalse(AiRepository.isRetryableMessage(null))
        assertFalse(AiRepository.isRetryableMessage(""))
    }

    @Test
    fun `retry budget is two`() {
        org.junit.Assert.assertEquals(2, AiRepository.MAX_RETRIES)
    }
}
