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
    val settings: List<SettingEntity>
)

object ExportDataBuilder {
    private val SENSITIVE_KEYS = setOf("apiKey", "apiSecret", "token")

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
        settings: List<SettingEntity>
    ): ExportData = ExportData(
        version = 2,
        exportedAt = exportedAt,
        records = records,
        tasks = tasks,
        tags = tags,
        recordTags = recordTags,
        keyInfos = keyInfos,
        reviews = reviews,
        memories = memories,
        diaries = diaries,
        settings = settings.filterNot { it.key in SENSITIVE_KEYS }
    )
}
