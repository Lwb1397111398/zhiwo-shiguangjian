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
import kotlinx.coroutines.launch

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
    /** 总览页是归档/暂停任务唯一的可触及处：恢复与彻底删除都从这走（生命周期设计 B6） */
    var deleteTarget by remember { mutableStateOf<TaskEntity?>(null) }
    var deleteOccCount by remember { mutableStateOf(-1) }
    var deleteSourceRecord by remember { mutableStateOf<com.zhiwo.shiguangjian.data.db.entity.RecordEntity?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

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

            state.blocks.forEach { block ->
                GoalBlockSection(
                    block, onGoalClick, onPlanClick,
                    onRestore = { task -> viewModel.restoreTask(task) },
                    onDeleteForever = { task ->
                        deleteOccCount = -1; deleteTarget = task; deleteSourceRecord = null
                        scope.launch {
                            deleteOccCount = viewModel.occurrenceCount(task.id)
                            deleteSourceRecord = viewModel.sourceRecordOf(task)
                        }
                    }
                )
            }
        }
    }

    deleteTarget?.let { task ->
        val sourceHint = deleteSourceRecord?.let { r ->
            "\n\n注意：这条任务来自记录《${r.title.ifBlank { r.content.take(20) }}》。删任务不会删记录——" +
                "它会回到安排页的「杂项待办」，在那里可以直接删记录本身。"
        } ?: ""
        ConfirmDialog(
            title = "彻底删除任务「${task.content}」？",
            body = if (deleteOccCount < 0) "正在数这条任务的打卡记录…"
            else "这会删掉任务本身、它的 $deleteOccCount 条打卡记录和提醒，删完找不回来。" +
                "只是不想再排它的话，改选「结束任务」留在安排页——历史保得住，这里还能恢复。$sourceHint",
            confirmText = "确认删除",
            onConfirm = {
                // 还没数完不许删：用户必须看到真实条数才能确认（不变量 §三-7）
                if (deleteOccCount >= 0) { viewModel.deleteTask(task); deleteTarget = null }
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun GoalBlockSection(
    block: OverviewGoal,
    onGoalClick: (Long) -> Unit,
    onPlanClick: (Long) -> Unit,
    onRestore: (TaskEntity) -> Unit,
    onDeleteForever: (TaskEntity) -> Unit
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

        block.plans.forEach { PlanLine(it, onPlanClick, onRestore, onDeleteForever) }
        if (block.directTasks.isNotEmpty()) {
            Text(
                "计划外的任务",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            block.directTasks.forEach { TaskBullet(it, onRestore, onDeleteForever) }
        }
    }
}

@Composable
private fun PlanLine(
    row: OverviewPlan,
    onPlanClick: (Long) -> Unit,
    onRestore: (TaskEntity) -> Unit,
    onDeleteForever: (TaskEntity) -> Unit
) {
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
            row.tasks.forEach { TaskBullet(it, onRestore, onDeleteForever) }
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

/** 任务一行小字：名字 + 类型 + 状态注脚，核对用的最小信息量；
 *  恢复/彻底删除两个动作出口也挂在这里（生命周期设计不变量 §三-1，B6 的修复） */
@Composable
private fun TaskBullet(
    task: TaskEntity,
    onRestore: (TaskEntity) -> Unit,
    onDeleteForever: (TaskEntity) -> Unit
) {
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
        if (task.status != "active") {
            TextButton(onClick = { onRestore(task) }) {
                Text("恢复", style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton(onClick = { onDeleteForever(task) }) {
            Text("删除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
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
