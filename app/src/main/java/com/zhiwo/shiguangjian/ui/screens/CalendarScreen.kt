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
import com.zhiwo.shiguangjian.data.tasks.getTaskDisplayDate
import com.zhiwo.shiguangjian.data.tasks.isTaskEffectivelyCompleted
import com.zhiwo.shiguangjian.ui.viewmodel.CalendarViewModel

@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = viewModel(),
    onRecordClick: (Long) -> Unit = {}
) {
    val tasks by viewModel.tasks.collectAsState()
    val records by viewModel.records.collectAsState()
    var selectedDate by remember {
        mutableStateOf(
            DateFormats.nowDate()
        )
    }

    val dateTasks by remember(tasks, selectedDate) {
        derivedStateOf {
            tasks.filter { task ->
                if (task.isPermanentlyCompleted) return@filter false
                val displayDate = getTaskDisplayDate(task)
                displayDate == selectedDate && !isTaskEffectivelyCompleted(task)
            }
        }
    }

    val overdueTasks by remember(tasks, selectedDate) {
        derivedStateOf {
            tasks.filter { task ->
                if (task.isPermanentlyCompleted) return@filter false
                !isTaskEffectivelyCompleted(task) &&
                task.taskType != "daily" && task.taskType != "weekly" &&
                task.dueDate.isNotBlank() && task.dueDate.take(10) < selectedDate
            }
        }
    }

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
                onSelectDate = { selectedDate = it }
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
            items(overdueTasks, key = { "overdue-${it.id}" }) { task ->
                TaskItem(
                    task = task,
                    onComplete = { viewModel.completeTask(task.id) },
                    onUncomplete = { viewModel.uncompleteTask(task.id) },
                    onPermanentlyComplete = { viewModel.permanentlyCompleteTask(task.id) },
                    showDate = true,
                    isEffectivelyCompleted = isTaskEffectivelyCompleted(task)
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
            items(dateTasks, key = { "task-${it.id}" }) { task ->
                TaskItem(
                    task = task,
                    onComplete = { viewModel.completeTask(task.id) },
                    onUncomplete = { viewModel.uncompleteTask(task.id) },
                    onPermanentlyComplete = { viewModel.permanentlyCompleteTask(task.id) },
                    isEffectivelyCompleted = isTaskEffectivelyCompleted(task)
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
}
