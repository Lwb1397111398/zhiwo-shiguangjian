package com.zhiwo.shiguangjian.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.tasks.DayEntry
import com.zhiwo.shiguangjian.data.tasks.deadlineDate
import com.zhiwo.shiguangjian.ui.theme.Success
import com.zhiwo.shiguangjian.ui.viewmodel.ScheduleSectionGroup
import com.zhiwo.shiguangjian.ui.viewmodel.ScheduleUiState
import com.zhiwo.shiguangjian.ui.viewmodel.ScheduleViewModel

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
    viewModel: ScheduleViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val miscTodo by viewModel.miscTodo.collectAsState()
    val activePlans by viewModel.activePlans.collectAsState()
    val activeGoals by viewModel.activeGoals.collectAsState()
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var notDoneTarget by remember { mutableStateOf<DayEntry?>(null) }
    var fillTarget by remember { mutableStateOf<DayEntry?>(null) }
    var fillDate by remember { mutableStateOf(state.today) }
    var notDoneDate by remember { mutableStateOf(state.today) }
    var showingPrefs by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("今日安排", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (state.loading) "载入中…" else "已完成 ${state.doneCount} / 应做 ${state.dueCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                    onConvert = { viewModel.convertRecordToTask(record) }
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
                                        if (done) viewModel.uncheck(entry, target) else viewModel.check(entry, target)
                                    },
                                    onLongPress = { if (entry.occurrence?.status != "done") { notDoneTarget = entry; notDoneDate = entry.date.ifBlank { state.today } } },
                                    onEdit = { editing = entry.task },
                                    onFill = { if (entry.task.kind == "blank") { fillTarget = entry; fillDate = entry.date.ifBlank { state.today } } else { notDoneTarget = entry; notDoneDate = entry.date.ifBlank { state.today } } }
                                )
                            }
                        }
                    }

                    // ---- 下面两段是层级入口（计划 / 目标），不参与今日完成率 ----
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
                onSave = { task -> viewModel.saveTask(task); creating = false; editing = null }
            )
        }
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
}

private val NOT_DONE_REASONS = listOf(
    "no_time" to "没时间", "tired" to "太累", "sick" to "身体不舒服",
    "forgot" to "忘了", "changed" to "情况变了", "other" to "其他"
)

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
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = onEdit, onLongClick = onLongPress),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isDone, onCheckedChange = { onToggle() })
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
    onDelete: (TaskEntity) -> Unit
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

@Composable
private fun MiscTodoRow(title: String, onCheck: () -> Unit, onConvert: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = false, onCheckedChange = { onCheck() })
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onConvert) { Text("转为任务") }
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
