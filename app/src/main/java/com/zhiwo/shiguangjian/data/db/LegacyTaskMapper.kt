package com.zhiwo.shiguangjian.data.db

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 老 tasks 表一行 → 新模型列值 + 历史打卡记录。纯函数，JVM 可测；迁移只负责搬运。
 * 任何解析失败都不丢任务：回退到最保守的取值并把问题记进 issues。
 */

const val MIGRATION_DATE_COLUMN = "2026-09-21"

data class LegacyTaskRow(
    val id: Long,
    val content: String,
    val recordId: Long?,
    val parentGoalId: Long?,
    val dueDate: String,
    val taskType: String,
    val isCompleted: Boolean,
    val completedAt: String?,
    val dailyCompletionDate: String?,
    val isPermanentlyCompleted: Boolean,
    val calendarEventId: Long?,
    val createdAt: String
)

data class MappedOccurrence(val taskId: Long, val date: String, val status: String)

data class MappedTask(
    val id: Long,
    val kind: String,
    val repeatRule: String,
    val weekdaysCsv: String,
    val intervalDays: Int,
    val dayPolicy: String,
    val startDate: String,
    val endDate: String,
    val scheduledDate: String,
    val remindTime: String,
    val durationMinutes: Int,
    val goalId: Long?,
    val status: String,
    val occurrences: List<MappedOccurrence>
)

/** 历史上日期列混存了 "yyyy-MM-dd"、"yyyy-MM-dd HH:mm:ss"、ISO 带 T 三种写法，取前 10 位统一收口 */
fun parseLooseDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return try {
        LocalDate.parse(value.trim().take(10), DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: Exception) {
        null
    }
}

/** "HH:mm"；老 dueDate 把提醒时间塞在日期后面，不提出来的话迁移后每日提醒会集体消失 */
fun parseLooseTime(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val body = value.trim()
    val spacePart = body.substringAfter(' ', "")
    val candidate = if (spacePart.isNotBlank()) spacePart else body.substringAfter('T', "")
    val m = Regex("""^(\d{1,2}):(\d{2})""").find(candidate.trim()) ?: return ""
    val h = m.groupValues[1].toIntOrNull() ?: return ""
    val min = m.groupValues[2].toIntOrNull() ?: return ""
    if (h > 23 || min > 59) return ""
    return "%02d:%02d".format(h, min)
}

private fun isoDayOfWeek(date: LocalDate): String = when (date.dayOfWeek.value) {
    in 1..7 -> date.dayOfWeek.value.toString()
    else -> ""
}

fun mapLegacyTask(
    row: LegacyTaskRow,
    goalIdOf: (Long) -> Long?,
    today: String
): Pair<MappedTask, List<String>> {
    val issues = mutableListOf<String>()
    val due = parseLooseDate(row.dueDate)
    val type = row.taskType.trim().lowercase()

    val kind: String
    var repeatRule: String
    var weekdaysCsv = ""
    var startDate = ""
    var scheduledDate = ""
    when (type) {
        "daily" -> { kind = "daily"; repeatRule = "everyday" }
        "weekly" -> {
            kind = "daily"; repeatRule = "custom"
            if (due == null) {
                repeatRule = "everyday"
                issues += "WEEKLY_NO_DUEDATE#${row.id}"
            } else {
                weekdaysCsv = isoDayOfWeek(due)
                startDate = MIGRATION_DATE_COLUMN   // 否则回填前每个历史周三都被当成"漏做"
            }
        }
        "once", "goal" -> { kind = "adhoc"; repeatRule = "everyday"; scheduledDate = due?.toString() ?: "" }
        else -> { kind = "adhoc"; repeatRule = "everyday"; scheduledDate = due?.toString() ?: ""; issues += "UNKNOWN_TASKTYPE#${row.id}:$type" }
    }

    val goalId = row.parentGoalId?.let { goalIdOf(it) }
        ?: if (type == "goal") row.recordId?.let { goalIdOf(it) } else null

    val occurrences = mutableListOf<MappedOccurrence>()
    if (row.isCompleted) {
        val date = if (type == "daily") parseLooseDate(row.dailyCompletionDate)?.toString() else null
        // 没有真实完成日期就用迁移日兜底；绝不能用 createdAt——"创建"不等于"完成"
        val when1 = date ?: parseLooseDate(row.completedAt)?.toString() ?: today
        occurrences += MappedOccurrence(row.id, when1, "done")
    } else if (row.dailyCompletionDate?.isNotBlank() == true) {
        issues += "COMPLETION_DATE_WITHOUT_DONE#${row.id}"
    }

    val status = if (row.isPermanentlyCompleted) "archived" else "active"

    return MappedTask(
        id = row.id,
        kind = kind,
        repeatRule = repeatRule,
        weekdaysCsv = weekdaysCsv,
        intervalDays = 1,
        dayPolicy = "all",
        startDate = startDate,
        endDate = "",
        scheduledDate = scheduledDate,
        remindTime = parseLooseTime(row.dueDate),
        durationMinutes = 0,
        goalId = goalId,
        status = status,
        occurrences = occurrences
    ) to issues
}

/** 老目标记录 → goals.status：completeGoal/abandonGoal 会把 category 改成 completed，靠类别还原 */
fun mapLegacyGoalStatus(category: String): String = if (category == "goal") "active" else "achieved"
