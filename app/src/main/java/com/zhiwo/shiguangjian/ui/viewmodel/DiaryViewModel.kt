package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import com.zhiwo.shiguangjian.data.diary.prepareEditedDiary
import com.zhiwo.shiguangjian.data.repository.DiaryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val diaryRepo = DiaryRepository(app.database.diaryDao())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val diaries: StateFlow<List<DiaryEntity>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) diaryRepo.getAllDiaries() else diaryRepo.searchDiaries(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _exportMessage = MutableSharedFlow<String>()
    val exportMessage: SharedFlow<String> = _exportMessage

    private val _cleanupMessage = MutableSharedFlow<String>()
    val cleanupMessage: SharedFlow<String> = _cleanupMessage

    fun searchDiaries(query: String) {
        _searchQuery.value = query
    }

    fun deleteDiary(id: Long) {
        viewModelScope.launch {
            diaryRepo.deleteDiaryById(id)
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

    fun exportDiaryAsTxt(diary: DiaryEntity) {
        viewModelScope.launch {
            try {
                val fileName = "日记_${diary.date}.txt"
                val content = buildString {
                    appendLine("═══════════════════════════════")
                    appendLine("  知我时光笺 · 日记")
                    appendLine("  ${formatDisplayDate(diary.date)}  情绪：${diary.mood}")
                    appendLine("═══════════════════════════════")
                    appendLine()
                    appendLine(diary.content)
                    appendLine()
                    appendLine("───────────────────────────────")
                    appendLine("导出时间：${com.zhiwo.shiguangjian.data.ai.DateFormats.nowDateTimeDisplay()}")
                }
                saveToDownloads(fileName, content)
                diaryRepo.markAsExported(diary.id)
                _exportMessage.emit("已导出到 Downloads/$fileName")
            } catch (e: Throwable) {
                _exportMessage.emit("导出失败：${e.message}")
            }
        }
    }

    fun exportAllDiariesAsTxt() {
        viewModelScope.launch {
            try {
                val allDiaries = diaryRepo.getAllDiaries().first()
                if (allDiaries.isEmpty()) {
                    _exportMessage.emit("没有可导出的日记")
                    return@launch
                }
                val fileName = "知我时光笺_全部日记_${com.zhiwo.shiguangjian.data.ai.DateFormats.nowDate()}.txt"
                val content = buildString {
                    appendLine("═══════════════════════════════")
                    appendLine("  知我时光笺 · 全部日记")
                    appendLine("  导出时间：${com.zhiwo.shiguangjian.data.ai.DateFormats.nowDateTimeDisplay()}")
                    appendLine("  共 ${allDiaries.size} 篇")
                    appendLine("═══════════════════════════════")
                    allDiaries.forEach { diary ->
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
                saveToDownloads(fileName, content)
                diaryRepo.markAllAsExported(allDiaries.map { it.id })
                _exportMessage.emit("已导出全部 ${allDiaries.size} 篇日记到 Downloads")
            } catch (e: Throwable) {
                _exportMessage.emit("导出失败：${e.message}")
            }
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
            if (parts.size == 3) "${parts[0]}年${parts[1].toInt()}月${parts[2].toInt()}日" else dateStr
        } catch (_: Exception) {
            dateStr
        }
    }
}
