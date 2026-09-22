package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.DayOverrideEntity
import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import com.zhiwo.shiguangjian.data.repository.TaskWriteBridge
import com.zhiwo.shiguangjian.data.tasks.DayType
import com.zhiwo.shiguangjian.data.tasks.ProgressOwner
import com.zhiwo.shiguangjian.data.tasks.ProgressSpan
import com.zhiwo.shiguangjian.data.tasks.entriesFor
import com.zhiwo.shiguangjian.data.tasks.isScheduledOn
import com.zhiwo.shiguangjian.data.tasks.progressOf
import java.time.LocalDate
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

/** 近 7 天格子的四种样子：实心=做完、空心圈=该做没做、浅点=记了原因、空白=那天没排 */
enum class WeekCellKind { NONE, DONE, MISSED, SKIPPED }

data class WeekCell(val date: String, val kind: WeekCellKind)

/** 详情页装配用的快照（五路 Flow 一起到达，避免读到半新半旧的层级） */
private data class PlanSnapshot(
    val tasks: List<TaskEntity>,
    val plans: List<PlanEntity>,
    val goals: List<GoalEntity>,
    val occurrences: List<TaskOccurrenceEntity>,
    val overrides: List<DayOverrideEntity>
)

data class PlanTaskRow(
    val task: TaskEntity,
    val scheduledToday: Boolean,
    val doneToday: Boolean,
    val cells: List<WeekCell>
)

data class PlanDetailUiState(
    val loading: Boolean = true,
    val plan: PlanEntity? = null,
    val goal: GoalEntity? = null,
    val span: ProgressSpan = EMPTY_SPAN,
    val daysLeft: Int? = null,
    val todayDue: Int = 0,
    val todayDone: Int = 0,
    val tasks: List<PlanTaskRow> = emptyList(),
    /** 该计划下任务的打卡条数：删除确认时要如实报数 */
    val occurrenceCount: Int = 0,
    val holidayWarning: String? = null
)

/** 计划详情页的数据装配 + 计划级动作（暂停/恢复/删除） */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val db = app.database
    private val planDao = db.planDao()
    private val goalDao = db.goalDao()
    private val taskDao = db.taskDao()
    private val bridge = TaskWriteBridge(db)

    val today: String = DateFormats.nowDate()
    private val rangeFrom: String =
        (ScheduleViewModel.parse(today)?.minusDays(365) ?: LocalDate.now()).toString()

    private val _planId = MutableStateFlow(0L)

    private val snapshot = combine(
        taskDao.getAllTasks(),
        planDao.getAllPlans(),
        goalDao.getAllGoals(),
        db.occurrenceDao().observeInRange(rangeFrom, today),
        db.dayOverrideDao().getAll()
    ) { tasks, plans, goals, occ, overrides -> PlanSnapshot(tasks, plans, goals, occ, overrides) }

    val state: StateFlow<PlanDetailUiState> = _planId.flatMapLatest { id ->
        snapshot.map { buildState(id, it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanDetailUiState())

    /** 编辑计划时"所属目标"的候选（含"无"由 UI 自己加） */
    val goalChoices: StateFlow<List<GoalEntity>> = goalDao.getAllGoals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun open(planId: Long) {
        if (_planId.value != planId) _planId.value = planId
    }

    private fun buildState(planId: Long, snap: PlanSnapshot): PlanDetailUiState {
        val plan = snap.plans.firstOrNull { it.id == planId }
            ?: return PlanDetailUiState(loading = false, plan = null)
        val resolver = dayTypeResolver(snap.overrides)
        val goal = plan.goalId?.let { id -> snap.goals.firstOrNull { it.id == id } }
        val planStatusById = snap.plans.associate { it.id to it.status }
        val goalStatusById = snap.goals.associate { it.id to it.status }
        val tasks = snap.tasks.filter { it.planId == planId }
        val progress = progressOf(
            snap.tasks, snap.plans, snap.goals, snap.occurrences,
            plan.startDate.ifBlank { rangeFrom }, today, resolver
        )
        val dayPlan = entriesFor(
            date = today, tasks = tasks, occurrences = snap.occurrences,
            dayType = resolver(today), planStatusById = planStatusById, goalStatusById = goalStatusById
        )
        val todayDate = ScheduleViewModel.parse(today) ?: LocalDate.now()
        return PlanDetailUiState(
            loading = false,
            plan = plan,
            goal = goal,
            span = progress[ProgressOwner.OfPlan(planId)] ?: EMPTY_SPAN,
            daysLeft = daysBetween(today, plan.endDate),
            todayDue = dayPlan.dueCount,
            todayDone = dayPlan.doneCount,
            tasks = tasks.map { task ->
                PlanTaskRow(
                    task = task,
                    scheduledToday = isScheduledOn(
                        task, today, resolver(today),
                        task.planId?.let { planStatusById[it] }, task.goalId?.let { goalStatusById[it] }
                    ),
                    doneToday = snap.occurrences.any {
                        it.taskId == task.id && it.date == today && it.status == "done"
                    },
                    cells = (6L downTo 0L).map { back ->
                        val key = todayDate.minusDays(back).toString()
                        weekCellOf(task, key, snap, resolver, planStatusById, goalStatusById)
                    }
                )
            },
            occurrenceCount = snap.occurrences.count { o -> tasks.any { it.id == o.taskId } },
            holidayWarning = holidayWarningOf(today)
        )
    }

    private fun weekCellOf(
        task: TaskEntity,
        date: String,
        snap: PlanSnapshot,
        resolver: (String) -> DayType?,
        planStatusById: Map<Long, String>,
        goalStatusById: Map<Long, String>
    ): WeekCell {
        val occ = snap.occurrences.firstOrNull { it.taskId == task.id && it.date == date }
        val kind = when {
            occ?.status == "done" -> WeekCellKind.DONE
            occ?.status == "not_done" -> WeekCellKind.SKIPPED
            !isScheduledOn(
                task, date, resolver(date),
                task.planId?.let { planStatusById[it] }, task.goalId?.let { goalStatusById[it] }
            ) -> WeekCellKind.NONE
            else -> WeekCellKind.MISSED
        }
        return WeekCell(date, kind)
    }

    // ========== 编辑 ==========

    /** 计划页里的"+ 新增任务"：起止与所属目标直接从计划继承，用户只填名字和类型 */
    fun addTask(planId: Long, content: String, kind: String, repeatRule: String, scheduledDate: String) =
        viewModelScope.launch {
            val plan = planDao.getById(planId) ?: return@launch
            val trimmed = content.trim()
            if (trimmed.isEmpty()) return@launch
            bridge.saveTask(
                TaskEntity(
                    content = trimmed,
                    createdAt = DateFormats.nowDateTimeIso(),
                    kind = kind,
                    repeatRule = if (kind == "daily") repeatRule else "everyday",
                    startDate = plan.startDate,
                    endDate = plan.endDate,
                    scheduledDate = if (kind == "adhoc") scheduledDate.ifBlank { today } else "",
                    planId = plan.id,
                    goalId = plan.goalId
                )
            )
        }

    fun savePlan(plan: PlanEntity) = viewModelScope.launch {
        val now = DateFormats.nowDateTimeIso()
        if (plan.id == 0L) planDao.insert(plan.copy(createdAt = now, updatedAt = now))
        else planDao.update(plan.copy(updatedAt = now))
    }

    fun setPlanStatus(plan: PlanEntity, status: String) = viewModelScope.launch {
        planDao.update(plan.copy(status = status, updatedAt = DateFormats.nowDateTimeIso()))
    }

    /**
     * 删除计划：其下任务退成自由任务（planId 与 goalId 一并清，否则还会算进旧目标），
     * 打卡史整批留着——那是用户真实发生过的事，删计划不该把它抹掉。
     */
    fun deletePlan(plan: PlanEntity) = viewModelScope.launch {
        val snap = snapshot.first()
        db.withTransaction {
            snap.tasks.filter { it.planId == plan.id }.forEach {
                taskDao.updateTask(it.copy(planId = null, goalId = null))
            }
            planDao.deleteById(plan.id)
        }
    }
}
