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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExportDataBuilderTest {

    @Test
    fun `build includes all user data groups`() {
        val export = ExportDataBuilder.build(
            exportedAt = "2026-06-08T10:00:00.000Z",
            records = listOf(record()),
            tasks = listOf(task()),
            tags = listOf(TagEntity(id = 1, name = "工作", color = "#111111")),
            recordTags = listOf(RecordTagCrossRef(recordId = 1, tagId = 1)),
            keyInfos = listOf(KeyInfoEntity(id = 1, recordId = 1, content = "重点")),
            reviews = listOf(ReviewEntity(id = 1, type = "daily", date = "2026-06-08", content = "不错", createdAt = "2026-06-08T10:00:00.000Z")),
            memories = listOf(MemoryEntity(id = 1, content = "喜欢安静", source = "review", createdAt = "2026-06-08T10:00:00.000Z", updatedAt = "2026-06-08T10:00:00.000Z")),
            diaries = listOf(DiaryEntity(id = 1, date = "2026-06-08", content = "今天不错", mood = "开心", createdAt = "2026-06-08T10:00:00.000Z")),
            settings = listOf(SettingEntity("darkMode", "true"))
        )

        assertEquals(1, export.records.size)
        assertEquals(1, export.tasks.size)
        assertEquals(1, export.tags.size)
        assertEquals(1, export.recordTags.size)
        assertEquals(1, export.keyInfos.size)
        assertEquals(1, export.reviews.size)
        assertEquals(1, export.memories.size)
        assertEquals(1, export.diaries.size)
        assertEquals(1, export.settings.size)
    }

    @Test
    fun `build excludes legacy api key from settings`() {
        val export = ExportDataBuilder.build(
            exportedAt = "2026-06-08T10:00:00.000Z",
            records = emptyList(),
            tasks = emptyList(),
            tags = emptyList(),
            recordTags = emptyList(),
            keyInfos = emptyList(),
            reviews = emptyList(),
            memories = emptyList(),
            diaries = emptyList(),
            settings = listOf(
                SettingEntity("apiKey", "secret"),
                SettingEntity("modelName", "model")
            )
        )

        assertEquals(1, export.settings.size)
        assertFalse(export.settings.any { it.key == "apiKey" })
    }

    private fun record() = RecordEntity(
        id = 1,
        title = "标题",
        content = "内容",
        category = "todo",
        summary = "摘要",
        createdAt = "2026-06-08T10:00:00.000Z",
        updatedAt = "2026-06-08T10:00:00.000Z"
    )

    private fun task() = TaskEntity(
        id = 1,
        recordId = 1,
        content = "任务",
        dueDate = "2026-06-08 09:00:00",
        taskType = "once",
        createdAt = "2026-06-08T10:00:00.000Z"
    )
}
