package com.zhiwo.shiguangjian.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.ui.theme.*
import com.zhiwo.shiguangjian.ui.viewmodel.OrganizeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeScreen(
    onBack: () -> Unit = {},
    viewModel: OrganizeViewModel = viewModel()
) {
    val context = LocalContext.current
    val completedRecords by viewModel.completedRecords.collectAsState()
    val oldRecords by viewModel.oldRecords.collectAsState()
    val reviews by viewModel.reviews.collectAsState()
    val isConfigured by viewModel.isConfigured.collectAsState()
    val aiLoading by viewModel.aiLoading.collectAsState()
    val aiStatus by viewModel.aiStatus.collectAsState()
    val sourceItemIds by viewModel.sourceItemIds.collectAsState()
    val sourceReviewIds by viewModel.sourceReviewIds.collectAsState()
    val cleanableItems by viewModel.cleanableItems.collectAsState()
    val memoryItems by viewModel.memoryItems.collectAsState()
    val evolveResults by viewModel.evolveResults.collectAsState()

    var expandedCompleted by remember { mutableStateOf(true) }
    var expandedOldRecords by remember { mutableStateOf(false) }
    var expandedReviews by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("整理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            // 说明卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        "整理已完成和过去的事情。AI帮你归并相关信息、判断去留，让记忆随时间演化升级。",
                        modifier = Modifier.padding(12.dp), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp
                    )
                }
            }

            // 一键整理按钮
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (!isConfigured) {
                            Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (completedRecords.isEmpty() && oldRecords.isEmpty() && reviews.isEmpty()) {
                            Toast.makeText(context, "暂无待整理项目", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        viewModel.autoOrganize()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    enabled = !aiLoading
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("一键整理", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // AI 操作按钮栏（单步操作）
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text("单步操作", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionButton("智能归并", Modifier.weight(1f), icon = Icons.Default.AutoAwesome) {
                        if (sourceItemIds.isEmpty() && sourceReviewIds.isEmpty()) {
                            Toast.makeText(context, "请先选择要归并的项目", Toast.LENGTH_SHORT).show()
                            return@ActionButton
                        }
                        if (!isConfigured) { Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show(); return@ActionButton }
                        viewModel.smartConsolidate()
                    }
                    ActionButton("智能分类", Modifier.weight(1f), icon = Icons.Default.Category) {
                        if (sourceItemIds.isEmpty() && sourceReviewIds.isEmpty()) {
                            Toast.makeText(context, "请先选择要分类的项目", Toast.LENGTH_SHORT).show()
                            return@ActionButton
                        }
                        if (!isConfigured) { Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show(); return@ActionButton }
                        viewModel.smartClassify()
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionButton("智慧清扫", Modifier.weight(1f), icon = Icons.Default.CleaningServices) {
                        if (cleanableItems.isEmpty()) {
                            Toast.makeText(context, "暂无待清扫项", Toast.LENGTH_SHORT).show()
                            return@ActionButton
                        }
                        if (!isConfigured) { Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show(); return@ActionButton }
                        viewModel.smartClean()
                    }
                    ActionButton("记忆演化", Modifier.weight(1f), icon = Icons.Default.HistoryEdu) {
                        if (!isConfigured) { Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show(); return@ActionButton }
                        viewModel.evolveMemories()
                    }
                }
            }

            // 加载提示
            if (aiLoading) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Text(aiStatus, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ===== 待整理区域 =====
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("待整理", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            }

            // 已完成记录组
            item {
                Spacer(modifier = Modifier.height(8.dp))
                ExpandableGroup(Icons.Default.CheckCircle, "已完成", completedRecords.size, expandedCompleted) { expandedCompleted = !expandedCompleted }
            }
            if (expandedCompleted) {
                if (completedRecords.isEmpty()) {
                    item { EmptyText("暂无") }
                } else {
                    items(completedRecords.take(20), key = { "comp-${it.id}" }) { record ->
                        SelectableItem(
                            title = record.title,
                            date = record.updatedAt.take(16),
                            isSelected = record.id in sourceItemIds,
                            onToggle = { viewModel.toggleSourceItem(record.id) }
                        )
                    }
                }
            }

            // 30天前记录组
            item {
                ExpandableGroup(Icons.Default.Edit, "30天前的记录", oldRecords.size, expandedOldRecords) { expandedOldRecords = !expandedOldRecords }
            }
            if (expandedOldRecords) {
                if (oldRecords.isEmpty()) {
                    item { EmptyText("暂无") }
                } else {
                    items(oldRecords.take(20), key = { "old-${it.id}" }) { record ->
                        SelectableItem(
                            title = record.title,
                            date = record.createdAt.take(16),
                            isSelected = record.id in sourceItemIds,
                            onToggle = { viewModel.toggleSourceItem(record.id) }
                        )
                    }
                }
            }

            // 评价组
            item {
                ExpandableGroup(Icons.Default.Star, "评价", reviews.size, expandedReviews) { expandedReviews = !expandedReviews }
            }
            if (expandedReviews) {
                if (reviews.isEmpty()) {
                    item { EmptyText("暂无") }
                } else {
                    items(reviews.take(20), key = { "rev-${it.id}" }) { review ->
                        SelectableItem(
                            title = "${if (review.type == "daily") "每日" else "每周"} · ${review.date}",
                            date = review.content.take(40),
                            isSelected = review.id in sourceReviewIds,
                            onToggle = { viewModel.toggleSourceReview(review.id) }
                        )
                    }
                }
            }

            // ===== 待清理面板 =====
            if (cleanableItems.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("待清理", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(" ${cleanableItems.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.outline)
                                .padding(horizontal = 8.dp, vertical = 1.dp))
                    }
                    Text("以下内容建议清理，可手动调整", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(cleanableItems.size, key = { cleanableItems[it] }) { index ->
                    val item = cleanableItems[index]
                    PanelItem(
                        text = item, markerColor = MaterialTheme.colorScheme.error,
                        onMove = { viewModel.moveToMemory(item) },
                        moveIcon = "🧠",
                        onRemove = { viewModel.removeCleanable(index) }
                    )
                }
                item {
                    Button(
                        onClick = { viewModel.confirmClean() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("确认清理选中项") }
                }
            }

            // ===== 待存入记忆面板 =====
            if (memoryItems.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("待存入记忆", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(" ${memoryItems.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.outline)
                                .padding(horizontal = 8.dp, vertical = 1.dp))
                    }
                    Text("以下内容将存入记忆", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(memoryItems.size, key = { memoryItems[it] }) { index ->
                    val item = memoryItems[index]
                    PanelItem(
                        text = item, markerColor = Success,
                        onMove = { viewModel.moveToCleanable(item) },
                        moveIcon = "🗑️",
                        onRemove = { viewModel.removeMemory(index) }
                    )
                }
                item {
                    Button(
                        onClick = {
                            viewModel.confirmSaveMemories()
                            Toast.makeText(context, "已存入记忆！", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("确认存入记忆") }
                }
            }

            // ===== 记忆演化面板 =====
            if (evolveResults.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("记忆演化结果", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("AI建议的记忆升级，请确认", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(evolveResults.size, key = { "${evolveResults[it].old}_${evolveResults[it].new}" }) { index ->
                    val item = evolveResults[index]
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.old, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = TextDecoration.LineThrough)
                        Text(" → ", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        Text(item.new, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.removeEvolve(index) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                item {
                    Button(
                        onClick = {
                            viewModel.confirmEvolve()
                            Toast.makeText(context, "记忆已演化升级！", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("确认演化") }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.primary
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ExpandableGroup(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, count: Int,
    expanded: Boolean, onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Badge { Text("$count") }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SelectableItem(
    title: String, date: String,
    isSelected: Boolean, onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface)
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = isSelected, onCheckedChange = { onToggle() },
            modifier = Modifier.size(20.dp), colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(date, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PanelItem(
    text: String,
    markerColor: androidx.compose.ui.graphics.Color,
    onMove: () -> Unit,
    moveIcon: String,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(markerColor.copy(alpha = 0.85f)))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(4.dp))
        Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline).clickable { onMove() },
            contentAlignment = Alignment.Center) { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp)) }
        Spacer(modifier = Modifier.width(4.dp))
        Box(modifier = Modifier.size(28.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f)).clickable { onRemove() },
            contentAlignment = Alignment.Center) { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
fun EmptyText(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
}
