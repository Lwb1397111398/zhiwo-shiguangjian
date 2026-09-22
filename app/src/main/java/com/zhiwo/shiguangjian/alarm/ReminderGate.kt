package com.zhiwo.shiguangjian.alarm

import com.zhiwo.shiguangjian.data.db.AppDatabase
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import com.zhiwo.shiguangjian.data.festival.HolidayCalendar
import com.zhiwo.shiguangjian.data.tasks.DayType
import com.zhiwo.shiguangjian.data.tasks.deadlineDate
import com.zhiwo.shiguangjian.data.tasks.dayTypeOrWeekend
import com.zhiwo.shiguangjian.data.tasks.isEffectivelyDone
import com.zhiwo.shiguangjian.data.tasks.isScheduledOn

/**
 * 闹钟响之前问一句「今天到底该不该提醒」。
 *
 * `AlarmManager` 的重复闹钟表达不了"只在工作日做""每周三做""隔三天做"，
 * 所以注册侧照旧挂每日重复（自愈、不依赖续排链），由这里在触发时把不该响的日子挡掉。
 */
object ReminderGate {

    /** 纯判定，不碰 Android 也不碰数据库 —— 单测直接打这里 */
    fun shouldRemind(
        task: TaskEntity,
        today: String,
        occurrence: TaskOccurrenceEntity?,
        overrideType: String?,
        holidays: Set<String>,
        makeupWorkdays: Set<String>,
        planStatus: String? = null,
        goalStatus: String? = null
    ): Boolean {
        if (task.status.trim().lowercase() != "active") return false
        if (today.isBlank()) return false
        val day = today.take(10)
        // 引擎的 isScheduledOn 对 adhoc 只看区间、不看"哪一天出现"（闹钟与排期页对同一件事的两套判据），
        // 在这里补上：临时任务只在它那一天响
        if (task.kind.trim().lowercase() == "adhoc") {
            val scheduled = task.scheduledDate.take(10)
            if (scheduled.isNotEmpty()) return day == scheduled && !isEffectivelyDone(occurrence, today)
            val deadline = task.deadlineDate.take(10)
            if (deadline.isNotEmpty() && day > deadline) return false
        }
        val dayType = dayTypeOrWeekend(today, holidays, makeupWorkdays, overrideType)
        if (!isScheduledOn(task, today, dayType, planStatus, goalStatus)) return false
        return !isEffectivelyDone(occurrence, today)
    }

    /** 从库里凑齐判定材料；任务查不到时返回 false（任务已不存在，不该再响） */
    suspend fun shouldRemindNow(
        ctx: android.content.Context,
        db: AppDatabase,
        taskId: Long,
        today: String
    ): Boolean {
        val task = db.taskDao().getTaskById(taskId) ?: return false
        val year = today.take(4).toIntOrNull()
        // 内置公告优先，公告没覆盖那一年才读系统日历（见 HolidaySets）；两边都没有就是空集，按周末猜
        val sets: Pair<Set<String>, Set<String>> =
            if (year == null) emptySet<String>() to emptySet<String>()
            else com.zhiwo.shiguangjian.data.festival.HolidaySets.of(ctx, year)
        val (holidays, makeup) = sets
        return shouldRemind(
            task = task,
            today = today,
            occurrence = db.occurrenceDao().findByTaskAndDate(taskId, today),
            overrideType = db.dayOverrideDao().get(today)?.type,
            holidays = holidays,
            makeupWorkdays = makeup,
            planStatus = task.planId?.let { db.planDao().getById(it)?.status },
            goalStatus = task.goalId?.let { db.goalDao().getById(it)?.status }
        )
    }
}
