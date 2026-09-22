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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
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
import com.zhiwo.shiguangjian.data.db.entity.OrganizeOpEntity
import com.zhiwo.shiguangjian.data.organizeops.OpPayloads
import com.zhiwo.shiguangjian.data.organizeops.OpStatus
import com.zhiwo.shiguangjian.data.organizeops.OpType
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
    val ops by viewModel.ops.collectAsState()
    val unfinishedOps by viewModel.unfinishedOps.collectAsState()
    val canUndoDismiss by viewModel.canUndoDismiss.collectAsState()
    val batchApplying by viewModel.batchApplying.collectAsState()

    var expandedCompleted by remember { mutableStateOf(true) }
    var expandedOldRecords by remember { mutableStateOf(false) }
    var expandedReviews by remember { mutableStateOf(false) }
    var expandedSources by remember { mutableStateOf(true) }

    // 级联取消确认：取消存记忆/演化提案时，存在依赖它的删除提案需要用户决策
    var cascadeOpId by remember { mutableStateOf<String?>(null) }
    // "只删不存"最终数据丢失警示
    var forceDeleteConfirmId by remember { mutableStateOf<String?>(null) }

    cascadeOpId?.let { opId ->
        val dependents = viewModel.dependentsOf(opId).filter { it.checked }
        AlertDialog(
            onDismissRequest = { cascadeOpId = null },
            title = { Text("取消这条提案？") },
            text = {
                if (dependents.isNotEmpty()) {
                    Text("有 ${dependents.size} 条「清理源数据」提案依赖它。\n\n建议同步取消勾选下游清理项；如果确认这条内容不值得保留为记忆、只想删掉源数据，请选择「只删不存」。")
                } else {
                    Text("确定取消勾选这条提案吗？")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dependents.isNotEmpty()) {
                        viewModel.uncheckWithCascade(opId)
                    } else {
                        viewModel.toggleChecked(opId)
                    }
                    cascadeOpId = null
                }) { Text(if (dependents.isNotEmpty()) "同步取消下游" else "确定") }
            },
            dismissButton = {
                if (dependents.isNotEmpty()) {
                    TextButton(onClick = {
                        forceDeleteConfirmId = opId
                        cascadeOpId = null
                    }) { Text("只删不存", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { cascadeOpId = null }) { Text("返回") }
                }
            }
        )
    }

    forceDeleteConfirmId?.let { opId ->
        AlertDialog(
            onDismissRequest = { forceDeleteConfirmId = null },
            title = { Text("确认只删不存？", color = MaterialTheme.colorScheme.error) },
            text = {
                Text("将直接删除关联的源记录/评价，且不会保存对应的记忆。\n\n此操作执行后数据无法找回，确定继续吗？")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forceUncheckSave(opId)
                    forceDeleteConfirmId = null
                    Toast.makeText(context, "已标记为只删不存，执行时将直接删除源数据", Toast.LENGTH_LONG).show()
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { forceDeleteConfirmId = null }) { Text("取消") }
            }
        )
    }

    val pendingCount = ops.count { it.status == OpStatus.PENDING && it.checked }
    // 未完成的整理：跨批次的所有非终态提案（老版本只认最新批次，上一批遗留的提案永不见天日）
    val currentBatchIds = ops.map { it.id }.toSet()
    val orphanOps = unfinishedOps.filterNot { it.id in currentBatchIds }

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
                        "AI 先生成整理提案，你逐条审核、修改后再统一执行。生成提案阶段不会改动任何数据。",
                        modifier = Modifier.padding(12.dp), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp
                    )
                }
            }

            // 一键整理（生成提案）按钮
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
                        viewModel.generateProposals()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    enabled = !aiLoading && !batchApplying
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("一键整理（生成提案）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // 单步操作（同样只生成提案）
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text("单步生成提案", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionButton("智能归并", Modifier.weight(1f), icon = Icons.Default.AutoAwesome) {
                        if (sourceItemIds.isEmpty() && sourceReviewIds.isEmpty()) {
                            Toast.makeText(context, "请先选择项目", Toast.LENGTH_SHORT).show(); return@ActionButton
                        }
                        if (!isConfigured) { Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show(); return@ActionButton }
                        viewModel.consolidateProposals()
                    }
                    ActionButton("智能分类", Modifier.weight(1f), icon = Icons.Default.Category) {
                        if (sourceItemIds.isEmpty() && sourceReviewIds.isEmpty()) {
                            Toast.makeText(context, "请先选择项目", Toast.LENGTH_SHORT).show(); return@ActionButton
                        }
                        if (!isConfigured) { Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show(); return@ActionButton }
                        viewModel.classifyProposals()
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionButton("清扫复审", Modifier.weight(1f), icon = Icons.Default.CleaningServices) {
                        if (!isConfigured) { Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show(); return@ActionButton }
                        viewModel.smartCleanProposals()
                    }
                    ActionButton("记忆演化", Modifier.weight(1f), icon = Icons.Default.HistoryEdu) {
                        if (!isConfigured) { Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show(); return@ActionButton }
                        viewModel.evolveProposals()
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
            } else if (aiStatus.isNotBlank()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(aiStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ===== 未完成的整理（跨批次常驻：PENDING / FAILED / STALE / BLOCKED）=====
            if (orphanOps.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HistoryEdu, contentDescription = null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("未完成的整理（${orphanOps.size}）", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.weight(1f))
                        if (canUndoDismiss) {
                            TextButton(onClick = { viewModel.undoDismiss() }) { Text("撤销忽略", fontSize = 12.sp) }
                        }
                    }
                    Text(
                        "这些是之前几批没跑完的提案，不会被自动忽略；处理完它们之前，被整理过的内容会一直挂着。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                items(orphanOps, key = { "unfinished-${it.id}" }) { op ->
                    UnfinishedOpRow(
                        op = op,
                        onRun = { viewModel.runSingleOp(op.id) },
                        onRetry = { viewModel.retryOp(op.id) },
                        onDiscard = { viewModel.discardOp(op.id) }
                    )
                }
            } else if (canUndoDismiss) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.undoDismiss() }) { Text("撤销刚才的忽略", fontSize = 12.sp) }
                }
            }

            // ===== 提案审核区 =====
            if (ops.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("整理提案（本批待执行 $pendingCount 条）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.weight(1f))
                        if (ops.any { it.status == OpStatus.APPLIED || it.status == OpStatus.DISMISSED }) {
                            TextButton(onClick = { viewModel.clearFinishedOps() }) {
                                Text("清理已完结", fontSize = 12.sp)
                            }
                        }
                    }
                    Text(
                        "勾选要在\"全部同意\"时执行的提案；点击提案可编辑内容",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 分组：演化 → 存记忆 → 清理
                val grouped = ops.sortedBy { typeRank(it.type) }
                items(grouped, key = { it.id }) { op ->
                    OpCard(
                        op = op,
                        onToggleChecked = {
                            // 取消勾选存记忆/演化类提案时走级联确认；其余直接切换
                            if (!op.checked) {
                                viewModel.toggleChecked(op.id)
                            } else if (op.type == OpType.SAVE_MEMORY || op.type == OpType.EVOLVE_MEMORY) {
                                cascadeOpId = op.id
                            } else {
                                viewModel.toggleChecked(op.id)
                            }
                        },
                        onEdit = { text -> viewModel.editOpText(op.id, text) },
                        onDismiss = { viewModel.dismissOp(op.id) },
                        onRestore = { viewModel.restorePreview(op.id) },
                        onRetry = { viewModel.retryOp(op.id) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // 全部同意按钮
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    val runnableCount = unfinishedOps.count { it.checked }
                    Button(
                        onClick = { viewModel.applyAllChecked() },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        enabled = !batchApplying && runnableCount > 0
                    ) {
                        if (batchApplying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在执行...", fontSize = 15.sp)
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("全部同意（执行勾选项 $runnableCount 条）", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ===== 待整理源选择区 =====
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expandedSources = !expandedSources },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("待整理", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(if (expandedSources) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (expandedSources) {
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
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

private fun typeRank(type: String): Int = OpType.executionOrder().indexOf(type).let { if (it < 0) 99 else it }

/** 单条提案卡片：勾选 + 内容展示/编辑 + 状态 + 操作 */
@Composable
private fun OpCard(
    op: OrganizeOpEntity,
    onToggleChecked: () -> Unit,
    onEdit: (String) -> Unit,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onRetry: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var editText by remember(op.payloadJson) { mutableStateOf(extractEditableText(op)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (op.status) {
                OpStatus.APPLIED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // 首行：勾选 + 类型 + 状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = op.checked,
                    onCheckedChange = { onToggleChecked() },
                    enabled = op.status == OpStatus.PENDING,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(typeLabel(op.type), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold)
                if (op.payloadJson != op.previewJson && op.previewJson != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("已修改", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
                StatusBadge(op.status)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 内容区
            when (op.type) {
                OpType.EVOLVE_MEMORY -> {
                    val payload = OpPayloads.decodeEvolveMemory(op.payloadJson)
                    if (payload != null) {
                        Text(payload.oldText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = TextDecoration.LineThrough)
                        Text("↓", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text(payload.newText, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium)
                    }
                }
                OpType.SAVE_MEMORY -> {
                    val payload = OpPayloads.decodeSaveMemory(op.payloadJson)
                    if (payload != null) {
                        Text(payload.content, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium)
                        if (payload.sourceDate.isNotBlank()) {
                            Text("来源日期：${payload.sourceDate}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OpType.DELETE_SOURCE -> {
                    val payload = OpPayloads.decodeDeleteSource(op.payloadJson)
                    if (payload != null) {
                        Text(payload.label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "将清理 ${payload.recordIds.size} 条记录、${payload.reviewIds.size} 条评价",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OpType.MERGE_MEMORY -> {
                    val payload = OpPayloads.decodeMergeMemory(op.payloadJson)
                    if (payload != null) {
                        Text("${payload.memoryIds.size} 条重复记忆合并为一条", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("↓", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text(payload.mergedContent, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium)
                        Text("旧记忆只标记为已更正，不会偷偷删除", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OpType.SPLIT_MEMORY -> {
                    val payload = OpPayloads.decodeSplitMemory(op.payloadJson)
                    if (payload != null) {
                        Text("记忆#${payload.memoryId} 拆成 ${payload.parts.size} 条", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        payload.parts.forEach { Text("· $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface) }
                    }
                }
                OpType.DELETE_MEMORY -> {
                    val payload = OpPayloads.decodeDeleteMemory(op.payloadJson)
                    if (payload != null) {
                        Text("彻底删除记忆#${payload.memoryId}", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                        Text("物理删除，执行后无法恢复", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // 错误/过期信息
            op.error?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (op.status == OpStatus.STALE) "⚠ 源数据已变化：$it（请重新生成提案）" else "✗ $it",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.error
                )
            }

            // 操作行
            if (op.status == OpStatus.PENDING) {
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    if (isEditableOp(op)) {
                        TextButton(onClick = {
                            if (editing) onEdit(editText)
                            editing = !editing
                        }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(if (editing) "保存" else "编辑", fontSize = 12.sp)
                        }
                        if (op.payloadJson != op.previewJson && op.previewJson != null) {
                            TextButton(onClick = onRestore, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("恢复原建议", fontSize = 12.sp)
                            }
                        }
                    }
                    TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("忽略", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (editing && isEditableOp(op)) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        minLines = 2,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            } else if (op.status == OpStatus.FAILED || op.status == OpStatus.STALE || op.status == OpStatus.BLOCKED) {
                // 失败/源已变化/被阻塞都能在卡片上直接再试一次（执行器会重新校验，不会盲跑旧提案）
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重试", fontSize = 12.sp)
                    }
                    TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("忽略", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun extractEditableText(op: OrganizeOpEntity): String = when (op.type) {
    OpType.SAVE_MEMORY -> OpPayloads.decodeSaveMemory(op.payloadJson)?.content ?: ""
    OpType.EVOLVE_MEMORY -> OpPayloads.decodeEvolveMemory(op.payloadJson)?.newText ?: ""
    OpType.MERGE_MEMORY -> OpPayloads.decodeMergeMemory(op.payloadJson)?.mergedContent ?: ""
    OpType.SPLIT_MEMORY -> OpPayloads.decodeSplitMemory(op.payloadJson)?.parts?.firstOrNull() ?: ""
    else -> ""
}

/** 只有"要写进记忆库的文本"可编辑；删除类提案一律不可编辑 */
private fun isEditableOp(op: OrganizeOpEntity): Boolean = when (op.type) {
    OpType.SAVE_MEMORY, OpType.EVOLVE_MEMORY, OpType.MERGE_MEMORY, OpType.SPLIT_MEMORY -> true
    else -> false
}

@Composable
private fun StatusBadge(status: String) {
    val (text, color) = when (status) {
        OpStatus.PENDING -> "待审核" to MaterialTheme.colorScheme.primary
        OpStatus.APPLIED -> "已执行" to Success
        OpStatus.DISMISSED -> "已忽略" to MaterialTheme.colorScheme.onSurfaceVariant
        OpStatus.FAILED -> "失败" to MaterialTheme.colorScheme.error
        OpStatus.STALE -> "源已变化" to MaterialTheme.colorScheme.error
        OpStatus.BLOCKED -> "已阻止" to MaterialTheme.colorScheme.tertiary
        else -> status to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text, fontSize = 10.sp, color = color,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

private fun typeLabel(type: String): String = when (type) {
    OpType.EVOLVE_MEMORY -> "记忆演化"
    OpType.SAVE_MEMORY -> "存入记忆"
    OpType.DELETE_SOURCE -> "清理源数据"
    OpType.MERGE_MEMORY -> "合并记忆"
    OpType.SPLIT_MEMORY -> "拆分记忆"
    OpType.DELETE_MEMORY -> "彻底删除记忆"
    else -> type
}

/** 「未完成的整理」单行：类型 / 状态 / 时间 / 失败原因 + 继续执行、重试、丢弃 */
@Composable
private fun UnfinishedOpRow(
    op: OrganizeOpEntity,
    onRun: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(typeLabel(op.type), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(op.status)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                        .format(java.util.Date(op.createdAt)),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                opBrief(op),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
            )
            op.error?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text("原因：$it", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (op.status == OpStatus.PENDING || op.status == OpStatus.BLOCKED) {
                    TextButton(onClick = onRun, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("继续执行", fontSize = 12.sp)
                    }
                }
                if (op.status == OpStatus.FAILED || op.status == OpStatus.STALE || op.status == OpStatus.BLOCKED) {
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重试", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = onDiscard, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("丢弃", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun opBrief(op: OrganizeOpEntity): String = when (op.type) {
    OpType.SAVE_MEMORY -> OpPayloads.decodeSaveMemory(op.payloadJson)?.content ?: ""
    OpType.EVOLVE_MEMORY -> OpPayloads.decodeEvolveMemory(op.payloadJson)
        ?.let { "${it.oldText} → ${it.newText}" } ?: ""
    OpType.DELETE_SOURCE -> OpPayloads.decodeDeleteSource(op.payloadJson)
        ?.let { "${it.label}（${it.recordIds.size} 条记录 / ${it.reviewIds.size} 条评价）" } ?: ""
    OpType.MERGE_MEMORY -> OpPayloads.decodeMergeMemory(op.payloadJson)
        ?.let { "${it.memoryIds.size} 条记忆 → ${it.mergedContent}" } ?: ""
    OpType.SPLIT_MEMORY -> OpPayloads.decodeSplitMemory(op.payloadJson)
        ?.let { "记忆#${it.memoryId} → ${it.parts.joinToString(" / ")}" } ?: ""
    OpType.DELETE_MEMORY -> OpPayloads.decodeDeleteMemory(op.payloadJson)
        ?.let { "彻底删除记忆#${it.memoryId}" } ?: ""
    else -> op.type
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
fun EmptyText(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
}
