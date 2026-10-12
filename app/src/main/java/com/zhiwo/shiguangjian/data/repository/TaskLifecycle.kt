package com.zhiwo.shiguangjian.data.repository

import android.app.Application
import com.zhiwo.shiguangjian.alarm.SmartScheduleManager
import com.zhiwo.shiguangjian.data.db.AppDatabase
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity

/**
 * 任务状态迁移的唯一编排（生命周期设计 §三-3）：安排页、计划总览页都调这里，别处不再各自拼一遍。
 *
 * 两条铁律：
 * ① 进非 active（暂停/结束）必停闹钟并删日历事件；回 active 必按最新行状态重新评估注册；
 * ② 勾选完成/撤销**不停铃**——当天误响由 ReminderGate 拦，每日任务的重复铃被停掉就再也回不来
 *   （v13 之前正是这样把每日提醒弄哑的，见模块总览第七节）。
 */
object TaskLifecycle {

    suspend fun changeStatus(db: AppDatabase, app: Application, task: TaskEntity, status: String) {
        val bridge = TaskWriteBridge(db)
        when (status) {
            "archived" -> bridge.archive(task)
            "paused" -> bridge.pause(task)
            else -> bridge.resume(task)
        }
        if (status != "active") {
            SmartScheduleManager.cancelAlarm(app, task.id, task.calendarEventId)
        } else {
            registerReminder(db, app, task.id)
        }
    }

    /** 物理删除：先停闹钟和系统日历事件，再删任务行（打卡按外键 CASCADE 随删）。 */
    suspend fun deleteForever(db: AppDatabase, app: Application, task: TaskEntity) {
        SmartScheduleManager.cancelAlarm(app, task.id, task.calendarEventId)
        db.taskDao().deleteTaskById(task.id)
    }

    /**
     * 保存/恢复后按最新行状态注册提醒：无提醒时间或非 active 一律反向清干净（防僵尸闹钟）；
     * 否则注册并把系统日历事件 id 回写——不存 id 下次就删不掉那个事件。
     */
    suspend fun registerReminder(db: AppDatabase, app: Application, taskId: Long) {
        val saved = db.taskDao().getTaskById(taskId) ?: return
        if (saved.remindTime.isBlank() || saved.status != "active") {
            SmartScheduleManager.cancelAlarm(app, saved.id, saved.calendarEventId)
            return
        }
        val autoCalendar = db.settingDao().getSettingValue("autoCalendarSync") != "false"
        val recordTitle = saved.recordId?.let { db.recordDao().getRecordById(it)?.title } ?: "手动新建"
        val (eventId, _) = SmartScheduleManager.scheduleTask(
            context = app,
            taskId = saved.id,
            taskContent = saved.content,
            taskType = saved.taskType,
            dueDate = saved.dueDate,
            recordTitle = recordTitle,
            syncCalendar = autoCalendar
        )
        if (eventId != null && eventId != saved.calendarEventId) {
            db.taskDao().updateTask(saved.copy(calendarEventId = eventId))
        }
    }
}
