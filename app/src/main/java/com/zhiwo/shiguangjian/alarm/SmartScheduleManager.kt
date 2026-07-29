package com.zhiwo.shiguangjian.alarm

import android.app.AlarmManager
import android.content.Context
import android.provider.CalendarContract
import com.zhiwo.shiguangjian.calendar.CalendarEvent
import com.zhiwo.shiguangjian.calendar.CalendarHelper
import java.text.SimpleDateFormat
import java.util.*

/**
 * 智能日程调度器：根据 AI 解析的任务自动创建日历事件和提醒闹钟
 */
object SmartScheduleManager {

    private const val REMIND_MINUTES_BEFORE = 15L  // 提前15分钟提醒
    private const val EVENT_DURATION_MILLIS = 60 * 60 * 1000L  // 事件持续1小时

    /**
     * 为任务创建日历事件和提醒闹钟
     * @param context 上下文
     * @param taskId 任务ID（用于生成闹钟ID）
     * @param taskContent 任务内容
     * @param taskType 任务类型（once/daily/weekly）
     * @param dueDate 截止时间，格式 "YYYY-MM-DD HH:mm:ss" 或 "YYYY-MM-DD"
     * @param recordTitle 来源记录标题（作为日历事件描述）
     * @return Pair<calendarEventId, alarmId>，失败时对应值为 null
     */
    fun scheduleTask(
        context: Context,
        taskId: Long,
        taskContent: String,
        taskType: String,
        dueDate: String,
        recordTitle: String
    ): Pair<Long?, Int> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val shortFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        // 解析截止时间
        val startMillis: Long = try {
            dateFormat.parse(dueDate)?.time
        } catch (_: Exception) {
            try {
                // 只有日期没有时间，补上 09:00
                shortFormat.parse(dueDate)?.time?.plus(9 * 3600 * 1000)
            } catch (_: Exception) {
                null
            }
        } ?: return Pair(null, 0)

        val endMillis = startMillis + EVENT_DURATION_MILLIS

        // 创建日历事件
        val eventId = CalendarHelper.addEvent(
            context,
            CalendarEvent(
                title = taskContent,
                description = "来自「$recordTitle」",
                startTime = startMillis,
                endTime = endMillis,
                allDay = false
            )
        )

        // 创建提醒闹钟（取模确保不溢出）
        val alarmId = ((taskId % (Int.MAX_VALUE - 10000)) + 10000).toInt()

        when (taskType) {
            "daily" -> {
                // 每日重复：从今天开始每天同一时间提醒
                val remindMillis = startMillis - REMIND_MINUTES_BEFORE * 60 * 1000
                val firstTrigger = if (remindMillis > System.currentTimeMillis()) {
                    remindMillis
                } else {
                    // 今天时间已过，从明天开始
                    remindMillis + AlarmManager.INTERVAL_DAY
                }
                AlarmScheduler.scheduleRepeatingTaskAlarm(
                    context = context,
                    alarmId = alarmId,
                    firstTriggerAtMillis = firstTrigger,
                    intervalMillis = AlarmManager.INTERVAL_DAY,
                    title = taskContent,
                    message = "⏰ 每日提醒：$taskContent"
                )
            }
            "weekly" -> {
                // 每周重复：每周同一时间提醒
                val remindMillis = startMillis - REMIND_MINUTES_BEFORE * 60 * 1000
                val firstTrigger = if (remindMillis > System.currentTimeMillis()) {
                    remindMillis
                } else {
                    // 本周时间已过，从下周开始
                    remindMillis + AlarmManager.INTERVAL_DAY * 7
                }
                AlarmScheduler.scheduleRepeatingTaskAlarm(
                    context = context,
                    alarmId = alarmId,
                    firstTriggerAtMillis = firstTrigger,
                    intervalMillis = AlarmManager.INTERVAL_DAY * 7,
                    title = taskContent,
                    message = "⏰ 每周提醒：$taskContent"
                )
            }
            else -> {
                // 一次性任务：提前15分钟提醒
                val remindMillis = startMillis - REMIND_MINUTES_BEFORE * 60 * 1000
                if (remindMillis > System.currentTimeMillis()) {
                    AlarmScheduler.scheduleTaskAlarm(
                        context = context,
                        alarmId = alarmId,
                        triggerAtMillis = remindMillis,
                        title = taskContent,
                        message = "⏰ 15分钟后：$taskContent"
                    )
                }
            }
        }

        return Pair(eventId, alarmId)
    }

    fun cancelAlarm(context: Context, taskId: Long, calendarEventId: Long? = null) {
        val alarmId = (taskId + 10000).toInt()
        AlarmScheduler.cancelTaskAlarm(context, alarmId)
        calendarEventId?.let { CalendarHelper.deleteEvent(context, it) }
    }
}
