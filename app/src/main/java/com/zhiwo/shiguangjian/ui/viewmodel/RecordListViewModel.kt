package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.alarm.SmartScheduleManager
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.ai.RecordAnalysisResult
import com.zhiwo.shiguangjian.data.ai.RecordMergeItem
import com.zhiwo.shiguangjian.data.ai.RecordSplitItem
import com.zhiwo.shiguangjian.data.ai.RecordGoalToTodos
import com.zhiwo.shiguangjian.data.ai.RecordTodosToGoal
import com.zhiwo.shiguangjian.data.ai.RecordToMemory
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.KeyInfoEntity
import com.zhiwo.shiguangjian.data.db.entity.TagEntity
import com.zhiwo.shiguangjian.data.organize.RecordOrganizer
import com.zhiwo.shiguangjian.data.repository.RecordRepository
import com.zhiwo.shiguangjian.data.repository.TaskRepository
import com.zhiwo.shiguangjian.data.repository.MemoryRepository
import com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import com.zhiwo.shiguangjian.data.tasks.getTaskDisplayDate as taskDisplayDate
import com.zhiwo.shiguangjian.data.tasks.isTaskEffectivelyCompleted as taskIsEffectivelyCompleted
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RecordListViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val recordRepo = RecordRepository(
        app.database, app.database.recordDao(), app.database.taskDao(),
        app.database.tagDao(), app.database.keyInfoDao()
    )
    private val taskRepo = TaskRepository(app.database.taskDao())
    private val bridge = com.zhiwo.shiguangjian.data.repository.TaskWriteBridge(app.database)
    private val memoryRepo = MemoryRepository(app.database.memoryDao())
    private val settingsRepo = SettingsRepository(app.database.settingDao())
    private val secureSettingsRepo = SecureSettingsRepository(app)
    private val tagDao = app.database.tagDao()
    private val recordOrganizer = RecordOrganizer(app, recordRepo, taskRepo, memoryRepo)
    val aiRepo get() = app.aiRepo

    private val _records = MutableStateFlow<List<RecordEntity>>(emptyList())
    val records: StateFlow<List<RecordEntity>> = _records

    private val _tasks = MutableStateFlow<List<TaskEntity>>(emptyList())
    val tasks: StateFlow<List<TaskEntity>> = _tasks

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _filterCategory = MutableStateFlow("")
    val filterCategory: StateFlow<String> = _filterCategory

    private val _analyzing = MutableStateFlow(false)
    val analyzing: StateFlow<Boolean> = _analyzing

    private val _analysisResult = MutableStateFlow<RecordAnalysisResult?>(null)
    val analysisResult: StateFlow<RecordAnalysisResult?> = _analysisResult

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured

    init {
        loadData()
        loadAiConfig()
    }

    private fun loadData() {
        viewModelScope.launch {
            recordRepo.getAllRecords().collect { _records.value = it }
        }
        viewModelScope.launch {
            taskRepo.getAllTasks().collect { _tasks.value = it }
        }
    }

    private suspend fun refreshAiConfig() {
            try {
                aiRepo.configureFromSettings(settingsRepo, secureSettingsRepo)
                _isConfigured.value = aiRepo.isConfigured
            } catch (e: Throwable) {
                Log.e("RecordListVM", "loadAiConfig failed", e)
            }
    }

    private fun loadAiConfig() {
        viewModelScope.launch {
            refreshAiConfig()
        }
    }

    // ========== 筛选记录 ==========

    val filteredRecords: StateFlow<List<RecordEntity>> = combine(
        _records, _searchQuery, _filterCategory
    ) { records, query, category ->
        records.filter { record ->
            val matchesQuery = query.isBlank() ||
                record.title.contains(query, ignoreCase = true) ||
                record.content.contains(query, ignoreCase = true)
            val matchesCategory = category.isBlank() || record.category == category
            matchesQuery && matchesCategory
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ========== 安排 Tab 数据 ==========

    /** 今日待办记录（category=todo 且有未过期未完成的任务） */
    val todayDueTodoRecords: StateFlow<List<RecordEntity>> = combine(
        _records, _tasks
    ) { records, tasks ->
        val today = DateFormats.nowDate()
        records.filter { r ->
            if (r.category != "todo") return@filter false
            val recordTasks = tasks.filter { it.recordId == r.id }
            if (recordTasks.isEmpty()) return@filter true
            // 有未过期且未完成的任务（dueDate 为空表示无截止日期，保留；dueDate >= today 表示未过期）
            recordTasks.any { t ->
                !isTaskEffectivelyCompleted(t, today) &&
                (t.dueDate.isBlank() || t.dueDate.take(10) >= today)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 目标记录 */
    val goalRecords: StateFlow<List<RecordEntity>> = _records.map { records ->
        records.filter { it.category == "goal" }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 当天已完成记录 */
    val completedRecords: StateFlow<List<RecordEntity>> = _records.map { records ->
        val today = DateFormats.nowDate()
        records.filter { it.category == "completed" && it.updatedAt.startsWith(today) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ========== 任务操作 ==========

    fun isTaskEffectivelyCompleted(task: TaskEntity, today: String = DateFormats.nowDate()): Boolean {
        return taskIsEffectivelyCompleted(task, today)
    }

    fun getTaskDisplayDate(task: TaskEntity): String {
        return taskDisplayDate(task)
    }

    fun completeTask(taskId: Long) {
        viewModelScope.launch {
            try {
                val task = taskRepo.getTaskById(taskId) ?: return@launch
                bridge.check(task, DateFormats.nowDate(), DateFormats.nowDateTimeIso())
                task.recordId?.let { recordId -> checkAndMoveCompleted(recordId) }
                // 取消对应的提醒闹钟
                SmartScheduleManager.cancelAlarm(app, taskId, task.calendarEventId)
            } catch (e: Throwable) {
                Log.e("RecordListVM", "completeTask failed", e)
            }
        }
    }

    fun uncompleteTask(taskId: Long) {
        viewModelScope.launch {
            try {
                val task = taskRepo.getTaskById(taskId) ?: return@launch
                bridge.uncheck(task, DateFormats.nowDate())
            } catch (e: Throwable) {
                Log.e("RecordListVM", "uncompleteTask failed", e)
            }
        }
    }

    private suspend fun checkAndMoveCompleted(recordId: Long) {
        try {
            val record = recordRepo.getRecordById(recordId) ?: return
            if (record.category != "todo") return
            val recordTasks = taskRepo.getTasksByRecordId(recordId).first()
            // 包含每日/每周任务的记录不应自动归档，因为它们需要持续执行
            if (recordTasks.any { it.taskType == "daily" || it.taskType == "weekly" }) return
            val today = DateFormats.nowDate()
            if (recordTasks.isNotEmpty() && recordTasks.all { isTaskEffectivelyCompleted(it, today) }) {
                recordRepo.updateRecord(record.copy(category = "completed"))
            }
        } catch (e: Throwable) {
            Log.e("RecordListVM", "checkAndMoveCompleted failed", e)
        }
    }

    fun completeGoal(goalRecordId: Long) {
        viewModelScope.launch {
            val goalRecord = recordRepo.getRecordById(goalRecordId) ?: return@launch
            val now = DateFormats.nowDate()
            // 完成关联到此目标的待办任务
            val linkedTasks = taskRepo.getTasksByParentGoalId(goalRecordId).first()
            linkedTasks.forEach { task ->
                bridge.check(task, now, DateFormats.nowDateTimeIso())
                if (task.kind == "daily" || task.kind == "blank") bridge.archive(task)
            }
            // 完成记录自身的任务
            val recordTasks = taskRepo.getTasksByRecordId(goalRecordId).first()
            recordTasks.forEach { task ->
                if (!task.isCompleted) {
                    if (task.taskType == "daily") {
                        taskRepo.updateTask(task.copy(isCompleted = true, completedAt = now, dailyCompletionDate = now, isPermanentlyCompleted = true))
                    } else {
                        taskRepo.updateTask(task.copy(isCompleted = true, completedAt = now))
                    }
                }
            }
            // 取消关联闹钟
            (linkedTasks + recordTasks).forEach { SmartScheduleManager.cancelAlarm(app, it.id, it.calendarEventId) }
            // 移动记录到已完成
            recordRepo.updateRecord(goalRecord.copy(category = "completed"))
            // 目标完成的事实同步给记忆（确定性入口，不靠 AI 脑补"达成"）
            notifyGoalOutcome(goalRecord.id, goalRecord.title, achieved = true)
        }
    }

    /**
     * 放弃目标：记录移出目标列表（归入 completed），任务与闹钟一并收尾，
     * 并把"已放弃"同步给记忆——推翻旧的"正在准备"类记忆。
     */
    fun abandonGoal(goalRecordId: Long) {
        viewModelScope.launch {
            try {
                val goalRecord = recordRepo.getRecordById(goalRecordId) ?: return@launch
                val now = DateFormats.nowDate()
                val linkedTasks = taskRepo.getTasksByParentGoalId(goalRecordId).first()
                val recordTasks = taskRepo.getTasksByRecordId(goalRecordId).first()
                (linkedTasks + recordTasks).forEach { task ->
                    if (!task.isCompleted) {
                        taskRepo.updateTask(task.copy(isCompleted = true, completedAt = now))
                    }
                    SmartScheduleManager.cancelAlarm(app, task.id, task.calendarEventId)
                }
                recordRepo.updateRecord(goalRecord.copy(category = "completed"))
                notifyGoalOutcome(goalRecord.id, goalRecord.title, achieved = false)
            } catch (e: Throwable) {
                Log.e("RecordListVM", "abandonGoal failed", e)
            }
        }
    }

    /** 用应用级作用域把目标结局同步给记忆对账，避免页面退出被取消 */
    private fun notifyGoalOutcome(goalRecordId: Long, goalTitle: String, achieved: Boolean) {
        val outcome = if (achieved) "已完成" else "已放弃（未达成）"
        app.appScope.launch {
            try {
                app.memoryReconciler.reconcileAndApply(
                    newContent = "目标「$goalTitle」$outcome，这是用户确认的事实",
                    now = DateFormats.nowDateTimeIso(),
                    sourceRecordId = goalRecordId
                )
            } catch (e: Throwable) {
                Log.e("RecordListVM", "目标结局同步记忆失败（忽略）", e)
            }
        }
    }

    // ========== 搜索和筛选 ==========

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setFilterCategory(category: String) { _filterCategory.value = category }

    // ========== 记录详情 ==========

    fun getRecord(id: Long): StateFlow<RecordEntity?> {
        return app.database.recordDao().getRecordByIdFlow(id)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    fun getKeyInfosForRecord(recordId: Long): StateFlow<List<KeyInfoEntity>> {
        return app.database.keyInfoDao().getKeyInfosByRecordId(recordId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun getTagsForRecord(recordId: Long): StateFlow<List<TagEntity>> {
        return app.database.tagDao().getTagIdsForRecordFlow(recordId)
            .map { tagIds -> tagIds.mapNotNull { tagDao.getTagById(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun addTagToRecord(recordId: Long, tagName: String) {
        viewModelScope.launch { recordRepo.addTagToRecord(recordId, tagName) }
    }

    fun removeTagFromRecord(recordId: Long, tagId: Long) {
        viewModelScope.launch { recordRepo.removeTagFromRecord(recordId, tagId) }
    }

    fun updateRecordTitle(recordId: Long, title: String) {
        viewModelScope.launch {
            val record = recordRepo.getRecordById(recordId) ?: return@launch
            recordRepo.updateRecord(record.copy(title = title))
        }
    }

    fun updateRecordContent(recordId: Long, title: String, content: String, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val record = recordRepo.getRecordById(recordId) ?: return@launch
                val now = DateFormats.nowDateTimeIso()
                recordRepo.updateRecord(record.copy(title = title, content = content, updatedAt = now))
                onSuccess?.invoke()
            } catch (e: Throwable) {
                Log.e("RecordListVM", "更新记录失败", e)
            }
        }
    }

    fun deleteRecord(id: Long, onSuccess: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                // 删除前取消关联任务的闹钟
                val tasks = taskRepo.getTasksByRecordId(id).first()
                tasks.forEach { SmartScheduleManager.cancelAlarm(app, it.id, it.calendarEventId) }
                recordRepo.deleteRecordKeepingTasks(id)
                onSuccess?.invoke()
            } catch (e: Throwable) {
                Log.e("RecordListVM", "删除记录失败", e)
                onError?.invoke(e.message ?: "删除失败")
            }
        }
    }

    // ========== AI 记录分析 ==========

    fun analyzeRecords() {
        viewModelScope.launch {
            refreshAiConfig()
            if (!aiRepo.isConfigured) return@launch
            val nonMemoryRecords = _records.value.filter { it.category != "completed" }
            if (nonMemoryRecords.size < 2) return@launch
            _analyzing.value = true
            _analysisResult.value = null
            try {
                val result = aiRepo.analyzeRecords(nonMemoryRecords)
                _analysisResult.value = result
            } catch (e: Throwable) {
                Log.e("RecordListVM", "分析记录失败", e)
                _analysisResult.value = null
            } finally {
                _analyzing.value = false
            }
        }
    }

    fun clearAnalysisResult() { _analysisResult.value = null }

    /** 从分析结果中移除已应用的单条建议，保留其余；全部移除后自动清空 */
    private fun removeAppliedSuggestion(
        mergeFilter: (RecordMergeItem) -> Boolean = { false },
        splitFilter: (RecordSplitItem) -> Boolean = { false },
        goalToTodosFilter: (RecordGoalToTodos) -> Boolean = { false },
        todosToGoalFilter: (RecordTodosToGoal) -> Boolean = { false },
        toMemoryFilter: (RecordToMemory) -> Boolean = { false }
    ) {
        val current = _analysisResult.value ?: return
        val updated = current.copy(
            merge = current.merge.filter { !mergeFilter(it) },
            split = current.split.filter { !splitFilter(it) },
            goalToTodos = current.goalToTodos.filter { !goalToTodosFilter(it) },
            todosToGoal = current.todosToGoal.filter { !todosToGoalFilter(it) },
            toMemory = current.toMemory.filter { !toMemoryFilter(it) }
        )
        _analysisResult.value = if (updated.merge.isEmpty() && updated.split.isEmpty() &&
            updated.goalToTodos.isEmpty() && updated.todosToGoal.isEmpty() && updated.toMemory.isEmpty()
        ) null else updated
    }

    /** UI 调用：跳过单条建议（按类型+索引） */
    fun skipSuggestion(type: String, index: Int) {
        val current = _analysisResult.value ?: return
        val updated = when (type) {
            "merge" -> current.copy(merge = current.merge.filterIndexed { i, _ -> i != index })
            "split" -> current.copy(split = current.split.filterIndexed { i, _ -> i != index })
            "goalToTodos" -> current.copy(goalToTodos = current.goalToTodos.filterIndexed { i, _ -> i != index })
            "todosToGoal" -> current.copy(todosToGoal = current.todosToGoal.filterIndexed { i, _ -> i != index })
            "toMemory" -> current.copy(toMemory = current.toMemory.filterIndexed { i, _ -> i != index })
            else -> current
        }
        _analysisResult.value = if (updated.merge.isEmpty() && updated.split.isEmpty() &&
            updated.goalToTodos.isEmpty() && updated.todosToGoal.isEmpty() && updated.toMemory.isEmpty()
        ) null else updated
    }

    // ========== 记录合并 ==========

    fun applyRecordMerge(sourceIds: List<Long>, mergedTitle: String, mergedCategory: String) {
        viewModelScope.launch {
            try {
                if (recordOrganizer.applyRecordMerge(sourceIds, mergedTitle, mergedCategory)) {
                    removeAppliedSuggestion(mergeFilter = { it.sourceIds == sourceIds })
                }
            } catch (e: Throwable) {
                Log.e("RecordListVM", "合并记录失败", e)
            }
        }
    }

    fun applyRecordSplit(sourceId: Long, splits: List<Pair<String, String>>) {
        viewModelScope.launch {
            try {
                if (recordOrganizer.applyRecordSplit(sourceId, splits)) {
                    removeAppliedSuggestion(splitFilter = { it.sourceId == sourceId })
                }
            } catch (e: Throwable) {
                Log.e("RecordListVM", "拆分记录失败", e)
            }
        }
    }

    fun applyToMemory(sourceId: Long, memoryContent: String) {
        viewModelScope.launch {
            try {
                if (recordOrganizer.applyToMemory(sourceId, memoryContent)) {
                    removeAppliedSuggestion(toMemoryFilter = { it.sourceId == sourceId })
                }
            } catch (e: Throwable) {
                Log.e("RecordListVM", "转记忆失败", e)
            }
        }
    }

    fun applyGoalToTodos(sourceId: Long, todos: List<String>) {
        viewModelScope.launch {
            try {
                if (recordOrganizer.applyGoalToTodos(sourceId, todos)) {
                    removeAppliedSuggestion(goalToTodosFilter = { it.sourceId == sourceId })
                }
            } catch (e: Throwable) {
                Log.e("RecordListVM", "目标拆解为待办失败", e)
            }
        }
    }

    fun applyTodosToGoal(sourceIds: List<Long>, goalTitle: String) {
        viewModelScope.launch {
            try {
                if (recordOrganizer.applyTodosToGoal(sourceIds, goalTitle)) {
                    removeAppliedSuggestion(todosToGoalFilter = { it.sourceIds == sourceIds })
                }
            } catch (e: Throwable) {
                Log.e("RecordListVM", "待办合并为目标失败", e)
            }
        }
    }

    /** 一键执行所有分析建议 */
    fun applyAllSuggestions() {
        val result = _analysisResult.value ?: return
        viewModelScope.launch {
            result.merge.forEach {
                try {
                    if (recordOrganizer.applyRecordMerge(it.sourceIds, it.mergedTitle, it.mergedCategory)) {
                        removeAppliedSuggestion(mergeFilter = { item -> item.sourceIds == it.sourceIds })
                    }
                } catch (e: Throwable) { Log.e("RecordListVM", "一键执行-合并失败", e) }
            }
            result.split.forEach {
                try {
                    if (recordOrganizer.applyRecordSplit(it.sourceId, it.splits.map { s -> s.title to s.category })) {
                        removeAppliedSuggestion(splitFilter = { item -> item.sourceId == it.sourceId })
                    }
                } catch (e: Throwable) { Log.e("RecordListVM", "一键执行-拆分失败", e) }
            }
            result.goalToTodos.forEach {
                try {
                    if (recordOrganizer.applyGoalToTodos(it.sourceId, it.todos)) {
                        removeAppliedSuggestion(goalToTodosFilter = { item -> item.sourceId == it.sourceId })
                    }
                } catch (e: Throwable) { Log.e("RecordListVM", "一键执行-目标拆解失败", e) }
            }
            result.todosToGoal.forEach {
                try {
                    if (recordOrganizer.applyTodosToGoal(it.sourceIds, it.goalTitle)) {
                        removeAppliedSuggestion(todosToGoalFilter = { item -> item.sourceIds == it.sourceIds })
                    }
                } catch (e: Throwable) { Log.e("RecordListVM", "一键执行-待办合并失败", e) }
            }
            result.toMemory.forEach {
                try {
                    if (recordOrganizer.applyToMemory(it.sourceId, it.memoryContent)) {
                        removeAppliedSuggestion(toMemoryFilter = { item -> item.sourceId == it.sourceId })
                    }
                } catch (e: Throwable) { Log.e("RecordListVM", "一键执行-转记忆失败", e) }
            }
        }
    }
}
