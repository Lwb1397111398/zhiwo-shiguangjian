package com.zhiwo.shiguangjian.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmType = intent.getStringExtra("alarm_type") ?: return
        val message = intent.getStringExtra("alarm_message") ?: return
        val alarmId = intent.getIntExtra("alarm_id", 0)

        // 固定闹钟（1001~1004）与升级前留下的旧码解不出任务，一律照原样响：
        // 宁可多响一次，也不能让判定失败变成"再也不响"
        val taskId = if (alarmType == AlarmType.TASK_REMINDER.name) ReminderIds.taskIdOf(alarmId) else null
        val app = context.applicationContext as? ZhiwoApplication
        if (taskId == null || app == null) {
            deliver(context, alarmType, message, alarmId)
            return
        }

        // 重复闹钟表达不了"只在工作日做/每周三做/隔三天做"，也不认打卡状态 —— 触发时问引擎一句
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val should = try {
                    ReminderGate.shouldRemindNow(context, app.database, taskId, DateFormats.nowDate())
                } catch (e: Exception) {
                    Log.e("AlarmReceiver", "提醒前判定失败，按原样提醒: taskId=$taskId ${e.message}", e)
                    true
                }
                if (should) deliver(context, alarmType, message, alarmId)
                else Log.d("AlarmReceiver", "今天不该提醒，已跳过: taskId=$taskId")
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private fun deliver(context: Context, alarmType: String, message: String, alarmId: Int) {
        NotificationHelper.createNotificationChannels(context)
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
            notificationId = alarmId,
            title = title,
            message = message
        )
    }
}
