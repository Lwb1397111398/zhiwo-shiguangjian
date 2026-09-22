package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import org.junit.Assert.*
import org.junit.Test

class TaskScheduleEngineTest {

    private fun task(
        id: Long = 1,
        kind: String = "daily",
        repeatRule: String = "everyday",
        weekdaysCsv: String = "",
        intervalDays: Int = 1,
        dayPolicy: String = "all",
        startDate: String = "",
        endDate: String = "",
        scheduledDate: String = "",
        dueDate: String = "",
        status: String = "active",
        planId: Long? = null,
        goalId: Long? = null,
        createdAt: String = "2026-09-21T08:00:00.000Z",
        content: String = "t"
    ) = TaskEntity(
        id = id, content = content, createdAt = createdAt, kind = kind, repeatRule = repeatRule,
        weekdaysCsv = weekdaysCsv, intervalDays = intervalDays, dayPolicy = dayPolicy,
        startDate = startDate, endDate = endDate, scheduledDate = scheduledDate, dueDate = dueDate,
        status = status, planId = planId, goalId = goalId
    )

    private fun occ(taskId: Long, date: String, status: String = "done") =
        TaskOccurrenceEntity(id = taskId, taskId = taskId, date = date, status = status, createdAt = date)

    private val alwaysWorkday: (String) -> DayType? = { DayType.WORKDAY }

    // ---------- 重复规则 ----------

    @Test fun MFB01_everyday_周一到周日都命中() {
        listOf("2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25", "2026-09-26", "2026-09-27").forEach { day ->
            assertTrue("$day 应出现", isScheduledOn(task(), day, DayType.WORKDAY))
        }
    }

    @Test fun MFB05_weekdays_周六不命中() {
        assertFalse(isScheduledOn(task(repeatRule = "weekdays"), "2026-09-26", DayType.WORKDAY))  // 周六
        assertTrue(isScheduledOn(task(repeatRule = "weekdays"), "2026-09-21", DayType.WORKDAY))   // 周一
        assertTrue(isScheduledOn(task(repeatRule = "weekdays"), "2026-09-25", DayType.WORKDAY))   // 周五
    }

    @Test fun MFB07_custom_按csv命中() {
        val t = task(repeatRule = "custom", weekdaysCsv = "1,3,5")
        assertTrue(isScheduledOn(t, "2026-09-21", null))   // 周一
        assertFalse(isScheduledOn(t, "2026-09-22", null))  // 周二
        assertFalse(isScheduledOn(t, "2026-09-27", null))   // 周日=7，不在 csv
    }

    @Test fun MFB08_custom_周日ISO为7() {
        val t = task(repeatRule = "custom", weekdaysCsv = "7")
        assertTrue(isScheduledOn(t, "2026-09-27", null))
        assertFalse(isScheduledOn(t, "2026-09-26", null))
    }

    @Test fun MFB13_custom空csv退化为每天() {
        val t = task(repeatRule = "custom", weekdaysCsv = "")
        assertTrue(isScheduledOn(t, "2026-09-22", null))
        assertTrue(isScheduledOn(t, "2026-09-26", null))
    }

    @Test fun MFB09_csv脏值忽略非法项() {
        val t = task(repeatRule = "custom", weekdaysCsv = "0,8,x,3")
        assertTrue(isScheduledOn(t, "2026-09-23", null))   // 周三合法
        assertFalse(isScheduledOn(t, "2026-09-22", null))
    }

    @Test fun MFI01_interval距锚点非整数倍不命中() {
        val t = task(repeatRule = "interval", intervalDays = 3, startDate = "2026-09-01")
        assertFalse(isScheduledOn(t, "2026-09-21", null))   // 距锚点 20 天，20%3!=0
        assertTrue(isScheduledOn(t, "2026-09-22", null))   // 21 天，命中
    }

    @Test fun MFI02_interval按锚点取模() {
        val t = task(repeatRule = "interval", intervalDays = 3, startDate = "2026-09-21")
        assertTrue(isScheduledOn(t, "2026-09-21", null))    // 第 0 天算一次
        assertFalse(isScheduledOn(t, "2026-09-22", null))
        assertTrue(isScheduledOn(t, "2026-09-24", null))
    }

    @Test fun MFI03_intervalDays小于1按1处理() {
        val t = task(repeatRule = "interval", intervalDays = 0, startDate = "2026-09-21")
        assertTrue(isScheduledOn(t, "2026-09-22", null))
        assertTrue(isScheduledOn(t, "2026-09-23", null))
    }

    @Test fun MFB14_interval锚点回退到创建日_迁移日之前一律不排() {
        val t = task(repeatRule = "interval", intervalDays = 2,
            startDate = "", createdAt = "2026-09-01T00:00:00.000Z")
        assertFalse(isScheduledOn(t, "2026-09-20", null))   // 早于迁移生效日
        assertTrue(isScheduledOn(t, "2026-09-21", null))     // 距锚点 20 天
        assertFalse(isScheduledOn(t, "2026-09-22", null))
    }

    // ---------- 区间与状态 ----------

    @Test fun MFB01_区间端点包含而越界排除() {
        val t = task(startDate = "2026-09-21", endDate = "2026-09-23")
        assertTrue(isScheduledOn(t, "2026-09-21", null))
        assertTrue(isScheduledOn(t, "2026-09-23", null))
        assertFalse(isScheduledOn(t, "2026-09-24", null))
    }

    @Test fun MFB04_起止倒挂永不出现且不崩() {
        val t = task(startDate = "2026-09-25", endDate = "2026-09-21")
        assertFalse(isScheduledOn(t, "2026-09-23", null))
    }

    @Test fun MFB11_paused与archived不出现() {
        assertFalse(isScheduledOn(task(status = "paused"), "2026-09-22", null))
        assertFalse(isScheduledOn(task(status = "archived"), "2026-09-22", null))
    }

    @Test fun MFB12_日期串非法时不出现不崩() {
        listOf("", "2026/09/22", "2026-02-30", "今天", "2026-9-2", "2026-13-01", "20260922").forEach { bad ->
            assertFalse(bad, isScheduledOn(task(), bad, null))
        }
        assertTrue(isScheduledOn(task(startDate = "2026-09-01"), "2026-09-22", null))
    }

    @Test fun MFB15_起始日按前10位解析() {
        val t = task(startDate = "2026-09-22")
        assertFalse(isScheduledOn(t, "2026-09-21", null))
        assertTrue(isScheduledOn(t, "2026-09-22", null))
    }

    // ---------- 节假日策略 ----------

    @Test fun MFC01_workday_only只在判定为工作日时出现() {
        val t = task(dayPolicy = "workday_only")
        assertTrue(isScheduledOn(t, "2026-09-22", DayType.WORKDAY))
        assertFalse(isScheduledOn(t, "2026-09-22", DayType.HOLIDAY))
    }

    @Test fun MFC02_该年无节假日数据时含策略的任务一律不排() {
        val t = task(dayPolicy = "workday_only")
        assertFalse("无数据年份不得按周末瞎猜", isScheduledOn(t, "2026-09-22", null))
    }

    @Test fun MFC03_调休补班日由dayTypeOf给出WORKDAY() {
        val holidayOnWeekend = dayTypeOf("2026-09-27", holidays = setOf(), makeupWorkdays = setOf("2026-09-27"), overrideType = null)
        assertEquals(DayType.WORKDAY, holidayOnWeekend)
        assertTrue(isScheduledOn(task(dayPolicy = "workday_only"), "2026-09-27", DayType.WORKDAY))
    }

    @Test fun MFC04_用户覆盖优先于内置表() {
        assertEquals(DayType.HOLIDAY, dayTypeOf("2026-09-22", overrideType = "holiday"))
        assertEquals(DayType.WORKDAY, dayTypeOf("2026-09-27", holidays = setOf("2026-09-27"), overrideType = "workday"))
        // 无覆盖、该年有内置数据时按表判定；表里没有该年则返回 null（见 MFC05）
        assertEquals(DayType.HOLIDAY, dayTypeOf("2026-10-01", holidays = setOf("2026-10-01")))
    }

    @Test fun MFC05_覆盖值脏了当作没填_该年无内置数据则返回null() {
        assertNull(dayTypeOf("2026-09-22", overrideType = "上班"))
        assertNull(dayTypeOf("2026-09-22", overrideType = null))
        assertEquals(DayType.HOLIDAY, dayTypeOf("2026-09-22", overrideType = "HOLIDAY "))
    }

    @Test fun MFC06_weekdays与workday_only取交集时周六补班仍不出现() {
        val t = task(repeatRule = "weekdays", dayPolicy = "workday_only")
        assertFalse(isScheduledOn(t, "2026-09-26", DayType.WORKDAY))
    }

    // ---------- 计划/目标暂停 ----------

    @Test fun MFD11_所属计划暂停时任务不出现() {
        val t = task(planId = 7)
        assertFalse(isScheduledOn(t, "2026-09-22", null, planStatus = "paused"))
        assertTrue(isScheduledOn(t, "2026-09-22", null, planStatus = "active"))
    }

    // ---------- 临时任务 ----------

    @Test fun MFD02_adhoc只在指定日出现_前一天不出现() {
        val t = task(kind = "adhoc", scheduledDate = "2026-09-22")
        val r1 = entriesFor("2026-09-21", listOf(t), emptyList(), null)
        assertTrue(r1.planned.isEmpty()); assertTrue(r1.overdue.isEmpty())
        val r2 = entriesFor("2026-09-22", listOf(t), emptyList(), null)
        assertEquals(1, r2.planned.size)
    }

    @Test fun MFD03_adhoc过了指定日未完成进过期() {
        val t = task(kind = "adhoc", scheduledDate = "2026-09-22")
        val r = entriesFor("2026-09-23", listOf(t), emptyList(), null)
        assertEquals(1, r.overdue.size)
        assertEquals(EntrySection.OVERDUE, r.overdue.first().section)
    }

    @Test fun MFD04_adhoc无指定日_超过期限才过期() {
        val t = task(kind = "adhoc", scheduledDate = "", dueDate = "2026-09-22")
        assertEquals(1, entriesFor("2026-09-21", listOf(t), emptyList(), null).planned.size)
        assertEquals(1, entriesFor("2026-09-23", listOf(t), emptyList(), null).overdue.size)
    }

    @Test fun MFD05_adhoc脏数据期限早于出现日_判过期不丢弃() {
        val t = task(kind = "adhoc", scheduledDate = "2026-09-25", dueDate = "2026-09-20")
        val r = entriesFor("2026-09-21", listOf(t), emptyList(), null)
        assertEquals(1, r.overdue.size)
    }

    @Test fun MFD06_adhoc今天完成则计入已完成且分母不变() {
        val t = task(id = 5, kind = "adhoc", scheduledDate = "2026-09-22")
        val r = entriesFor("2026-09-22", listOf(t), listOf(occ(5, "2026-09-22")), null)
        assertEquals(1, r.dueCount)
        assertEquals(1, r.doneCount)
    }

    @Test fun MFD07_adhoc昨天补的打卡不抬高今天的分母() {
        val t = task(id = 5, kind = "adhoc", scheduledDate = "2026-09-21")
        val r = entriesFor("2026-09-22", listOf(t), listOf(occ(5, "2026-09-21")), null)
        assertEquals(0, r.dueCount)
        assertEquals(1, r.overdue.size + r.planned.size)
    }

    // ---------- 留白 ----------

    @Test fun MFD08_blank今天出现() {
        val t = task(kind = "blank")
        assertEquals(1, entriesFor("2026-09-22", listOf(t), emptyList(), null).planned.size)
    }

    @Test fun MFD09_blank回填后计入已完成_未回填不改变分母() {
        val t = task(id = 9, kind = "blank")
        val empty = entriesFor("2026-09-22", listOf(t), emptyList(), null)
        val filled = entriesFor("2026-09-22", listOf(t), listOf(occ(9, "2026-09-22")), null)
        assertEquals(empty.dueCount, filled.dueCount)
        assertEquals(0, empty.doneCount)
        assertEquals(1, filled.doneCount)
    }

    // ---------- 打卡与状态 ----------

    @Test fun MFD10_done与not_done在同一任务上互斥显示() {
        val t = task(id = 3)
        val notDone = listOf(occ(3, "2026-09-22", "not_done").copy(reasonCode = "no_time"))
        val r = entriesFor("2026-09-22", listOf(t), notDone, null)
        assertEquals(1, r.planned.size)
        assertEquals(EntryState.NOT_DONE, r.planned.first().state)
        assertEquals(1, r.dueCount)
        assertEquals(0, r.doneCount)
    }

    // ---------- 漏做与连续 ----------

    @Test fun MFD13_missedOn不回溯到迁移日之前() {
        val t = task(id = 11, createdAt = "2020-01-01T00:00:00.000Z")
        val missed = missedOn(listOf(t), emptyList(), "2026-09-22", lookbackDays = 30, dayTypeOfDate = alwaysWorkday)
        assertEquals("只允许迁移日之后的真实缺口，实际 ${missed.map { it.date }}", listOf("2026-09-21"), missed.map { it.date })
    }

    @Test fun MFD14_昨天该做没做算一条漏做() {
        val t = task(id = 12, createdAt = "2026-09-01T00:00:00.000Z")
        val missed = missedOn(listOf(t), emptyList(), "2026-09-22", lookbackDays = 30, dayTypeOfDate = alwaysWorkday)
        assertEquals(listOf("2026-09-21"), missed.map { it.date })
        assertEquals(EntrySection.MISSED, missed.first().section)
    }

    @Test fun MFD15_不被重复规则跳过的日子打断连续() {
        val occs = listOf(occ(13, "2026-09-23"), occ(13, "2026-09-21"))
        val d = task(id = 13, repeatRule = "custom", weekdaysCsv = "1,3,5",
            createdAt = "2026-09-21T00:00:00.000Z")
        // 9-23 周三、9-21 周一已打卡；9-22 周二本就不该做 → 不打断
        assertEquals(2, streakOf(d, occs, "2026-09-23") { DayType.WORKDAY })
    }

    @Test fun MFD16_漏做打断连续() {
        val t = task(id = 14, createdAt = "2026-09-01T00:00:00.000Z")
        val streak = streakOf(t, listOf(occ(14, "2026-09-19")), "2026-09-21", alwaysWorkday)
        assertEquals(0, streak)
    }

    @Test fun MFD17_同一天重复调用结果一致且不改入参() {
        val tasks = listOf(task(id = 21), task(id = 22, kind = "adhoc", scheduledDate = "2026-09-22"))
        val occs = listOf(occ(21, "2026-09-22"))
        val a = entriesFor("2026-09-22", tasks, occs, null)
        val b = entriesFor("2026-09-22", tasks, occs, null)
        assertEquals(a, b)
        assertEquals(2, tasks.size)
    }

    @Test fun MFD18_每个任务当天至多一条() {
        val tasks = (1L..30L).map { task(id = it, content = "t$it") } + task(id = 31, kind = "adhoc", scheduledDate = "2026-09-22")
        val r = entriesFor("2026-09-22", tasks, emptyList(), null)
        assertEquals(tasks.map { it.id }.distinct().size, r.planned.size)
    }
}
