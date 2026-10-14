package com.zhiwo.shiguangjian.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.ui.theme.Success
import com.zhiwo.shiguangjian.ui.theme.Warning
import com.zhiwo.shiguangjian.ui.viewmodel.PlanDetailViewModel
import com.zhiwo.shiguangjian.ui.viewmodel.PlanTaskRow
import com.zhiwo.shiguangjian.ui.viewmodel.WeekCellKind
import kotlinx.coroutines.launch

/** 计划详情页：区间、进度、其下任务的今天状态与近 7 天格子，以及暂停/删除。任务点击可直接编辑 */
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
    /** 点任务卡/编辑按钮唤出的编辑器目标；null=编辑器关着 */
    var editingTask by remember { mutableStateOf<TaskEntity?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    /** 真删除确认前要如实报数：打卡条数与来源记录去向 */
    var deleteTarget by remember { mutableStateOf<TaskEntity?>(null) }
    var deleteOccCount by remember { mutableIntStateOf(-1) }
    var deleteSourceRecord by remember { mutableStateOf<RecordEntity?>(null) }
    val scope = rememberCoroutineScope()

    val plan = state.plan
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(plan?.title?.ifBlank { "未命名计划" } ?: "计划", maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
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
                        // 五个按钮挤一行会文字竖排——按语义拆两行（同目标详情页的教训）
                        Button(onClick = { addOpen = true }, modifier = Modifier.weight(1f)) {
                            Text("+ 新增任务", maxLines = 1)
                        }
                        OutlinedButton(onClick = { onEditPlan(plan.id, plan.goalId ?: 0L) },
                            modifier = Modifier.weight(1f)) {
                            Text("编辑计划", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.setPlanStatus(plan, if (plan.status == "active") "paused" else "active")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (plan.status == "paused") "恢复" else "暂停", maxLines = 1)
                        }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error, maxLines = 1)
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
            state.tasks.forEach { row -> TaskLine(row, onClick = { editingTask = row.task }) }
        }
    }

    if (addOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { addOpen = false }, sheetState = sheetState) {
            // 与安排页同一个编辑器：提醒/时长/重复细节全都能设，起止日期按计划区间预填，保存即挂到本计划下
            TaskEditorSheet(
                initial = null,
                defaultDate = viewModel.today,
                planPreset = plan,
                onSave = { task ->
                    viewModel.saveTask(task)
                    addOpen = false
                }
            )
        }
    }

    editingTask?.let { editing ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { editingTask = null }, sheetState = sheetState) {
            TaskEditorSheet(
                initial = editing,
                defaultDate = viewModel.today,
                onSave = { task -> viewModel.saveTask(task); editingTask = null },
                onDelete = { task -> viewModel.setStatus(task, "archived"); editingTask = null },
                onPause = { task -> viewModel.setStatus(task, "paused"); editingTask = null },
                onDeleteForever = { task ->
                    editingTask = null
                    deleteTarget = task
                    scope.launch {
                        deleteOccCount = viewModel.occurrenceCount(task.id)
                        deleteSourceRecord = viewModel.sourceRecordOf(task)
                    }
                }
            )
        }
    }

    deleteTarget?.let { task ->
        val sourceHint = deleteSourceRecord?.let { r ->
            "\n\n注意：这条任务来自记录《${r.title.ifBlank { r.content.take(20) }}》。删任务不会删记录——" +
                "它会回到「杂项待办」，在那里可以直接删记录本身。"
        } ?: ""
        ConfirmDialog(
            title = "彻底删除任务「${task.content}」？",
            body = if (deleteOccCount < 0) "正在数这条任务的打卡记录…"
            else "这会删掉任务本身、它的 $deleteOccCount 条打卡记录和提醒，删完找不回来。" +
                "只是这阵子不做了的话，关掉这里改选「结束任务」——记录留着，随时能从「我的计划」恢复。$sourceHint",
            confirmText = "确认删除",
            onConfirm = {
                if (deleteOccCount >= 0) {
                    viewModel.deleteTaskForever(task)
                    deleteTarget = null
                }
            },
            onDismiss = { deleteTarget = null }
        )
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
private fun TaskLine(row: PlanTaskRow, onClick: () -> Unit = {}) {
    val task = row.task
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    todayLabel(row) + " ›",
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
                    if (task.status == "paused") "已暂停：不参与排期与进度，点开可恢复"
                    else "已归档：不再出现在安排页，点开可恢复",
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
