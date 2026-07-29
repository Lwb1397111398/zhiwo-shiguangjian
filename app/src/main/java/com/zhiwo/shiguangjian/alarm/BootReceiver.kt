package com.zhiwo.shiguangjian.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zhiwo.shiguangjian.ZhiwoApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    private var scope: CoroutineScope? = null

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "开机完成，重新设置闹钟")
            try {
                // 恢复固定闹钟
                AlarmScheduler.scheduleAllAlarms(context)
            } catch (e: Exception) {
                Log.e("BootReceiver", "开机重新设置固定闹钟失败: ${e.message}", e)
            }
            // 恢复任务提醒闹钟
            val pendingResult = goAsync()
            restoreTaskAlarms(context.applicationContext, pendingResult)
        }
    }

    private fun restoreTaskAlarms(context: Context, pendingResult: PendingResult) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this.scope = scope
        scope.launch {
            try {
                val app = context.applicationContext as? ZhiwoApplication ?: return@launch
                val taskDao = app.database.taskDao()
                // 获取所有未完成的任务
                val allTasks = taskDao.getAllTasksList()
                val now = System.currentTimeMillis()
                var restored = 0
                for (task in allTasks) {
                    if (task.isCompleted) continue
                    if (task.dueDate.isBlank()) continue
                    val startMillis = parseDueDate(task.dueDate) ?: continue
                    // 跳过已过期超过 1 小时的任务
                    if (startMillis < now - 3600_000) continue
                    val alarmId = (task.id + 10000).toInt()
                    val triggerAtMillis = startMillis - 15 * 60 * 1000 // 提前 15 分钟
                    if (triggerAtMillis > now) {
                        AlarmScheduler.scheduleTaskAlarm(
                            context = context,
                            alarmId = alarmId,
                            triggerAtMillis = triggerAtMillis,
                            title = task.content,
                            message = "⏰ 15分钟后：${task.content}"
                        )
                        restored++
                    }
                    // 对于 daily 类型，恢复明天的提醒
                    if (task.taskType == "daily") {
                        AlarmScheduler.scheduleTaskAlarm(
                            context = context,
                            alarmId = alarmId + 1, // 使用不同 ID 避免冲突
                            triggerAtMillis = startMillis + 24 * 3600_000 - 15 * 60 * 1000,
                            title = task.content,
                            message = "⏰ 15分钟后：${task.content}"
                        )
                    }
                }
                Log.d("BootReceiver", "任务闹钟恢复完成，恢复了 $restored 个提醒")
            } catch (e: Exception) {
                Log.e("BootReceiver", "恢复任务闹钟失败: ${e.message}", e)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private fun parseDueDate(dueDate: String): Long? {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .parse(dueDate)?.time
        } catch (_: Exception) {
            try {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .parse(dueDate)?.time?.plus(9 * 3600 * 1000)
            } catch (_: Exception) {
                null
            }
        }
    }
}
