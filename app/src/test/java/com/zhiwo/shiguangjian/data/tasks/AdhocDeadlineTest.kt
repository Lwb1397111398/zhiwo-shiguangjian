package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-c：adhoc「最后期限」的语义搬迁。
 * 红线是"老数据的期限一条都不许丢"——期限过去写在 dueDate（常带时间），新编辑器写在 endDate。
 */
class AdhocDeadlineTest {

    private fun t(
        id: Long,
        kind: String = "adhoc",
        dueDate: String = "",
        endDate: String = "",
        scheduledDate: String = ""
    ) = TaskEntity(
        id = id, content = "c$id", createdAt = "2026-09-01T00:00:00.000Z",
        kind = kind, dueDate = dueDate, endDate = endDate, scheduledDate = scheduledDate
    )

    @Test fun D01_只有老dueDate时期限取日期部分() {
        assertEquals("2026-09-20", t(1, dueDate = "2026-09-20 09:00:00").deadlineDate)
    }

    @Test fun D02_两个都有值时以endDate为准() {
        assertEquals("2026-09-25", t(2, dueDate = "2026-09-20", endDate = "2026-09-25").deadlineDate)
    }

    @Test fun D03_两个都空就是没有期限() {
        assertEquals("", t(3).deadlineDate)
    }

    @Test fun D05_期限当天不算过期次日才过期() {
        val task = t(5, dueDate = "2026-09-20 09:00:00")
        assertEquals(false, adhocOverdueOn(task, "2026-09-20"))
        assertEquals(true, adhocOverdueOn(task, "2026-09-21"))
    }

    @Test fun D06_无期限的临时任务过了出现日就算过期() {
        val task = t(6, scheduledDate = "2026-09-20")
        assertEquals(false, adhocOverdueOn(task, "2026-09-20"))
        assertEquals(true, adhocOverdueOn(task, "2026-09-21"))
    }

    @Test fun D07_既无期限又无出现日则永不过期() {
        assertEquals(false, adhocOverdueOn(t(7), "2026-12-31"))
    }

    @Test fun D08_引擎仍把只有老dueDate的临时任务判为过期() {
        val legacy = t(8, dueDate = "2026-09-20 09:00:00")
        val nextDay = entriesFor("2026-09-21", listOf(legacy), emptyList(), DayType.WORKDAY)
        assertEquals(listOf(8L), nextDay.overdue.map { it.task.id })
        assertEquals(emptyList<Long>(), nextDay.planned.map { it.task.id })

        val onDeadline = entriesFor("2026-09-20", listOf(legacy), emptyList(), DayType.WORKDAY)
        assertEquals(listOf(8L), onDeadline.planned.map { it.task.id })
        assertEquals(emptyList<Long>(), onDeadline.overdue.map { it.task.id })
    }

    @Test fun D09_期限搬进endDate之后判定完全一致() {
        val moved = t(9, endDate = "2026-09-20")
        val nextDay = entriesFor("2026-09-21", listOf(moved), emptyList(), DayType.WORKDAY)
        assertEquals(listOf(9L), nextDay.overdue.map { it.task.id })
        assertEquals(emptyList<Long>(), nextDay.planned.map { it.task.id })
    }
}
