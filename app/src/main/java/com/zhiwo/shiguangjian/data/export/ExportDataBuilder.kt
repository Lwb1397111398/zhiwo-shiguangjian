package com.zhiwo.shiguangjian.data.export

import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import com.zhiwo.shiguangjian.data.db.entity.KeyInfoEntity
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordTagCrossRef
import com.zhiwo.shiguangjian.data.db.entity.ReviewEntity
import com.zhiwo.shiguangjian.data.db.entity.SettingEntity
import com.zhiwo.shiguangjian.data.db.entity.TagEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import com.zhiwo.shiguangjian.data.db.entity.DayOverrideEntity

data class ExportData(
    val version: Int,
    val exportedAt: String,
    val records: List<RecordEntity>,
    val tasks: List<TaskEntity>,
    val tags: List<TagEntity>,
    val recordTags: List<RecordTagCrossRef>,
    val keyInfos: List<KeyInfoEntity>,
    val reviews: List<ReviewEntity>,
    val memories: List<MemoryEntity>,
    val diaries: List<DiaryEntity>,
    val settings: List<SettingEntity>,
    val goals: List<GoalEntity> = emptyList(),
    val plans: List<PlanEntity> = emptyList(),
    val taskOccurrences: List<TaskOccurrenceEntity> = emptyList(),
    val dayOverrides: List<DayOverrideEntity> = emptyList()
)

object ExportDataBuilder {
    // 精确敏感键 + 敏感前缀（防止未来新增键遗漏；apiBaseUrl 等非凭证键正常导出）
    private val SENSITIVE_KEYS = setOf("apiKey", "apiSecret", "token")
    private val SENSITIVE_PREFIXES = listOf("password", "secret", "token")

    private fun isSensitive(key: String): Boolean {
        if (key in SENSITIVE_KEYS) return true
        val lowered = key.lowercase()
        return SENSITIVE_PREFIXES.any { lowered.startsWith(it) }
    }

    fun build(
        exportedAt: String,
        records: List<RecordEntity>,
        tasks: List<TaskEntity>,
        tags: List<TagEntity>,
        recordTags: List<RecordTagCrossRef>,
        keyInfos: List<KeyInfoEntity>,
        reviews: List<ReviewEntity>,
        memories: List<MemoryEntity>,
        diaries: List<DiaryEntity>,
        settings: List<SettingEntity>,
        goals: List<GoalEntity> = emptyList(),
        plans: List<PlanEntity> = emptyList(),
        taskOccurrences: List<TaskOccurrenceEntity> = emptyList(),
        dayOverrides: List<DayOverrideEntity> = emptyList()
    ): ExportData = ExportData(
        version = 3,
        exportedAt = exportedAt,
        records = records,
        tasks = tasks,
        tags = tags,
        recordTags = recordTags,
        keyInfos = keyInfos,
        reviews = reviews,
        memories = memories,
        diaries = diaries,
        settings = settings.filterNot { isSensitive(it.key) },
        goals = goals,
        plans = plans,
        taskOccurrences = taskOccurrences,
        dayOverrides = dayOverrides
    )
}
