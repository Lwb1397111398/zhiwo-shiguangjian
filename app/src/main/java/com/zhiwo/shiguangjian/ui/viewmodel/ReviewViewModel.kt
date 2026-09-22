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
    private val snapshotReader = com.zhiwo.shiguangjian.data.tasks.TaskSnapshotReader(app.database)
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
                val taskSummary = snapshotReader.summaryFor(today)

                // 只注入生效中的记忆（superseded 的视为不存在）
                val activeMemories = memories.filter { it.status == "active" }
                val relatedMemories = activeMemories.take(5).joinToString("\n") { "· ${it.content}" }

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
                        createdAt = DateFormats.nowDateTimeIso(),
                        // 数据契约：记录生成依据，供历史重生成与溯源
                        sourceRecordIds = todayRecords.map { it.id }.toString(),
                        generationVersion = 1,
                        isUserEdited = false
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
                                    createdAt = DateFormats.nowDateTimeIso(),
                                    // 数据契约：记录生成依据，供后续"依据新事实重新生成"与编辑保护判断
                                    sourceRecordIds = todayRecords.map { it.id }.toString(),
                                    generationVersion = 1,
                                    isUserEdited = false
                                )
                            )
                        }
                    }
                } catch (_: Throwable) {
                    // 日记生成失败不影响评价流程
                }

                // 提取记忆 + 画像（独立失败不影响评价/日记）
                // 每次实时读取 flag/blocklist；flag=false 时注入空串省 token（写入侧本就会忽略 updatedProfile）
                val extractionResult = try {
                    val autoUpdate = settingsRepo.getProfileAutoUpdateEnabled()
                    val blocklist = settingsRepo.getProfileBlocklist()
                    val blockedText = if (!autoUpdate) {
                        // autoUpdate 关闭：updatedProfile 会被丢弃，不必把 blocklist 塞进 Prompt
                        ""
                    } else {
                        com.zhiwo.shiguangjian.data.profile.ProfileBlocklistPrompt.render(blocklist)
                    }
                    aiRepo.extractMemoriesAndProfile(
                        userContent = todayRecordsText + "\n" + userInput,
                        currentProfile = profile,
                        blockedItemsText = blockedText
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
                                    updatedAt = now,
                                    // 记忆讲的"哪天发生的事"与"什么时候写进库"必须分开，否则排序与上限淘汰都会认错
                                    occurredAt = "${today}T00:00:00.000Z"
                                )
                            )
                        }
                        memoryRepo.enforceMemoryLimit(100)
                    }
                } catch (e: Throwable) {
                    Log.e("ReviewViewModel", "保存新记忆失败，不影响评价", e)
                }

                // 记忆对账：今日内容若推翻了旧记忆（如"准备法考"被"忘记报名"推翻），
                // 自动停用被取代的旧记忆；中低置信建议进记忆页待确认队列
                try {
                    val reconcileContent = todayRecordsText + "\n" + userInput
                    if (reconcileContent.isNotBlank() && activeMemories.isNotEmpty()) {
                        val applied = app.memoryReconciler.reconcileAndApply(
                            newContent = reconcileContent,
                            now = DateFormats.nowDateTimeIso()
                        )
                        if (applied > 0) {
                            Log.i("ReviewViewModel", "记忆对账：自动更正 $applied 条")
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("ReviewViewModel", "记忆对账失败，不影响评价", e)
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

    /**
     * 历史每日评价一键重生成：按当前 active 记忆 + 该日期记录/任务重写内容，
     * 复用原评价保存的用户感受（message）。isUserEdited=true 拒绝覆盖。
     */
    fun regenerateDailyReview(reviewId: Long, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val review = reviews.value.find { it.id == reviewId }
                    ?: reviewRepo.getReviewById(reviewId)
                    ?: run { onResult(false, "原评价已不存在"); return@launch }
                if (review.type != "daily") { onResult(false, "周报暂不支持重生成"); return@launch }
                if (review.isUserEdited) { onResult(false, "这条评价已被手动编辑过，不做覆盖"); return@launch }

                refreshAiConfig()
                if (!aiRepo.isConfigured) { onResult(false, "请先配置 AI 接口"); return@launch }

                val date = review.date
                val allRecords = recordRepo.getAllRecords().first()
                val dayRecords = allRecords.filter { it.createdAt.contains(date) }
                val todayRecordsText = dayRecords.joinToString("\n") { "· ${it.title}: ${it.content.take(80)}" }

                val taskSummary = snapshotReader.summaryFor(date)

                val relatedMemories = memoryRepo.getAllMemories().first()
                    .filter { it.status == "active" }
                    .take(5).joinToString("\n") { "· ${it.content}" }

                val profile = settingsRepo.getUserProfile()
                val profileSummary = UserProfileCodec.toPromptSummary(profile)

                val newContent = aiRepo.generateDailyReview(
                    taskSummary = taskSummary.ifBlank { "当日暂无任务" },
                    todayRecords = todayRecordsText.ifBlank { "当日暂无记录" },
                    userInput = review.message,  // 复用用户当天的感受输入
                    relatedMemories = relatedMemories.ifBlank { "暂无记忆" },
                    userProfileSummary = profileSummary
                )

                reviewRepo.updateReview(
                    review.copy(
                        content = newContent,
                        sourceRecordIds = dayRecords.map { it.id }.toString(),
                        generationVersion = review.generationVersion + 1,
                        isUserEdited = false
                    )
                )
                loadReviews()
                onResult(true, "已重新生成（第 ${review.generationVersion + 1} 版）")
            } catch (e: Throwable) {
                Log.e("ReviewViewModel", "重生成评价失败", e)
                onResult(false, "重生成失败：${e.message ?: "未知错误"}")
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
                val memories = memoryRepo.getAllMemories().first().filter { it.status == "active" }
                val relatedMemories = memories.take(5).joinToString("\n") { "· ${it.content}" }

                // 计算本周范围（周一到周日）
                val today = java.time.LocalDate.now()
                val mondayOffset = if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) -6 else 1 - today.dayOfWeek.value
                val weekStart = today.plusDays(mondayOffset.toLong()).format(DateFormats.DATE)
                val weekEnd = today.plusDays(mondayOffset.toLong() + 6).format(DateFormats.DATE)

                // 本周完成率（包含本周到期的一次性任务 + 所有持续性任务）
                val weekDays = (0L until 7L).map { weekStart.let { d -> java.time.LocalDate.parse(d).plusDays(it).toString() } }
                val (totalCount, completedCount) = snapshotReader.weekProgress(weekDays)
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
