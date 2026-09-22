package com.zhiwo.shiguangjian.data.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object AiJsonParser {
    private val jsonFenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
    private val tildeFenceRegex = Regex("~~~(?:json)?\\s*([\\s\\S]*?)~~~")

    fun parseObject(raw: String): JsonObject = JsonParser.parseString(clean(raw)).asJsonObject

    fun parseArray(raw: String): JsonArray = JsonParser.parseString(clean(raw)).asJsonArray

    /**
     * 解析"可清理项"：兼容两种写法
     * - `{"text": "...", "items": [1,3]}` —— items 是 prompt 里条目的 1 基序号（清理提案只删自己那一组的前提）
     * - `"..."` —— 老模型只回文本，序号为空，由 [com.zhiwo.shiguangjian.data.organizeops.DeleteSourcePlanner]
     *   退回文本匹配；匹配不到就**不生成清理提案**，绝不退化成"删全部所选"。
     */
    fun parseCleanable(array: JsonArray?): List<CleanableItem> {
        if (array == null) return emptyList()
        val out = mutableListOf<CleanableItem>()
        for (element in array) {
            try {
                if (element.isJsonPrimitive) {
                    val text = element.asString.trim()
                    if (text.isNotEmpty()) out += CleanableItem(text)
                } else if (element.isJsonObject) {
                    val obj = element.asJsonObject
                    val text = (obj.get("text") ?: obj.get("content"))?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                    if (text.isEmpty()) continue
                    val indexes = ((obj.get("items") ?: obj.get("sources"))?.takeIf { it.isJsonArray }?.asJsonArray)
                        ?.let { arr -> (0 until arr.size()).mapNotNull { i -> runCatching { arr.get(i).asInt }.getOrNull() } }
                        ?.filter { it >= 1 }
                        ?.distinct()
                        ?: emptyList()
                    out += CleanableItem(text, indexes)
                }
            } catch (_: Throwable) {
                // 单条损坏跳过，不影响其余
            }
        }
        return out
    }

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
