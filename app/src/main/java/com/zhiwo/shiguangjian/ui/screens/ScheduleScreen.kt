package com.zhiwo.shiguangjian.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.tasks.DayEntry
import com.zhiwo.shiguangjian.data.tasks.deadlineDate
import com.zhiwo.shiguangjian.ui.components.DoneRing
import com.zhiwo.shiguangjian.ui.components.TimeLensCard
import com.zhiwo.shiguangjian.ui.theme.Success
import com.zhiwo.shiguangjian.ui.viewmodel.ScheduleSectionGroup
import com.zhiwo.shiguangjian.ui.viewmodel.ScheduleUiState
import com.zhiwo.shiguangjian.ui.viewmodel.ScheduleViewModel
import com.zhiwo.shiguangjian.ui.viewmodel.TimeLensViewModel
import kotlinx.coroutines.launch

private val SECTION_TITLES = mapOf(
    ScheduleSectionGroup.DAILY_FIXED to "每日 · 固定",
    ScheduleSectionGroup.DAILY_BLANK to "每日 · 留白",
    ScheduleSectionGroup.ADHOC_TODAY to "临时 · 今天",
    ScheduleSectionGroup.MISC_TODO to "杂项待办（来自记录）",
    ScheduleSectionGroup.OVERDUE to "已过期",
    ScheduleSectionGroup.MISSED to "待补录",
    ScheduleSectionGroup.DONE_TODAY to "今日已完成"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onRecordClick: (Long) -> Unit,
    onPlanClick: (Long) -> Unit = {},
    onGoalClick: (Long) -> Unit = {},
    onShowAllPlans: () -> Unit = {},
    onOpenTimeLens: () -> Unit = {},
    viewModel: ScheduleViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val lensVm: TimeLensViewModel = viewModel()
    val lensState by lensVm.state.collectAsState()
    val miscTodo by viewModel.miscTodo.collectAsState()
    val activePlans by viewModel.activePlans.collectAsState()
    val activeGoals by viewModel.activeGoals.collectAsState()
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var notDoneTarget by remember { mutableStateOf<DayEntry?>(null) }
    var fillTarget by remember { mutableStateOf<DayEntry?>(null) }
    var fillDate by remember { mutableStateOf(state.today) }
    var notDoneDate by remember { mutableStateOf(state.today) }
    /** 勾完成后顺手补"实际花了多久"的小面板 */
    var minutesTarget by remember { mutableStateOf<DayEntry?>(null) }
    var minutesDate by remember { mutableStateOf(state.today) }
    var showingPrefs by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TaskEntity?>(null) }
    var deleteOccCount by remember { mutableStateOf(-1) }
    /** 删除确认里如实提示：任务若由记录派生，删任务后记录会回杂项（生命周期设计 B1） */
    var deleteSourceRecord by remember { mutableStateOf<com.zhiwo.shiguangjian.data.db.entity.RecordEntity?>(null) }
    var miscDeleteTarget by remember { mutableStateOf<com.zhiwo.shiguangjian.data.db.entity.RecordEntity?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 数字滚动 + 完成小环：数据变化时平滑过渡，不常驻帧循环
    val doneAnim by animateIntAsState(state.doneCount, spring(), label = "doneAnim")
    val dueAnim by animateIntAsState(state.dueCount, spring(), label = "dueAnim")

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("今日安排", style = MaterialTheme.typography.headlineMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DoneRing(doneAnim, dueAnim, size = 18.dp, strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.loading) "载入中…" else "已完成 $doneAnim / 应做 $dueAnim",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FilledTonalIconButton(onClick = { showingPrefs = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "显示设置")
                }
                FilledTonalIconButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, contentDescription = "新建任务")
                }
            }
            state.holidayWarning?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }

            if (!state.loading && state.groups.isEmpty() && activePlans.isEmpty() && activeGoals.isEmpty()) {
                EmptySchedule(onCreate = { creating = true })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp, start = 4.dp, end = 4.dp)
                ) {
                    item(key = "lens-card") {
                        TimeLensCard(
                            budget = lensState.budget,
                            trend = lensState.trend,
                            loading = lensState.loading,
                            onOpen = onOpenTimeLens,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    orderedVisible(state).forEach { (group, rows) ->
                        if (rows.isEmpty()) return@forEach
                        item(key = "sec-" + group.name) {
                            SectionHeader(
                                SECTION_TITLES[group] ?: group.name,
                                if (group == ScheduleSectionGroup.MISC_TODO) miscTodo.size else rows.size,
                                done = group == ScheduleSectionGroup.DONE_TODAY
                            )
                        }
                        if (group == ScheduleSectionGroup.MISC_TODO) {
                            items(miscTodo, key = { "misc-" + it.id }) { record ->
                                MiscTodoRow(
                                    title = record.title.ifBlank { record.content.take(40) },
                                    onCheck = { viewModel.completeRecord(record) },
                                    onConvert = { viewModel.convertRecordToTask(record) },
                                    onDelete = { miscDeleteTarget = record },
                                    onOpen = { onRecordClick(record.id) }
                                )
                            }
                        } else {
                            items(rows, key = { group.name + "-" + it.task.id + "-" + it.date }) { entry ->
                                TaskRow(
                                    entry = entry,
                                    onToggle = {
                                        // 只看这一条自己的状态：否则"今天已完成"会让昨天的漏做点不动
                                        val done = entry.occurrence?.status == "done" ||
                                            entry.state == com.zhiwo.shiguangjian.data.tasks.EntryState.DONE
                                        val target = entry.date.ifBlank { state.today }
                                        if (done) {
                                            viewModel.uncheck(entry, target)
                                        } else {
                                            viewModel.check(entry, target)
                                            // 留白有自己的回填流程；其余勾完成顺手问一句实际花了多久（可跳过）
                                            if (entry.task.kind != "blank" &&
                                                (entry.occurrence?.actualMinutes ?: 0) == 0
                                            ) {
                                                minutesTarget = entry
                                                minutesDate = target
                                            }
                                        }
                                    },
                                    onLongPress = { if (entry.occurrence?.status != "done") { notDoneTarget = entry; notDoneDate = entry.date.ifBlank { state.today } } },
                                    onEdit = { editing = entry.task },
                                    onFill = { if (entry.task.kind == "blank") { fillTarget = entry; fillDate = entry.date.ifBlank { state.today } } else { notDoneTarget = entry; notDoneDate = entry.date.ifBlank { state.today } } }
                                )
                            }
                        }
                    }

                    // ---- 下面两段是层级入口（计划 / 目标），不参与今日完成率 ----
                    item(key = "sec-plans-all") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("想核对全部的目标、计划和任务？",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = onShowAllPlans) { Text("我的计划 ›") }
                        }
                    }
                    if (activePlans.isNotEmpty()) {
                        item(key = "sec-plans") {
                            SectionHeader("进行中的计划", activePlans.size, done = false)
                        }
                        items(activePlans, key = { "plan-${it.id}" }) { plan ->
                            HierarchyCard(
                                title = plan.title,
                                subtitle = "${plan.startDate.ifBlank { "未设起" }} ~ ${plan.endDate.ifBlank { "未设止" }}",
                                onClick = { onPlanClick(plan.id) }
                            )
                        }
                    }
                    if (activeGoals.isNotEmpty()) {
                        item(key = "sec-goals") {
                            SectionHeader("目标", activeGoals.size, done = false)
                        }
                        items(activeGoals, key = { "goal-${it.id}" }) { goal ->
                            HierarchyCard(
                                title = goal.title,
                                subtitle = if (goal.targetDate.isBlank()) "长期目标" else "截止 ${goal.targetDate}",
                                onClick = { onGoalClick(goal.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showingPrefs) {
        ModalBottomSheet(onDismissRequest = { showingPrefs = false }, sheetState = sheetState) {
            DisplaySettingsSheet(
                groups = ScheduleSectionGroup.values().toList(),
                isHidden = { group -> state.hidden.contains(group.name) },
                onToggle = viewModel::setSectionVisible,
                onMove = viewModel::moveSection
            )
        }
    }

    if (creating || editing != null) {
        ModalBottomSheet(onDismissRequest = { creating = false; editing = null }, sheetState = sheetState) {
            TaskEditorSheet(
                initial = editing,
                defaultDate = viewModel.todayNow(),
                onDelete = { task -> viewModel.setStatus(task, "archived"); creating = false; editing = null },
                onPause = { task -> viewModel.setStatus(task, "paused"); creating = false; editing = null },
                onDeleteForever = { task ->
                    creating = false; editing = null
                    deleteOccCount = -1; deleteTarget = task; deleteSourceRecord = null
                    scope.launch {
                        deleteOccCount = viewModel.occurrenceCount(task.id)
                        deleteSourceRecord = viewModel.sourceRecordOf(task)
                    }
                },
                onSave = { task -> viewModel.saveTask(task); creating = false; editing = null }
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
                "只是这阵子不做了的话，关掉这里改选「结束任务」——记录留着，随时能恢复。$sourceHint",
            confirmText = "确认删除",
            onConfirm = {
                // 还没数完不许删：用户必须看到真实条数才能确认
                if (deleteOccCount >= 0) { viewModel.deleteTask(task); deleteTarget = null }
            },
            onDismiss = { deleteTarget = null }
        )
    }

    miscDeleteTarget?.let { record ->
        ConfirmDialog(
            title = "删除记录《${record.title.ifBlank { record.content.take(20) }}》？",
            body = "这条待办只存在于记录里（没有派生任务）。删除会把记录本体从记录页一起去掉，找不回来。" +
                "只是不想在安排页看到它的话，用勾选完成——那会把它归入「已完成」类别，记录页还留着原文。",
            confirmText = "确认删除",
            onConfirm = { viewModel.deleteRecord(record); miscDeleteTarget = null },
            onDismiss = { miscDeleteTarget = null }
        )
    }

    fillTarget?.let { entry ->
        var note by remember(entry) { mutableStateOf(entry.occurrence?.note ?: "") }
        var minutes by remember(entry) { mutableStateOf((entry.task.durationMinutes).toString()) }
        AlertDialog(
            onDismissRequest = { fillTarget = null },
            title = { Text("这一小时做了什么") },
            text = {
                Column {
                    OutlinedTextField(
                        value = note, onValueChange = { note = it },
                        label = { Text("写下这段时间做的事（会进当天回顾与 AI 评价）") },
                        minLines = 3, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = minutes, onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("实际分钟数") }, singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.fillBlank(entry, note.trim(), minutes.toIntOrNull() ?: 0, fillDate)
                    fillTarget = null
                }) { Text("记下") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.markNotDone(entry, "skipped_blank", "今天没用到这段时间", fillDate)
                        fillTarget = null
                    }) { Text("今天跳过") }
                    TextButton(onClick = { fillTarget = null }) { Text("取消") }
                }
            }
        )
    }

    notDoneTarget?.let { entry ->
        var reason by remember(entry) { mutableStateOf("no_time") }
        var note by remember(entry) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { notDoneTarget = null },
            title = { Text("今天没做，是因为") },
            text = {
                Column {
                    NOT_DONE_REASONS.forEach { (code, label) ->
                        Row(Modifier.fillMaxWidth().clickable { reason = code }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reason == code, onClick = { reason = code })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OutlinedTextField(value = note, onValueChange = { note = it },
                        label = { Text("补一句（可留空）") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.markNotDone(entry, reason, note.trim(), notDoneDate)
                    notDoneTarget = null
                }) { Text("记下") }
            },
            dismissButton = { TextButton(onClick = { notDoneTarget = null }) { Text("取消") } }
        )
    }

    minutesTarget?.let { entry ->
        ActualMinutesSheet(
            title = entry.task.content,
            suggested = entry.task.durationMinutes,
            onSave = { minutes ->
                viewModel.recordActualMinutes(entry.task.id, minutesDate, minutes)
                minutesTarget = null
            },
            onSkip = { minutesTarget = null }
        )
    }
}

private val NOT_DONE_REASONS = listOf(
    "no_time" to "没时间", "tired" to "太累", "sick" to "身体不舒服",
    "forgot" to "忘了", "changed" to "情况变了", "other" to "其他"
)

/**
 * 打卡顺手补「实际花了多久」：快捷 chips + 自定义输入，不填直接跳过也不影响完成。
 * 只是给趋势图攒"真实投入"数据，不改变打卡本身的任何语义。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActualMinutesSheet(
    title: String,
    suggested: Int,
    onSave: (Int) -> Unit,
    onSkip: () -> Unit
) {
    var custom by remember { mutableStateOf("") }
    val options = remember(suggested) {
        (listOf(15, 30, 45, 60, 90).takeIf { suggested in it } ?: (listOf(suggested) + listOf(15, 30, 45, 60, 90)))
            .distinct().sorted()
    }
    var selected by remember { mutableStateOf(if (suggested > 0) suggested else -1) }
    ModalBottomSheet(onDismissRequest = onSkip) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("「${title.take(18)}${if (title.length > 18) "…" else ""}」实际花了多久？",
                style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { m ->
                    FilterChip(
                        selected = selected == m && custom.isBlank(),
                        onClick = { selected = m; custom = "" },
                        label = { Text("$m 分") }
                    )
                }
            }
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text("自定义分钟数（填了优先用这个）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    enabled = (custom.toIntOrNull() ?: 0) > 0 || selected > 0,
                    onClick = { onSave(custom.toIntOrNull()?.takeIf { it > 0 } ?: selected) },
                    modifier = Modifier.weight(1f)
                ) { Text("就这么多") }
                OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("不填了") }
            }
        }
    }
}

@Composable
private fun EmptySchedule(onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("今天还没有安排", style = MaterialTheme.typography.titleMedium)
        Text("点右上角 + 建一个每日任务、临时任务或留白时段",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onCreate) { Text("新建任务") }
    }
}

/** 计划 / 目标入口卡：只显示名字和时间窗，点进去才有进度 */
@Composable
private fun HierarchyCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "未命名" }, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, done: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Badge(containerColor = if (done) Success else MaterialTheme.colorScheme.surfaceVariant) {
            Text("$count", color = if (done) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    entry: DayEntry,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onEdit: () -> Unit,
    onFill: () -> Unit
) {
    val task = entry.task
    val isDone = entry.occurrence?.status == "done" || entry.state == com.zhiwo.shiguangjian.data.tasks.EntryState.DONE
    val isNotDone = entry.occurrence?.status == "not_done"
    // 勾选瞬间的弹性小庆祝：true 时从 85% 弹回 100%（一次性的，不常驻）
    val checkboxScale = remember { Animatable(1f) }
    LaunchedEffect(isDone) {
        if (isDone) {
            checkboxScale.snapTo(0.85f)
            checkboxScale.animateTo(
                1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
            )
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = onEdit, onLongClick = onLongPress),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = isDone,
                onCheckedChange = { onToggle() },
                modifier = Modifier.scale(checkboxScale.value)
            )
            Column(Modifier.weight(1f)) {
                Text(task.content, style = MaterialTheme.typography.bodyLarge)
                val meta = buildString {
                    if (task.durationMinutes > 0) append("${task.durationMinutes} 分钟 ")
                    if (task.remindTime.isNotBlank()) append("${task.remindTime} ")
                    if (isNotDone) append("未完成·")
                    entry.occurrence?.reasonCode?.takeIf { it.isNotEmpty() }?.let { code ->
                        append(NOT_DONE_REASONS.firstOrNull { it.first == code }?.second ?: code)
                    }
                    entry.occurrence?.note?.takeIf { it.isNotBlank() }?.let { append("｜$it") }
                }.trim()
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (task.kind == "blank" && !isDone) {
                TextButton(onClick = onFill) { Text("回填") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditorSheet(
    initial: TaskEntity?,
    defaultDate: String,
    onSave: (TaskEntity) -> Unit,
    onDelete: (TaskEntity) -> Unit,
    onPause: (TaskEntity) -> Unit,
    onDeleteForever: (TaskEntity) -> Unit
) {
    var content by remember(initial) { mutableStateOf(initial?.content ?: "") }
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: "daily") }
    var repeatRule by remember(initial) { mutableStateOf(initial?.repeatRule ?: "everyday") }
    var dayPolicy by remember(initial) { mutableStateOf(initial?.dayPolicy ?: "all") }
    var startDate by remember(initial) { mutableStateOf(initial?.startDate ?: "") }
    // adhoc 的"最后期限"存在这里：新写 endDate，v14 之前同时镜像到老 dueDate（回滚到 v13 代码也不坏）
    var endDate by remember(initial) {
        mutableStateOf(if (initial?.kind == "adhoc") initial.deadlineDate else initial?.endDate ?: "")
    }
    var scheduledDate by remember(initial) { mutableStateOf(initial?.scheduledDate ?: defaultDate) }
    var duration by remember(initial) { mutableStateOf((initial?.durationMinutes ?: 60).toString()) }
    var remindTime by remember(initial) { mutableStateOf(initial?.remindTime ?: "") }
    var picking by remember { mutableStateOf("") }
    val now = defaultDate

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(if (initial == null) "新建任务" else "编辑任务", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = content, onValueChange = { content = it },
            label = { Text("要做什么") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        SegmentedButtonRow(kind) { kind = it }

        when (kind) {
            "daily" -> {
                Text("重复", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("everyday" to "每天", "weekdays" to "工作日", "custom" to "选星期", "interval" to "每隔几天")
                        .forEach { (v, label) ->
                            FilterChip(selected = repeatRule == v, onClick = { repeatRule = v }, label = { Text(label) })
                        }
                }
                DateField("开始日期（可留空）", startDate, onOpen = { picking = "start" }, onClear = { startDate = "" })
                DateField("结束日期（留空=长期）", endDate, onOpen = { picking = "end" }, onClear = { endDate = "" })
                PolicyChips(dayPolicy) { dayPolicy = it }
            }
            "adhoc" -> {
                DateField("哪一天出现", scheduledDate, onOpen = { picking = "sched" }, onClear = { scheduledDate = "" })
                DateField("最后期限（可留空）", endDate, onOpen = { picking = "end" }, onClear = { endDate = "" })
                if (endDate.isNotBlank() && scheduledDate.isNotBlank() && endDate < scheduledDate) {
                    Text("期限早于出现日期：这天一到就会显示过期", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            "blank" -> {
                Text("每天留出一段时间，结束时回填做了什么。", style = MaterialTheme.typography.bodySmall)
                PolicyChips(dayPolicy) { dayPolicy = it }
            }
        }

        OutlinedTextField(value = remindTime, onValueChange = { remindTime = it.take(5) },
            label = { Text("提醒时间 HH:mm（留空不提醒）") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60, 90).forEach { m ->
                FilterChip(selected = duration == m.toString(), onClick = { duration = m.toString() },
                    label = { Text("$m") })
            }
        }

        if (picking.isNotEmpty()) {
            DatePickerDialogField(
                initial = when (picking) {
                    "start" -> startDate.ifBlank { now }
                    "end" -> endDate.ifBlank { now }
                    else -> scheduledDate.ifBlank { now }
                },
                onConfirm = { day ->
                    when (picking) {
                        "start" -> startDate = day
                        "end" -> endDate = day
                        else -> scheduledDate = day
                    }
                    picking = ""
                },
                onDismiss = { picking = "" }
            )
        }

        val trimmed = content.trim()
        val dateBad = startDate.isNotBlank() && endDate.isNotBlank() && endDate < startDate
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                enabled = trimmed.isNotEmpty() && !dateBad,
                onClick = {
                    val minutes = duration.toIntOrNull()?.coerceIn(0, 1440) ?: 0
                    val base = initial ?: TaskEntity(content = "", createdAt = DateFormats.nowDateTimeIso())
                    onSave(base.copy(
                        content = trimmed, kind = kind, repeatRule = if (kind == "daily") repeatRule else "everyday",
                        dayPolicy = if (kind == "adhoc") "all" else dayPolicy,
                        startDate = if (kind == "daily") startDate else "",
                        endDate = if (kind == "daily" || kind == "adhoc") endDate else "",
                        scheduledDate = if (kind == "adhoc") scheduledDate else "",
                        dueDate = if (kind == "adhoc") endDate else "",
                        durationMinutes = if (kind == "blank") 60 else minutes,
                        remindTime = remindTime.takeIf { it.matches(Regex("""^([01]\d|2[0-3]):[0-5]\d$""")) } ?: ""
                    ))
                }
            ) { Text("保存") }
            if (initial != null) {
                OutlinedButton(onClick = { onDelete(initial) }) { Text("结束任务") }
            }
            if (initial != null && initial.status.trim().lowercase() == "active") {
                // 暂停：排期不再出现、提醒停掉，但随时可从总览页恢复（生命周期设计 B7）
                OutlinedButton(onClick = { onPause(initial) }) { Text("暂停") }
            }
        }
        if (initial != null) {
            // 与"结束任务"分开一行、分开语义：归档保史，删除抹史，别让人按错
            TextButton(onClick = { onDeleteForever(initial) }) {
                Text("删除任务（连打卡记录一起删）", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SegmentedButtonRow(kind: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("daily" to "每日固定", "adhoc" to "临时一次", "blank" to "每日留白").forEach { (v, label) ->
            FilterChip(selected = kind == v, onClick = { onPick(v) }, label = { Text(label) })
        }
    }
}

@Composable
private fun PolicyChips(policy: String, onPick: (String) -> Unit) {
    Text("节假日", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("all" to "照常", "workday_only" to "只在工作日", "holiday_only" to "只在休息日")
            .forEach { (v, label) -> FilterChip(selected = policy == v, onClick = { onPick(v) }, label = { Text(label) }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(
    label: String,
    value: String,
    onOpen: () -> Unit,
    onClear: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
            Text(if (value.isBlank()) "$label：未设" else "$label：$value")
        }
        if (value.isNotBlank()) {
            TextButton(onClick = onClear) { Text("清空") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialogField(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = ScheduleViewModel.parse(initial)?.let {
            java.time.LocalDateTime.of(it, java.time.LocalTime.MIDNIGHT)
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    onConfirm(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString())
                } ?: onDismiss()
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        text = { DatePicker(state = state) }
    )
}

/**
 * 杂项待办行（生命周期设计 §2.3"每一行可见项都有出口"）：
 * 勾选完成（归类不动本体）/ 转为任务 / 删除记录（确认框兜底）/ 点整行看原文——四路都通。
 */
@Composable
private fun MiscTodoRow(
    title: String,
    onCheck: () -> Unit,
    onConvert: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = false, onCheckedChange = { onCheck() })
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onConvert) { Text("转为任务") }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySettingsSheet(
    groups: List<ScheduleSectionGroup>,
    isHidden: (ScheduleSectionGroup) -> Boolean,
    onToggle: (ScheduleSectionGroup, Boolean) -> Unit,
    onMove: (ScheduleSectionGroup, Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("显示的段与顺序", style = MaterialTheme.typography.titleLarge)
        Text("隐藏的段只是不在安排页出现，数据不会被删。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        groups.forEach { group ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(SECTION_TITLES[group] ?: group.name, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = { onMove(group, -1) }) {
                    Icon(Icons.Default.ExpandLess, contentDescription = "上移")
                }
                IconButton(onClick = { onMove(group, 1) }) {
                    Icon(Icons.Default.ExpandMore, contentDescription = "下移")
                }
                Switch(checked = !isHidden(group), onCheckedChange = { onToggle(group, it) })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 段的顺序与显隐都取自 state 里的偏好快照，保证改设置后界面一定跟着变 */
private fun orderedVisible(state: ScheduleUiState): List<Pair<ScheduleSectionGroup, List<DayEntry>>> {
    val rank = state.sectionOrder.withIndex().associate { (i, name) -> name to i }
    return state.groups.entries
        .filter { (group, rows) -> rows.isNotEmpty() && !state.hidden.contains(group.name) }
        .sortedBy { (group, _) -> rank[group.name] ?: Int.MAX_VALUE }
        .map { (group, entries) -> group to entries }
}
