package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import org.junit.Assert.*
import org.junit.Test

class ProgressCalculatorTest {

    private var occId = 1L

    private fun goal(
        id: Long = 1,
        status: String = "active",
        startDate: String = "2026-09-01"
    ) = GoalEntity(
        id = id, title = "目标$id", startDate = startDate, status = status,
        createdAt = "2026-09-21T08:00:00.000Z"
    )

    private fun plan(
        id: Long,
        goalId: Long? = 1,
        status: String = "active",
        startDate: String = "2026-09-21"
    ) = PlanEntity(
        id = id, goalId = goalId, title = "计划$id", startDate = startDate, status = status,
        createdAt = "2026-09-21T08:00:00.000Z"
    )

    private fun task(
        id: Long = 1,
        kind: String = "daily",
        planId: Long? = null,
        goalId: Long? = null,
        repeatRule: String = "everyday",
        weekdaysCsv: String = "",
        startDate: String = "2026-09-21",
        endDate: String = "",
        scheduledDate: String = "",
        status: String = "active",
        createdAt: String = "2026-09-21T08:00:00.000Z"
    ) = TaskEntity(
        id = id, content = "任务$id", createdAt = createdAt, kind = kind, repeatRule = repeatRule,
        weekdaysCsv = weekdaysCsv, startDate = startDate, endDate = endDate,
        scheduledDate = scheduledDate, planId = planId, goalId = goalId, status = status
    )

    private fun occ(taskId: Long, date: String, status: String = "done") = TaskOccurrenceEntity(
        id = occId++, taskId = taskId, date = date, status = status, createdAt = date
    )

    private val workday: (String) -> DayType? = { DayType.WORKDAY }

    /** 2026-09-21 周一 … 2026-09-27 周日 */
    private val weekFrom = "2026-09-21"
    private val weekTo = "2026-09-27"

    private fun spanOf(
        owner: ProgressOwner,
        tasks: List<TaskEntity>,
        plans: List<PlanEntity> = emptyList(),
        goals: List<GoalEntity> = emptyList(),
        occurrences: List<TaskOccurrenceEntity> = emptyList(),
        from: String = weekFrom,
        to: String = weekTo
    ): ProgressSpan {
        val map = progressOf(tasks, plans, goals, occurrences, from, to, workday)
        val span = map[owner]
        assertNotNull("缺少归属条目：$owner（实际 ${map.keys}）", span)
        return span!!
    }

    // ---------- 基本口径 ----------

    @Test fun MFP01_单计划三任务混合的应做已做漏做() {
        val plans = listOf(plan(11, goalId = 1))
        val goals = listOf(goal(1))
        val tasks = listOf(
            task(id = 21, planId = 11),                                    // 每日：7 天做 3 天
            task(id = 22, kind = "blank", planId = 11),                    // 留白：7 天全没回填
            task(id = 23, kind = "adhoc", planId = 11, scheduledDate = "2026-09-23")
        )
        val occs = listOf(
            occ(21, "2026-09-21"), occ(21, "2026-09-22"), occ(21, "2026-09-23"), occ(23, "2026-09-23")
        )
        val span = spanOf(ProgressOwner.OfPlan(11), tasks, plans, goals, occs)
        assertEquals(15, span.due)
        assertEquals(4, span.done)
        assertEquals(9, span.missed)   // 末端 09-27 只免漏做
        assertEquals(27, span.percent)          // 4/15 四舍五入
        assertEquals("2026-09-21", span.rangeStart)
        assertEquals("2026-09-27", span.rangeEnd)
        assertFalse(span.truncated)
    }

    @Test fun MFP02_目标含两个计划时不得双算() {
        val plans = listOf(plan(11, goalId = 1), plan(12, goalId = 1))
        val goals = listOf(goal(1))
        // 任务同时写 planId 与 goalId：只能算一次
        val tasks = listOf(
            task(id = 21, planId = 11, goalId = 1),
            task(id = 22, planId = 12, goalId = 1)
        )
        val map = progressOf(tasks, plans, goals, emptyList(), weekFrom, weekTo, workday)
        assertEquals(7, map.getValue(ProgressOwner.OfPlan(11)).due)
        assertEquals(7, map.getValue(ProgressOwner.OfPlan(12)).due)
        assertEquals("目标不得把计划的任务算两遍", 14, map.getValue(ProgressOwner.OfGoal(1)).due)
    }

    @Test fun MFP03_直挂目标的任务与其下计划一并计入目标() {
        val plans = listOf(plan(11, goalId = 1))
        val goals = listOf(goal(1))
        val tasks = listOf(
            task(id = 21, planId = 11),
            task(id = 30, goalId = 1)          // 直挂目标、无计划
        )
        val map = progressOf(tasks, plans, goals, emptyList(), weekFrom, weekTo, workday)
        assertEquals(7, map.getValue(ProgressOwner.OfPlan(11)).due)
        assertEquals(14, map.getValue(ProgressOwner.OfGoal(1)).due)
    }

    @Test fun MFP04_自由任务不进任何进度() {
        val plans = listOf(plan(11, goalId = 1))
        val goals = listOf(goal(1))
        val map = progressOf(listOf(task(id = 40)), plans, goals, emptyList(), weekFrom, weekTo, workday)
        assertEquals(0, map.getValue(ProgressOwner.OfPlan(11)).due)
        assertEquals(0, map.getValue(ProgressOwner.OfGoal(1)).due)
        assertEquals(-1, map.getValue(ProgressOwner.OfGoal(1)).percent)
    }

    @Test fun MFP05_应做为零时percent为负一而非零() {
        val span = spanOf(ProgressOwner.OfPlan(11), emptyList(), listOf(plan(11)), listOf(goal(1)))
        assertEquals(0, span.due)
        assertEquals(-1, span.percent)
        assertEquals(0, span.streak)
    }

    // ---------- 区间 ----------

    @Test fun MFP06_超过三百六十六天只算最近一年并标记截断() {
        val tasks = listOf(task(id = 21, planId = 11, startDate = "2026-09-21"))
        val span = spanOf(
            ProgressOwner.OfPlan(11), tasks, listOf(plan(11)), listOf(goal(1)),
            from = "2025-06-01", to = "2026-09-28"
        )
        assertTrue(span.truncated)
        assertEquals("2025-09-28", span.rangeStart)
        assertEquals("2026-09-28", span.rangeEnd)
        assertEquals("实际生效下界是迁移日 09-21，只应有 8 天", 8, span.due)
    }

    @Test fun MFP08_扫描下界不得早于任务生效日() {
        // createdAt 在 2020 年：引擎的 effectiveStart 会把它抬到迁移日 2026-09-21
        val tasks = listOf(task(id = 21, startDate = "", goalId = 1, createdAt = "2020-01-01T00:00:00.000Z"))
        val span = spanOf(ProgressOwner.OfGoal(1), tasks, emptyList(), listOf(goal(1)), to = "2026-09-27", from = "2026-09-15")
        assertEquals("09-15~09-20 不得凭空算漏做", 7, span.due)
        assertEquals(6, span.missed)   // 末端当天（09-27）还没过完，只免漏做
    }

    @Test fun MFP09_重复规则跳过的那天不算应做() {
        val tasks = listOf(task(id = 21, repeatRule = "custom", weekdaysCsv = "1,3,5", goalId = 1))
        val span = spanOf(ProgressOwner.OfGoal(1), tasks, emptyList(), listOf(goal(1)))
        assertEquals("周一三五", 3, span.due)
        assertEquals(3, span.missed)
    }

    // ---------- 打卡状态 ----------

    @Test fun MFP07_记了未完成原因的不算漏做() {
        val tasks = listOf(task(id = 21, planId = 11))
        val occs = listOf(occ(21, "2026-09-21", "not_done"), occ(21, "2026-09-22", "not_done"))
        val span = spanOf(ProgressOwner.OfPlan(11), tasks, listOf(plan(11)), listOf(goal(1)), occs)
        assertEquals(7, span.due)
        assertEquals(0, span.done)
        assertEquals(4, span.missed)
        assertEquals(0, span.percent)
    }

    @Test fun MFP10_撤销完成的软标记等同于没有记录() {
        val tasks = listOf(task(id = 21, goalId = 1))
        val span = spanOf(
            ProgressOwner.OfGoal(1), tasks, emptyList(), listOf(goal(1)),
            listOf(occ(21, "2026-09-21", "pending")), from = "2026-09-21", to = "2026-09-21"
        )
        assertEquals(1, span.due)
        assertEquals(0, span.done)
        assertEquals(0, span.missed)   // 单日窗口即今天：还没过完不算漏做
    }

    // ---------- 临时任务 ----------

    @Test fun MFP11_临时任务过了那天没做算漏做_当天未过不算() {
        val plans = listOf(plan(11))
        val tasks = listOf(
            task(id = 51, kind = "adhoc", planId = 11, scheduledDate = "2026-09-23"),
            task(id = 52, kind = "adhoc", planId = 11, scheduledDate = "2026-09-27")
        )
        val span = spanOf(ProgressOwner.OfPlan(11), tasks, plans, listOf(goal(1)))
        assertEquals(2, span.due)
        assertEquals(1, span.missed)     // 只有 09-23 那条过期了
        assertEquals(0, span.done)
    }

    @Test fun MFP12_临时任务任意日期的完成记录都算已做() {
        val tasks = listOf(task(id = 51, kind = "adhoc", planId = 11, scheduledDate = "2026-09-23"))
        val span = spanOf(
            ProgressOwner.OfPlan(11), tasks, listOf(plan(11)), listOf(goal(1)),
            listOf(occ(51, "2026-09-27")), from = "2026-09-21", to = "2026-09-23"
        )
        assertEquals(1, span.due)
        assertEquals(1, span.done)
        assertEquals(0, span.missed)
    }

    @Test fun MFP13_未填出现日期的临时任务不计入() {
        val tasks = listOf(task(id = 51, kind = "adhoc", planId = 11, scheduledDate = ""))
        val span = spanOf(ProgressOwner.OfPlan(11), tasks, listOf(plan(11)), listOf(goal(1)))
        assertEquals(0, span.due)
        assertEquals(-1, span.percent)
    }

    // ---------- 层级状态 ----------

    @Test fun MFP14_计划暂停时其任务既不计计划也不计目标() {
        val plans = listOf(plan(11, goalId = 1, status = "paused"))
        val tasks = listOf(task(id = 21, planId = 11), task(id = 51, kind = "adhoc", planId = 11, scheduledDate = "2026-09-23"))
        val map = progressOf(tasks, plans, listOf(goal(1)), emptyList(), weekFrom, weekTo, workday)
        assertEquals(0, map.getValue(ProgressOwner.OfPlan(11)).due)
        assertEquals(0, map.getValue(ProgressOwner.OfGoal(1)).due)
        assertEquals(-1, map.getValue(ProgressOwner.OfPlan(11)).percent)
    }

    @Test fun MFP15_目标已达成时其任务不再计入() {
        val tasks = listOf(task(id = 21, goalId = 1))
        val span = spanOf(ProgressOwner.OfGoal(1), tasks, emptyList(), listOf(goal(1, status = "achieved")))
        assertEquals(0, span.due)
    }

    @Test fun MFP16_计划脱离目标后其任务不再算进旧目标() {
        val plans = listOf(plan(11, goalId = null))
        val tasks = listOf(task(id = 21, planId = 11, goalId = 1))   // 脏数据：任务还记着旧 goalId
        val map = progressOf(tasks, plans, listOf(goal(1)), emptyList(), weekFrom, weekTo, workday)
        assertEquals(7, map.getValue(ProgressOwner.OfPlan(11)).due)
        assertEquals(0, map.getValue(ProgressOwner.OfGoal(1)).due)
    }

    // ---------- 连续天数 ----------

    @Test fun MFP17_连续天数与引擎streakOf一致() {
        val tasks = listOf(task(id = 21, planId = 11))
        val occs = (21L..25L).map { occ(21, "2026-09-$it") }
        val span = spanOf(ProgressOwner.OfPlan(11), tasks, listOf(plan(11)), listOf(goal(1)), occs, to = "2026-09-25")
        assertEquals(5, span.streak)
        assertEquals(
            "与引擎同一口径",
            streakOf(tasks.first(), occs, "2026-09-25", workday),
            span.streak
        )
    }

    @Test fun MFP18_中间漏做一天连续归零且与streakOf一致() {
        val tasks = listOf(task(id = 21, planId = 11))
        val occs = listOf(21L, 22L, 24L, 25L).map { occ(21, "2026-09-$it") }   // 缺 09-23
        val span = spanOf(ProgressOwner.OfPlan(11), tasks, listOf(plan(11)), listOf(goal(1)), occs, to = "2026-09-25")
        assertEquals(2, span.streak)
        assertEquals(streakOf(tasks.first(), occs, "2026-09-25", workday), span.streak)
    }

    @Test fun MFP19_被重复规则跳过的天不打断连续() {
        val tasks = listOf(task(id = 21, planId = 11, repeatRule = "custom", weekdaysCsv = "1,3"))
        val occs = listOf(occ(21, "2026-09-21"), occ(21, "2026-09-23"))   // 周一、周三
        val span = spanOf(ProgressOwner.OfPlan(11), tasks, listOf(plan(11)), listOf(goal(1)), occs, to = "2026-09-25")
        assertEquals(2, span.streak)
        assertEquals(streakOf(tasks.first(), occs, "2026-09-25", workday), span.streak)
    }

    // ---------- 健壮性 ----------

    @Test fun MFP20_空输入与脏日期都不崩() {
        assertTrue(progressOf(emptyList(), emptyList(), emptyList(), emptyList(), weekFrom, weekTo, workday).isEmpty())
        assertTrue(progressOf(emptyList(), emptyList(), emptyList(), emptyList(), "", weekTo, workday).isEmpty())
        assertTrue(progressOf(emptyList(), emptyList(), emptyList(), emptyList(), "今天", weekTo, workday).isEmpty())
        assertTrue(progressOf(emptyList(), emptyList(), emptyList(), emptyList(), weekFrom, "2026-02-30", workday).isEmpty())
        // 起晚于止：窗口不成立
        assertTrue(progressOf(emptyList(), listOf(plan(11)), listOf(goal(1)), emptyList(), "2026-09-28", weekFrom, workday).isEmpty())
        // 任务字段脏了也不崩
        val dirty = listOf(task(id = 61, kind = "daily", startDate = "2026-09-21", endDate = "2026-09-20"))
        assertEquals(0, spanOf(ProgressOwner.OfGoal(1), dirty, emptyList(), listOf(goal(1))).due)
    }

    @Test fun MFP21_每个计划与目标都有条目且可安全重复调用() {
        val plans = listOf(plan(11, goalId = 1), plan(12, goalId = null))
        val goals = listOf(goal(1), goal(2))
        val tasks = listOf(task(id = 21, planId = 11), task(id = 22, goalId = 2))
        val a = progressOf(tasks, plans, goals, emptyList(), weekFrom, weekTo, workday)
        val b = progressOf(tasks, plans, goals, emptyList(), weekFrom, weekTo, workday)
        assertEquals(a, b)
        assertEquals(4, a.size)     // 2 计划 + 2 目标，一个都不落
        assertEquals(7, a.getValue(ProgressOwner.OfGoal(2)).due)
    }
}
