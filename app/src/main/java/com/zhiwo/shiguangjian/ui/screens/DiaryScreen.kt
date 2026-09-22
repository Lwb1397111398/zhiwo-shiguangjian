package com.zhiwo.shiguangjian.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import com.zhiwo.shiguangjian.ui.viewmodel.DiaryViewModel
import kotlinx.coroutines.launch

/** 情绪 → 色板（集中定义，避免散落硬编码） */
val MoodColors = mapOf(
    "开心" to 0xFFFFB84D, "平淡" to 0xFF7FC8A9, "低落" to 0xFF8CA3B8,
    "焦虑" to 0xFFF28C6B, "充实" to 0xFF6FBF8B, "疲惫" to 0xFFA79BB8,
    "感动" to 0xFFF2A6B8, "释然" to 0xFF8BC4E0
)

fun moodColor(mood: String): Color = Color(MoodColors[mood] ?: 0xFFB8C4CE)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DiaryScreen(
    onNavigateToReview: () -> Unit = {},
    viewModel: DiaryViewModel = viewModel()
) {
    val context = LocalContext.current
    val diaries by viewModel.diaries.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val moodFilter by viewModel.moodFilter.collectAsState()
    val regeneratingId by viewModel.regenerating.collectAsState()

    var editingDiary by remember { mutableStateOf<DiaryEntity?>(null) }
    var diaryToDelete by remember { mutableStateOf<DiaryEntity?>(null) }
    var readingDiary by remember { mutableStateOf<DiaryEntity?>(null) }
    var showExportAllMenu by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    val allDiaries by viewModel.allDiaries.collectAsState()

    LaunchedEffect(Unit) {
        launch { viewModel.exportMessage.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
        launch { viewModel.cleanupMessage.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    }

    // ===== 编辑对话框 =====
    editingDiary?.let { diary ->
        var text by remember(diary.id) { mutableStateOf(diary.content) }
        AlertDialog(
            onDismissRequest = { editingDiary = null },
            title = { Text("编辑日记") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                    maxLines = 12
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateDiaryContent(diary, text) { editingDiary = null }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingDiary = null }) { Text("取消") } }
        )
    }

    // ===== 删除确认 =====
    diaryToDelete?.let { diary ->
        AlertDialog(
            onDismissRequest = { diaryToDelete = null },
            title = { Text("删除日记") },
            text = { Text("确定删除 ${diary.date} 的日记吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDiary(diary.id); diaryToDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { diaryToDelete = null }) { Text("取消") } }
        )
    }

    // ===== 全部导出格式选择 =====
    if (showExportAllMenu) {
        AlertDialog(
            onDismissRequest = { showExportAllMenu = false },
            title = { Text("导出全部日记") },
            text = { Text("选择导出格式：Markdown 适合笔记软件归档，纯文本通用性最好。") },
            confirmButton = {
                TextButton(onClick = { viewModel.exportAllDiaries(asMarkdown = true); showExportAllMenu = false }) { Text("Markdown (.md)") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.exportAllDiaries(asMarkdown = false); showExportAllMenu = false }) { Text("纯文本 (.txt)") }
            }
        )
    }

    // ===== 手动新建日记 =====
    if (showCreateDialog) {
        var date by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
        var mood by remember { mutableStateOf("平淡") }
        var content by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("补写日记") },
            text = {
                Column {
                    OutlinedTextField(
                        value = date, onValueChange = { date = it },
                        label = { Text("日期（yyyy-MM-dd）") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        viewModel.moods.forEach { m ->
                            FilterChip(
                                selected = mood == m, onClick = { mood = m },
                                label = { Text(m, fontSize = 12.sp) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = content, onValueChange = { content = it },
                        label = { Text("今天想写点什么…") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createDiary(date, mood, content) { ok, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        if (ok) showCreateDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("取消") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ===== 顶栏 =====
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("我的日记", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { showCreateDialog = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Add, contentDescription = "补写日记", modifier = Modifier.size(22.dp))
            }
            TextButton(onClick = { viewModel.cleanupExportedDiaries() }) {
                Text("清理已导出", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showExportAllMenu = true }) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("全部导出", fontSize = 13.sp)
            }
            TextButton(onClick = onNavigateToReview) {
                Text("每日评价", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
        }

        // ===== 那年今日 =====
        if (searchQuery.isBlank() && moodFilter.isBlank()) {
            val today = java.time.LocalDate.now()
            val suffix = "-${today.monthValue.toString().padStart(2, '0')}-${today.dayOfMonth.toString().padStart(2, '0')}"
            val onThisDay = allDiaries.filter { it.date.endsWith(suffix) && it.date != today.toString() }
            if (onThisDay.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("🕰 那年今日", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        onThisDay.take(2).forEach { past ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clickable { readingDiary = past },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(moodColor(past.mood)))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "${past.date.take(4)}年的今天：${past.content.take(40)}…",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ===== 统计条 =====
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("共 ${stats.total} 篇", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text("连续 ${stats.streakDays} 天", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1.2f))
                Column(modifier = Modifier.weight(1.8f)) {
                    Text("近30天情绪", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(3.dp))
                    Row {
                        stats.last30MoodCounts.entries.sortedByDescending { it.value }.take(5).forEach { (mood, count) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(moodColor(mood)))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("$count", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (stats.last30MoodCounts.isEmpty()) {
                            Text("暂无", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ===== 情绪筛选 =====
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            viewModel.moods.forEach { mood ->
                FilterChip(
                    selected = moodFilter == mood,
                    onClick = { viewModel.setMoodFilter(mood) },
                    label = { Text(mood, fontSize = 12.sp) },
                    leadingIcon = {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(moodColor(mood)))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = moodColor(mood).copy(alpha = 0.25f)
                    )
                )
            }
        }

        // ===== 搜索框 =====
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.searchDiaries(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            placeholder = { Text("搜索日记内容", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { viewModel.searchDiaries("") }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            },
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall
        )

        // ===== 日记列表（按月分组 + 吸顶） =====
        if (diaries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无日记\n完成每日评价后会自动生成", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        } else {
            val grouped = diaries.groupBy { it.date.take(7) }  // "yyyy-MM"
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
            ) {
                grouped.forEach { (month, monthDiaries) ->
                    stickyHeader(key = "header-$month") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Text(
                                formatMonthTitle(month),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                    items(monthDiaries, key = { it.id }) { diary ->
                        DiaryCard(diary = diary, onClick = { readingDiary = diary })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    // ===== 沉浸阅读视图 =====
    readingDiary?.let { diary ->
        ModalBottomSheet(
            onDismissRequest = { readingDiary = null },
            containerColor = MaterialTheme.colorScheme.background
        ) {
            ReadingView(
                diary = diary,
                regenerating = regeneratingId == diary.id,
                onEdit = { editingDiary = diary; readingDiary = null },
                onDelete = { diaryToDelete = diary; readingDiary = null },
                onExport = { asMd -> viewModel.exportDiary(diary, asMarkdown = asMd) },
                onShare = {
                    try {
                        val file = com.zhiwo.shiguangjian.data.diary.DiaryShareCard.render(context, diary)
                        context.startActivity(
                            android.content.Intent.createChooser(
                                com.zhiwo.shiguangjian.data.diary.DiaryShareCard.shareIntent(context, file),
                                "分享日记图片"
                            )
                        )
                    } catch (e: Throwable) {
                        Toast.makeText(context, "生成分享图片失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                onRegenerate = {
                    if (diary.isUserEdited) {
                        Toast.makeText(context, "这篇日记你手动编辑过，不做覆盖；如需重写请先删除", Toast.LENGTH_LONG).show()
                    } else {
                        viewModel.regenerateDiary(diary) { _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }
}

private fun formatMonthTitle(yyyyMm: String): String = try {
    val parts = yyyyMm.split("-")
    "${parts[0]}年${parts[1].toInt()}月"
} catch (_: Throwable) { yyyyMm }

/** 列表卡片：左侧情绪色条 + 日期/情绪标签/摘要 */
@Composable
private fun DiaryCard(diary: DiaryEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
    ) {
        Box(modifier = Modifier.width(5.dp).heightIn(min = 88.dp).background(moodColor(diary.mood)))
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(diary.date, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(moodColor(diary.mood).copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(diary.mood, fontSize = 11.sp, color = Color(0xFF3D4A54))
                }
                Spacer(modifier = Modifier.weight(1f))
                when {
                    diary.isUserEdited -> Text("已编辑", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    diary.generationVersion > 1 -> Text("v${diary.generationVersion}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                diary.content,
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )
        }
    }
}

/** 沉浸阅读：情绪 + 日期头部、正文排版、底部操作 */
@Composable
private fun ReadingView(
    diary: DiaryEntity,
    regenerating: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: (Boolean) -> Unit,
    onRegenerate: () -> Unit,
    onShare: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(moodColor(diary.mood)))
            Spacer(modifier = Modifier.width(8.dp))
            Text(diary.mood, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.weight(1f))
            Text(diary.date, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(modifier = Modifier.height(16.dp))
        if (regenerating) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("正在重新生成...", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            diary.content,
            fontSize = 15.sp, lineHeight = 26.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("编辑", fontSize = 12.sp)
            }
            OutlinedButton(onClick = onRegenerate, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), enabled = !regenerating) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("重新生成", fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onExport(true) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Text("导出 .md", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { onExport(false) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Text("导出 .txt", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("分享图片", fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("删除这篇日记", fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            if (diary.isUserEdited) "这篇日记经过你的手动编辑，重新生成已被保护"
            else "重新生成会基于当前有效记忆和当日记录覆盖本篇",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
