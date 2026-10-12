package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.tasks.OverviewGoal
import com.zhiwo.shiguangjian.data.tasks.buildOverview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PlansOverviewState(
    val loading: Boolean = true,
    val blocks: List<OverviewGoal> = emptyList(),
    val goalCount: Int = 0,
    val planCount: Int = 0,
    val taskCount: Int = 0
)

/**
 * 计划总览页的数据装配：把整棵"目标 → 计划 → 任务"树摊平给核对场景。
 * 判断一律不在这里做——分组排序的唯一出处是 PlanHierarchy.buildOverview（JVM 有单测）。
 */
class PlansOverviewViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as ZhiwoApplication).database

    val state: StateFlow<PlansOverviewState> = combine(
        db.taskDao().getAllTasks(),
        db.planDao().getAllPlans(),
        db.goalDao().getAllGoals()
    ) { tasks: List<TaskEntity>, plans: List<PlanEntity>, goals: List<GoalEntity> ->
        PlansOverviewState(
            loading = false,
            blocks = buildOverview(tasks, plans, goals),
            goalCount = goals.size,
            planCount = plans.size,
            taskCount = tasks.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlansOverviewState())
}
