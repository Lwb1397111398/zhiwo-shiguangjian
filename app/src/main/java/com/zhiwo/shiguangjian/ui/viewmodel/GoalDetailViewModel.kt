package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.alarm.SmartScheduleManager
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.DayOverrideEntity
import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import com.zhiwo.shiguangjian.data.festival.HolidayCalendar
import com.zhiwo.shiguangjian.data.repository.TaskOccurrenceRepository
import com.zhiwo.shiguangjian.data.repository.TaskWriteBridge
import com.zhiwo.shiguangjian.data.tasks.DayType
import com.zhiwo.shiguangjian.data.tasks.ProgressOwner
import com.zhiwo.shiguangjian.data.tasks.ProgressSpan
import com.zhiwo.shiguangjian.data.tasks.dayTypeOf
import com.zhiwo.shiguangjian.data.tasks.entriesFor
import com.zhiwo.shiguangjian.data.tasks.isScheduledOn
import com.zhiwo.shiguangjian.data.tasks.progressOf
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 详情页要显示的进度空壳：归属行还没读到时用，percent=-1 一律显示"暂无排期" */
internal val EMPTY_SPAN = ProgressSpan(0, 0, 0, -1, 0, "", "", false)

/**
 * 日型判定：与安排页同一口径。内置节假日表缺这一年时退化成"按周末判"，
 * 页面必须同时给出 holidayWarning，否则用户会以为休息日判定是准的。
 */
internal fun dayTypeResolver(overrides: List<DayOverrideEntity>): (String) -> DayType? {
    val overrideByDate = overrides.associate { it.date to it.type }
    return { date ->
        val year = ScheduleViewModel.yearOf(date)
        dayTypeOf(
            date, HolidayCalendar.holidaysOf(year), HolidayCalendar.makeupWorkdaysOf(year),
            overrideByDate[date]
        ) ?: run {
            val d = ScheduleViewModel.parse(date) ?: return@run DayType.WORKDAY
            if (d.dayOfWeek.value <= 5) DayType.WORKDAY else DayType.HOLIDAY
        }
    }
}

internal fun holidayWarningOf(today: String): String? =
    if (HolidayCalendar.hasDataFor(ScheduleViewModel.yearOf(today))) null
    else "今年尚未录入法定节假日安排，带「只在工作日/只在休息日」的任务暂按周末判定"

/** 剩余天数：负数表示已过期，null 表示长期（没有截止日） */
internal fun daysBetween(today: String, target: String): Int? {
    if (target.isBlank()) return null
    val t = ScheduleViewModel.parse(today) ?: return null
    val end = ScheduleViewModel.parse(target) ?: return null
    return ChronoUnit.DAYS.between(t, end).toInt()
}

private data class HierarchySnapshot(
    val tasks: List<TaskEntity>,
    val plans: List<PlanEntity>,
    val goals: List<GoalEntity>,
    val occurrences: List<TaskOccurrenceEntity>,
    val overrides: List<DayOverrideEntity>
)

/** 一张计划卡要显示的东西 */
data class PlanRow(
    val plan: PlanEntity,
    val span: ProgressSpan,
    val taskCount: Int,
    /** 临时任务过了预定那天仍没做的条数 */
    val overdueCount: Int
)

data class GoalDetailUiState(
    val loading: Boolean = true,
    val goal: GoalEntity? = null,
    val plans: List<PlanRow> = emptyList(),
    val directTasks: List<TaskEntity> = emptyList(),
    val overall: ProgressSpan = EMPTY_SPAN,
    val todayDue: Int = 0,
    val todayDone: Int = 0,
    val daysLeft: Int? = null,
    val planCount: Int = 0,
    val taskCount: Int = 0,
    val holidayWarning: String? = null
)

/**
 * 目标详情页的数据装配 +「达成 / 放弃」两个终结动作。
 * 判断一律不自建：某天该不该做问 TaskScheduleEngine，累计问 ProgressCalculator。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val db = app.database
    private val goalDao = db.goalDao()
    private val planDao = db.planDao()
    private val taskDao = db.taskDao()
    private val bridge = TaskWriteBridge(db)
    private val occurrences = TaskOccurrenceRepository(db)

    val today: String = DateFormats.nowDate()

    /** 打卡只观察最近一年：进度窗本身也被 progressOf 截到一年 */
    private val rangeFrom: String =
        (ScheduleViewModel.parse(today)?.minusDays(365) ?: LocalDate.now()).toString()

    private val _goalId = MutableStateFlow(0L)

    private val snapshot = combine(
        taskDao.getAllTasks(),
        planDao.getAllPlans(),
        goalDao.getAllGoals(),
        db.occurrenceDao().observeInRange(rangeFrom, today),
        db.dayOverrideDao().getAll()
    ) { tasks, plans, goals, occ, overrides -> HierarchySnapshot(tasks, plans, goals, occ, overrides) }

    val state: StateFlow<GoalDetailUiState> = _goalId.flatMapLatest { id ->
        snapshot.map { buildState(id, it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalDetailUiState())

    fun open(goalId: Long) {
        if (_goalId.value != goalId) _goalId.value = goalId
    }

    private fun buildState(goalId: Long, snap: HierarchySnapshot): GoalDetailUiState {
        val goal = snap.goals.firstOrNull { it.id == goalId }
            ?: return GoalDetailUiState(loading = false, goal = null)
        val resolver = dayTypeResolver(snap.overrides)
        val childPlans = snap.plans.filter { it.goalId == goalId }
        val childPlanIds = childPlans.map { it.id }.toSet()
        val inPlan = { t: TaskEntity -> t.planId?.let { p -> childPlanIds.contains(p) } == true }
        val ownedTasks = snap.tasks.filter { inPlan(it) || (it.planId == null && it.goalId == goalId) }
        val directTasks = snap.tasks.filter { it.planId == null && it.goalId == goalId }

        // 整棵层级一起交给纯函数算，避免"只传子集导致归属解析不到"
        val progress = progressOf(
            snap.tasks, snap.plans, snap.goals, snap.occurrences,
            goal.startDate.ifBlank { rangeFrom }, today, resolver
        )
        val planStatusById = snap.plans.associate { it.id to it.status }
        val goalStatusById = snap.goals.associate { it.id to it.status }
        val dayPlan = entriesFor(
            date = today, tasks = ownedTasks, occurrences = snap.occurrences,
            dayType = resolver(today), planStatusById = planStatusById, goalStatusById = goalStatusById
        )
        return GoalDetailUiState(
            loading = false,
            goal = goal,
            plans = childPlans.map { plan ->
                val tasks = snap.tasks.filter { it.planId == plan.id }
                PlanRow(
                    plan = plan,
                    span = progress[ProgressOwner.OfPlan(plan.id)] ?: EMPTY_SPAN,
                    taskCount = tasks.size,
                    overdueCount = overdueAdhoc(tasks, snap.occurrences)
                )
            },
            directTasks = directTasks,
            overall = progress[ProgressOwner.OfGoal(goalId)] ?: EMPTY_SPAN,
            todayDue = dayPlan.dueCount,
            todayDone = dayPlan.doneCount,
            daysLeft = daysBetween(today, goal.targetDate),
            planCount = childPlans.size,
            taskCount = ownedTasks.size,
            holidayWarning = holidayWarningOf(today)
        )
    }

    /** 逾期 = 临时任务过了它那天（无 scheduledDate 时看期限）还没有任何完成记录 */
    private fun overdueAdhoc(tasks: List<TaskEntity>, occs: List<TaskOccurrenceEntity>): Int {
        val doneIds = occs.filter { it.status == "done" }.map { it.taskId }.toSet()
        return tasks.count { t ->
            val day = if (t.scheduledDate.isNotBlank()) t.scheduledDate.take(10) else t.dueDate.take(10)
            t.kind.trim().lowercase() == "adhoc" && t.status.trim().lowercase() == "active" &&
                t.id !in doneIds && day.isNotBlank() && day < today
        }
    }

    // ========== 编辑 ==========

    fun saveGoal(goal: GoalEntity) = viewModelScope.launch {
        val now = DateFormats.nowDateTimeIso()
        if (goal.id == 0L) goalDao.insert(goal.copy(createdAt = now, updatedAt = now))
        else goalDao.update(goal.copy(updatedAt = now))
    }

    // ========== 达成 / 放弃 ==========

    fun achieveGoal(goalId: Long) = finishGoal(goalId, achieved = true)

    fun abandonGoal(goalId: Long) = finishGoal(goalId, achieved = false)

    /**
     * 目标终结：daily/blank 归档（adhoc 不动，它是那一天的一次性事实），
     * 未完成者补一条 not_done + changed，免得它们以"漏做"的样子留在安排页。
     */
    private fun finishGoal(goalId: Long, achieved: Boolean) = viewModelScope.launch {
        val snap = snapshot.first()
        val goal = snap.goals.firstOrNull { it.id == goalId } ?: return@launch
        val childPlans = snap.plans.filter { it.goalId == goalId }
        val childPlanIds = childPlans.map { it.id }.toSet()
        val owned = snap.tasks.filter {
            it.planId?.let { p -> childPlanIds.contains(p) } == true ||
                (it.planId == null && it.goalId == goalId)
        }
        val resolver = dayTypeResolver(snap.overrides)
        val planStatusById = snap.plans.associate { it.id to it.status }
        val goalStatusById = snap.goals.associate { it.id to it.status }
        val doneEver = snap.occurrences.filter { it.status == "done" }.map { it.taskId }.toSet()
        val note = if (achieved) "目标已达成" else "目标已放弃"
        val now = DateFormats.nowDateTimeIso()

        db.withTransaction {
            goalDao.update(goal.copy(status = if (achieved) "achieved" else "abandoned", updatedAt = now))
            childPlans.forEach {
                planDao.update(it.copy(status = if (achieved) "done" else "dropped", updatedAt = now))
            }
            owned.forEach { task ->
                val kind = task.kind.trim().lowercase()
                if (kind == "adhoc") {
                    // adhoc 是"那天的一次性事实"，不动它的状态，只把还欠着的那条坐实
                    val day = if (task.scheduledDate.isNotBlank()) task.scheduledDate.take(10) else task.dueDate.take(10)
                    val owed = task.status.trim().lowercase() == "active" && task.id !in doneEver &&
                        day.isNotBlank() && day <= today
                    if (owed) occurrences.setCheck(task.id, day, "not_done", "changed", note, now = now)
                } else {
                    // 归档后引擎不再排它，历史漏做也随之消失；今天这条 not_done 只是把当天坐实
                    if (achieved) bridge.archive(task) else taskDao.updateTask(task.copy(status = "archived"))
                    val doneToday = snap.occurrences.any {
                        it.taskId == task.id && it.status == "done" && it.date == today
                    }
                    val scheduledToday = isScheduledOn(
                        task, today, resolver(today),
                        task.planId?.let { planStatusById[it] }, task.goalId?.let { goalStatusById[it] }
                    )
                    if (!doneToday && scheduledToday) {
                        occurrences.setCheck(task.id, today, "not_done", "changed", note, now = now)
                    }
                }
            }
        }
        owned.forEach { SmartScheduleManager.cancelAlarm(app, it.id, it.calendarEventId) }
        notifyGoalOutcome(goal, achieved)
    }

    /** 用应用级作用域把目标结局同步给记忆对账，避免页面退出被取消 */
    private fun notifyGoalOutcome(goal: GoalEntity, achieved: Boolean) {
        val outcome = if (achieved) "已完成" else "已放弃（未达成）"
        app.appScope.launch {
            try {
                app.memoryReconciler.reconcileAndApply(
                    newContent = "目标「${goal.title}」$outcome，这是用户确认的事实",
                    now = DateFormats.nowDateTimeIso(),
                    sourceRecordId = goal.recordId
                )
            } catch (e: Throwable) {
                Log.e("GoalDetailVM", "目标结局同步记忆失败（忽略）", e)
            }
        }
    }
}
