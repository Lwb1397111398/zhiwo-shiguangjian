package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import org.junit.Assert.*
import org.junit.Test

/** 计划总览的层级装配：分组、排序、不吞任务 */
class PlanHierarchyTest {

    private val iso = "2026-09-21T08:00:00.000Z"

    private fun goal(id: Long, status: String = "active", sortOrder: Int = 0) =
        GoalEntity(id = id, title = "目标$id", status = status, sortOrder = sortOrder, createdAt = iso)

    private fun plan(id: Long, goalId: Long? = 1, status: String = "active", startDate: String = "") =
        PlanEntity(id = id, goalId = goalId, title = "计划$id", status = status, startDate = startDate, createdAt = iso)

    private fun task(id: Long, planId: Long? = null, goalId: Long? = null, status: String = "active") =
        TaskEntity(id = id, content = "任务$id", createdAt = iso, planId = planId, goalId = goalId, status = status)

    @Test
    fun `每个目标一块 计划挂对归属`() {
        val blocks = buildOverview(
            tasks = listOf(task(11, planId = 1), task(12, planId = 2)),
            plans = listOf(plan(1), plan(2)),
            goals = listOf(goal(1))
        )
        assertEquals(1, blocks.size)
        assertEquals(listOf(1L, 2L), blocks[0].plans.map { it.plan.id })
        assertEquals(listOf(11L), blocks[0].plans[0].tasks.map { it.id })
        assertEquals(listOf(12L), blocks[0].plans[1].tasks.map { it.id })
    }

    @Test
    fun `进行中目标在前 达成其次 放弃垫底`() {
        val blocks = buildOverview(
            tasks = emptyList(),
            plans = emptyList(),
            goals = listOf(goal(3, "abandoned"), goal(2, "achieved"), goal(1, "active"))
        )
        assertEquals(listOf(1L, 2L, 3L), blocks.map { it.goal!!.id })
    }

    @Test
    fun `块内计划按状态再按起始日新到旧`() {
        val blocks = buildOverview(
            tasks = emptyList(),
            plans = listOf(
                plan(1, status = "done", startDate = "2026-09-01"),
                plan(2, status = "active", startDate = "2026-08-01"),
                plan(3, status = "active", startDate = "2026-09-01")
            ),
            goals = listOf(goal(1))
        )
        assertEquals(listOf(3L, 2L, 1L), blocks[0].plans.map { it.plan.id })
    }

    @Test
    fun `无主计划与无主任务进最后一块 归档的也在`() {
        val blocks = buildOverview(
            tasks = listOf(task(21, status = "archived")),
            plans = listOf(plan(9, goalId = null)),
            goals = listOf(goal(1))
        )
        assertEquals(2, blocks.size)
        assertNull(blocks[1].goal)
        assertEquals(listOf(9L), blocks[1].plans.map { it.plan.id })
        assertEquals(listOf(21L), blocks[1].directTasks.map { it.id })
    }

    @Test
    fun `没有无主内容时不出现自由块`() {
        val blocks = buildOverview(
            tasks = listOf(task(11, planId = 1, goalId = 1)),
            plans = listOf(plan(1)),
            goals = listOf(goal(1))
        )
        assertEquals(1, blocks.size)
    }

    @Test
    fun `目标下计划外直挂任务单列`() {
        val blocks = buildOverview(
            tasks = listOf(task(11, planId = 1, goalId = 1), task(12, goalId = 1), task(13, goalId = null)),
            plans = listOf(plan(1)),
            goals = listOf(goal(1))
        )
        assertEquals(listOf(12L), blocks.first { it.goal != null }.directTasks.map { it.id })
        assertEquals(listOf(13L), blocks.first { it.goal == null }.directTasks.map { it.id })
    }

    @Test
    fun `块内任务进行中的排前面`() {
        val blocks = buildOverview(
            tasks = listOf(task(1, goalId = 1, status = "archived"), task(2, goalId = 1, status = "active")),
            plans = emptyList(),
            goals = listOf(goal(1))
        )
        assertEquals(listOf(2L, 1L), blocks[0].directTasks.map { it.id })
    }

    @Test
    fun `引用已不存在目标的计划与任务不丢`() {
        // goalId 指向不存在的目标：任务并进自由块，计划按无主处理（防 SET_NULL 之前的半截数据消失）
        val blocks = buildOverview(
            tasks = listOf(task(31, goalId = 99)),
            plans = listOf(plan(7, goalId = 99)),
            goals = listOf(goal(1))
        )
        val allTaskIds = blocks.flatMap { b -> b.plans.flatMap { it.tasks } + b.directTasks }.map { it.id }
        assertTrue(allTaskIds.contains(31L))
        val allPlanIds = blocks.flatMap { it.plans }.map { it.plan.id }
        // 计划引用死目标：它不在任何已知 goalId 块里；必须仍然出现在总览（归进自由块或专项可见）
        val free = blocks.first { it.goal == null }
        assertTrue("引用死目标的计划必须可见，当前自由块计划：${free.plans.map { it.plan.id }}", allPlanIds.contains(7L))
    }
}
