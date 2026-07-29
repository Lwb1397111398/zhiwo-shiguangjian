package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.alarm.SmartScheduleManager
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.KeyInfoEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TagEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordTagCrossRef
import com.zhiwo.shiguangjian.ui.viewmodel.CategoryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ScheduledTaskRequest(
    val taskId: Long,
    val taskContent: String,
    val taskType: String,
    val dueDate: String,
    val recordTitle: String
)

class InputViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val recordRepo = app.database.let {
        com.zhiwo.shiguangjian.data.repository.RecordRepository(
            it, it.recordDao(), it.taskDao(), it.tagDao(), it.keyInfoDao()
        )
    }
    private val taskRepo = com.zhiwo.shiguangjian.data.repository.TaskRepository(app.database.taskDao())
    private val tagRepo = app.database.tagDao()
    private val settingsRepo = com.zhiwo.shiguangjian.data.repository.SettingsRepository(app.database.settingDao())
    private val secureSettingsRepo = com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository(app)
    private val aiRepo get() = app.aiRepo

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured

    private val _categories = MutableStateFlow<List<CategoryInfo>>(emptyList())
    val categories: StateFlow<List<CategoryInfo>> = _categories

    init {
        viewModelScope.launch {
            aiRepo.configureFromSettings(settingsRepo, secureSettingsRepo)
            _isConfigured.value = aiRepo.isConfigured
        }
        loadCategories()
    }

    private fun loadCategories() {
        viewModelScope.launch {
            val json = settingsRepo.getSetting("categories")
            if (json != null) {
                try {
                    val type = object : TypeToken<List<CategoryInfo>>() {}.type
                    val all = Gson().fromJson<List<CategoryInfo>>(json, type)
                    // 排除 "completed" 分类，不显示在记录输入页
                    _categories.value = all.filter { it.id != "completed" }
                } catch (_: Exception) {
                    _categories.value = getDefaultCategories()
                }
            } else {
                _categories.value = getDefaultCategories()
            }
        }
    }

    private fun getDefaultCategories(): List<CategoryInfo> = listOf(
        CategoryInfo("todo", "待办事项", "📝", "#6B8E9F"),
        CategoryInfo("goal", "目标设定", "🎯", "#F7A8B8"),
        CategoryInfo("idea", "想法灵感", "💡", "#98D8C8"),
        CategoryInfo("emotion", "情绪记录", "💭", "#FFD166"),
        CategoryInfo("question", "问题思考", "❓", "#A78BFA"),
        CategoryInfo("study", "学习笔记", "📚", "#84A59D"),
        CategoryInfo("other", "其他", "📌", "#999999")
    )

    fun saveAndAnalyze(
        content: String,
        selectedCategory: String = "",
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                aiRepo.configureFromSettings(settingsRepo, secureSettingsRepo)
                _isConfigured.value = aiRepo.isConfigured
                if (!aiRepo.isConfigured) {
                    onError("请先配置 AI 接口")
                    return@launch
                }
                val analysis = aiRepo.analyzeContent(content)

                // 如果用户手动选择了分类，优先使用；否则使用 AI 分析结果
                val baseCategory = if (selectedCategory.isNotBlank()) {
                    selectedCategory
                } else {
                    analysis.category
                }

                // 后置校验：tasks 非空但 category 不是 todo → 强制修正
                val correctedCategory = if (analysis.tasks.isNotEmpty() && baseCategory != "todo") {
                    "todo"
                } else {
                    baseCategory
                }

                // 后置校验：title 为空时，取 content 前 20 字符作为标题
                val safeTitle = analysis.title.ifBlank {
                    content.take(20).ifBlank { "无标题" }
                }

                val now = DateFormats.nowDateTimeIso()

                val scheduledTasks = mutableListOf<ScheduledTaskRequest>()

                // 数据库写入切换到 IO 线程并保持事务原子性
                val recordId = withContext(Dispatchers.IO) {
                    app.database.withTransaction {
                        val id = recordRepo.insertRecord(
                            RecordEntity(
                                title = safeTitle,
                                content = content,
                                category = correctedCategory,
                                summary = analysis.summary,
                                createdAt = now,
                                updatedAt = now
                            )
                        )

                        if (analysis.keyInfo.isNotEmpty()) {
                            app.database.keyInfoDao().insertKeyInfos(
                                analysis.keyInfo.map { KeyInfoEntity(recordId = id, content = it) }
                            )
                        }

                        analysis.tags.forEach { tagName ->
                            val existingTag = tagRepo.getTagByName(tagName)
                            val tagId = existingTag?.id ?: tagRepo.insertTag(TagEntity(name = tagName))
                            tagRepo.insertRecordTagCrossRef(RecordTagCrossRef(id, tagId))
                        }

                        if (analysis.tasks.isNotEmpty()) {
                            val autoSync = settingsRepo.getSetting("autoCalendarSync") != "false"
                            analysis.tasks.forEach { task ->
                                val taskId = taskRepo.insertTask(
                                    TaskEntity(
                                        recordId = id,
                                        parentGoalId = if (correctedCategory == "goal") id else null,
                                        content = task.content,
                                        dueDate = task.dueDate,
                                        taskType = task.taskType,
                                        isCompleted = false,
                                        createdAt = now
                                    )
                                )

                                if (autoSync && task.dueDate.isNotBlank()) {
                                    scheduledTasks.add(
                                        ScheduledTaskRequest(
                                            taskId = taskId,
                                            taskContent = task.content,
                                            taskType = task.taskType,
                                            dueDate = task.dueDate,
                                            recordTitle = safeTitle
                                        )
                                    )
                                }
                            }
                        }

                        id
                    }
                }

                scheduledTasks.forEach { request ->
                    val (eventId, _) = SmartScheduleManager.scheduleTask(
                        context = app,
                        taskId = request.taskId,
                        taskContent = request.taskContent,
                        taskType = request.taskType,
                        dueDate = request.dueDate,
                        recordTitle = request.recordTitle
                    )
                    if (eventId != null) {
                        val task = taskRepo.getTaskById(request.taskId)
                        if (task != null) {
                            taskRepo.updateTask(task.copy(calendarEventId = eventId))
                        }
                    }
                }

                onSuccess(recordId)
            } catch (e: Throwable) {
                android.util.Log.e("InputVM", "saveAndAnalyze 失败", e)
                onError("AI 分析失败：${e.message ?: "未知错误"}。请检查配置或稍后重试。")
            }
        }
    }
}
