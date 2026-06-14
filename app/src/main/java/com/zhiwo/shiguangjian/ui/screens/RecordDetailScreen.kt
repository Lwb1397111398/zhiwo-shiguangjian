package com.zhiwo.shiguangjian.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.db.entity.KeyInfoEntity
import com.zhiwo.shiguangjian.data.db.entity.TagEntity
import com.zhiwo.shiguangjian.ui.components.CategoryChip
import com.zhiwo.shiguangjian.ui.components.TaskItem
import com.zhiwo.shiguangjian.ui.theme.*
import com.zhiwo.shiguangjian.ui.viewmodel.RecordListViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordDetailScreen(
    recordId: Long,
    onBack: () -> Unit = {},
    viewModel: RecordListViewModel = viewModel()
) {
    val context = LocalContext.current
    // 使用remember缓存StateFlow，避免每次recomposition重新创建导致闪烁
    val recordFlow = remember(recordId) { viewModel.getRecord(recordId) }
    val tasksFlow = remember { viewModel.tasks }
    val keyInfosFlow = remember(recordId) { viewModel.getKeyInfosForRecord(recordId) }
    val tagsFlow = remember(recordId) { viewModel.getTagsForRecord(recordId) }

    val record by recordFlow.collectAsState(initial = null)
    val tasks by tasksFlow.collectAsState()
    val keyInfos by keyInfosFlow.collectAsState(initial = emptyList())
    val tags by tagsFlow.collectAsState(initial = emptyList())

    var showDeleteDialog by remember(recordId) { mutableStateOf(false) }
    var showAddTag by remember(recordId) { mutableStateOf(false) }
    var newTagText by remember(recordId) { mutableStateOf("") }

    if (record == null) {
        // 记录不存在或正在加载
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))
                Text("加载中...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
        }
    } else {
        val rec = record!!
        val recordTasks = tasks.filter { it.recordId == rec.id }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("记录详情") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding),
                contentPadding = PaddingValues(16.dp)
            ) {
                // ===== 标题和分类 =====
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CategoryChip(category = rec.category)
                                Text(
                                    text = formatTime(rec.createdAt),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = rec.title,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 28.sp
                            )
                        }
                    }
                }

                // ===== 原始内容 =====
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    SectionCard(title = "原始内容") {
                        Text(
                            text = rec.content,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 24.sp
                        )
                    }
                }

                // ===== 摘要 =====
                if (rec.summary.isNotBlank()) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionCard(title = "摘要") {
                            Text(
                                text = rec.summary,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 20.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                    }
                }

                // ===== 关键信息 =====
                if (keyInfos.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionCard(title = "关键信息") {
                            keyInfos.forEachIndexed { index, info ->
                                Text(
                                    text = "💡 ${info.content}",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                                if (index < keyInfos.lastIndex) {
                                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outline))
                                }
                            }
                        }
                    }
                }

                // ===== 相关任务 =====
                if (recordTasks.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionCard(title = "相关任务") {
                            recordTasks.forEach { task ->
                                TaskItem(
                                    task = task,
                                    onComplete = { viewModel.completeTask(task.id) },
                                    onUncomplete = { viewModel.uncompleteTask(task.id) }
                                )
                            }
                        }
                    }
                }

                // ===== 标签 =====
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    SectionCard(title = "标签") {
                        if (tags.isNotEmpty()) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                tags.forEach { tag ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(MaterialTheme.colorScheme.primary)
                                            .clickable {
                                                viewModel.removeTagFromRecord(rec.id, tag.id)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = tag.name,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = "×",
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (showAddTag) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = newTagText,
                                    onValueChange = { newTagText = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("输入标签名", fontSize = 13.sp) },
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                    ),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                                )
                                TextButton(onClick = {
                                    if (newTagText.isBlank()) return@TextButton
                                    viewModel.addTagToRecord(rec.id, newTagText.trim())
                                    newTagText = ""
                                    showAddTag = false
                                }) {
                                    Text("添加", color = MaterialTheme.colorScheme.primary)
                                }
                                TextButton(onClick = {
                                    newTagText = ""
                                    showAddTag = false
                                }) {
                                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            TextButton(onClick = { showAddTag = true }) {
                                Text("+ 添加标签", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // 底部间距
                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }

        // 删除确认对话框
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("确认删除") },
                text = { Text("确定要删除这条记录吗？此操作不可恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteDialog = false
                        // 先异步删除，再返回上一页
                        viewModel.deleteRecord(
                            id = rec.id,
                            onSuccess = {
                                onBack()
                            },
                            onError = { error ->
                                Toast.makeText(context, "删除失败：$error", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }
    } // else 结束
}

// ========== 辅助组件 ==========

@Composable
fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outline).padding(bottom = 8.dp))
            content()
        }
    }
}

fun formatTime(isoDate: String): String {
    return try {
        isoDate.take(16).replace("T", " ")
    } catch (e: Exception) {
        isoDate.take(10)
    }
}

