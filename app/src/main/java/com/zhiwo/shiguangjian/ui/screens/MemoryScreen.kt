package com.zhiwo.shiguangjian.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.ai.MergeItem
import com.zhiwo.shiguangjian.data.ai.SplitItem
import com.zhiwo.shiguangjian.data.ai.EvolveItem
import com.zhiwo.shiguangjian.ui.theme.*
import com.zhiwo.shiguangjian.ui.viewmodel.MemoryViewModel

@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel = viewModel(),
    onNavigateToReview: () -> Unit = {}
) {
    val context = LocalContext.current
    val memories by viewModel.memories.collectAsState()
    val analyzing by viewModel.analyzing.collectAsState()
    val isConfigured by viewModel.isConfigured.collectAsState()
    val analysisResult by viewModel.analysisResult.collectAsState()
    val applying by viewModel.applying.collectAsState()
    var memoryToDelete by remember { mutableStateOf<Long?>(null) }

    // 收集操作反馈消息
    LaunchedEffect(Unit) {
        viewModel.applyMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 删除确认对话框
    if (memoryToDelete != null) {
        AlertDialog(
            onDismissRequest = { memoryToDelete = null },
            title = { Text("删除记忆") },
            text = { Text("确定要删除这条记忆吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    memoryToDelete?.let { viewModel.deleteMemory(it) }
                    memoryToDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { memoryToDelete = null }) { Text("取消") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // 标题栏
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("我的记忆", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onNavigateToReview) {
                    Text("每日评价", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                }
            }
        }

        // AI 分析按钮
        item {
            Button(
                onClick = {
                    if (!isConfigured) {
                        Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (memories.size < 2) {
                        Toast.makeText(context, "至少需要2条记忆才能分析", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    viewModel.analyzeMemories()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                enabled = !analyzing && memories.size >= 2,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (analyzing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("分析中...", fontSize = 14.sp)
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AI分析合并/拆分记忆")
                }
            }
        }

        // 分析结果面板
        analysisResult?.let { result ->
            val hasAnyResult = result.merge.isNotEmpty() || result.split.isNotEmpty() || result.evolve.isNotEmpty()
            if (hasAnyResult) {
                // 一键全部应用按钮
                item {
                    Button(
                        onClick = { viewModel.applyAll() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        enabled = !applying,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (applying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("处理中...", fontSize = 14.sp)
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("一键全部应用")
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("分析结果", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)

                            if (result.merge.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Icon(Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("建议合并", style = MaterialTheme.typography.titleSmall)
                                result.merge.forEach { merge ->
                                    MergeItemCard(merge, memories, applying) { ids, merged ->
                                        viewModel.applyMerge(ids, merged)
                                    }
                                }
                            }

                            if (result.split.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("建议拆分", style = MaterialTheme.typography.titleSmall)
                                result.split.forEach { split ->
                                    SplitItemCard(split, memories, applying) { id, splits ->
                                        viewModel.applySplit(id, splits)
                                    }
                                }
                            }

                            if (result.evolve.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Icon(Icons.Default.Lightbulb, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("建议演化", style = MaterialTheme.typography.titleSmall)
                                result.evolve.forEach { evolve ->
                                    EvolveItemCard(evolve, memories, applying) { id, newContent ->
                                        viewModel.applyEvolve(id, newContent)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.clearAnalysisResult() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("关闭", fontSize = 13.sp) }
                        }
                    }
                }
            }
        }

        // 记忆列表
        if (memories.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(56.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("还没有记忆", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("完成每日评价后会自动提取记忆", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(memories, key = { it.id }) { memory ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).animateContentSize(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(memory.content, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(formatMemoryDate(memory.createdAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(
                                onClick = { memoryToDelete = memory.id },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MergeItemCard(
    merge: MergeItem,
    memories: List<com.zhiwo.shiguangjian.data.db.entity.MemoryEntity>,
    applying: Boolean,
    onApply: (List<Long>, String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            val memMap = memories.associateBy { it.id }
            val sourceTexts = merge.sourceIds.mapNotNull { memMap[it]?.content }
            Text(
                text = sourceTexts.joinToString(" + ") + " → ${merge.merged}",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface
            )
            Text(merge.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = { onApply(merge.sourceIds, merge.merged) }, enabled = !applying) {
                Text("应用", color = if (applying) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun SplitItemCard(
    split: SplitItem,
    memories: List<com.zhiwo.shiguangjian.data.db.entity.MemoryEntity>,
    applying: Boolean,
    onApply: (Long, List<String>) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            val sourceContent = memories.find { it.id == split.sourceId }?.content ?: ""
            Text(
                text = sourceContent + " → " + split.splits.joinToString(" / "),
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface
            )
            Text(split.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = { onApply(split.sourceId, split.splits) }, enabled = !applying) {
                Text("应用", color = if (applying) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun EvolveItemCard(
    evolve: EvolveItem,
    memories: List<com.zhiwo.shiguangjian.data.db.entity.MemoryEntity>,
    applying: Boolean,
    onApply: (Long, String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            val sourceContent = memories.find { it.id == evolve.sourceId }?.content ?: ""
            Text(
                text = sourceContent + " → ${evolve.newContent}",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface
            )
            Text(evolve.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = { onApply(evolve.sourceId, evolve.newContent) }, enabled = !applying) {
                Text("应用", color = if (applying) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        }
    }
}

fun formatMemoryDate(dateStr: String): String {
    return try {
        val parts = dateStr.take(10).split("-")
        if (parts.size == 3) "${parts[1].toInt()}/${parts[2].toInt()}" else dateStr.take(10)
    } catch (e: Exception) {
        dateStr.take(10)
    }
}
