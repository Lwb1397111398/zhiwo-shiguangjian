package com.zhiwo.shiguangjian.data.db

import org.junit.Assert.*
import org.junit.Test

/** v13 存量搬运的映射规则。每条都对应真机上可能遇到的历史数据形态 */
class LegacyTaskMapperTest {

    private fun row(
        id: Long = 1,
        taskType: String = "once",
        dueDate: String = "",
        isCompleted: Boolean = false,
        completedAt: String? = null,
        dailyCompletionDate: String? = null,
        isPermanentlyCompleted: Boolean = false,
        parentGoalId: Long? = null,
        recordId: Long? = null,
        createdAt: String = "2026-08-01T09:00:00.000Z"
    ) = LegacyTaskRow(
        id = id, content = "c$id", recordId = recordId, parentGoalId = parentGoalId, dueDate = dueDate,
        taskType = taskType, isCompleted = isCompleted, completedAt = completedAt,
        dailyCompletionDate = dailyCompletionDate, isPermanentlyCompleted = isPermanentlyCompleted,
        calendarEventId = null, createdAt = createdAt
    )

    private fun map(r: LegacyTaskRow, goalMap: Map<Long, Long> = emptyMap()) =
        mapLegacyTask(r, { goalMap[it] }, "2026-09-21")

    @Test fun M01_daily映射为每日固定且每天重复() {
        val (m, _) = map(row(taskType = "daily"))
        assertEquals("daily", m.kind)
        assertEquals("everyday", m.repeatRule)
        assertEquals("all", m.dayPolicy)
        assertEquals("", m.startDate)
    }

    @Test fun M02_weekly按dueDate的星期生成custom且起始日不早于迁移日() {
        // 2026-09-23 是周三
        val (m, _) = map(row(taskType = "weekly", dueDate = "2026-09-23 07:30:00"))
        assertEquals("daily", m.kind)
        assertEquals("custom", m.repeatRule)
        assertEquals("3", m.weekdaysCsv)
        assertEquals("07:30", m.remindTime)
        assertEquals(MIGRATION_DATE_COLUMN, m.startDate)
    }

    @Test fun M03_weekly周日为ISO_7() {
        val (m, _) = map(row(taskType = "weekly", dueDate = "2026-09-27 08:00:00"))
        assertEquals("7", m.weekdaysCsv)
    }

    @Test fun M04_weekly缺日期时退化为每天并记问题() {
        val (m, issues) = map(row(taskType = "weekly", dueDate = ""))
        assertEquals("everyday", m.repeatRule)
        assertEquals("", m.weekdaysCsv)
        assertTrue(issues.any { it.startsWith("WEEKLY_NO_DUEDATE") })
    }

    @Test fun M05_once带完整时间串时只取日期与提醒() {
        val (m, _) = map(row(taskType = "once", dueDate = "2026-09-25 14:00:00"))
        assertEquals("adhoc", m.kind)
        assertEquals("2026-09-25", m.scheduledDate)
        assertEquals("14:00", m.remindTime)
    }

    @Test fun M06_once只有日期时提醒为空() {
        val (m, _) = map(row(taskType = "once", dueDate = "2026-09-25"))
        assertEquals("2026-09-25", m.scheduledDate)
        assertEquals("", m.remindTime)
    }

    @Test fun M07_once无日期则不指定出现日() {
        val (m, _) = map(row(taskType = "once", dueDate = ""))
        assertEquals("", m.scheduledDate)
    }

    @Test fun M08_未知类型一律回退临时并记问题() {
        listOf("", "someday", "DAILY!", "weird type").forEach { bad ->
            val (m, issues) = map(row(taskType = bad))
            assertEquals(bad, "adhoc", m.kind)
            assertTrue(bad, issues.any { it.startsWith("UNKNOWN_TASKTYPE") })
        }
    }

    @Test fun M08b_大小写与空格变体按已知类型处理且不报问题() {
        val (g, gi) = map(row(taskType = "Goal"))
        assertEquals("adhoc", g.kind); assertTrue(gi.isEmpty())
        val (w, wi) = map(row(taskType = "weekly ", dueDate = "2026-09-23 08:00:00"))
        assertEquals("custom", w.repeatRule); assertTrue(wi.isEmpty())
        val (d, di) = map(row(taskType = " Daily "))
        assertEquals("daily", d.kind); assertTrue(di.isEmpty())
    }

    @Test fun M09_每日任务已打卡只产一条历史打卡() {
        val (m, issues) = map(row(taskType = "daily", isCompleted = true,
            dailyCompletionDate = "2026-09-19", completedAt = "2026-09-19"))
        assertEquals(1, m.occurrences.size)
        assertEquals("2026-09-19", m.occurrences.single().date)
        assertEquals("done", m.occurrences.single().status)
        assertTrue(issues.isEmpty())
    }

    @Test fun M10_无每日完成日期时用completedAt否则用今天() {
        assertEquals("2026-09-18", map(row(taskType = "weekly", isCompleted = true,
            completedAt = "2026-09-18")).first.occurrences.single().date)
        assertEquals(MIGRATION_DATE_COLUMN, map(row(taskType = "once", isCompleted = true,
            completedAt = null)).first.occurrences.single().date)
    }

    @Test fun M11_completedAt是垃圾串时回退迁移日不抛() {
        listOf("", "  ", "今天", "2026-13-45", "2026-09-20T10:00:00.000Z").forEach { dirty ->
            val m = map(row(taskType = "once", isCompleted = true, completedAt = dirty)).first
            assertEquals(dirty, 1, m.occurrences.size)
        }
    }

    @Test fun M12_未完成但有每日完成日期视为脏数据并记问题() {
        val (m, issues) = map(row(taskType = "daily", isCompleted = false, dailyCompletionDate = "2026-09-19"))
        assertTrue(m.occurrences.isEmpty())
        assertTrue(issues.any { it.startsWith("COMPLETION_DATE_WITHOUT_DONE") })
    }

    @Test fun M13_真正完成同时归档并保留打卡() {
        val (m, _) = map(row(taskType = "daily", isCompleted = true,
            dailyCompletionDate = "2026-09-20", isPermanentlyCompleted = true))
        assertEquals("archived", m.status)
        assertEquals(1, m.occurrences.size)
    }

    @Test fun M14_只勾了真正完成但未打卡也归档且无打卡() {
        val (m, _) = map(row(taskType = "daily", isCompleted = false, isPermanentlyCompleted = true))
        assertEquals("archived", m.status)
        assertTrue(m.occurrences.isEmpty())
    }

    @Test fun M15_未完成的活跃任务状态为active() {
        assertEquals("active", map(row(taskType = "daily")).first.status)
    }

    @Test fun M16_parentGoalId命中映射表才挂目标() {
        val (m, _) = map(row(taskType = "once", parentGoalId = 77), goalMap = mapOf(77L to 5L))
        assertEquals(5L, m.goalId)
    }

    @Test fun M17_孤儿parentGoalId不挂目标也不崩() {
        val (m, _) = map(row(taskType = "once", parentGoalId = 77), goalMap = emptyMap())
        assertNull(m.goalId)
    }

    @Test fun M18_目标型任务没有parentGoalId时用自身记录溯源() {
        val (m, _) = map(row(taskType = "goal", recordId = 88), goalMap = mapOf(88L to 9L))
        assertEquals(9L, m.goalId)
    }

    @Test fun M19_目标型任务两者皆无时目标为空() {
        assertNull(map(row(taskType = "goal")).first.goalId)
    }

    @Test fun M20_映射函数对同一输入两次结果一致() {
        val r = row(taskType = "weekly", dueDate = "2026-09-23 06:00:00", isCompleted = true, completedAt = "2026-09-16")
        assertEquals(map(r), map(r))
    }

    @Test fun M21_目标状态按记录类别还原() {
        assertEquals("active", mapLegacyGoalStatus("goal"))
        assertEquals("achieved", mapLegacyGoalStatus("completed"))
    }

    @Test fun M22_时间解析容忍缺分与非法值() {
        assertEquals("09:00", parseLooseTime("2026-09-22 09:00:00"))
        assertEquals("07:05", parseLooseTime("2026-09-22T07:05:00"))
        listOf("", "2026-09-22", "25:00", "9:60", "abc").forEach { assertEquals(it, "", parseLooseTime(it)) }
    }

    @Test fun M23_日期解析只认合法日期() {
        assertEquals(java.time.LocalDate.of(2026, 9, 22), parseLooseDate("2026-09-22 09:00:00"))
        assertEquals(java.time.LocalDate.of(2026, 9, 22), parseLooseDate("2026-09-22"))
        assertNull(parseLooseDate("2026/09/22"))
        assertNull(parseLooseDate(""))
        assertNull(parseLooseDate(null))
    }
}
