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

    /** 任务闹钟 ID 的唯一换算公式，所有注册/取消/恢复路径必须统一使用 */
    fun taskIdToAlarmId(taskId: Long): Int = ReminderIds.of(taskId)

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
        recordTitle: String,
        syncCalendar: Boolean = true
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

        // 系统日历事件是"可选副作用"：关掉时不建，也不因为没建就不响闹钟
        val eventId = if (!syncCalendar) null else CalendarHelper.addEvent(
            context,
            CalendarEvent(
                title = taskContent,
                description = "来自「$recordTitle」",
                startTime = startMillis,
                endTime = endMillis,
                allDay = false
            )
        )

        // 创建提醒闹钟（与取消/开机恢复统一使用同一换算公式）
        val alarmId = taskIdToAlarmId(taskId)
        // 一个任务只有一个码：先清掉旧公式留下的两个码，否则升级后新旧两把闹钟各响一次
        ReminderIds.legacyIds(taskId).forEach { AlarmScheduler.cancelTaskAlarm(context, it) }

        when (taskType) {
            "daily" -> {
                // 每日重复：从今天开始每天同一时间提醒
                val remindMillis = startMillis - REMIND_MINUTES_BEFORE * 60 * 1000
                val firstTrigger = ReminderIds.nextRepeatingTrigger(
                    remindMillis, AlarmManager.INTERVAL_DAY, System.currentTimeMillis()
                )
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
                val firstTrigger = ReminderIds.nextRepeatingTrigger(
                    remindMillis, AlarmManager.INTERVAL_DAY * 7, System.currentTimeMillis()
                )
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
        // 两个 id 都要取消：BootReceiver 会为 daily 任务额外注册"明日提醒"，
        // 只取消当日那个会让它变成僵尸闹钟，重启后还被再注册一次。
        AlarmScheduler.cancelTaskAlarm(context, taskIdToAlarmId(taskId))
        ReminderIds.legacyIds(taskId).forEach { AlarmScheduler.cancelTaskAlarm(context, it) }
        calendarEventId?.let { CalendarHelper.deleteEvent(context, it) }
    }
}
