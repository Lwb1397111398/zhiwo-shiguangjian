package com.zhiwo.shiguangjian.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import com.zhiwo.shiguangjian.ui.viewmodel.DiaryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    viewModel: DiaryViewModel = viewModel(),
    onNavigateToReview: () -> Unit = {}
) {
    val context = LocalContext.current
    val diaries by viewModel.diaries.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var diaryToDelete by remember { mutableStateOf<Long?>(null) }
    var expandedDiaryId by remember { mutableStateOf<Long?>(null) }
    var showCleanupDialog by remember { mutableStateOf(false) }

    // 收集导出反馈
    LaunchedEffect(Unit) {
        viewModel.exportMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 收集清理反馈
    LaunchedEffect(Unit) {
        viewModel.cleanupMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 删除确认对话框
    if (diaryToDelete != null) {
        AlertDialog(
            onDismissRequest = { diaryToDelete = null },
            title = { Text("删除日记") },
            text = { Text("确定要删除这篇日记吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    diaryToDelete?.let { viewModel.deleteDiary(it) }
                    diaryToDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { diaryToDelete = null }) { Text("取消") }
            }
        )
    }

    // 清理已导出日记对话框
    if (showCleanupDialog) {
        AlertDialog(
            onDismissRequest = { showCleanupDialog = false },
            title = { Text("清理已导出的日记") },
            text = { Text("将删除所有已导出为TXT的日记，释放本地空间。\n\n未导出的日记不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showCleanupDialog = false
                    viewModel.cleanupExportedDiaries()
                }) { Text("清理", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupDialog = false }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateToReview) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("我的日记", style = MaterialTheme.typography.headlineMedium)
            }
            Row {
                TextButton(onClick = { showCleanupDialog = true }) {
                    Text("清理", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                TextButton(onClick = { viewModel.exportAllDiariesAsTxt() }) {
                    Text("全部导出", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                }
            }
        }

        // 搜索栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.searchDiaries(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("搜索日记内容...", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        if (diaries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(56.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    if (searchQuery.isBlank()) {
                        Text("还没有日记", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("完成每日评价后会自动生成日记", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("没有找到匹配的日记", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(diaries, key = { it.id }) { diary ->
                    DiaryCard(
                        diary = diary,
                        isExpanded = expandedDiaryId == diary.id,
                        onToggleExpand = {
                            expandedDiaryId = if (expandedDiaryId == diary.id) null else diary.id
                        },
                        onExport = { viewModel.exportDiaryAsTxt(diary) },
                        onDelete = { diaryToDelete = diary.id },
                        onSaveEdit = { newContent ->
                            viewModel.updateDiaryContent(diary, newContent)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DiaryCard(
    diary: DiaryEntity,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onSaveEdit: (String) -> Unit = {}
) {
    var isEditing by remember(diary.id) { mutableStateOf(false) }
    var editContent by remember(diary.id) { mutableStateOf(diary.content) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize()
            .clickable { if (!isEditing) onToggleExpand() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 日期 + 情绪标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatDiaryDate(diary.date),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(moodColor(diary.mood).copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        "${moodIcon(diary.mood)} ${diary.mood}",
                        fontSize = 12.sp,
                        color = moodColor(diary.mood),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 日记内容
            if (isExpanded) {
                if (isEditing) {
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 22.sp)
                    )
                } else {
                    Text(
                        diary.content,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 22.sp
                    )
                }
            } else {
                Text(
                    diary.content.take(80) + if (diary.content.length > 80) "..." else "",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 22.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 展开时显示操作按钮
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isEditing) {
                        TextButton(onClick = {
                            isEditing = false
                            editContent = diary.content
                        }) {
                            Text("取消", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(
                            onClick = {
                                isEditing = false
                                onSaveEdit(editContent)
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("保存", fontSize = 12.sp)
                        }
                    } else {
                        IconButton(
                            onClick = { isEditing = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "编辑",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = onExport) {
                            Text("📄 导出TXT", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDiaryDate(dateStr: String): String {
    return try {
        val parts = dateStr.split("-")
        if (parts.size == 3) "${parts[0]}年${parts[1].toInt()}月${parts[2].toInt()}日" else dateStr
    } catch (_: Exception) {
        dateStr
    }
}

private fun moodColor(mood: String): Color = when (mood) {
    "开心" -> Color(0xFFFFD54F)
    "充实" -> Color(0xFF81C784)
    "感动" -> Color(0xFFFFAB91)
    "释然" -> Color(0xFF80DEEA)
    "平淡" -> Color(0xFFBDBDBD)
    "疲惫" -> Color(0xFF90A4AE)
    "低落" -> Color(0xFF7986CB)
    "焦虑" -> Color(0xFFE57373)
    else -> Color(0xFFBDBDBD)
}

private fun moodIcon(mood: String): String = when (mood) {
    "开心" -> "☀️"
    "充实" -> "✅"
    "感动" -> "💗"
    "释然" -> "🌿"
    "平淡" -> "🍃"
    "疲惫" -> "🌙"
    "低落" -> "☁️"
    "焦虑" -> "⏳"
    else -> "🍃"
}
