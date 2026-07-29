package com.zhiwo.shiguangjian.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zhiwo.shiguangjian.notification.NotificationHelper

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper.createNotificationChannels(context)
        val alarmType = intent.getStringExtra("alarm_type") ?: return
        val message = intent.getStringExtra("alarm_message") ?: return

        Log.d("AlarmReceiver", "收到闹钟: $alarmType - $message")

        val channelId = when (alarmType) {
            AlarmType.DAILY_REVIEW.name, AlarmType.WEEKLY_REVIEW.name ->
                NotificationHelper.CHANNEL_REVIEWS
            else -> NotificationHelper.CHANNEL_TASKS
        }

        val title = when (alarmType) {
            AlarmType.MORNING_TASKS.name -> "早安提醒"
            AlarmType.EVENING_TASKS.name -> "任务提醒"
            AlarmType.DAILY_REVIEW.name -> "每日评价"
            AlarmType.WEEKLY_REVIEW.name -> "每周报告"
            AlarmType.TASK_REMINDER.name -> "⏰ 任务提醒"
            else -> "知我时光笺"
        }

        NotificationHelper.showNotification(
            context = context,
            channelId = channelId,
            notificationId = intent.getIntExtra("alarm_id", 0),
            title = title,
            message = message
        )
    }
}
