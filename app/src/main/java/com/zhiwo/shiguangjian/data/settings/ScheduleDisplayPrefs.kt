package com.zhiwo.shiguangjian.data.settings

import com.google.gson.JsonParser
import com.google.gson.JsonObject

/**
 * 安排页的显示偏好：段顺序与显隐。存 settings 表的一个 JSON 键。
 * 坏 JSON / 缺段 / 多段一律回退到"默认顺序 + 未知丢弃 + 缺失追加"，不抛异常。
 */
enum class ScheduleSectionId { DAILY_FIXED, DAILY_BLANK, ADHOC_TODAY, MISC_TODO, OVERDUE, MISSED, PLANS, GOALS, DONE_TODAY, UNFILLED }

data class ScheduleDisplayPrefs(
    val order: List<ScheduleSectionId>,
    val hidden: Set<ScheduleSectionId>
) {
    val visibleOrder: List<ScheduleSectionId> get() = order.filterNot { it in hidden }

    companion object {
        const val SETTINGS_KEY = "schedule_display_prefs_v1"

        val DEFAULT = ScheduleDisplayPrefs(ScheduleSectionId.values().toList(), emptySet())

        fun parse(json: String?): ScheduleDisplayPrefs {
            if (json.isNullOrBlank()) return DEFAULT
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                val known = ScheduleSectionId.values().toSet()
                val order = mutableListOf<ScheduleSectionId>()
                obj.optJsonArray("order")?.forEachString { name ->
                    known.firstOrNull { it.name == name }?.takeIf { it !in order }?.let { order += it }
                }
                // 新版本新增的段自动追加到末尾；全被隐藏的旧配置回退默认，避免页面空白
                ScheduleSectionId.values().forEach { if (it !in order) order += it }
                val hidden = mutableSetOf<ScheduleSectionId>()
                obj.optJsonArray("hidden")?.forEachString { name ->
                    known.firstOrNull { it.name == name }?.let { hidden += it }
                }
                val safe = if (hidden.size >= ScheduleSectionId.values().size) DEFAULT.hidden else hidden
                ScheduleDisplayPrefs(order, safe)
            } catch (_: RuntimeException) {
                DEFAULT
            }
        }

        fun encode(prefs: ScheduleDisplayPrefs): String {
            val obj = JsonObject()
            obj.addProperty("v", 1)
            obj.add("order", JsonArray_.of(prefs.order.map { it.name }))
            obj.add("hidden", JsonArray_.of(prefs.hidden.map { it.name }))
            return obj.toString()
        }

        private fun JsonObject.optJsonArray(key: String) =
            if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

        private fun com.google.gson.JsonArray.forEachString(block: (String) -> Unit) {
            for (i in 0 until size()) {
                val e = get(i)
                if (e.isJsonPrimitive) block(e.asString)
            }
        }

        private object JsonArray_ {
            fun of(values: List<String>): com.google.gson.JsonArray =
                com.google.gson.JsonArray().apply { values.forEach { add(it) } }
        }

        fun move(order: List<ScheduleSectionId>, id: ScheduleSectionId, delta: Int): List<ScheduleSectionId> {
            val from = order.indexOf(id)
            if (from < 0) return order
            val to = (from + delta).coerceIn(0, order.lastIndex)
            if (from == to) return order
            return order.toMutableList().also { it.add(to, it.removeAt(from)) }
        }
    }
}
