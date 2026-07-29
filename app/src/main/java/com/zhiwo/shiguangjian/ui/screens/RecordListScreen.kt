package com.zhiwo.shiguangjian.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.ui.components.RecordCard
import com.zhiwo.shiguangjian.ui.components.getCategoryInfo
import com.zhiwo.shiguangjian.ui.theme.*
import com.zhiwo.shiguangjian.data.ai.*
import com.zhiwo.shiguangjian.data.ai.RecordAnalysisResult
import com.zhiwo.shiguangjian.ui.viewmodel.RecordListViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordListScreen(
    onRecordClick: (Long) -> Unit = {},
    onFabClick: () -> Unit = {},
    viewModel: RecordListViewModel = viewModel()
) {
    val context = LocalContext.current
    val records by viewModel.records.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterCategory by viewModel.filterCategory.collectAsState()
    val analyzing by viewModel.analyzing.collectAsState()
    val isConfigured by viewModel.isConfigured.collectAsState()
    val analysisResult by viewModel.analysisResult.collectAsState()

    val todayDueTodo by viewModel.todayDueTodoRecords.collectAsState()
    val goalRecords by viewModel.goalRecords.collectAsState()
    val completedRecords by viewModel.completedRecords.collectAsState()
    val filteredRecords by viewModel.filteredRecords.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var showSearch by remember { mutableStateOf(false) }
    var showCompleted by remember { mutableStateOf(false) }
    var fadingTaskIds by remember { mutableStateOf(setOf<Long>()) }
    var completingRecordIds by remember { mutableStateOf(setOf<Long>()) }
    val coroutineScope = rememberCoroutineScope()

    val today = DateFormats.nowDate()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 18.dp, end = 16.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (selectedTab == 0) "今日安排" else "全部记录",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = if (selectedTab == 0) "把今天要做的事放在手边" else "慢慢翻阅你的时光笺",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showSearch = !showSearch }) {
                    Icon(Icons.Default.Search, contentDescription = "搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            AnimatedVisibility(visible = showSearch) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("搜索记录...") },
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("安排", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("全部", modifier = Modifier.padding(12.dp))
                }
            }

            if (selectedTab == 0) {
                ScheduleTab(
                    todayDueTodo = todayDueTodo,
                    goalRecords = goalRecords,
                    completedRecords = completedRecords,
                    tasks = tasks,
                    today = today,
                    showCompleted = showCompleted,
                    onToggleShowCompleted = { showCompleted = !showCompleted },
                    fadingTaskIds = fadingTaskIds,
                    completingRecordIds = completingRecordIds,
                    onTaskToggle = { task ->
                        if (viewModel.isTaskEffectivelyCompleted(task, today)) {
                            viewModel.uncompleteTask(task.id)
                        } else {
                            viewModel.completeTask(task.id)
                            coroutineScope.launch {
                                if (task.taskType == "daily") {
                                    fadingTaskIds = fadingTaskIds + task.id
                                }
                                val recordTasks = tasks.filter { t -> t.recordId == task.recordId }
                                val allDone = recordTasks.all { t ->
                                    if (t.id == task.id) true
                                    else viewModel.isTaskEffectivelyCompleted(t, today)
                                }
                                if (allDone && recordTasks.isNotEmpty()) {
                                    task.recordId?.let { completingRecordIds = completingRecordIds + it }
                                }
                            }
                        }
                    },
                    onRecordClick = onRecordClick,
                    onCompleteGoal = { viewModel.completeGoal(it) }
                )
            }

            if (selectedTab == 1) {
                AllRecordsTab(
                    records = records,
                    filteredRecords = filteredRecords,
                    tasks = tasks,
                    filterCategory = filterCategory,
                    analyzing = analyzing,
                    isConfigured = isConfigured,
                    onFilterChange = { viewModel.setFilterCategory(it) },
                    onAnalyze = { viewModel.analyzeRecords() },
                    onRecordClick = onRecordClick,
                    onTaskComplete = { viewModel.completeTask(it) },
                    onTaskUncomplete = { viewModel.uncompleteTask(it) }
                )
            }
        }

        if (selectedTab == 1) {
            FloatingActionButton(
                onClick = onFabClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 80.dp, end = 20.dp),
                containerColor = Primary,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建记录", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        analysisResult?.let { result ->
            RecordAnalysisResultSheet(
                result = result,
                records = records,
                sheetState = sheetState,
                onDismiss = { viewModel.clearAnalysisResult() },
                onExecuteMerge = { ids, title, cat -> viewModel.applyRecordMerge(ids, title, cat) },
                onExecuteSplit = { id, splits -> viewModel.applyRecordSplit(id, splits) },
                onExecuteGoalToTodos = { id, todos -> viewModel.applyGoalToTodos(id, todos) },
                onExecuteTodosToGoal = { ids, title -> viewModel.applyTodosToGoal(ids, title) },
                onExecuteToMemory = { id, content -> viewModel.applyToMemory(id, content) },
                onSkip = { type, index -> viewModel.skipSuggestion(type, index) },
                onApplyAll = { viewModel.applyAllSuggestions() }
            )
        }
    }
}

// ========== 安排 Tab ==========

@Composable
private fun ScheduleTab(
    todayDueTodo: List<RecordEntity>,
    goalRecords: List<RecordEntity>,
    completedRecords: List<RecordEntity>,
    tasks: List<TaskEntity>,
    today: String,
    showCompleted: Boolean,
    onToggleShowCompleted: () -> Unit,
    fadingTaskIds: Set<Long>,
    completingRecordIds: Set<Long>,
    onTaskToggle: (TaskEntity) -> Unit,
    onRecordClick: (Long) -> Unit,
    onCompleteGoal: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        item { SectionHeader(IconsDefault.CheckList, "待办", todayDueTodo.size) }
        if (todayDueTodo.isEmpty()) {
            item { EmptyHint("今天暂无待办") }
        } else {
            items(todayDueTodo, key = { "todo-${it.id}" }) { record ->
                ScheduleRecordItem(
                    record = record,
                    tasks = tasks.filter { t -> t.recordId == record.id },
                    today = today,
                    isCompleting = completingRecordIds.contains(record.id),
                    fadingTaskIds = fadingTaskIds,
                    onTaskToggle = onTaskToggle,
                    onClick = { onRecordClick(record.id) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { SectionHeader(IconsDefault.Flag, "目标", goalRecords.size) }
        if (goalRecords.isEmpty()) {
            item { EmptyHint("暂无目标") }
        } else {
            items(goalRecords, key = { "goal-${it.id}" }) { record ->
                GoalRecordItem(
                    record = record,
                    tasks = tasks.filter { t -> t.recordId == record.id },
                    onComplete = { onCompleteGoal(record.id) },
                    onClick = { onRecordClick(record.id) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleShowCompleted() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Success, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("今日已完成", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Badge { Text("${completedRecords.size}") }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    if (showCompleted) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showCompleted) {
            if (completedRecords.isEmpty()) {
                item { EmptyHint("暂无已完成") }
            } else {
                items(completedRecords, key = { "completed-${it.id}" }) { record ->
                    CompletedRecordItem(record = record, onClick = { onRecordClick(record.id) })
                }
            }
        }
    }
}

// ========== 全部 Tab ==========

@Composable
private fun AllRecordsTab(
    records: List<RecordEntity>,
    filteredRecords: List<RecordEntity>,
    tasks: List<TaskEntity>,
    filterCategory: String,
    analyzing: Boolean,
    isConfigured: Boolean,
    onFilterChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onRecordClick: (Long) -> Unit,
    onTaskComplete: (Long) -> Unit,
    onTaskUncomplete: (Long) -> Unit
) {
    val context = LocalContext.current
    val categories = listOf(
        "" to "全部", "todo" to "待办事项", "goal" to "目标设定",
        "idea" to "想法灵感", "emotion" to "情绪记录",
        "question" to "问题思考", "study" to "学习笔记", "other" to "其他"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { (catId, catName) ->
                val isActive = filterCategory == catId
                val chipColor = getCategoryColor(catId)
                SuggestionChip(
                    onClick = { onFilterChange(if (isActive) "" else catId) },
                    label = {
                        Text(catName,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (isActive) chipColor else MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(20.dp)
                )
            }
        }

        val nonCompletedCount = records.count { it.category != "completed" }
        Button(
            onClick = {
                if (!isConfigured) {
                    Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (nonCompletedCount < 2) {
                    Toast.makeText(context, "至少需要2条记录才能分析", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                onAnalyze()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            enabled = !analyzing && nonCompletedCount >= 2,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            if (analyzing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("分析中...")
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("AI分析记录（合并/拆分/转记忆）")
            }
        }

        if (filteredRecords.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(IconsDefault.EditNote, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("还没有记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("点击底部 + 开始记录吧", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredRecords, key = { it.id }) { record ->
                    RecordCard(
                        record = record,
                        tasks = tasks.filter { t -> t.recordId == record.id },
                        onClick = { onRecordClick(record.id) },
                        onTaskComplete = { onTaskComplete(it) },
                        onTaskUncomplete = { onTaskUncomplete(it) }
                    )
                }
            }
        }
    }
}

// ========== AI 分析结果弹窗 ==========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordAnalysisResultSheet(
    result: RecordAnalysisResult,
    records: List<RecordEntity>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onExecuteMerge: (List<Long>, String, String) -> Unit,
    onExecuteSplit: (Long, List<Pair<String, String>>) -> Unit,
    onExecuteGoalToTodos: (Long, List<String>) -> Unit,
    onExecuteTodosToGoal: (List<Long>, String) -> Unit,
    onExecuteToMemory: (Long, String) -> Unit,
    onSkip: (String, Int) -> Unit,
    onApplyAll: () -> Unit
) {
    val hasAnySuggestion = result.merge.isNotEmpty() || result.split.isNotEmpty() ||
            result.goalToTodos.isNotEmpty() || result.todosToGoal.isNotEmpty() ||
            result.toMemory.isNotEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = if (hasAnySuggestion) 120.dp else 24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("AI 分析建议", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))

                if (!hasAnySuggestion) {
                    Text("暂无优化建议，记录状态良好！", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                }

                result.merge.forEachIndexed { index, item ->
                    val mergeIds = item.sourceIds
                    val mergeTitle = item.mergedTitle
                    val mergeCategory = item.mergedCategory
                    AnalysisSuggestionCard(
                        icon = IconsDefault.MergeType, title = "合并记录", reason = item.reason,
                        detail = "合并为「$mergeTitle」", records = records, recordIds = mergeIds,
                        onExecute = { onExecuteMerge(mergeIds, mergeTitle, mergeCategory) },
                        onSkip = { onSkip("merge", index) }
                    )
                }
                result.split.forEachIndexed { index, item ->
                    val splitId = item.sourceId
                    val splits = item.splits.map { split -> split.title to split.category }
                    val splitTitles = item.splits.joinToString("、") { "「${it.title}」" }
                    AnalysisSuggestionCard(
                        icon = IconsDefault.ContentCut, title = "拆分记录", reason = item.reason,
                        detail = "拆分为$splitTitles",
                        records = records, recordIds = listOf(splitId),
                        onExecute = { onExecuteSplit(splitId, splits) },
                        onSkip = { onSkip("split", index) }
                    )
                }
                result.goalToTodos.forEachIndexed { index, item ->
                    val sourceId = item.sourceId
                    val todos = item.todos
                    AnalysisSuggestionCard(
                        icon = IconsDefault.ListAlt, title = "目标拆解为待办", reason = item.reason,
                        detail = todos.joinToString("、"), records = records, recordIds = listOf(sourceId),
                        onExecute = { onExecuteGoalToTodos(sourceId, todos) },
                        onSkip = { onSkip("goalToTodos", index) }
                    )
                }
                result.todosToGoal.forEachIndexed { index, item ->
                    val ids = item.sourceIds
                    val goalTitle = item.goalTitle
                    AnalysisSuggestionCard(
                        icon = IconsDefault.Flag, title = "待办合并为目标", reason = item.reason,
                        detail = "创建目标「$goalTitle」", records = records, recordIds = ids,
                        onExecute = { onExecuteTodosToGoal(ids, goalTitle) },
                        onSkip = { onSkip("todosToGoal", index) }
                    )
                }
                result.toMemory.forEachIndexed { index, item ->
                    val memId = item.sourceId
                    val memContent = item.memoryContent
                    AnalysisSuggestionCard(
                        icon = IconsDefault.Lightbulb, title = "转为记忆", reason = item.reason,
                        detail = "「$memContent」", records = records, recordIds = listOf(memId),
                        onExecute = { onExecuteToMemory(memId, memContent) },
                        onSkip = { onSkip("toMemory", index) }
                    )
                }
            }

            if (hasAnySuggestion) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Button(onClick = onApplyAll, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) { Text("一键全部执行") }
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("全部跳过") }
                }
            }
        }
    }
}

// ========== 子组件 ==========

@Composable
fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Badge { Text("$count") }
    }
}

@Composable
fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
fun ScheduleRecordItem(
    record: RecordEntity, tasks: List<TaskEntity>, today: String,
    isCompleting: Boolean, fadingTaskIds: Set<Long>,
    onTaskToggle: (TaskEntity) -> Unit, onClick: () -> Unit
) {
    val (_, catColor) = getCategoryInfo(record.category)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(catColor.copy(alpha = 0.85f)))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(record.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (tasks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    tasks.take(3).forEach { task ->
                        val isDone = task.taskType == "daily" &&
                            task.isCompleted && task.dailyCompletionDate == today ||
                            task.taskType != "daily" && task.isCompleted
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .then(if (task.id in fadingTaskIds) Modifier.alpha(0.45f) else Modifier)
                                .then(if (!isCompleting) Modifier.clickable { onTaskToggle(task) } else Modifier)
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isDone) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            } else {
                                Box(modifier = Modifier.size(18.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = task.content, style = MaterialTheme.typography.bodySmall,
                                color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (task.taskType == "daily") {
                                Text("每日", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondary,
                                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                        .background(Warning)
                                        .padding(horizontal = 5.dp))
                            }
                            if (task.taskType == "weekly") {
                                Text("每周", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                        .background(Lavender)
                                        .padding(horizontal = 5.dp))
                            }
                        }
                    }
                    if (tasks.size > 3) {
                        Text("+${tasks.size - 3}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 26.dp, top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun GoalRecordItem(
    record: RecordEntity, tasks: List<TaskEntity>,
    onComplete: () -> Unit, onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Error.copy(alpha = 0.85f)))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(record.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                tasks.take(3).forEach { task ->
                    Text("• ${task.content}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onComplete,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = ButtonDefaults.outlinedButtonBorder,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)
                ) {
                    Icon(IconsDefault.Flag, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("完成", maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun CompletedRecordItem(record: RecordEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Success.copy(alpha = 0.85f)))
        Spacer(modifier = Modifier.width(10.dp))
        Text(record.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = TextDecoration.LineThrough, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AnalysisSuggestionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, reason: String, detail: String,
    records: List<RecordEntity>, recordIds: List<Long>,
    onExecute: () -> Unit, onSkip: () -> Unit
) {
    val sourceNames = recordIds.mapNotNull { id -> records.find { it.id == id }?.title }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            if (sourceNames.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("记录：${sourceNames.joinToString("、")}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (reason.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text("原因：$reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onSkip, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                    Text("跳过")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onExecute,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("执行") }
            }
        }
    }
}

// ========== 共享图标与工具 ==========

/** 模块内用到的 Material 图标集中定义，避免每个文件重复 import */
private object IconsDefault {
    val CheckList = Icons.Default.CheckCircle
    val Flag = Icons.Default.Flag
    val EditNote = Icons.Default.Edit
    val MergeType = Icons.Default.MergeType
    val ContentCut = Icons.Default.ContentCut
    val ListAlt = Icons.Default.List
    val Lightbulb = Icons.Default.Lightbulb
}

fun getCategoryColor(category: String): Color = when (category) {
    "todo" -> Primary
    "goal" -> Error
    "idea" -> Success
    "emotion" -> Warning
    "question" -> Lavender
    "study" -> Sage
    else -> TextSecondary
}
