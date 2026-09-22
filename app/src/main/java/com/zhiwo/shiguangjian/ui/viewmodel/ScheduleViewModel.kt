package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.DayOverrideEntity
import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.SettingEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import com.zhiwo.shiguangjian.data.festival.HolidayCalendar
import com.zhiwo.shiguangjian.data.repository.TaskOccurrenceRepository
import com.zhiwo.shiguangjian.data.repository.TaskWriteBridge
import com.zhiwo.shiguangjian.data.settings.ScheduleDisplayPrefs
import com.zhiwo.shiguangjian.data.settings.ScheduleSectionId
import com.zhiwo.shiguangjian.data.tasks.DayEntry
import com.zhiwo.shiguangjian.data.tasks.DayType
import com.zhiwo.shiguangjian.data.tasks.EntrySection
import com.zhiwo.shiguangjian.data.tasks.EntryState
import com.zhiwo.shiguangjian.data.tasks.ScheduleConstants
import com.zhiwo.shiguangjian.data.tasks.dayTypeOf
import com.zhiwo.shiguangjian.data.tasks.dayTypeOrWeekend
import com.zhiwo.shiguangjian.data.tasks.entriesFor
import com.zhiwo.shiguangjian.data.tasks.missedOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 安排页的段；枚举声明顺序即默认显示顺序 */
enum class ScheduleSectionGroup { DAILY_FIXED, DAILY_BLANK, ADHOC_TODAY, MISC_TODO, OVERDUE, MISSED, DONE_TODAY }

data class ScheduleUiState(
    val today: String = "",
    val groups: Map<ScheduleSectionGroup, List<DayEntry>> = emptyMap(),
    val dueCount: Int = 0,
    val doneCount: Int = 0,
    /** 今年没有节假日数据时非空：含节假日策略的任务按周末折算，页面要如实提示 */
    val holidayWarning: String? = null,
    val issues: List<String> = emptyList(),
    val loading: Boolean = true,
    /** 偏好快照必须进 state：只读 _prefs.value 会让改设置后界面不重组 */
    val sectionOrder: List<String> = emptyList(),
    val hidden: List<String> = emptyList()
) {
    companion object { val EMPTY = ScheduleUiState() }
}

/** typed combine 只支持 5 个上游，先把五路数据聚成一份快照 */
private data class ScheduleSources(
    val tasks: List<TaskEntity> = emptyList(),
    val occurrences: List<TaskOccurrenceEntity> = emptyList(),
    val overrides: List<DayOverrideEntity> = emptyList(),
    val plans: List<PlanEntity> = emptyList(),
    val goals: List<GoalEntity> = emptyList()
)

/**
 * 安排页的数据装配：只取数、只调引擎，不写排期判断（判断的唯一出处是 TaskScheduleEngine）。
 */
class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as ZhiwoApplication).database
    private val bridge = TaskWriteBridge(db)
    private val occurrencesRepo = TaskOccurrenceRepository(db)

    val today: String = DateFormats.nowDate()

    fun todayNow(): String = DateFormats.nowDate()

    private val _prefs = MutableStateFlow(ScheduleDisplayPrefs.DEFAULT)
    val prefs: StateFlow<ScheduleDisplayPrefs> = _prefs.asStateFlow()

    private val sources: StateFlow<ScheduleSources> = combine(
        db.taskDao().getAllTasks(),
        db.occurrenceDao().observeInRange(rangeStart(today), today),
        db.dayOverrideDao().getAll(),
        db.planDao().getAllPlans(),
        db.goalDao().getAllGoals()
    ) { tasks, occ, overrides, plans, goals -> ScheduleSources(tasks, occ, overrides, plans, goals) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScheduleSources())

    private val recordsFlow = db.recordDao().getAllRecords()

    val activePlans: StateFlow<List<PlanEntity>> = sources.map { src -> src.plans.filter { it.status == "active" } }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeGoals: StateFlow<List<GoalEntity>> = sources.map { src -> src.goals.filter { it.status == "active" } }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 只存在于记录里的待办（没有子任务）：过渡段，可勾选完成或一键转成真任务 */
    val miscTodo: StateFlow<List<RecordEntity>> = combine(sources, recordsFlow) { src, records ->
        records.filter { r -> r.category == "todo" && src.tasks.none { t -> t.recordId == r.id } }
            .sortedByDescending { it.createdAt }.take(MISC_TODO_MAX)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<ScheduleUiState> = combine(
        combine(sources, recordsFlow) { src, records -> src to records },
        combine(_prefs, miscTodo) { p, misc -> p to misc }
    ) { (src, _), (prefs, misc) ->
        val overrideByDate = src.overrides.associate { it.date to it.type }
        val plan = entriesFor(
            date = today,
            tasks = src.tasks,
            occurrences = src.occurrences.filter { it.date == today },
            dayType = resolverFor(today, overrideByDate),
            planStatusById = src.plans.associate { it.id to it.status },
            goalStatusById = src.goals.associate { it.id to it.status }
        )
        val groups = LinkedHashMap<ScheduleSectionGroup, MutableList<DayEntry>>()
        ScheduleSectionGroup.values().forEach { groups[it] = mutableListOf() }

        plan.planned.filterNot { it.state == EntryState.DONE }.forEach { groups.getValue(groupOf(it.section)) += it }
        plan.done.forEach { groups.getValue(ScheduleSectionGroup.DONE_TODAY) += it }
        plan.overdue.forEach { groups.getValue(ScheduleSectionGroup.OVERDUE) += it }
        missedOn(
            tasks = src.tasks,
            occurrences = src.occurrences,
            fromDate = today,
            dayTypeOfDate = { resolverFor(it, overrideByDate) },
            planStatusById = src.plans.associate { it.id to it.status },
            goalStatusById = src.goals.associate { it.id to it.status }
        ).forEach { groups.getValue(ScheduleSectionGroup.MISSED) += it }
        // 杂项段没有 DayEntry（它是记录不是任务），用一个占位条目只为让段顺序与显隐统一走同一套
        if (misc.isNotEmpty()) groups.getValue(ScheduleSectionGroup.MISC_TODO) += miscPlaceholder

        ScheduleUiState(
            today = today,
            groups = groups.filterValues { it.isNotEmpty() },
            dueCount = plan.dueCount,
            doneCount = plan.doneCount,
            holidayWarning = if (HolidayCalendar.hasDataFor(yearOf(today))) null else
                "今年尚未录入法定节假日安排，含「只在工作日/只在休息日」的任务暂按周末判定",
            issues = plan.issues,
            loading = false,
            sectionOrder = prefs.order.map { it.name },
            hidden = prefs.hidden.map { it.name }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState.EMPTY)

    /** 段的显示顺序：偏好里没列出的段按默认顺序排到最后 */
    fun orderedGroups(state: ScheduleUiState): List<Pair<ScheduleSectionGroup, List<DayEntry>>> {
        val rank = _prefs.value.order.mapIndexed { i, id -> id.name to i }.toMap()
        return state.groups.entries.sortedBy { (group, _) -> rank[group.name] ?: Int.MAX_VALUE }
            .map { (group, entries) -> group to entries }
    }

    fun visibleGroups(state: ScheduleUiState): List<Pair<ScheduleSectionGroup, List<DayEntry>>> =
        orderedGroups(state).filterNot { (group, _) -> isHidden(group) }

    fun isHidden(group: ScheduleSectionGroup): Boolean =
        _prefs.value.hidden.any { it.name == group.name }

    fun setSectionVisible(group: ScheduleSectionGroup, visible: Boolean) {
        val id = ScheduleSectionId.values().firstOrNull { it.name == group.name } ?: return
        val hidden = _prefs.value.hidden.toMutableSet()
        if (visible) hidden -= id else hidden += id
        if (hidden.size >= ScheduleSectionId.values().size) return      // 至少留一段可见，否则整页空白
        savePrefs(_prefs.value.copy(hidden = hidden))
    }

    fun moveSection(group: ScheduleSectionGroup, delta: Int) {
        val id = ScheduleSectionId.values().firstOrNull { it.name == group.name } ?: return
        savePrefs(_prefs.value.copy(order = ScheduleDisplayPrefs.move(_prefs.value.order, id, delta)))
    }

    private fun savePrefs(prefs: ScheduleDisplayPrefs) {
        _prefs.value = prefs
        viewModelScope.launch {
            val key = ScheduleDisplayPrefs.SETTINGS_KEY
            val row = SettingEntity(key = key, value = ScheduleDisplayPrefs.encode(prefs))
            if (db.settingDao().getSettingByKey(key) == null) db.settingDao().insertSetting(row)
            else db.settingDao().updateSetting(row)
        }
    }

    private fun resolverFor(date: String, overrideByDate: Map<String, String>): DayType? =
        dayTypeOrWeekend(
            date,
            HolidayCalendar.holidaysOf(yearOf(date)),
            HolidayCalendar.makeupWorkdaysOf(yearOf(date)),
            overrideByDate[date]
        )

    /** 日型判定唯一入口：该年没有节假日数据时按周末折算，并用 holidayWarning 明确告知 */
    private fun weekendOnly(date: String): DayType = dayTypeOrWeekend(date, emptySet(), emptySet(), null)

    fun check(entry: DayEntry, date: String = today) = viewModelScope.launch {
        bridge.check(entry.task, date, DateFormats.nowDateTimeIso())
    }

    fun uncheck(entry: DayEntry, date: String = today) = viewModelScope.launch {
        bridge.uncheck(entry.task, date)
    }

    fun markNotDone(entry: DayEntry, reasonCode: String, reasonNote: String, date: String = today) =
        viewModelScope.launch {
            bridge.markNotDone(entry.task, date, reasonCode, reasonNote, DateFormats.nowDateTimeIso())
        }

    /** 留白回填：note 就是"这段时间做了什么"，会进当天回顾与 AI 摘要 */
    fun fillBlank(entry: DayEntry, note: String, minutes: Int, date: String = today) = viewModelScope.launch {
        bridge.checkBlank(entry.task, date, note, minutes, DateFormats.nowDateTimeIso())
    }

    fun saveTask(task: TaskEntity) = viewModelScope.launch {
        val id = bridge.saveTask(task)
        // 提醒只在 remindTime 非空时注册；archived/paused 一律取消，避免僵尸闹钟
        val saved = db.taskDao().getTaskById(if (id > 0) id else task.id) ?: return@launch
        if (saved.remindTime.isBlank() || saved.status != "active") {
            com.zhiwo.shiguangjian.alarm.SmartScheduleManager.cancelAlarm(
                getApplication(), saved.id, saved.calendarEventId
            )
        } else {
            val autoCalendar = db.settingDao().getSettingValue("autoCalendarSync") != "false"
            val (eventId, _) = com.zhiwo.shiguangjian.alarm.SmartScheduleManager.scheduleTask(
                context = getApplication(),
                taskId = saved.id,
                taskContent = saved.content,
                taskType = saved.taskType,
                dueDate = saved.dueDate,
                recordTitle = saved.recordId?.let { recordTitleOf(it) } ?: "手动新建",
                syncCalendar = autoCalendar
            )
            // 建了日历就必须回写 id，否则下次取消删不掉它（重复事件）
            if (eventId != null && eventId != saved.calendarEventId) {
                db.taskDao().updateTask(saved.copy(calendarEventId = eventId))
            }
        }
    }

    private suspend fun recordTitleOf(recordId: Long): String? =
        db.recordDao().getRecordById(recordId)?.title

    fun setStatus(task: TaskEntity, status: String) = viewModelScope.launch {
        when (status) {
            "archived" -> bridge.archive(task)
            "paused" -> bridge.pause(task)
            else -> bridge.resume(task)
        }
        // 暂停/结束必须同时停掉提醒，否则"结束任务"之后每天照样响
        if (status != "active") {
            com.zhiwo.shiguangjian.alarm.SmartScheduleManager.cancelAlarm(
                getApplication(), task.id, task.calendarEventId
            )
        } else {
            saveTask(task)
        }
    }

    /** 把"只在记录里的待办"变成真任务：记录保留，只多一条挂着它的任务 */
    fun convertRecordToTask(record: RecordEntity) = viewModelScope.launch {
        bridge.saveTask(
            TaskEntity(
                content = record.title.ifBlank { record.content.take(30) },
                recordId = record.id,
                kind = "adhoc",
                scheduledDate = today,
                createdAt = DateFormats.nowDateTimeIso()
            )
        )
    }

    fun completeRecord(record: RecordEntity) = viewModelScope.launch {
        db.recordDao().updateRecord(record.copy(category = "completed", updatedAt = DateFormats.nowDateTimeIso()))
    }

    suspend fun taskById(id: Long): TaskEntity? = db.taskDao().getTaskById(id)

    suspend fun occurrenceCount(taskId: Long): Int = occurrencesRepo.countByTask(taskId)

    init {
        viewModelScope.launch {
            _prefs.value = ScheduleDisplayPrefs.parse(
                db.settingDao().getSettingValue(ScheduleDisplayPrefs.SETTINGS_KEY)
            )
        }
    }

    companion object {
        private const val MISC_TODO_MAX = 20
        private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

        /** 只为让"杂项待办"参与统一的段排序/显隐；渲染时按 name 特殊处理 */
        val miscPlaceholder = DayEntry(
            task = TaskEntity(content = "", createdAt = ""),
            occurrence = null,
            state = EntryState.DUE,
            section = EntrySection.MISC_TODO
        )

        fun parse(date: String): LocalDate? =
            try { LocalDate.parse(date.take(10), ISO) } catch (_: Exception) { null }

        fun yearOf(date: String): Int = parse(date)?.year ?: LocalDate.now().year

        private fun rangeStart(today: String): String =
            parse(today)?.minusDays(ScheduleConstants.MISSED_LOOKBACK_DAYS.toLong())?.toString() ?: today

        private fun groupOf(section: EntrySection) = when (section) {
            EntrySection.DAILY_FIXED -> ScheduleSectionGroup.DAILY_FIXED
            EntrySection.DAILY_BLANK -> ScheduleSectionGroup.DAILY_BLANK
            else -> ScheduleSectionGroup.ADHOC_TODAY
        }
    }
}
