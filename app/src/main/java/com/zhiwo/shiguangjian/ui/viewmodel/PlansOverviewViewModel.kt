package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.repository.TaskLifecycle
import com.zhiwo.shiguangjian.data.repository.TaskOccurrenceRepository
import com.zhiwo.shiguangjian.data.tasks.OverviewGoal
import com.zhiwo.shiguangjian.data.tasks.buildOverview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlansOverviewState(
    val loading: Boolean = true,
    val blocks: List<OverviewGoal> = emptyList(),
    val goalCount: Int = 0,
    val planCount: Int = 0,
    val taskCount: Int = 0
)

/**
 * 计划总览页的数据装配 + 任务的管理动作：把整棵"目标 → 计划 → 任务"树摊平给核对场景，
 * 核对出问题就地处理——恢复（终态可逆，生命周期设计不变量 §三-2）与彻底删除（确认报数）。
 * 判断一律不在这里做——分组排序的唯一出处是 PlanHierarchy.buildOverview（JVM 有单测），
 * 状态迁移的编排与安排页共用 TaskLifecycle。
 */
class PlansOverviewViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val db = app.database
    private val occurrencesRepo = TaskOccurrenceRepository(db)

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

    /** 确认框先数后删（不变量 §三-7）：物理删任务会 CASCADE 掉这些打卡 */
    suspend fun occurrenceCount(taskId: Long): Int = occurrencesRepo.countByTask(taskId)

    suspend fun sourceRecordOf(task: TaskEntity): RecordEntity? =
        task.recordId?.let { db.recordDao().getRecordById(it) }

    /** 恢复 = 回 active（提醒按最新状态重新评估注册）；结束走安排页编辑器 */
    fun restoreTask(task: TaskEntity) = viewModelScope.launch {
        TaskLifecycle.changeStatus(db, app, task, "active")
    }

    fun deleteTask(task: TaskEntity) = viewModelScope.launch {
        TaskLifecycle.deleteForever(db, app, task)
    }
}
