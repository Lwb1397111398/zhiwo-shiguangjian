package com.zhiwo.shiguangjian.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

object AlarmScheduler {

    private val FIXED_ALARM_IDS = listOf(
        AlarmIds.MORNING_TASKS, AlarmIds.EVENING_TASKS, AlarmIds.DAILY_REVIEW, AlarmIds.WEEKLY_REVIEW
    )

    /**
     * 固定提醒（1001-1004）纳入"智能提醒"开关：
     * 开启时正常调度，关闭时取消全部固定闹钟（任务级闹钟不受此开关影响）。
     * 调用方需在协程中执行（读设置走 Room）。
     */
    suspend fun syncFixedAlarms(context: Context) {
        val app = context.applicationContext as? com.zhiwo.shiguangjian.ZhiwoApplication ?: run {
            scheduleAllAlarms(context)
            return
        }
        val enabled = try {
            app.database.settingDao().getSettingValue("smartReminder") != "false"
        } catch (_: Throwable) {
            true
        }
        if (enabled) {
            scheduleAllAlarms(context)
        } else {
            cancelFixedAlarms(context)
        }
    }

    /** 取消全部固定提醒闹钟 */
    fun cancelFixedAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        FIXED_ALARM_IDS.forEach { id ->
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
        Log.d("AlarmScheduler", "智能提醒已关闭，固定闹钟全部取消")
    }

    fun scheduleAllAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Log.e("AlarmScheduler", "无法获取 AlarmManager，跳过闹钟设置")
            return
        }

        // 早8点任务提醒
        scheduleAlarm(
            context, alarmManager,
            AlarmData(
                id = AlarmIds.MORNING_TASKS,
                type = AlarmType.MORNING_TASKS,
                hour = 8, minute = 0,
                message = "📋 今天有任务等你完成，加油！"
            )
        )

        // 晚6点未完成任务提醒
        scheduleAlarm(
            context, alarmManager,
            AlarmData(
                id = AlarmIds.EVENING_TASKS,
                type = AlarmType.EVENING_TASKS,
                hour = 18, minute = 0,
                message = "⏰ 还有未完成的任务，别忘了哦~"
            )
        )

        // 晚9点每日评价提醒
        scheduleAlarm(
            context, alarmManager,
            AlarmData(
                id = AlarmIds.DAILY_REVIEW,
                type = AlarmType.DAILY_REVIEW,
                hour = 21, minute = 0,
                message = "🌙 今天过得怎么样？来记录一下今天的感受吧~"
            )
        )

        // 周日晚8点每周报告提醒
        scheduleAlarm(
            context, alarmManager,
            AlarmData(
                id = AlarmIds.WEEKLY_REVIEW,
                type = AlarmType.WEEKLY_REVIEW,
                hour = 20, minute = 0,
                dayOfWeek = Calendar.SUNDAY,
                message = "📊 本周回顾已生成，来看看你这周的精彩瞬间吧~"
            )
        )

        Log.d("AlarmScheduler", "所有闹钟已设置")
    }

    private fun scheduleAlarm(
        context: Context,
        alarmManager: AlarmManager,
        data: AlarmData
    ) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("alarm_id", data.id)
            putExtra("alarm_type", data.type.name)
            putExtra("alarm_message", data.message)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, data.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, data.hour)
            set(Calendar.MINUTE, data.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            data.dayOfWeek?.let { set(Calendar.DAY_OF_WEEK, it) }

            // 如果今天的时间已过，设置为明天/下周
            if (timeInMillis <= System.currentTimeMillis()) {
                if (data.dayOfWeek != null) {
                    add(Calendar.WEEK_OF_YEAR, 1)
                } else {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }

        try {
            // setRepeating 在 API 19+ 已是不精确的，无需精确闹钟权限
            // 但 Android 12+ 上如果用户关闭了精确闹钟权限，setRepeating 仍可正常使用
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                if (data.dayOfWeek != null) AlarmManager.INTERVAL_DAY * 7 else AlarmManager.INTERVAL_DAY,
                pendingIntent
            )
            Log.d("AlarmScheduler", "闹钟已设置: ${data.type} at ${data.hour}:${data.minute}")
        } catch (e: SecurityException) {
            // Android 12+ 精确闹钟权限被拒绝时的降级处理
            Log.w("AlarmScheduler", "精确闹钟权限不可用，降级为不精确闹钟: ${e.message}")
            try {
                alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    if (data.dayOfWeek != null) AlarmManager.INTERVAL_DAY * 7 else AlarmManager.INTERVAL_DAY,
                    pendingIntent
                )
            } catch (e2: Exception) {
                Log.e("AlarmScheduler", "降级设置闹钟也失败: ${e2.message}", e2)
            }
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "设置闹钟失败: ${e.message}", e)
        }
    }

    /**
     * 为任务创建一次性精确闹钟（提前提醒）
     */
    fun scheduleTaskAlarm(
        context: Context,
        alarmId: Int,
        triggerAtMillis: Long,
        title: String,
        message: String
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("alarm_id", alarmId)
            putExtra("alarm_type", AlarmType.TASK_REMINDER.name)
            putExtra("alarm_message", message)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, alarmId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // 精确闹钟权限不可用，降级为不精确一次性闹钟
                Log.w("AlarmScheduler", "精确闹钟权限不可用，降级为普通闹钟")
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            Log.d("AlarmScheduler", "任务闹钟已设置: id=$alarmId, title=$title, at=$triggerAtMillis")
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "设置任务闹钟失败: ${e.message}", e)
        }
    }

    /**
     * 为重复任务创建不精确重复闹钟（daily/weekly）
     * 使用 setInexactRepeating 节省电量，系统会批量处理重复闹钟
     */
    fun scheduleRepeatingTaskAlarm(
        context: Context,
        alarmId: Int,
        firstTriggerAtMillis: Long,
        intervalMillis: Long,
        @Suppress("UNUSED_PARAMETER") title: String,
        message: String
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("alarm_id", alarmId)
            putExtra("alarm_type", AlarmType.TASK_REMINDER.name)
            putExtra("alarm_message", message)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, alarmId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                firstTriggerAtMillis,
                intervalMillis,
                pendingIntent
            )
            Log.d("AlarmScheduler", "重复任务闹钟已设置: id=$alarmId, interval=${intervalMillis / 60000}min")
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "设置重复任务闹钟失败: ${e.message}", e)
        }
    }

    /**
     * 取消任务闹钟
     */
    fun cancelTaskAlarm(context: Context, alarmId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // 取消主闹钟
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, alarmId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        // 同时取消可能存在的重复闹钟（ID+1）
        val pendingIntent2 = PendingIntent.getBroadcast(
            context, alarmId + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent2)
        Log.d("AlarmScheduler", "任务闹钟已取消: id=$alarmId")
    }

    fun cancelAllAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Log.e("AlarmScheduler", "无法获取 AlarmManager，跳过取消闹钟")
            return
        }
        listOf(
            AlarmIds.MORNING_TASKS,
            AlarmIds.EVENING_TASKS,
            AlarmIds.DAILY_REVIEW,
            AlarmIds.WEEKLY_REVIEW
        ).forEach { id ->
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }
}
