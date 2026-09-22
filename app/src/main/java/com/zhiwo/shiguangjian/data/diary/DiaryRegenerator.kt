package com.zhiwo.shiguangjian.data.diary

import com.zhiwo.shiguangjian.data.ai.AiRepository
import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.db.entity.ReviewEntity
import com.zhiwo.shiguangjian.data.repository.DiaryRepository
import com.zhiwo.shiguangjian.data.repository.MemoryRepository
import com.zhiwo.shiguangjian.data.repository.RecordRepository
import com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import com.zhiwo.shiguangjian.data.repository.TaskRepository
import kotlinx.coroutines.flow.first

/**
 * 日记重生成共享服务：DiaryViewModel（手动重生成）与记忆页（历史纠错扫描）共用。
 * isUserEdited=true 拒绝覆盖；生成走当前 active 记忆 + 当日记录（sourceRecordIds 为空时按日期回退）。
 */
class DiaryRegenerator(
    private val diaryRepo: DiaryRepository,
    private val recordRepo: RecordRepository,
    private val taskRepo: TaskRepository,
    private val memoryRepo: MemoryRepository,
    private val settingsRepo: SettingsRepository,
    private val secureSettingsRepo: SecureSettingsRepository,
    private val aiRepo: AiRepository,
    private val snapshotReader: com.zhiwo.shiguangjian.data.tasks.TaskSnapshotReader
) {

    sealed class Outcome {
        data class Success(val newVersion: Int) : Outcome()
        data class Rejected(val reason: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    suspend fun regenerate(diary: DiaryEntity): Outcome {
        if (diary.isUserEdited) return Outcome.Rejected("这篇日记你手动编辑过，为避免覆盖你的内容已停止")
        return try {
            aiRepo.configureFromSettings(settingsRepo, secureSettingsRepo)
            if (!aiRepo.isConfigured) return Outcome.Failed("请先配置 AI 接口")

            val day = diary.date
            val dayRecords = recordRepo.getAllRecords().first().filter { it.createdAt.contains(day) }
            val todayRecordsText = dayRecords.joinToString("\n") { "· ${it.title}: ${it.content.take(80)}" }

            // 这天做过什么由排期引擎判定（历史日期不能拿"现在勾上的完成态"倒推）
            val taskSummary = snapshotReader.summaryFor(day)

            val relatedMemories = memoryRepo.getAllMemories().first()
                .filter { it.status == "active" }
                .take(5).joinToString("\n") { "· ${it.content}" }

            val result = aiRepo.generateDiary(
                todayRecords = todayRecordsText.ifBlank { "当日暂无记录" },
                taskSummary = taskSummary.ifBlank { "当日暂无任务" },
                userInput = "（历史日记重新生成，无当日用户输入）",
                relatedMemories = relatedMemories.ifBlank { "暂无记忆" }
            )
            if (result.content.isBlank()) return Outcome.Failed("生成结果为空")

            val newVersion = diary.generationVersion + 1
            diaryRepo.updateDiary(
                diary.copy(
                    content = result.content,
                    mood = result.mood,
                    sourceRecordIds = dayRecords.map { it.id }.toString(),
                    generationVersion = newVersion,
                    isUserEdited = false
                )
            )
            Outcome.Success(newVersion)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Outcome.Failed(e.message ?: "未知错误")
        }
    }
}

/**
 * 历史纠错扫描（启发式）：
 * 在已更正（superseded）记忆的基础上，找出生成时间早于更正时间、
 * 且正文命中旧记忆 ≥2 字词元的日记/评价。
 * 无法穷尽改写过的表述，仅作清理辅助——UI 文案需如实标注。
 */
object HistoryCorrectionScanner {

    data class Hit(
        val type: String,            // "diary" | "review"
        val id: Long,
        val date: String,
        val matchedMemory: String,   // 命中的旧记忆文本
        val matchedFragment: String, // 命中的词元
        val isUserEdited: Boolean
    )

    fun scan(
        diaries: List<DiaryEntity>,
        reviews: List<ReviewEntity>,
        supersededMemories: List<MemoryEntity>
    ): List<Hit> {
        val hits = mutableListOf<Hit>()
        for (memory in supersededMemories) {
            val correctedAt = memory.updatedAt
            val tokens = extractTokens(memory.content)
            if (tokens.isEmpty()) continue
            for (diary in diaries) {
                if (diary.createdAt >= correctedAt) continue
                val fragment = tokens.firstOrNull { diary.content.contains(it) } ?: continue
                hits.add(Hit("diary", diary.id, diary.date, memory.content, fragment, diary.isUserEdited))
            }
            for (review in reviews) {
                if (review.createdAt >= correctedAt) continue
                val fragment = tokens.firstOrNull { review.content.contains(it) } ?: continue
                hits.add(Hit("review", review.id, review.date, memory.content, fragment, false))
            }
        }
        return hits
    }

    /** 把记忆文本切成 ≥2 字的词元（按标点/空白切段，长段再滑动切 4 字块） */
    internal fun extractTokens(text: String, minLen: Int = 2): List<String> {
        return text.split(Regex("[\\p{Punct}\\s\\u3000，。！？、；：\\u201C\\u201D\\u2018\\u2019（）·—…《》【】]+"))
            .map { it.trim() }
            .filter { it.length >= minLen }
            .flatMap { segment ->
                if (segment.length <= 4) listOf(segment)
                else segment.windowed(size = 4, step = 2)  // 长段切成 4 字滑窗，降低误配
            }
    }
}
