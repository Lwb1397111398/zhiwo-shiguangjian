package com.zhiwo.shiguangjian.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.tasks.OverviewGoal
import com.zhiwo.shiguangjian.data.tasks.OverviewPlan
import com.zhiwo.shiguangjian.ui.theme.Warning
import com.zhiwo.shiguangjian.ui.viewmodel.PlansOverviewState
import com.zhiwo.shiguangjian.ui.viewmodel.PlansOverviewViewModel

/**
 * 计划总览页：把"目标 → 计划 → 任务"整棵树平铺在一页里，供老板核对
 * "该写的都写上去了没有"。所有状态都在（含已归档/已放弃），一条不藏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansOverviewScreen(
    onBack: () -> Unit = {},
    onPlanClick: (Long) -> Unit = {},
    onGoalClick: (Long) -> Unit = {},
    onAddGoal: () -> Unit = {},
    onAddPlan: () -> Unit = {},
    viewModel: PlansOverviewViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的计划") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
        ) {
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "共 ${state.goalCount} 个目标 · ${state.planCount} 个计划 · ${state.taskCount} 个任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onAddGoal) { Text("+ 新建目标") }
                    OutlinedButton(onClick = onAddPlan) { Text("+ 新建计划") }
                }
            }

            if (state.blocks.isEmpty()) {
                Text(
                    "这里会很空：还没有目标，也没有计划。\n" +
                        "先点「+ 新建目标」定个大方向，再在它下面拆计划。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 32.dp)
                )
                return@Column
            }

            state.blocks.forEach { block -> GoalBlockSection(block, onGoalClick, onPlanClick) }
        }
    }
}

@Composable
private fun GoalBlockSection(
    block: OverviewGoal,
    onGoalClick: (Long) -> Unit,
    onPlanClick: (Long) -> Unit
) {
    Column(Modifier.padding(top = 18.dp)) {
        if (block.goal != null) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onGoalClick(block.goal.id) }
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            block.goal.title.ifBlank { "未命名目标" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        HierarchyStatusBadge(block.goal.status)
                    }
                    Text(
                        (if (block.goal.targetDate.isBlank()) "长期目标" else "截止 ${block.goal.targetDate}") +
                            "　· ${block.plans.size} 个计划" +
                            (if (block.directTasks.isNotEmpty()) " · ${block.directTasks.size} 个计划外任务" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(
                "自由计划与无主任务",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            Text(
                "没有归到任何目标下的计划和任务。该归的目标归好，核对起来才顺。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        block.plans.forEach { PlanLine(it, onPlanClick) }
        if (block.directTasks.isNotEmpty()) {
            Text(
                "计划外的任务",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            block.directTasks.forEach { TaskBullet(it) }
        }
    }
}

@Composable
private fun PlanLine(row: OverviewPlan, onPlanClick: (Long) -> Unit) {
    val plan = row.plan
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onPlanClick(plan.id) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plan.title.ifBlank { "未命名计划" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (plan.status != "active") {
                    Text(
                        overviewPlanStatus(plan.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (plan.status == "paused") Warning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("　›", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "${plan.startDate.ifBlank { "未设起" }} ~ ${plan.endDate.ifBlank { "未设止" }}　${row.tasks.size} 个任务",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            row.tasks.forEach { TaskBullet(it) }
            if (row.tasks.isEmpty()) {
                Text(
                    "（还没往里放任务）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** 任务一行小字：名字 + 类型 + 状态注脚，核对用的最小信息量 */
@Composable
private fun TaskBullet(task: TaskEntity) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("·", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            task.content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            buildString {
                append(overviewKind(task.kind))
                if (task.status != "active") {
                    append(if (task.status == "paused") "·已暂停" else "·已结束")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun overviewKind(kind: String) = when (kind.trim().lowercase()) {
    "daily" -> "每日"
    "blank" -> "留白"
    else -> "临时"
}

private fun overviewPlanStatus(status: String) = when (status) {
    "paused" -> "已暂停"
    "done" -> "已完成"
    "dropped" -> "已放弃"
    else -> status
}
