package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import com.zhiwo.shiguangjian.data.diary.prepareEditedDiary
import com.zhiwo.shiguangjian.data.repository.DiaryRepository
import com.zhiwo.shiguangjian.data.repository.MemoryRepository
import com.zhiwo.shiguangjian.data.repository.RecordRepository
import com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import com.zhiwo.shiguangjian.data.repository.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 日记统计（连续天数跨月正确计算） */
data class DiaryStats(
    val total: Int,
    val streakDays: Int,
    val last30MoodCounts: Map<String, Int>
)

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val diaryRepo = DiaryRepository(app.database.diaryDao())
    private val recordRepo = RecordRepository(
        app.database, app.database.recordDao(), app.database.taskDao(),
        app.database.tagDao(), app.database.keyInfoDao()
    )
    private val taskRepo = TaskRepository(app.database.taskDao())
    private val memoryRepo = MemoryRepository(app.database.memoryDao())
    private val settingsRepo = SettingsRepository(app.database.settingDao())
    private val secureSettingsRepo = SecureSettingsRepository(app)
    private val aiRepo get() = app.aiRepo

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _moodFilter = MutableStateFlow("")
    val moodFilter: StateFlow<String> = _moodFilter

    /** 可选情绪列表（生成 prompt 约定的 8 种） */
    val moods = listOf("开心", "平淡", "低落", "焦虑", "充实", "疲惫", "感动", "释然")

    val diaries: StateFlow<List<DiaryEntity>> = combine(_searchQuery, _moodFilter) { query, mood ->
        query to mood
    }.flatMapLatest { (query, mood) ->
        when {
            query.isNotBlank() && mood.isNotBlank() ->
                diaryRepo.searchDiaries(query).map { list -> list.filter { it.mood == mood } }
            query.isNotBlank() -> diaryRepo.searchDiaries(query)
            mood.isNotBlank() -> diaryRepo.getAllDiaries().map { list -> list.filter { it.mood == mood } }
            else -> diaryRepo.getAllDiaries()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 全量日记统计（不受筛选影响） */
    val stats: StateFlow<DiaryStats> = diaryRepo.getAllDiaries()
        .map { list -> computeStats(list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DiaryStats(0, 0, emptyMap()))

    /** 全量日记（不筛情绪/搜索），供"那年今日"使用 */
    val allDiaries: StateFlow<List<DiaryEntity>> = diaryRepo.getAllDiaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _exportMessage = MutableSharedFlow<String>()
    val exportMessage: SharedFlow<String> = _exportMessage

    private val _cleanupMessage = MutableSharedFlow<String>()
    val cleanupMessage: SharedFlow<String> = _cleanupMessage

    private val _regenerating = MutableStateFlow<Long?>(null)  // 正在重生成的日记 id（防重复点击）
    val regenerating: StateFlow<Long?> = _regenerating

    companion object {
        /** 纯函数统计：连续天数从今天（或昨天，容错当天还没写）往回数 */
        fun computeStats(all: List<DiaryEntity>): DiaryStats {
            val dates = all.map { it.date }.toSortedSet()
            val total = all.size
            val today = java.time.LocalDate.now()
            val dateSet = dates.mapNotNull { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }.toHashSet()
            var streak = 0
            var cursor = if (today in dateSet) today else today.minusDays(1)  // 今天没写不断连
            while (cursor in dateSet) {
                streak++
                cursor = cursor.minusDays(1)
            }
            val thirtyDaysAgo = today.minusDays(30)
            val moodCounts = all
                .filter { runCatching { java.time.LocalDate.parse(it.date) }.getOrNull()?.isAfter(thirtyDaysAgo) == true }
                .groupingBy { it.mood }
                .eachCount()
            return DiaryStats(total, streak, moodCounts)
        }
    }

    fun searchDiaries(query: String) { _searchQuery.value = query }

    fun setMoodFilter(mood: String) {
        _moodFilter.value = if (_moodFilter.value == mood) "" else mood
    }

    fun deleteDiary(id: Long) {
        viewModelScope.launch { diaryRepo.deleteDiaryById(id) }
    }

    /** 手动新建/补写日记：用户手写内容，isUserEdited=true（永不被自动重生成覆盖） */
    fun createDiary(date: String, mood: String, content: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val trimmedDate = date.trim()
                val trimmed = content.trim()
                if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(trimmedDate)) {
                    onResult(false, "日期格式应为 yyyy-MM-dd"); return@launch
                }
                if (trimmed.isEmpty()) { onResult(false, "内容不能为空"); return@launch }
                if (diaryRepo.getDiaryByDate(trimmedDate) != null) {
                    onResult(false, "$trimmedDate 已有日记（每天一篇），可直接编辑那篇"); return@launch
                }
                diaryRepo.insertDiary(
                    DiaryEntity(
                        date = trimmedDate,
                        content = trimmed,
                        mood = mood.ifBlank { "平淡" },
                        createdAt = DateFormats.nowDateTimeIso(),
                        sourceRecordIds = null,
                        generationVersion = 1,
                        isUserEdited = true
                    )
                )
                onResult(true, "已保存 $trimmedDate 的日记")
            } catch (e: Throwable) {
                _exportMessage.emit("保存失败：${e.message}")
                onResult(false, "保存失败：${e.message}")
            }
        }
    }

    fun updateDiary(diary: DiaryEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                diaryRepo.updateDiary(diary)
                onDone()
            } catch (e: Throwable) {
                _exportMessage.emit("保存失败：${e.message}")
            }
        }
    }

    fun updateDiaryContent(diary: DiaryEntity, newContent: String, onDone: () -> Unit = {}) {
        updateDiary(prepareEditedDiary(diary, newContent), onDone)
    }

    /**
     * 重新生成日记：委托共享的 DiaryRegenerator（记忆页历史纠错扫描共用同一逻辑）。
     * isUserEdited=true 的日记拒绝覆盖（用户内容保护）。
     */
    fun regenerateDiary(diary: DiaryEntity, onResult: (Boolean, String) -> Unit) {
        if (_regenerating.value != null) { onResult(false, "正在生成中，请稍候"); return }
        viewModelScope.launch {
            _regenerating.value = diary.id
            try {
                when (val outcome = app.diaryRegenerator.regenerate(diary)) {
                    is com.zhiwo.shiguangjian.data.diary.DiaryRegenerator.Outcome.Success ->
                        onResult(true, "已重新生成（第 ${outcome.newVersion} 版）")
                    is com.zhiwo.shiguangjian.data.diary.DiaryRegenerator.Outcome.Rejected ->
                        onResult(false, outcome.reason)
                    is com.zhiwo.shiguangjian.data.diary.DiaryRegenerator.Outcome.Failed ->
                        onResult(false, "重新生成失败：${outcome.reason}")
                }
            } finally {
                _regenerating.value = null
            }
        }
    }

    fun cleanupExportedDiaries() {
        viewModelScope.launch {
            try {
                val exportedCount = diaryRepo.getExportedCount()
                if (exportedCount == 0) {
                    _cleanupMessage.emit("没有已导出的日记可清理")
                    return@launch
                }
                diaryRepo.deleteExportedDiaries()
                _cleanupMessage.emit("已清理 $exportedCount 篇已导出的日记")
            } catch (e: Throwable) {
                _cleanupMessage.emit("清理失败：${e.message}")
            }
        }
    }

    /** 导出格式：txt 纯文本 或 md（Markdown） */
    fun exportDiary(diary: DiaryEntity, asMarkdown: Boolean) {
        viewModelScope.launch {
            try {
                val ext = if (asMarkdown) "md" else "txt"
                val fileName = "日记_${diary.date}.$ext"
                val content = if (asMarkdown) buildMarkdown(listOf(diary)) else buildTxt(listOf(diary), single = true)
                withContext(Dispatchers.IO) { saveToDownloads(fileName, content) }
                diaryRepo.markAsExported(diary.id)
                _exportMessage.emit("已导出到 Downloads/$fileName")
            } catch (e: Throwable) {
                _exportMessage.emit("导出失败：${e.message}")
            }
        }
    }

    fun exportAllDiaries(asMarkdown: Boolean) {
        viewModelScope.launch {
            try {
                val allDiaries = diaryRepo.getAllDiaries().first()
                if (allDiaries.isEmpty()) {
                    _exportMessage.emit("没有可导出的日记")
                    return@launch
                }
                val ext = if (asMarkdown) "md" else "txt"
                val fileName = "知我时光笺_全部日记_${DateFormats.nowDate()}.$ext"
                val content = if (asMarkdown) buildMarkdown(allDiaries) else buildTxt(allDiaries, single = false)
                withContext(Dispatchers.IO) { saveToDownloads(fileName, content) }
                diaryRepo.markAllAsExported(allDiaries.map { it.id })
                _exportMessage.emit("已导出全部 ${allDiaries.size} 篇日记到 Downloads")
            } catch (e: Throwable) {
                _exportMessage.emit("导出失败：${e.message}")
            }
        }
    }

    private fun buildTxt(list: List<DiaryEntity>, single: Boolean): String = buildString {
        appendLine("═══════════════════════════════")
        appendLine("  知我时光笺 · ${if (single) "日记" else "全部日记"}")
        appendLine("  导出时间：${DateFormats.nowDateTimeDisplay()}")
        if (!single) appendLine("  共 ${list.size} 篇")
        appendLine("═══════════════════════════════")
        list.forEach { diary ->
            appendLine()
            appendLine("───────────────────────────────")
            appendLine("${formatDisplayDate(diary.date)}  情绪：${diary.mood}")
            appendLine("───────────────────────────────")
            appendLine()
            appendLine(diary.content)
        }
        appendLine()
        appendLine("═══════════════════════════════")
    }

    private fun buildMarkdown(list: List<DiaryEntity>): String = buildString {
        appendLine("# 知我时光笺 · 日记")
        appendLine()
        appendLine("> 导出时间：${DateFormats.nowDateTimeDisplay()} · 共 ${list.size} 篇")
        appendLine()
        list.forEach { diary ->
            appendLine("## ${formatDisplayDate(diary.date)}")
            appendLine()
            appendLine("**情绪**：${diary.mood}")
            appendLine()
            appendLine(diary.content)
            appendLine()
            appendLine("---")
            appendLine()
        }
    }

    private fun saveToDownloads(fileName: String, content: String) {
        val context = getApplication<android.app.Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建导出文件")
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("无法写入导出文件")
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeText(content, Charsets.UTF_8)
        }
    }

    private fun formatDisplayDate(dateStr: String): String {
        return try {
            val parts = dateStr.split("-")
            if (parts.size == 3) "${parts[0]}年${parts[1].toInt()}月${parts[2].toInt()}" else dateStr
        } catch (_: Exception) {
            dateStr
        }
    }
}
