package com.zhiwo.shiguangjian.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.ui.components.CalendarGrid
import com.zhiwo.shiguangjian.ui.components.RecordCard
import com.zhiwo.shiguangjian.ui.components.TaskItem
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.tasks.EntryState
import com.zhiwo.shiguangjian.ui.viewmodel.CalendarViewModel

@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = viewModel(),
    onRecordClick: (Long) -> Unit = {}
) {
    val tasks by viewModel.tasks.collectAsState()
    val records by viewModel.records.collectAsState()
    var markingDate by remember { mutableStateOf<String?>(null) }
    var selectedDate by remember {
        mutableStateOf(
            DateFormats.nowDate()
        )
    }

    val occurrences by viewModel.occurrences.collectAsState()
    val overrides by viewModel.overrides.collectAsState()
    // 与安排页同一个引擎：同一天在两个页面看到的集合必然一致
    val dayPlan by remember(tasks, occurrences, overrides, selectedDate) {
        derivedStateOf { viewModel.dayPlan(selectedDate) }
    }
    val dateTasks = dayPlan.planned
    val overdueTasks = dayPlan.overdue + dayPlan.missed

    val dateRecords by remember(records, selectedDate) {
        derivedStateOf { records.filter { it.createdAt.startsWith(selectedDate) } }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
    ) {
        item {
            CalendarGrid(
                tasks = tasks,
                selectedDate = selectedDate,
                onSelectDate = { selectedDate = it },
                plannedCountOf = { viewModel.plannedCount(it) },
                dayLabel = { viewModel.dayLabel(it) },
                onLongPressDate = { markingDate = it }
            )
        }

        item {
            Text(
                text = selectedDate,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp)
            )
        }

        // 过期任务
        if (overdueTasks.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("过期任务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                }
            }
            items(overdueTasks, key = { "overdue-" + it.task.id + "-" + it.date }) { entry ->
                TaskItem(
                    task = entry.task,
                    onComplete = { viewModel.completeTask(entry.task.id, selectedDate) },
                    onUncomplete = { viewModel.uncompleteTask(entry.task.id, selectedDate) },
                    onPermanentlyComplete = { viewModel.permanentlyCompleteTask(entry.task.id) },
                    showDate = true,
                    isEffectivelyCompleted = entry.state == EntryState.DONE
                )
            }
        }

        // 当日任务
        if (dateTasks.isNotEmpty()) {
            item {
                Text(
                    text = "当日任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp)
                )
            }
            items(dateTasks, key = { "task-" + it.task.id }) { entry ->
                TaskItem(
                    task = entry.task,
                    onComplete = { viewModel.completeTask(entry.task.id, selectedDate) },
                    onUncomplete = { viewModel.uncompleteTask(entry.task.id, selectedDate) },
                    onPermanentlyComplete = { viewModel.permanentlyCompleteTask(entry.task.id) },
                    isEffectivelyCompleted = entry.state == EntryState.DONE
                )
            }
        }

        // 当日记录
        if (dateRecords.isNotEmpty()) {
            item {
                Text(
                    text = "当日记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp)
                )
            }
            items(dateRecords, key = { "record-${it.id}" }) { record ->
                RecordCard(
                    record = record,
                    onClick = { onRecordClick(record.id) }
                )
            }
        }

        // 空状态
        if (dateTasks.isEmpty() && dateRecords.isEmpty() && overdueTasks.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("这天没有任务和记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // 长按日历上的某天 → 手动标「班 / 休」
    val marking = markingDate
        if (marking != null) {
            val date = marking
            AlertDialog(
                onDismissRequest = { markingDate = null },
                title = { Text("$date 算哪天") },
                text = {
                    Column {
                        Text("标成「工作日」或「休息日」后，带「只在工作日 / 只在休息日」的任务会按你标的这天安排。")
                        Spacer(Modifier.height(6.dp))
                        Text("恢复默认则按法定节假日与周末判定。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton(onClick = { viewModel.setDayType(date, "workday"); markingDate = null }) { Text("工作日") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { viewModel.setDayType(date, "holiday") ; markingDate = null }) { Text("休息日") }
                        TextButton(onClick = { viewModel.setDayType(date, null); markingDate = null }) { Text("恢复默认") }
                    }
                }
            )
        }
}