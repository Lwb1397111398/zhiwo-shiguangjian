package com.zhiwo.shiguangjian.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.ui.theme.Success
import com.zhiwo.shiguangjian.ui.theme.Warning
import com.zhiwo.shiguangjian.ui.viewmodel.PlanDetailViewModel
import com.zhiwo.shiguangjian.ui.viewmodel.PlanTaskRow
import com.zhiwo.shiguangjian.ui.viewmodel.WeekCellKind

/** 计划详情页：区间、进度、其下任务的今天状态与近 7 天格子，以及暂停/删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDetailScreen(
    planId: Long,
    onBack: () -> Unit = {},
    onEditPlan: (Long, Long) -> Unit = { _, _ -> },
    viewModel: PlanDetailViewModel = viewModel()
) {
    LaunchedEffect(planId) { viewModel.open(planId) }
    val state by viewModel.state.collectAsState()
    var addOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val plan = state.plan
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(plan?.title?.ifBlank { "未命名计划" } ?: "计划", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            if (plan != null) {
                Surface(shadowElevation = 8.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { addOpen = true }, modifier = Modifier.weight(1f)) {
                            Text("+ 新增任务")
                        }
                        OutlinedButton(onClick = { onEditPlan(plan.id, plan.goalId ?: 0L) }) { Text("编辑") }
                        OutlinedButton(
                            onClick = {
                                viewModel.setPlanStatus(plan, if (plan.status == "active") "paused" else "active")
                            }
                        ) { Text(if (plan.status == "paused") "恢复" else "暂停") }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(bottom = 28.dp)
        ) {
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            if (plan == null) {
                Text(
                    "这个计划已经不在了",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
                return@Column
            }

            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HierarchyStatusBadge(plan.status)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${plan.startDate.ifBlank { "未设起" }} ~ ${plan.endDate.ifBlank { "未设止" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.daysLeft?.let {
                            when {
                                it > 0 -> "还剩 $it 天"
                                it == 0 -> "就是最后一天"
                                else -> "已过期 ${-it} 天"
                            }
                        } ?: "未设结束日期",
                        style = MaterialTheme.typography.bodySmall,
                        color = if ((state.daysLeft ?: 0) < 0) MaterialTheme.colorScheme.error else Warning
                    )
                }
                Spacer(Modifier.height(6.dp))
                GoalTitleLine(state.goal)
                if (plan.description.isNotBlank()) {
                    Text(
                        plan.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
                ProgressBar("计划进度", state.span)
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

            DetailSectionTitle("任务（${state.tasks.size}）", null, {})
            if (state.tasks.isEmpty()) {
                Text(
                    "这个计划还没有任务。点下面「+ 新增任务」放一件每天做的事进去。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            state.tasks.forEach { row -> TaskLine(row) }
        }
    }

    if (addOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { addOpen = false }, sheetState = sheetState) {
            QuickTaskSheet(
                defaultDate = viewModel.today,
                onCancel = { addOpen = false },
                onSave = { content, kind, repeatRule, scheduledDate ->
                    viewModel.addTask(planId, content, kind, repeatRule, scheduledDate)
                    addOpen = false
                }
            )
        }
    }

    plan?.let {
        if (confirmDelete) {
            ConfirmDialog(
                title = "删除计划「${it.title}」？",
                body = "删除后，其下 ${state.tasks.size} 个任务会变成自由任务：不再计入这个计划，也不再算进" +
                    "${if (it.goalId == null) "任何目标" else "所属目标"}，仍会照常出现在安排页。" +
                    "${state.occurrenceCount} 条打卡记录全部保留，不会跟着删掉。计划本身无法恢复。",
                confirmText = "确认删除",
                onConfirm = {
                    confirmDelete = false
                    viewModel.deletePlan(it)
                    onBack()
                },
                onDismiss = { confirmDelete = false }
            )
        }
    }
}

@Composable
private fun TaskLine(row: PlanTaskRow) {
    val task = row.task
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    todayLabel(row),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        row.doneToday -> Success
                        row.scheduledToday -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                row.cells.forEach { cell -> WeekDot(cell.kind) }
                Text(
                    "近 7 天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            if (task.status != "active") {
                Text(
                    if (task.status == "paused") "已暂停：不参与排期与进度" else "已归档：不再出现在安排页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

private fun todayLabel(row: PlanTaskRow): String = when {
    row.doneToday -> "今日已完成"
    !row.scheduledToday -> "今天没排"
    row.task.status != "active" -> "未参与排期"
    else -> "今日待做"
}

/** 实心=做完、空心圈=该做没做、浅点=记了原因、空白=那天没排 */
@Composable
private fun WeekDot(kind: WeekCellKind) {
    val tint = MaterialTheme.colorScheme.primary
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        when (kind) {
            WeekCellKind.DONE -> Box(Modifier.size(12.dp).background(tint, CircleShape))
            WeekCellKind.MISSED -> Box(Modifier.size(11.dp).border(1.5.dp, tint, CircleShape))
            WeekCellKind.SKIPPED -> Box(
                Modifier.size(6.dp).background(tint.copy(alpha = 0.35f), CircleShape)
            )
            WeekCellKind.NONE -> Box(Modifier.size(4.dp).background(Color.Gray.copy(alpha = 0.25f), CircleShape))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickTaskSheet(
    defaultDate: String,
    onCancel: () -> Unit,
    onSave: (content: String, kind: String, repeatRule: String, scheduledDate: String) -> Unit
) {
    var content by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("daily") }
    var repeatRule by remember { mutableStateOf("everyday") }
    var scheduledDate by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("给这个计划加一件任务", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = content, onValueChange = { content = it },
            label = { Text("要做什么") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("daily" to "每日固定", "adhoc" to "临时一次", "blank" to "每日留白").forEach { (v, label) ->
                FilterChip(selected = kind == v, onClick = { kind = v }, label = { Text(label) })
            }
        }
        if (kind == "daily") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("everyday" to "每天", "weekdays" to "工作日").forEach { (v, label) ->
                    FilterChip(selected = repeatRule == v, onClick = { repeatRule = v }, label = { Text(label) })
                }
            }
        }
        if (kind == "adhoc") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
                    Text(if (scheduledDate.isBlank()) "哪一天出现：今天" else "哪一天出现：$scheduledDate")
                }
                if (scheduledDate.isNotBlank()) TextButton(onClick = { scheduledDate = "" }) { Text("清空") }
            }
            if (picking) {
                DatePickerDialogField(
                    initial = scheduledDate.ifBlank { defaultDate },
                    onConfirm = { scheduledDate = it; picking = false },
                    onDismiss = { picking = false }
                )
            }
        }
        Text(
            "任务的起止与所属目标跟着计划走；要改重复细节，请到安排页编辑这条任务。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(enabled = content.isNotBlank(), onClick = {
                onSave(content.trim(), kind, repeatRule, scheduledDate)
            }) { Text("保存") }
            OutlinedButton(onClick = onCancel) { Text("取消") }
        }
    }
}
