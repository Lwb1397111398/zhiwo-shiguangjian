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

/**
 * 开机后把闹钟请回来。
 *
 * v13 这里自己读 `isCompleted/dueDate/taskType` 拼一次性闹钟，于是每日任务重启后只响"今天+明天"两枪，
 * 之后静默。现在把注册交给与"保存任务"完全同一条路径（`SmartScheduleManager.scheduleTask`）：
 * 每日/留白挂系统级每日重复闹钟（自带自愈，不依赖任何续排链），临时任务挂一次性，
 * 该不该响由 [ReminderGate] 在触发时判。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d("BootReceiver", "开机完成，重新设置闹钟")
        val pendingResult = goAsync()
        restore(context.applicationContext, pendingResult)
    }

    private fun restore(context: Context, pendingResult: PendingResult) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val startedAt = System.currentTimeMillis()
            var restored = 0
            var cleaned = 0
            var skippedExpired = 0
            try {
                try {
                    AlarmScheduler.syncFixedAlarms(context)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "开机重新设置固定闹钟失败: ${e.message}", e)
                }
                val app = context as? ZhiwoApplication ?: return@launch
                val tasks = app.database.taskDao().getAllTasksList()
                val now = System.currentTimeMillis()
                for (task in tasks.take(MAX_TASKS_AT_BOOT)) {
                    val repeating = task.kind.trim().lowercase() in REPEATING_KINDS
                    val active = task.status.trim().lowercase() == "active"
                    // 提醒时刻可以来自新 remindTime，也可以来自老 dueDate 里带的时间（v13 迁移进来的任务只有后者）
                    val hasTime = task.remindTime.isNotBlank() || task.dueDate.contains(' ')
                    val deadline = if (repeating) null else parseLooseDateTime(task.dueDate)
                    if (!active || !hasTime || (!repeating && deadline != null && deadline < now - GRACE_MILLIS)) {
                        // v13 只看 isCompleted，暂停/归档的任务重启后照样响；这里反过来：不该提醒的一律清干净
                        cancelEveryAlarmId(context, task.id)
                        cleaned++
                        if (!repeating && deadline != null && deadline < now - GRACE_MILLIS) skippedExpired++
                        continue
                    }
                    val (_, alarmId) = SmartScheduleManager.scheduleTask(
                        context = context,
                        taskId = task.id,
                        taskContent = task.content,
                        taskType = task.taskType,
                        dueDate = task.dueDate,
                        recordTitle = task.content,
                        syncCalendar = false
                    )
                    if (alarmId != 0) restored++
                }
                Log.d(
                    "BootReceiver",
                    "任务闹钟恢复完成：注册 $restored，清理 $cleaned（其中过期一次性 $skippedExpired），" +
                        "共 ${tasks.size} 个任务，耗时 ${System.currentTimeMillis() - startedAt}ms"
                )
            } catch (e: Exception) {
                Log.e("BootReceiver", "恢复任务闹钟失败: ${e.message}", e)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private fun cancelEveryAlarmId(context: Context, taskId: Long) {
        AlarmScheduler.cancelTaskAlarm(context, SmartScheduleManager.taskIdToAlarmId(taskId))
        ReminderIds.legacyIds(taskId).forEach { AlarmScheduler.cancelTaskAlarm(context, it) }
    }

    /** 老 dueDate 可能是 "yyyy-MM-dd HH:mm:ss" 也可能只有日期（只有日期时按 09:00 算） */
    private fun parseLooseDateTime(value: String): Long? {
        if (value.isBlank()) return null
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .parse(value.take(19))?.time
        } catch (_: Exception) {
            try {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .parse(value.take(10))?.time?.plus(9 * 3600_000)
            } catch (_: Exception) {
                null
            }
        }
    }

    private companion object {
        const val GRACE_MILLIS = 3600_000L
        const val MAX_TASKS_AT_BOOT = 500
        val REPEATING_KINDS = setOf("daily", "blank")
    }
}
