package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.alarm.SmartScheduleManager
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.KeyInfoEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TagEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordTagCrossRef
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
            it.recordDao(), it.taskDao(), it.tagDao(), it.keyInfoDao()
        )
    }
    private val taskRepo = com.zhiwo.shiguangjian.data.repository.TaskRepository(app.database.taskDao())
    private val tagRepo = app.database.tagDao()
    private val settingsRepo = com.zhiwo.shiguangjian.data.repository.SettingsRepository(app.database.settingDao())
    private val secureSettingsRepo = com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository(app)
    private val aiRepo get() = app.aiRepo

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured

    init {
        viewModelScope.launch {
            aiRepo.configureFromSettings(settingsRepo, secureSettingsRepo)
            _isConfigured.value = aiRepo.isConfigured
        }
    }

    fun saveAndAnalyze(
        content: String,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        android.util.Log.i("InputVM", "saveAndAnalyze 开始, content长度=${content.length}")
        viewModelScope.launch {
            try {
                android.util.Log.i("InputVM", "开始 analyzeContent")
                val analysis = aiRepo.analyzeContent(content)
                android.util.Log.i("InputVM", "analyzeContent 完成: title=${analysis.title}, category=${analysis.category}, tasks=${analysis.tasks.size}")

                // 后置校验：tasks 非空但 category 不是 todo → 强制修正
                val correctedCategory = if (analysis.tasks.isNotEmpty() && analysis.category != "todo") {
                    android.util.Log.w("InputVM", "tasks 非空但 category=${analysis.category}，强制修正为 todo")
                    "todo"
                } else {
                    analysis.category
                }

                // 后置校验：title 为空时，取 content 前 20 字符作为标题
                val safeTitle = analysis.title.ifBlank {
                    android.util.Log.w("InputVM", "AI 返回空标题，使用内容前 20 字符作为标题")
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
