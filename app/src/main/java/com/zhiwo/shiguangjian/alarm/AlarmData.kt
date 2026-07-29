package com.zhiwo.shiguangjian.alarm

data class AlarmData(
    val id: Int,
    val type: AlarmType,
    val hour: Int,
    val minute: Int,
    val dayOfWeek: Int? = null,
    val message: String
)

enum class AlarmType {
    MORNING_TASKS,      // 早8点任务提醒
    EVENING_TASKS,      // 晚6点未完成任务提醒
    DAILY_REVIEW,       // 晚9点每日评价提醒
    WEEKLY_REVIEW,      // 周日晚8点每周报告提醒
    TASK_REMINDER       // 任务到期前15分钟提醒
}

object AlarmIds {
    const val MORNING_TASKS = 1001
    const val EVENING_TASKS = 1002
    const val DAILY_REVIEW = 1003
    const val WEEKLY_REVIEW = 1004
}
