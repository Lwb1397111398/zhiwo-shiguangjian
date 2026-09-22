package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import org.junit.Assert.*
import org.junit.Test

/** 评价 / 日记取的是这份文本：历史日期的完成态必须由打卡记录决定 */
class TaskSummaryBuilderTest {

    private fun task(id: Long, kind: String = "daily", due: String = "", content: String = "t$id") =
        TaskEntity(id = id, content = content, createdAt = "2026-09-21T00:00:00.000Z",
            kind = kind, dueDate = due, scheduledDate = due)

    private fun occ(id: Long, date: String, status: String = "done", reason: String = "", note: String = "") =
        TaskOccurrenceEntity(id = id, taskId = id, date = date, status = status,
            reasonCode = reason, note = note, createdAt = date)

    private val workday: (String) -> DayType? = { DayType.WORKDAY }

    @Test fun S01_当天已打卡输出勾并且带回填内容() {
        val lines = summaryForDate("2026-09-22", listOf(task(1)), listOf(occ(1, "2026-09-22", note = "看了两章")), DayType.WORKDAY)
        assertEquals(1, lines.size)
        assertEquals("✓", lines.single().mark)
        assertEquals("看了两章", lines.single().note)
    }

    @Test fun S02_历史日期不能用今天的完成态倒推() {
        // 今天才勾上的老式一次性任务（无当天打卡）在昨天不该显示成已完成
        val lines = summaryForDate("2026-09-21", listOf(task(2, "adhoc", "2026-09-21")),
            listOf(occ(2, "2026-09-22")), DayType.WORKDAY)
        assertEquals("○", lines.single().mark)
    }

    @Test fun S03_未做带原因时输出叉与原因() {
        val lines = summaryForDate("2026-09-22", listOf(task(3)),
            listOf(occ(3, "2026-09-22", status = "not_done", reason = "no_time")), DayType.WORKDAY)
        assertEquals("✗", lines.single().mark)
        assertTrue(lines.single().note.contains("no_time"))
    }

    @Test fun S04_文本拼接包含内容原因与留白记录() {
        val text = summaryText(summaryForDate("2026-09-22", listOf(task(4, content = "自由阅读")),
            listOf(occ(4, "2026-09-22", note = "读民法")), DayType.WORKDAY))
        assertTrue(text.contains("✓ 自由阅读"))
        assertTrue(text.contains("读民法"))
    }

    @Test fun S05_无任务时返回空文本而不是空话() {
        assertEquals("", summaryText(summaryForDate("2026-09-22", emptyList(), emptyList(), DayType.WORKDAY)))
    }

    @Test fun S06_周进度按排期日展开且只算已打卡() {
        val week = listOf("2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25", "2026-09-26", "2026-09-27")
        val (due, done) = weekProgress(week, listOf(task(5)), listOf(occ(5, "2026-09-22")), workday)
        assertEquals(7, due)
        assertEquals(1, done)
    }

    @Test fun S07_周进度里被重复规则跳过的天不计入分母() {
        val t = task(6).copy(repeatRule = "custom", weekdaysCsv = "1,5")   // 周一、周五
        val week = listOf("2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25")
        val (due, _) = weekProgress(week, listOf(t), emptyList(), workday)
        assertEquals(2, due)
    }

    @Test fun S08_休息日不计入只在工作日任务的分母() {
        val t = task(7).copy(dayPolicy = "workday_only")
        val resolver: (String) -> DayType? = { if (it == "2026-10-01") DayType.HOLIDAY else DayType.WORKDAY }
        val (due, _) = weekProgress(listOf("2026-09-30", "2026-10-01"), listOf(t), emptyList(), resolver)
        assertEquals(1, due)
    }
}
