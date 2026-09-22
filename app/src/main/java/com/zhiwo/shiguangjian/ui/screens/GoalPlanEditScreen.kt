package com.zhiwo.shiguangjian.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.ui.theme.Warning
import com.zhiwo.shiguangjian.ui.viewmodel.GoalDetailViewModel
import com.zhiwo.shiguangjian.ui.viewmodel.PlanDetailViewModel

/**
 * 目标 / 计划的新建与编辑。同一个文件两套表单，字段差别不大但落库的表不同。
 * 日期一律走 DatePickerDialogField（material3 1.2.0 没有 DatePickerDialog，只能 AlertDialog + DatePicker）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalEditScreen(
    goalId: Long = 0L,
    onBack: () -> Unit = {},
    viewModel: GoalDetailViewModel = viewModel()
) {
    LaunchedEffect(goalId) { viewModel.open(goalId) }
    val state by viewModel.state.collectAsState()
    val loaded = state.goal

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(DateFormats.nowDate()) }
    var targetDate by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf("") }

    LaunchedEffect(loaded?.id) {
        loaded?.let {
            title = it.title
            description = it.description
            startDate = it.startDate.ifBlank { DateFormats.nowDate() }
            targetDate = it.targetDate
        }
    }

    val editing = goalId != 0L
    EditScaffold(
        title = if (editing) "编辑目标" else "新建目标",
        onBack = onBack,
        loading = editing && state.loading,
        saveEnabled = title.isNotBlank() && !dateReversed(startDate, targetDate),
        onSave = {
            val base = loaded ?: GoalEntity(
                title = "",
                createdAt = DateFormats.nowDateTimeIso(),
                status = "active"
            )
            viewModel.saveGoal(
                base.copy(
                    title = title.trim(),
                    description = description.trim(),
                    startDate = startDate,
                    targetDate = targetDate
                )
            )
            onBack()
        }
    ) {
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("目标叫什么（必填）") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = description, onValueChange = { description = it },
            label = { Text("为什么想做这件事（可留空）") }, minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        DateField("开始日期", startDate, onOpen = { picking = "start" }, onClear = { startDate = "" })
        DateField("截止日期（留空 = 长期）", targetDate, onOpen = { picking = "end" }, onClear = { targetDate = "" })
        DateHint(startDate, targetDate)
        if (!editing && loaded == null) {
            Text("目标是大方向，比如「今年通过法考」；具体每天做什么交给它下面的计划。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (picking.isNotEmpty()) {
        DatePickerDialogField(
            initial = if (picking == "end") targetDate.ifBlank { startDate.ifBlank { DateFormats.nowDate() } }
            else startDate.ifBlank { DateFormats.nowDate() },
            onConfirm = { day ->
                if (picking == "end") targetDate = day else startDate = day
                picking = ""
            },
            onDismiss = { picking = "" }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditScreen(
    planId: Long = 0L,
    goalId: Long = 0L,
    onBack: () -> Unit = {},
    viewModel: PlanDetailViewModel = viewModel()
) {
    LaunchedEffect(planId) { viewModel.open(planId) }
    val state by viewModel.state.collectAsState()
    val goals by viewModel.goalChoices.collectAsState()
    val loaded = state.plan

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(DateFormats.nowDate()) }
    var endDate by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf(if (goalId > 0L) goalId else null) }
    var picking by remember { mutableStateOf("") }

    LaunchedEffect(loaded?.id) {
        loaded?.let {
            title = it.title
            description = it.description
            startDate = it.startDate.ifBlank { DateFormats.nowDate() }
            endDate = it.endDate
            owner = it.goalId
        }
    }

    val editing = planId != 0L
    EditScaffold(
        title = if (editing) "编辑计划" else "新建计划",
        onBack = onBack,
        loading = editing && state.loading,
        saveEnabled = title.isNotBlank() && !dateReversed(startDate, endDate),
        onSave = {
            val base = loaded ?: PlanEntity(
                title = "",
                createdAt = DateFormats.nowDateTimeIso(),
                status = "active"
            )
            viewModel.savePlan(
                base.copy(
                    title = title.trim(),
                    description = description.trim(),
                    startDate = startDate,
                    endDate = endDate,
                    goalId = owner
                )
            )
            onBack()
        }
    ) {
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("计划叫什么（必填）") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = description, onValueChange = { description = it },
            label = { Text("这段打算怎么推进（可留空）") }, minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        DateField("开始日期", startDate, onOpen = { picking = "start" }, onClear = { startDate = "" })
        DateField("结束日期（留空 = 未定）", endDate, onOpen = { picking = "end" }, onClear = { endDate = "" })
        DateHint(startDate, endDate)
        GoalDropdown(goals, owner) { owner = it }
        if (goals.isEmpty()) {
            Text("还没有目标，可以先不归到任何目标下；建好目标后再回来编辑。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (picking.isNotEmpty()) {
        DatePickerDialogField(
            initial = if (picking == "end") endDate.ifBlank { startDate.ifBlank { DateFormats.nowDate() } }
            else startDate.ifBlank { DateFormats.nowDate() },
            onConfirm = { day ->
                if (picking == "end") endDate = day else startDate = day
                picking = ""
            },
            onDismiss = { picking = "" }
        )
    }
}

// ========== 共用的表单骨架 ==========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditScaffold(
    title: String,
    onBack: () -> Unit,
    loading: Boolean,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    fields: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            fields()
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(enabled = saveEnabled, onClick = onSave) { Text("保存") }
                OutlinedButton(onClick = onBack) { Text("取消") }
            }
        }
    }
}

private fun dateReversed(start: String, end: String): Boolean =
    start.isNotBlank() && end.isNotBlank() && end < start

@Composable
private fun DateHint(start: String, end: String) {
    if (dateReversed(start, end)) {
        Text("结束日期早于开始日期，保存不了。", style = MaterialTheme.typography.bodySmall, color = Warning)
    }
}

/** 所属目标下拉：0 号选项是"无"，自由计划也合法（进度只在计划上） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalDropdown(goals: List<GoalEntity>, selected: Long?, onPick: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when {
        selected == null -> "无（不归目标）"
        else -> goals.firstOrNull { it.id == selected }?.title?.ifBlank { "未命名目标" } ?: "目标已不存在"
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("所属目标") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("无（不归目标）") },
                onClick = { onPick(null); expanded = false }
            )
            goals.forEach { goal ->
                DropdownMenuItem(
                    text = { Text(goal.title.ifBlank { "未命名目标" }) },
                    onClick = { onPick(goal.id); expanded = false }
                )
            }
        }
    }
}
