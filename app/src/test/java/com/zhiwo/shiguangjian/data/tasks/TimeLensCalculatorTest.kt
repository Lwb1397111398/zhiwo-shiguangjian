package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * TimeLensCalculator 口径单测。
 * 2026-09-21 是周一：本周取 09-21(一) ~ 09-27(日)。
 */
class TimeLensCalculatorTest {

    private var occId = 1L

    private fun goal(id: Long = 1, status: String = "active") = GoalEntity(
        id = id, title = "目标$id", startDate = "2026-09-01", status = status,
        createdAt = "2026-09-21T08:00:00.000Z"
    )

    private fun plan(id: Long, goalId: Long? = 1, status: String = "active") = PlanEntity(
        id = id, goalId = goalId, title = "计划$id", startDate = "2026-09-21", status = status,
        createdAt = "2026-09-21T08:00:00.000Z"
    )

    private fun task(
        id: Long = 1,
        kind: String = "daily",
        planId: Long? = null,
        goalId: Long? = null,
        repeatRule: String = "everyday",
        durationMinutes: Int = 0,
        scheduledDate: String = "",
        status: String = "active"
    ) = TaskEntity(
        id = id, content = "任务$id", createdAt = "2026-09-21T08:00:00.000Z",
        kind = kind, repeatRule = repeatRule, startDate = "2026-09-21",
        scheduledDate = scheduledDate, planId = planId, goalId = goalId,
        status = status, durationMinutes = durationMinutes
    )

    private fun occ(taskId: Long, date: String, status: String = "done", minutes: Int = 0) =
        TaskOccurrenceEntity(
            id = occId++, taskId = taskId, date = date, status = status,
            actualMinutes = minutes, createdAt = date
        )

    private val alwaysWorkday: (String) -> DayType? = { DayType.WORKDAY }

    // ===== lensPlannedMinutes =====

    @Test
    fun `留白任务没填时长按60分钟计`() {
        assertEquals(60, lensPlannedMinutes(task(kind = "blank", durationMinutes = 0)))
        assertEquals(30, lensPlannedMinutes(task(kind = "blank", durationMinutes = 30)))
    }

    @Test
    fun `非留白任务时长0就是不限不进环`() {
        assertEquals(0, lensPlannedMinutes(task(kind = "daily", durationMinutes = 0)))
        assertEquals(45, lensPlannedMinutes(task(kind = "daily", durationMinutes = 45)))
    }

    // ===== todayBudget =====

    @Test
    fun `环按归属聚合并按时长降序 计划优先于目标`() {
        val tasks = listOf(
            task(id = 1, planId = 10, goalId = 1, durationMinutes = 45),
            task(id = 2, planId = 10, goalId = 1, durationMinutes = 30),
            task(id = 3, goalId = 1, durationMinutes = 90),
            task(id = 4, durationMinutes = 60)              // 无归属
        )
        val b = todayBudget("2026-09-23", tasks, emptyList(), DayType.WORKDAY,
            listOf(plan(10)), listOf(goal(1)))
        assertEquals(225, b.plannedMinutes)
        assertEquals(listOf("goal-1", "plan-10", "free"), b.slices.map { it.key })
        assertEquals(listOf(90, 75, 60), b.slices.map { it.minutes })
        assertEquals(2, b.slices[1].items.size)
    }

    @Test
    fun `未定时长不进环只计数`() {
        val tasks = listOf(
            task(id = 1, durationMinutes = 0),
            task(id = 2, durationMinutes = 30)
        )
        val b = todayBudget("2026-09-23", tasks, emptyList(), DayType.WORKDAY, emptyList(), emptyList())
        assertEquals(30, b.plannedMinutes)
        assertEquals(1, b.unboundedCount)
        assertEquals(1, b.slices.size)
        assertEquals("未归属", b.slices[0].title)
    }

    @Test
    fun `暂停计划的每日任务不进今天的环`() {
        val tasks = listOf(task(id = 1, planId = 10, durationMinutes = 60))
        val paused = todayBudget("2026-09-23", tasks, emptyList(), DayType.WORKDAY,
            listOf(plan(10, status = "paused")), listOf(goal(1)))
        assertEquals(0, paused.plannedMinutes)
        assertTrue(paused.slices.isEmpty())

        val active = todayBudget("2026-09-23", tasks, emptyList(), DayType.WORKDAY,
            listOf(plan(10)), listOf(goal(1)))
        assertEquals(60, active.plannedMinutes)
    }

    @Test
    fun `adhoc只在到期那天进环 之前不算`() {
        val tasks = listOf(task(id = 1, kind = "adhoc", scheduledDate = "2026-09-23", durationMinutes = 40))
        val before = todayBudget("2026-09-22", tasks, emptyList(), DayType.WORKDAY, emptyList(), emptyList())
        assertEquals(0, before.plannedMinutes)
        val onDay = todayBudget("2026-09-23", tasks, emptyList(), DayType.WORKDAY, emptyList(), emptyList())
        assertEquals(40, onDay.plannedMinutes)
    }

    @Test
    fun `已记录投入只算当天done打卡的实际分钟`() {
        val tasks = listOf(task(id = 1, durationMinutes = 60), task(id = 2, kind = "blank", durationMinutes = 0))
        val occurrences = listOf(
            occ(1, "2026-09-23", status = "done", minutes = 50),
            occ(2, "2026-09-23", status = "done", minutes = 15),
            occ(1, "2026-09-22", status = "done", minutes = 999),   // 昨天，不算今天
            occ(2, "2026-09-23", status = "not_done")                // 没做，不算
        )
        val b = todayBudget("2026-09-23", tasks, occurrences, DayType.WORKDAY, emptyList(), emptyList())
        assertEquals(65, b.loggedMinutes)
    }

    // ===== dailyInvestment =====

    @Test
    fun `工作日任务只在周一到周五有计划`() {
        val tasks = listOf(task(id = 1, repeatRule = "weekdays", durationMinutes = 30))
        val series = dailyInvestment("2026-09-21", "2026-09-27", tasks, emptyList(),
            alwaysWorkday, emptyList(), emptyList())
        assertEquals(7, series.size)
        assertEquals(listOf(30, 30, 30, 30, 30, 0, 0), series.map { it.plannedMinutes })
        assertEquals(listOf(1, 1, 1, 1, 1, 0, 0), series.map { it.dueCount })
    }

    @Test
    fun `完成与未记录分别计入done和logged`() {
        val tasks = listOf(task(id = 1, durationMinutes = 40))
        val occurrences = listOf(
            occ(1, "2026-09-21", status = "done", minutes = 25),
            occ(1, "2026-09-22", status = "not_done")
        )
        val series = dailyInvestment("2026-09-21", "2026-09-23", tasks, occurrences,
            alwaysWorkday, emptyList(), emptyList())
        val mon = series[0]; val tue = series[1]; val wed = series[2]
        assertEquals(1, mon.doneCount)
        assertEquals(25, mon.loggedMinutes)
        // not_done 的那天：仍算"计划投入"（本来该做），但没完成、没记录时长
        assertEquals(40, tue.plannedMinutes)
        assertEquals(0, tue.doneCount)
        assertEquals(0, tue.loggedMinutes)
        // 没打卡的过去日：该做没做也算进计划
        assertEquals(40, wed.plannedMinutes)
        assertEquals(1, wed.dueCount)
    }

    @Test
    fun `留白回填的时长计入logged且与计划并存`() {
        val tasks = listOf(task(id = 1, kind = "blank", durationMinutes = 0))
        val occurrences = listOf(occ(1, "2026-09-21", status = "done", minutes = 75))
        val series = dailyInvestment("2026-09-21", "2026-09-21", tasks, occurrences,
            alwaysWorkday, emptyList(), emptyList())
        assertEquals(60, series[0].plannedMinutes)   // 留白没填 → 按 60 计
        assertEquals(75, series[0].loggedMinutes)    // 回填了 75
        assertEquals(1, series[0].doneCount)
    }
}
