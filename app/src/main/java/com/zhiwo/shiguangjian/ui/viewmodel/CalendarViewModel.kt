package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.alarm.SmartScheduleManager
import com.zhiwo.shiguangjian.data.repository.RecordRepository
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.repository.TaskRepository
import com.zhiwo.shiguangjian.data.tasks.isTaskEffectivelyCompleted
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

    private fun loadData() {
        viewModelScope.launch {
            recordRepo.getAllRecords().collect { _records.value = it }
        }
        viewModelScope.launch {
            taskRepo.getAllTasks().collect { taskList ->
                try {
                    // 每日任务跨天后重置 isCompleted，但跳过已"真正完成"的任务
                    val today = DateFormats.nowDate()
                    val tasksToReset = taskList.filter {
                        it.taskType == "daily" && !it.isPermanentlyCompleted &&
                        !isTaskEffectivelyCompleted(it, today) && it.isCompleted
                    }
                    if (tasksToReset.isNotEmpty()) {
                        tasksToReset.forEach { task ->
                            taskRepo.updateTask(task.copy(isCompleted = false, completedAt = null, dailyCompletionDate = null))
                        }
                        // 重置后不使用旧快照，让下一次 Flow 发射更新 UI
                    } else {
                        _tasks.value = taskList
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("CalendarVM", "每日任务重置失败", e)
                    _tasks.value = taskList
                }
            }
        }
    }

    fun completeTask(taskId: Long) {
        viewModelScope.launch {
            try {
                val task = taskRepo.getTaskById(taskId) ?: return@launch
                val now = DateFormats.nowDate()
                if (task.taskType == "daily") {
                    taskRepo.updateTask(task.copy(isCompleted = true, completedAt = now, dailyCompletionDate = now))
                } else {
                    taskRepo.updateTask(task.copy(isCompleted = true, completedAt = now))
                }
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
                val now = DateFormats.nowDate()
                taskRepo.updateTask(
                    task.copy(
                        isCompleted = true,
                        completedAt = now,
                        dailyCompletionDate = now,
                        isPermanentlyCompleted = true
                    )
                )
            } catch (e: Throwable) {
                android.util.Log.e("CalendarVM", "真正完成任务失败", e)
            }
        }
    }

    fun uncompleteTask(taskId: Long) {
        viewModelScope.launch {
            try {
                val task = taskRepo.getTaskById(taskId) ?: return@launch
                if (task.taskType == "daily") {
                    taskRepo.updateTask(task.copy(isCompleted = false, completedAt = null, dailyCompletionDate = null))
                } else {
                    taskRepo.updateTask(task.copy(isCompleted = false, completedAt = null))
                }
            } catch (e: Throwable) {
                android.util.Log.e("CalendarVM", "取消完成任务失败", e)
            }
        }
    }
}
