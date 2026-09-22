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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.tasks.ProgressSpan
import com.zhiwo.shiguangjian.ui.theme.Success
import com.zhiwo.shiguangjian.ui.theme.Warning
import com.zhiwo.shiguangjian.ui.viewmodel.GoalDetailViewModel
import com.zhiwo.shiguangjian.ui.viewmodel.PlanRow

/** 目标详情页：目标本身 + 其下计划 + 直属任务 + 达成/放弃两个终结动作 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalDetailScreen(
    goalId: Long,
    onBack: () -> Unit = {},
    onPlanClick: (Long) -> Unit = {},
    onEditGoal: (Long) -> Unit = {},
    onAddPlan: (Long) -> Unit = {},
    viewModel: GoalDetailViewModel = viewModel()
) {
    LaunchedEffect(goalId) { viewModel.open(goalId) }
    val state by viewModel.state.collectAsState()
    var confirmAchieve by remember { mutableStateOf(false) }
    var confirmAbandon by remember { mutableStateOf(false) }

    val goal = state.goal
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(goal?.title?.ifBlank { "未命名目标" } ?: "目标", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            if (goal != null && goal.status == "active") {
                ActionBar(
                    onAddPlan = { onAddPlan(goal.id) },
                    onEdit = { onEditGoal(goal.id) },
                    onAchieve = { confirmAchieve = true },
                    onAbandon = { confirmAbandon = true }
                )
            }
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
            if (goal == null) {
                Text(
                    "这个目标已经不在了",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
                return@Column
            }

            // ---------- 顶部概览 ----------
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HierarchyStatusBadge(goal.status)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        goal.targetDate.ifBlank { "长期目标" }.let {
                            if (goal.targetDate.isBlank()) it else "截止 $it"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    state.daysLeft?.let { left ->
                        Text(
                            when {
                                left > 0 -> "还剩 $left 天"
                                left == 0 -> "就是最后一天"
                                else -> "已过期 ${-left} 天"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (left < 0) MaterialTheme.colorScheme.error else Warning
                        )
                    }
                }
                if (goal.description.isNotBlank()) {
                    Text(
                        goal.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
                ProgressBar("总进度", state.overall)
                Spacer(Modifier.height(10.dp))
                Text(
                    "今日应做 ${state.todayDue} · 已完成 ${state.todayDone}",
                    style = MaterialTheme.typography.bodyMedium
                )
                state.holidayWarning?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Warning,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }

            // ---------- 其下计划 ----------
            DetailSectionTitle("计划（${state.plans.size}）", "＋ 新增计划") { onAddPlan(goal.id) }
            if (state.plans.isEmpty()) {
                Text(
                    "还没有计划。把目标拆成几段带日期的计划，进度才有落点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            state.plans.forEach { row -> PlanCard(row, onClick = { onPlanClick(row.plan.id) }) }

            // ---------- 直属任务 ----------
            if (state.directTasks.isNotEmpty()) {
                DetailSectionTitle("直属任务（${state.directTasks.size}）", null, {})
                state.directTasks.forEach { task ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(task.content, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                kindLabel(task.kind) + if (task.status != "active") "·${task.status}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    goal?.let {
        if (confirmAchieve) {
            ConfirmDialog(
                title = "标记为已达成？",
                body = "目标「${it.title}」达成后，其下 ${state.plans.size} 个计划、${state.taskCount} 个任务一并收尾：" +
                    "每日与留白任务归档（不再出现在安排页，也不再计入漏做），临时任务保持原样；" +
                    "没做完的会记一条「情况变了」，不会凭空变成漏做。这件事会同步给记忆。",
                confirmText = "确认达成",
                onConfirm = { confirmAchieve = false; viewModel.achieveGoal(it.id) },
                onDismiss = { confirmAchieve = false }
            )
        }
        if (confirmAbandon) {
            ConfirmDialog(
                title = "放弃这个目标？",
                body = "目标「${it.title}」放弃后，其下 ${state.plans.size} 个计划、${state.taskCount} 个任务一并收尾：" +
                    "每日与留白任务归档，临时任务保持原样，打卡记录全部保留。" +
                    "已做过的不会抹掉，但这条线到此为止；这件事会同步给记忆。",
                confirmText = "确认放弃",
                onConfirm = { confirmAbandon = false; viewModel.abandonGoal(it.id) },
                onDismiss = { confirmAbandon = false }
            )
        }
    }
}

@Composable
private fun ActionBar(onAddPlan: () -> Unit, onEdit: () -> Unit, onAchieve: () -> Unit, onAbandon: () -> Unit) {
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onAddPlan, modifier = Modifier.weight(1.2f)) { Text("+ 新增计划") }
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(0.8f)) { Text("编辑") }
            FilledTonalButton(onClick = onAchieve, modifier = Modifier.weight(1f)) { Text("标记达成") }
            TextButton(onClick = onAbandon) { Text("放弃", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun DetailSectionTitle(title: String, action: String?, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun HierarchyStatusBadge(status: String) {
    val (label, color) = when (status) {
        "achieved" -> "已达成" to Success
        "done" -> "已完成" to Success
        "abandoned", "dropped" -> "已放弃" to MaterialTheme.colorScheme.error
        "paused" -> "已暂停" to Warning
        else -> "进行中" to MaterialTheme.colorScheme.primary
    }
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** percent = -1 是"这段时间一件都没排"，绝不能画成 0% */
@Composable
internal fun ProgressBar(label: String, span: ProgressSpan) {
    val none = span.percent < 0
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                if (none) "暂无排期" else "${span.done} / ${span.due}　${span.percent}%",
                style = MaterialTheme.typography.labelLarge,
                color = if (none) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (none) 0f else span.percent / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                if (!none) {
                    append("漏做 ${span.missed} · 连续 ${span.streak} 天")
                    append("　统计 ${span.rangeStart} ~ ${span.rangeEnd}")
                    if (span.truncated) append("（更早的不在此统计）")
                }
            }.ifBlank { "还没有排期，先去建一个计划或任务" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanCard(row: PlanRow, onClick: () -> Unit) {
    val plan = row.plan
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plan.title.ifBlank { "未命名计划" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (plan.status != "active") {
                    Text(
                        planStatusLabel(plan.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${plan.startDate.ifBlank { "未设" }} ~ ${plan.endDate.ifBlank { "未设" }}" +
                    "　${row.taskCount} 个任务" +
                    if (row.overdueCount > 0) "　逾期 ${row.overdueCount}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (row.overdueCount > 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            val none = row.span.percent < 0
            LinearProgressIndicator(
                progress = { if (none) 0f else row.span.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (none) Color.Transparent else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                if (none) "暂无排期" else "已完成 ${row.span.done} / 应做 ${row.span.due}（${row.span.percent}%）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { Text(body) }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("再想想") } }
    )
}

private fun kindLabel(kind: String) = when (kind.trim().lowercase()) {
    "daily" -> "每日"
    "blank" -> "留白"
    else -> "临时"
}

private fun planStatusLabel(status: String) = when (status) {
    "paused" -> "已暂停"
    "done" -> "已完成"
    "dropped" -> "已放弃"
    else -> status
}

/** 供计划详情页复用：目标标题徽标同款 */
@Composable
internal fun GoalTitleLine(goal: GoalEntity?) {
    if (goal == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("所属目标：", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(goal.title.ifBlank { "未命名目标" }, style = MaterialTheme.typography.bodySmall)
    }
}
