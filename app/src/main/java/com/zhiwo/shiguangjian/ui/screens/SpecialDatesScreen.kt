package com.zhiwo.shiguangjian.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.db.entity.SpecialDateEntity
import com.zhiwo.shiguangjian.ui.viewmodel.SpecialDateViewModel

/**
 * 重要日子管理页：添加/删除生日、纪念日等，支持公历/农历，
 * 列表按"距离下次出现"排序并显示倒计时。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecialDatesScreen(
    viewModel: SpecialDateViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val specialDates by viewModel.specialDates.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SpecialDateEntity?>(null) }

    // 按下一次出现的天数排序
    val sorted = remember(specialDates) {
        specialDates
            .map { it to viewModel.upcomingOf(it) }
            .sortedBy { it.second?.daysUntil ?: Int.MAX_VALUE }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("重要日子") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加重要日子")
            }
        }
    ) { padding ->
        if (sorted.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Favorite, contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "还没有重要日子\n添加爸妈的生日、纪念日吧",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sorted, key = { it.first.id }) { (entity, upcoming) ->
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                when (entity.type) {
                                    "birthday" -> Icons.Default.Cake
                                    "anniversary" -> Icons.Default.Favorite
                                    else -> Icons.Default.Event
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entity.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    buildString {
                                        append(if (entity.isLunar) "农历 " else "")
                                        entity.year?.let { append("${it}年") }
                                        append("${entity.month}月${entity.day}日")
                                        append(if (entity.year == null) " · 每年" else " · 仅一次")
                                        when {
                                            upcoming == null && entity.year != null -> append("  ·  已过")
                                            upcoming != null -> {
                                                append("  ·  ")
                                                append(if (upcoming.daysUntil == 0) "就是今天！" else "还有${upcoming.daysUntil}天")
                                            }
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (upcoming?.daysUntil == 0)
                                        MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { deleteTarget = entity }) {
                                Icon(
                                    Icons.Default.Delete, contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSpecialDateDialog(
            onConfirm = { title, month, day, isLunar, type, year ->
                viewModel.addSpecialDate(title, month, day, isLunar, type, year)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除重要日子") },
            text = { Text("确定删除「${target.title}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSpecialDate(target.id)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AddSpecialDateDialog(
    onConfirm: (title: String, month: Int, day: Int, isLunar: Boolean, type: String, year: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var monthText by remember { mutableStateOf("") }
    var dayText by remember { mutableStateOf("") }
    var isLunar by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf("birthday") }
    var repeatYearly by remember { mutableStateOf(true) }
    val currentYear = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    var yearText by remember { mutableStateOf(currentYear.toString()) }

    val month = monthText.toIntOrNull()
    val day = dayText.toIntOrNull()
    val year = yearText.toIntOrNull()
    val valid = title.isNotBlank() &&
            month != null && month in 1..12 &&
            day != null && day in 1..(if (isLunar) 30 else 31) &&
            (repeatYearly || (year != null && year in currentYear..currentYear + 50))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加重要日子") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("名称，如：妈妈的生日") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("birthday" to "生日", "anniversary" to "纪念日", "other" to "其他").forEach { (key, label) ->
                        FilterChip(
                            selected = type == key,
                            onClick = { type = key },
                            label = { Text(label) }
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!repeatYearly) {
                        OutlinedTextField(
                            value = yearText,
                            onValueChange = { yearText = it.filter { c -> c.isDigit() }.take(4) },
                            label = { Text("年") },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f)
                        )
                    }
                    OutlinedTextField(
                        value = monthText,
                        onValueChange = { monthText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("月") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = dayText,
                        onValueChange = { dayText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("日") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("每年重复", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = repeatYearly, onCheckedChange = {
                        repeatYearly = it
                        if (!it) isLunar = false  // 仅一次的日子按公历填写
                    })
                }
                Text(
                    if (repeatYearly) "生日、纪念日等每年提醒" else "只在指定日期提醒一次，如考试报名、截止日",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (repeatYearly) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("按农历计算", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = isLunar, onCheckedChange = { isLunar = it })
                    }
                    if (isLunar) {
                        Text(
                            "农历日期每年对应的公历日期会自动换算",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, month!!, day!!, isLunar, type, if (repeatYearly) null else year) },
                enabled = valid
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
