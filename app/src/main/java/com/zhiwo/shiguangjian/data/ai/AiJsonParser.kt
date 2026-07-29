package com.zhiwo.shiguangjian.data.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object AiJsonParser {
    private val jsonFenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
    private val tildeFenceRegex = Regex("~~~(?:json)?\\s*([\\s\\S]*?)~~~")

    fun parseObject(raw: String): JsonObject = JsonParser.parseString(clean(raw)).asJsonObject

    fun parseArray(raw: String): JsonArray = JsonParser.parseString(clean(raw)).asJsonArray

    private fun clean(raw: String): String {
        val trimmed = raw.trim()
        // 1. 尝试匹配代码围栏（``` 和 ~~~）
        val fenceMatch = jsonFenceRegex.find(trimmed) ?: tildeFenceRegex.find(trimmed)
        if (fenceMatch != null) return fenceMatch.groupValues[1].trim()

        // 2. 尝试直接解析
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed

        // 3. 从文本中提取 JSON 子串（查找第一个 { 或 [ 到最后一个 } 或 ]）
        val firstBrace = trimmed.indexOfFirst { it == '{' || it == '[' }
        val lastBrace = trimmed.indexOfLast { it == '}' || it == ']' }
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1)
        }

        return trimmed
    }
}
