package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.db.entity.ReviewEntity
import com.zhiwo.shiguangjian.data.profile.MemoryExtractionResult
import com.zhiwo.shiguangjian.data.profile.applyAiProfileUpdate
import com.zhiwo.shiguangjian.data.profile.UserProfileCodec
import com.zhiwo.shiguangjian.data.repository.DiaryRepository
import com.zhiwo.shiguangjian.data.repository.MemoryRepository
import com.zhiwo.shiguangjian.data.repository.RecordRepository
import com.zhiwo.shiguangjian.data.repository.ReviewRepository
import com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import com.zhiwo.shiguangjian.data.repository.TaskRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ReviewViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val reviewRepo = ReviewRepository(app.database.reviewDao())
    private val memoryRepo = MemoryRepository(app.database.memoryDao())
    private val diaryRepo = DiaryRepository(app.database.diaryDao())
    private val recordRepo = RecordRepository(
        app.database, app.database.recordDao(), app.database.taskDao(),
        app.database.tagDao(), app.database.keyInfoDao()
    )
    private val taskRepo = TaskRepository(app.database.taskDao())
    private val settingsRepo = SettingsRepository(app.database.settingDao())
    private val secureSettingsRepo = SecureSettingsRepository(app)
    private val aiRepo get() = app.aiRepo

    private val _reviews = MutableStateFlow<List<ReviewEntity>>(emptyList())
    val reviews: StateFlow<List<ReviewEntity>> = _reviews

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured

    init {
        loadReviews()
        configureAi()
    }

    private fun loadReviews() {
        viewModelScope.launch {
            reviewRepo.getAllReviews().collect { _reviews.value = it }
        }
    }

    private suspend fun refreshAiConfig() {
        aiRepo.configureFromSettings(settingsRepo, secureSettingsRepo)
        _isConfigured.value = aiRepo.isConfigured
    }

    private fun configureAi() {
        viewModelScope.launch { refreshAiConfig() }
    }

    fun generateDailyReview(userInput: String, onComplete: (String?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                refreshAiConfig()
                if (!aiRepo.isConfigured) {
                    onComplete(null, "请先配置 AI 接口")
                    return@launch
                }
                val today = DateFormats.nowDate()
                val allRecords = recordRepo.getAllRecords().first()
                val allTasks = taskRepo.getAllTasks().first()
                val memories = memoryRepo.getAllMemories().first()

                // 今日记录
                val todayRecords = allRecords.filter {
                    it.createdAt.contains(today)
                }
                val todayRecordsText = todayRecords.joinToString("\n") { "· ${it.title}: ${it.content.take(80)}" }

                // 今日任务
                val todayTasks = allTasks.filter { task ->
                    val displayDate = when (task.taskType) {
                        "daily", "weekly", "goal" -> today
                        else -> task.dueDate.take(10)
                    }
                    displayDate == today
                }
                val taskSummary = todayTasks.joinToString("\n") { task ->
                    val status = if (task.isCompleted) "✓" else "○"
                    "$status ${task.content}"
                }

                val relatedMemories = memories.take(5).joinToString("\n") { "· ${it.content}" }

                // 加载已有画像，注入评价（新画像从下次开始用）
                val profile = settingsRepo.getUserProfile()
                val profileSummary = UserProfileCodec.toPromptSummary(profile)

                val reviewContent = aiRepo.generateDailyReview(
                    taskSummary = taskSummary.ifBlank { "今日暂无任务" },
                    todayRecords = todayRecordsText.ifBlank { "今日暂无记录" },
                    userInput = userInput,
                    relatedMemories = relatedMemories.ifBlank { "暂无记忆" },
                    userProfileSummary = profileSummary
                )

                reviewRepo.insertReview(
                    ReviewEntity(
                        type = "daily",
                        date = today,
                        content = reviewContent,
                        message = userInput,
                        createdAt = DateFormats.nowDateTimeIso()
                    )
                )

                // 自动生成日记
                try {
                    val existingDiary = diaryRepo.getDiaryByDate(today)
                    if (existingDiary == null) {
                        val diaryResult = aiRepo.generateDiary(
                            todayRecords = todayRecordsText.ifBlank { "今日暂无记录" },
                            taskSummary = taskSummary.ifBlank { "今日暂无任务" },
                            userInput = userInput,
                            relatedMemories = relatedMemories.ifBlank { "暂无记忆" }
                        )
                        if (diaryResult.content.isNotBlank()) {
                            diaryRepo.insertDiary(
                                DiaryEntity(
                                    date = today,
                                    content = diaryResult.content,
                                    mood = diaryResult.mood,
                                    createdAt = DateFormats.nowDateTimeIso()
                                )
                            )
                        }
                    }
                } catch (_: Throwable) {
                    // 日记生成失败不影响评价流程
                }

                // 提取记忆 + 画像（独立失败不影响评价/日记）
                val extractionResult = try {
                    aiRepo.extractMemoriesAndProfile(
                        userContent = todayRecordsText + "\n" + userInput,
                        currentProfile = profile
                    )
                } catch (e: Throwable) {
                    Log.e("ReviewViewModel", "提取记忆+画像失败，不影响评价", e)
                    MemoryExtractionResult()
                }

                // 保守去重后最多插入 2 条；仅在确实插入时 enforce 上限
                try {
                    val deduped = filterNewMemories(extractionResult.newMemories, memories)
                    if (deduped.isNotEmpty()) {
                        val now = DateFormats.nowDateTimeIso()
                        deduped.forEach { memoryContent ->
                            memoryRepo.insertMemory(
                                MemoryEntity(
                                    content = memoryContent,
                                    source = "review",
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        }
                        memoryRepo.enforceMemoryLimit(100)
                    }
                } catch (e: Throwable) {
                    Log.e("ReviewViewModel", "保存新记忆失败，不影响评价", e)
                }

                // 保存画像（独立 try-catch）：每次实时读取 flag + blocklist
                try {
                    val updated = extractionResult.updatedProfile
                    if (updated != null) {
                        val autoUpdate = settingsRepo.getProfileAutoUpdateEnabled()
                        val blocklist = settingsRepo.getProfileBlocklist()
                        val next = applyAiProfileUpdate(
                            current = profile,
                            aiUpdate = updated,
                            blocklist = blocklist,
                            autoUpdateEnabled = autoUpdate
                        )
                        // 仅当结果相对当前有变化时才写回（避免无意义写入）
                        if (next != profile) {
                            settingsRepo.saveUserProfile(next)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("ReviewViewModel", "保存画像失败，不影响评价和日记", e)
                }

                loadReviews()
                onComplete(reviewContent, null)
            } catch (e: Throwable) {
                onComplete(null, e.message ?: "生成失败")
            }
        }
    }

    fun generateWeeklyReview(onComplete: (String?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                refreshAiConfig()
                if (!aiRepo.isConfigured) {
                    onComplete(null, "请先配置 AI 接口")
                    return@launch
                }
                val allRecords = recordRepo.getAllRecords().first()
                val allTasks = taskRepo.getAllTasks().first()
                val memories = memoryRepo.getAllMemories().first()
                val relatedMemories = memories.take(5).joinToString("\n") { "· ${it.content}" }

                // 计算本周范围（周一到周日）
                val today = java.time.LocalDate.now()
                val mondayOffset = if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) -6 else 1 - today.dayOfWeek.value
                val weekStart = today.plusDays(mondayOffset.toLong()).format(DateFormats.DATE)
                val weekEnd = today.plusDays(mondayOffset.toLong() + 6).format(DateFormats.DATE)

                // 本周完成率（包含本周到期的一次性任务 + 所有持续性任务）
                val weekTasks = allTasks.filter { t ->
                    when (t.taskType) {
                        "daily", "weekly", "goal" -> true
                        else -> {
                            val d = t.dueDate.take(10)
                            d >= weekStart && d <= weekEnd
                        }
                    }
                }
                val completedCount = weekTasks.count { it.isCompleted }
                val totalCount = weekTasks.size
                val completionRate = if (totalCount > 0)
                    "已完成 $completedCount / $totalCount（${(completedCount * 100 / totalCount)}%）" else "本周暂无任务"

                // 分类统计
                val weekRecords = allRecords.filter { r ->
                    val d = r.createdAt.take(10)
                    d >= weekStart && d <= weekEnd
                }
                val categoryStats = weekRecords.groupBy { it.category }
                    .map { (cat, recs) -> "$cat: ${recs.size}条" }
                    .joinToString(", ")

                // 亮点
                val highlights = weekRecords.take(5).joinToString("\n") { "· ${it.title}" }

                val reviewContent = aiRepo.generateWeeklyReview(
                    completionRate = completionRate,
                    categoryStats = categoryStats.ifBlank { "暂无分类统计" },
                    highlights = highlights.ifBlank { "本周暂无记录" },
                    relatedMemories = relatedMemories.ifBlank { "暂无记忆" }
                )

                reviewRepo.insertReview(
                    ReviewEntity(
                        type = "weekly",
                        date = weekStart,
                        content = reviewContent,
                        createdAt = DateFormats.nowDateTimeIso()
                    )
                )

                loadReviews()
                onComplete(reviewContent, null)
            } catch (e: Throwable) {
                onComplete(null, e.message ?: "生成失败")
            }
        }
    }

    /**
     * 保守去重：trim + 英文忽略大小写；不做语义去重。
     * 最终最多返回 2 条。
     */
    companion object {
        fun filterNewMemories(
            candidates: List<String>,
            existing: List<MemoryEntity>
        ): List<String> {
            val existingKeys = existing
                .map { it.content.trim().lowercase(java.util.Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .toHashSet()
            val seen = linkedSetOf<String>()
            val result = mutableListOf<String>()
            for (raw in candidates) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) continue
                val key = trimmed.lowercase(java.util.Locale.ROOT)
                if (key in existingKeys || key in seen) continue
                seen += key
                result += trimmed
                if (result.size >= 2) break
            }
            return result
        }
    }
}
