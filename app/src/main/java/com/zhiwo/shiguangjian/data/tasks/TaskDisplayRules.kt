package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import java.time.LocalDate
import java.time.LocalDateTime

fun isTaskEffectivelyCompleted(task: TaskEntity, today: String = DateFormats.nowDate()): Boolean {
    if (task.isPermanentlyCompleted) return true
    if (task.taskType == "daily") {
        return task.isCompleted && task.dailyCompletionDate == today
    }
    return task.isCompleted
}

fun getTaskDisplayDate(task: TaskEntity, today: String = DateFormats.nowDate()): String {
    return when (task.taskType) {
        "daily", "goal" -> today
        "weekly" -> nextWeeklyDate(task.dueDate, today)
        else -> parseDateOnly(task.dueDate)?.format(DateFormats.DATE) ?: task.dueDate.take(10)
    }
}

fun parseDateOnly(value: String): LocalDate? {
    if (value.isBlank()) return null
    return try {
        LocalDateTime.parse(value, DateFormats.DATE_TIME_FULL).toLocalDate()
    } catch (_: Throwable) {
        try {
            LocalDate.parse(value.take(10), DateFormats.DATE)
        } catch (_: Throwable) {
            null
        }
    }
}

private fun nextWeeklyDate(dueDate: String, today: String): String {
    val due = parseDateOnly(dueDate) ?: return ""
    val base = parseDateOnly(today) ?: LocalDate.now()
    val taskDay = due.dayOfWeek.value
    val currentDay = base.dayOfWeek.value
    val diff = if (taskDay >= currentDay) taskDay - currentDay else taskDay - currentDay + 7
    return base.plusDays(diff.toLong()).format(DateFormats.DATE)
}
