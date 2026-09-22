package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.alarm.SmartScheduleManager
import com.zhiwo.shiguangjian.data.repository.RecordRepository
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.repository.TaskRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class CalendarViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val recordRepo: RecordRepository
    private val taskRepo: TaskRepository

    private val _records = MutableStateFlow<List<com.zhiwo.shiguangjian.data.db.entity.RecordEntity>>(emptyList())
    val records: StateFlow<List<com.zhiwo.shiguangjian.data.db.entity.RecordEntity>> = _records.asStateFlow()

    private val _tasks = MutableStateFlow<List<com.zhiwo.shiguangjian.data.db.entity.TaskEntity>>(emptyList())
    val tasks: StateFlow<List<com.zhiwo.shiguangjian.data.db.entity.TaskEntity>> = _tasks.asStateFlow()

    init {
        recordRepo = RecordRepository(
            app.database,
            app.database.recordDao(),
            app.database.taskDao(),
            app.database.tagDao(),
            app.database.keyInfoDao()
        )
        taskRepo = TaskRepository(app.database.taskDao())
        loadData()
    }
    private val bridge = com.zhiwo.shiguangjian.data.repository.TaskWriteBridge(app.database)


    private val _overrides = MutableStateFlow<List<com.zhiwo.shiguangjian.data.db.entity.DayOverrideEntity>>(emptyList())
    val overrides: StateFlow<List<com.zhiwo.shiguangjian.data.db.entity.DayOverrideEntity>> = _overrides.asStateFlow()
    private val _occurrences = MutableStateFlow<List<com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity>>(emptyList())
    val occurrences: StateFlow<List<com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity>> = _occurrences.asStateFlow()
    private val _planStatus = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val _goalStatus = MutableStateFlow<Map<Long, String>>(emptyMap())

    private val overrideDao get() = app.database.dayOverrideDao()

    /** 某天的排期结果：与安排页同一个引擎，不再各算一套 */
    fun dayPlan(date: String): com.zhiwo.shiguangjian.data.tasks.DayPlanResult =
        com.zhiwo.shiguangjian.data.tasks.entriesFor(
            date = date,
            tasks = _tasks.value,
            occurrences = _occurrences.value.filter { it.date == date },
            dayType = dayType(date),
            planStatusById = _planStatus.value,
            goalStatusById = _goalStatus.value
        )

    fun plannedCount(date: String): Int = dayPlan(date).planned.size

    fun missedCount(date: String): Int =
        dayPlan(date).let { it.overdue.size + it.missed.size }

    /** 与安排页、日记取数同一函数：同一天的判定不可能两个答案 */
    fun dayType(date: String): com.zhiwo.shiguangjian.data.tasks.DayType {
        val year = date.take(4).toIntOrNull() ?: return com.zhiwo.shiguangjian.data.tasks.DayType.WORKDAY
        return com.zhiwo.shiguangjian.data.tasks.dayTypeOrWeekend(
            date,
            com.zhiwo.shiguangjian.data.festival.HolidayCalendar.holidaysOf(year),
            com.zhiwo.shiguangjian.data.festival.HolidayCalendar.makeupWorkdaysOf(year),
            _overrides.value.firstOrNull { it.date == date }?.type
        )
    }

    /** 只有用户手动标过的日子才显示角标 */
    fun dayLabel(date: String): String? = when (_overrides.value.firstOrNull { it.date == date }?.type) {
        "workday" -> "班"
        "holiday" -> "休"
        else -> null
    }

    fun setDayType(date: String, type: String?) = viewModelScope.launch {
        if (type == null) overrideDao.clear(date)
        else overrideDao.upsert(
            com.zhiwo.shiguangjian.data.db.entity.DayOverrideEntity(
                date = date, type = type, createdAt = DateFormats.nowDateTimeIso()
            )
        )
    }

    private fun loadData() {
        viewModelScope.launch {
            recordRepo.getAllRecords().collect { _records.value = it }
        }
        viewModelScope.launch {
            app.database.occurrenceDao().observeInRange("1970-01-01", "9999-12-31")
                .collect { _occurrences.value = it }
        }
        viewModelScope.launch { overrideDao.getAll().collect { _overrides.value = it } }
        viewModelScope.launch {
            app.database.planDao().getAllPlans().collect { plans -> _planStatus.value = plans.associate { it.id to it.status } }
        }
        viewModelScope.launch {
            app.database.goalDao().getAllGoals().collect { goals -> _goalStatus.value = goals.associate { it.id to it.status } }
        }
        viewModelScope.launch {
            // 跨天重置已收敛到 TaskRepository.resetDailyTasksForNewDay（应用启动时触发），
            // 此处纯展示订阅，无写库副作用
            taskRepo.getAllTasks().collect { _tasks.value = it }
        }
    }

    fun completeTask(taskId: Long, date: String = DateFormats.nowDate()) {
        viewModelScope.launch {
            try {
                val task = taskRepo.getTaskById(taskId) ?: return@launch
                // 打卡写进 task_occurrences（真值），老列由桥接同步维护，供还没改造的读路径使用
                bridge.check(task, date, DateFormats.nowDateTimeIso())
                // 取消对应的提醒闹钟（与 RecordListViewModel 行为一致）
                SmartScheduleManager.cancelAlarm(app, taskId, task.calendarEventId)
            } catch (e: Throwable) {
                android.util.Log.e("CalendarVM", "完成任务失败", e)
            }
        }
    }

    // 真正完成每日任务——标记后不再每日刷新
    fun permanentlyCompleteTask(taskId: Long) {
        viewModelScope.launch {
            try {
                val task = taskRepo.getTaskById(taskId) ?: return@launch
                bridge.check(task, DateFormats.nowDate(), DateFormats.nowDateTimeIso())
                bridge.archive(taskRepo.getTaskById(taskId) ?: task)   // 等价旧「真正完成」：不再每日刷新
            } catch (e: Throwable) {
                android.util.Log.e("CalendarVM", "真正完成任务失败", e)
            }
        }
    }

    fun uncompleteTask(taskId: Long, date: String = DateFormats.nowDate()) {
        viewModelScope.launch {
            try {
                val task = taskRepo.getTaskById(taskId) ?: return@launch
                bridge.uncheck(task, date)
            } catch (e: Throwable) {
                android.util.Log.e("CalendarVM", "取消完成任务失败", e)
            }
        }
    }
}
