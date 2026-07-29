package com.zhiwo.shiguangjian.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zhiwo.shiguangjian.MainActivity
import com.zhiwo.shiguangjian.R

object NotificationHelper {

    const val CHANNEL_TASKS = "channel_tasks"
    const val CHANNEL_REVIEWS = "channel_reviews"
    const val CHANNEL_GENERAL = "channel_general"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val taskChannel = NotificationChannel(
                CHANNEL_TASKS,
                "任务提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "每日任务和习惯提醒"
                enableVibration(true)
            }

            val reviewChannel = NotificationChannel(
                CHANNEL_REVIEWS,
                "评价提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "每日评价和每周报告提醒"
            }

            val generalChannel = NotificationChannel(
                CHANNEL_GENERAL,
                "通用通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "通用应用通知"
            }

            manager.createNotificationChannels(listOf(taskChannel, reviewChannel, generalChannel))
        }
    }

    fun showNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        message: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("NotificationHelper", "通知权限未授权，跳过发送通知")
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(notificationId, notification)
    }
}
