package com.zhiwo.shiguangjian.data.ai

import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Test

class AiJsonParserTest {

    @Test
    fun `parseObject reads plain json object`() {
        val parsed = AiJsonParser.parseObject(" { \"title\": \"记录\" } ")

        assertEquals("记录", parsed.get("title").asString)
    }

    @Test
    fun `parseObject reads json object from markdown fence`() {
        val parsed = AiJsonParser.parseObject("""
            ```json
            { "category": "todo" }
            ```
        """.trimIndent())

        assertEquals("todo", parsed.get("category").asString)
    }

    @Test
    fun `parseArray reads json array from markdown fence`() {
        val parsed = AiJsonParser.parseArray("""
            ```json
            ["偏好", "目标"]
            ```
        """.trimIndent())

        assertEquals("偏好", parsed[0].asString)
        assertEquals("目标", parsed[1].asString)
    }

    @Test(expected = JsonSyntaxException::class)
    fun `parseObject rejects invalid json`() {
        AiJsonParser.parseObject("不是 JSON")
    }
}
