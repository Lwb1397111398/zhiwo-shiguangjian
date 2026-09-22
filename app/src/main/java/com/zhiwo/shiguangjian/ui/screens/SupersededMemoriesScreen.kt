package com.zhiwo.shiguangjian.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.ui.viewmodel.MemoryViewModel

/**
 * 「已更正的记忆」二级页。
 *
 * 为什么单独一页：整理/对账不会物理删记忆，只会置 superseded。以前这些条目挤在记忆页折叠区里，
 * 只有"恢复"没有"删除"，用户点完整理仍旧看到它们，误以为整理没生效。这里给全三个动作：
 * 恢复、彻底删除（二次确认）、清空全部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupersededMemoriesScreen(
    onBack: () -> Unit = {},
    viewModel: MemoryViewModel = viewModel()
) {
    val context = LocalContext.current
    val superseded by viewModel.supersededMemories.collectAsState()
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    var showClearAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.applyMessage.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("彻底删除这条记忆？", color = MaterialTheme.colorScheme.error) },
            text = { Text("这条记忆已被新记忆取代。彻底删除后无法恢复（内容也不会留在任何列表里）。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSupersededForever(id)
                    pendingDeleteId = null
                }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("取消") } }
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("清空全部已更正记忆？", color = MaterialTheme.colorScheme.error) },
            text = { Text("将彻底删除 ${superseded.size} 条已更正记忆，无法恢复。\n如果只想清掉很旧的，取消后逐条删除更稳妥。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllSuperseded()
                    showClearAll = false
                }) { Text("全部删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearAll = false }) { Text("取消") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("已更正的记忆") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (superseded.isNotEmpty()) {
                        TextButton(onClick = { showClearAll = true }) {
                            Text("清空全部", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        }
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
            item {
                Text(
                    "整理只会把旧表述标为「已更正」，不会偷偷删掉你的历史。" +
                        "确认新记忆没问题后再逐条删除；超过 ${com.zhiwo.shiguangjian.data.memory.MemoryRetention.SUPERSEDED_KEEP} 条之外的最旧记录会在启动时自动归档后清掉。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (superseded.isEmpty()) {
                item {
                    Text("没有已更正的记忆", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(superseded, key = { it.id }) { memory ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                memory.content, fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textDecoration = TextDecoration.LineThrough, lineHeight = 21.sp
                            )
                            memory.note?.let {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("更正原因：$it", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                            memory.supersededBy?.let {
                                Text("已被记忆 #$it 取代", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                            Text(
                                "记于 ${formatMemoryDate(memory.occurredAt.ifBlank { memory.createdAt })}　" +
                                    "取代：${memory.createdAt.take(10)}",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { viewModel.restoreMemory(memory.id) }) {
                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("恢复", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                                TextButton(onClick = { pendingDeleteId = memory.id }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("彻底删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
