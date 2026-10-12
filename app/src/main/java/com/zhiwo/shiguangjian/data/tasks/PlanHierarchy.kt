package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity

/**
 * 计划总览页的层级装配：把"目标 → 计划 → 任务"整棵树一次摊平，供核对用。
 * 归属口径与详情页一致（goalId/planId 新列为真值，见 GoalDetailViewModel.buildState）。
 * 两条铁律：
 * 1) 不过滤任何状态——总览的意义是"该写的都写上了没有"，已归档、已放弃的也要看得见；
 * 2) 一条计划/任务都不许凭空消失——指向已删除目标的，全部并进"自由计划与无主任务"块。
 */
data class OverviewPlan(
    val plan: PlanEntity,
    val tasks: List<TaskEntity>
)

/** goal = null 的那一块是"自由计划与无主任务"，永远排在最后 */
data class OverviewGoal(
    val goal: GoalEntity?,
    val plans: List<OverviewPlan>,
    /** 挂在这个目标下、但没进任何计划的任务；自由块里还兜住所有指向失效归属的数据 */
    val directTasks: List<TaskEntity>
)

private val GOAL_STATUS_RANK = mapOf("active" to 0, "achieved" to 1, "abandoned" to 2)

private fun planRank(status: String) = when (status) {
    "active" -> 0
    "paused" -> 1
    "done" -> 2
    "dropped" -> 3
    else -> 4
}

private fun goalRank(status: String) = when (status) {
    "active" -> 0
    "achieved" -> 1
    "abandoned" -> 2
    else -> 3
}

fun buildOverview(
    tasks: List<TaskEntity>,
    plans: List<PlanEntity>,
    goals: List<GoalEntity>
): List<OverviewGoal> {
    val knownPlans = plans.map { it.id }.toSet()
    val knownGoals = goals.map { it.id }.toSet()
    val byPlan = tasks.groupBy { it.planId }

    fun plansFor(goalId: Long?, dangling: Boolean = false): List<OverviewPlan> =
        plans.filter { p -> if (dangling) p.goalId != null && p.goalId !in knownGoals else p.goalId == goalId }
            .sortedWith(
                compareBy<PlanEntity> { planRank(it.status) }
                    .thenByDescending { it.startDate }
                    .thenBy { it.id }
            )
            .map { OverviewPlan(it, tasksOf(byPlan[it.id] ?: emptyList())) }

    val blocks = goals
        .sortedWith(
            compareBy<GoalEntity> { goalRank(it.status) }
                .thenBy { it.sortOrder }
                .thenBy { it.id }
        )
        .map { g ->
            OverviewGoal(
                goal = g,
                plans = plansFor(g.id),
                directTasks = tasksOf(tasks.filter { it.planId == null && it.goalId == g.id })
            )
        }

    // 兜底：凡没被上面任何一块认领的（无主、或指向已删除的归属），全进自由块
    val claimed = { t: TaskEntity ->
        (t.planId != null && t.planId in knownPlans) ||
            (t.planId == null && t.goalId != null && t.goalId in knownGoals)
    }
    val free = OverviewGoal(
        goal = null,
        plans = plansFor(null) + plansFor(null, dangling = true),
        directTasks = tasksOf(tasks.filterNot(claimed))
    )
    return if (free.plans.isEmpty() && free.directTasks.isEmpty()) blocks else blocks + free
}

/** 块内排序：进行中的在前，其余按建单顺序 */
private fun tasksOf(list: List<TaskEntity>): List<TaskEntity> =
    list.sortedWith(
        compareBy<TaskEntity> { if (it.status == "active") 0 else 1 }
            .thenBy { it.sortOrder }
            .thenBy { it.id }
    )
