package com.zhiwo.shiguangjian.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.profile.BlocklistCategory
import com.zhiwo.shiguangjian.data.profile.PersonalityTrait
import com.zhiwo.shiguangjian.data.profile.ProfileBlocklist
import com.zhiwo.shiguangjian.data.profile.RecentState
import com.zhiwo.shiguangjian.data.profile.UserProfileEditor
import com.zhiwo.shiguangjian.ui.viewmodel.UserProfileViewModel
import com.zhiwo.shiguangjian.ui.viewmodel.UserProfileViewModel.TextField as ProfileTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    viewModel: UserProfileViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val draft by viewModel.draft.collectAsState()
    val blocklist by viewModel.blocklist.collectAsState()
    val autoUpdateEnabled by viewModel.autoUpdateEnabled.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val dirty by viewModel.dirty.collectAsState()
    val error by viewModel.error.collectAsState()
    var blocklistExpanded by remember { mutableStateOf(false) }

    var showDiscardDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    // 文本编辑对话框
    var textDialog by remember { mutableStateOf<TextDialogState?>(null) }
    // 性格编辑
    var personalityDialog by remember { mutableStateOf<PersonalityDialogState?>(null) }
    // 近期状态
    var recentDialog by remember { mutableStateOf<RecentDialogState?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is com.zhiwo.shiguangjian.data.profile.UserProfileEditorSession.Event.Message -> {
                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                }
                com.zhiwo.shiguangjian.data.profile.UserProfileEditorSession.Event.SavedAndExit,
                com.zhiwo.shiguangjian.data.profile.UserProfileEditorSession.Event.ClearedAndExit -> onBack()
            }
        }
    }

    fun requestBack() {
        if (saving) return // 保存中不允许返回造成状态不明
        if (dirty) showDiscardDialog = true else onBack()
    }


    BackHandler(enabled = true) { requestBack() }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { if (!saving) showDiscardDialog = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("你有未保存的修改，离开后将丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    // 先清 dirty 再返回，避免 BackHandler 再次拦截
                    viewModel.discardChanges()
                    onBack()
                }) { Text("放弃修改") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空用户画像？") },
            text = {
                Text("这会删除 AI 当前保存的个性化信息，且这些条目不会再被自动学习。不会删除记录、日记、评价和普通记忆。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearProfile()
                    },
                    enabled = !saving,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    textDialog?.let { state ->
        TextEditDialog(
            title = state.title,
            initial = state.initial,
            onDismiss = { textDialog = null },
            onConfirm = { value ->
                if (state.index == null) {
                    viewModel.addText(state.field, value)
                } else {
                    viewModel.updateText(state.field, state.index, value)
                }
                textDialog = null
            }
        )
    }

    personalityDialog?.let { state ->
        PersonalityEditDialog(
            initialTrait = state.initialTrait,
            initialConfidence = state.initialConfidence,
            onDismiss = { personalityDialog = null },
            onConfirm = { trait, conf ->
                if (state.index == null) viewModel.addPersonality(trait, conf)
                else viewModel.updatePersonality(state.index, trait, conf)
                personalityDialog = null
            }
        )
    }

    recentDialog?.let { state ->
        RecentEditDialog(
            initialContent = state.initialContent,
            initialDays = state.initialDays,
            onDismiss = { recentDialog = null },
            onConfirm = { content, days ->
                if (state.index == null) viewModel.addRecent(content, days)
                else viewModel.updateRecent(state.index, content, days)
                recentDialog = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("用户画像") },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.save(exitAfter = true) },
                        enabled = !saving && !loading
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Save, contentDescription = "保存画像")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            item {
                Text(
                    "画像由日常记录逐步形成，你可以随时修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动更新画像", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            if (autoUpdateEnabled)
                                "每日评价时，AI 会根据记录补充画像"
                            else
                                "仅手动维护，AI 不会修改画像",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoUpdateEnabled,
                        onCheckedChange = { viewModel.setAutoUpdateEnabled(it) },
                        enabled = !saving
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            error?.let { msg ->
                item {
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            // 稳定信息
            item {
                ProfileSectionHeader(
                    title = "稳定信息",
                    count = draft.stableFacts.size,
                    max = UserProfileEditor.MAX_LIST,
                    canAdd = draft.stableFacts.size < UserProfileEditor.MAX_LIST && !saving,
                    onAdd = {
                        textDialog = TextDialogState("添加稳定信息", ProfileTextField.STABLE_FACTS, null, "")
                    }
                )
            }
            if (draft.stableFacts.isEmpty()) {
                item { ProfileEmptyHint("暂无稳定信息") }
            } else {
                itemsIndexed(draft.stableFacts, key = { i, v -> "sf-$i-$v" }) { index, text ->
                    TextItemRow(
                        text = text,
                        enabled = !saving,
                        onEdit = {
                            textDialog = TextDialogState("编辑稳定信息", ProfileTextField.STABLE_FACTS, index, text)
                        },
                        onDelete = { viewModel.removeText(ProfileTextField.STABLE_FACTS, index) }
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // 兴趣与偏好
            item {
                ProfileSectionHeader(
                    title = "兴趣与偏好",
                    count = draft.preferences.size,
                    max = UserProfileEditor.MAX_LIST,
                    canAdd = draft.preferences.size < UserProfileEditor.MAX_LIST && !saving,
                    onAdd = {
                        textDialog = TextDialogState("添加兴趣与偏好", ProfileTextField.PREFERENCES, null, "")
                    }
                )
            }
            if (draft.preferences.isEmpty()) {
                item { ProfileEmptyHint("暂无兴趣与偏好") }
            } else {
                itemsIndexed(draft.preferences, key = { i, v -> "pf-$i-$v" }) { index, text ->
                    TextItemRow(
                        text = text,
                        enabled = !saving,
                        onEdit = {
                            textDialog = TextDialogState("编辑兴趣与偏好", ProfileTextField.PREFERENCES, index, text)
                        },
                        onDelete = { viewModel.removeText(ProfileTextField.PREFERENCES, index) }
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // 沟通方式
            item {
                ProfileSectionHeader(
                    title = "沟通与安慰方式",
                    count = draft.supportStyle.size,
                    max = UserProfileEditor.MAX_LIST,
                    canAdd = draft.supportStyle.size < UserProfileEditor.MAX_LIST && !saving,
                    onAdd = {
                        textDialog = TextDialogState("添加沟通方式", ProfileTextField.SUPPORT_STYLE, null, "")
                    }
                )
            }
            if (draft.supportStyle.isEmpty()) {
                item { ProfileEmptyHint("暂无沟通偏好") }
            } else {
                itemsIndexed(draft.supportStyle, key = { i, v -> "ss-$i-$v" }) { index, text ->
                    TextItemRow(
                        text = text,
                        enabled = !saving,
                        onEdit = {
                            textDialog = TextDialogState("编辑沟通方式", ProfileTextField.SUPPORT_STYLE, index, text)
                        },
                        onDelete = { viewModel.removeText(ProfileTextField.SUPPORT_STYLE, index) }
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // 性格倾向
            item {
                ProfileSectionHeader(
                    title = "性格倾向（推测，非诊断）",
                    count = draft.personality.size,
                    max = UserProfileEditor.MAX_PERSONALITY,
                    canAdd = draft.personality.size < UserProfileEditor.MAX_PERSONALITY && !saving,
                    onAdd = {
                        personalityDialog = PersonalityDialogState(null, "", 0.5f)
                    }
                )
            }
            if (draft.personality.isEmpty()) {
                item { ProfileEmptyHint("暂无性格倾向") }
            } else {
                itemsIndexed(draft.personality, key = { i, t -> "pt-$i-${t.trait}" }) { index, trait ->
                    PersonalityRow(
                        trait = trait,
                        enabled = !saving,
                        onEdit = {
                            personalityDialog = PersonalityDialogState(index, trait.trait, trait.confidence.toFloat())
                        },
                        onDelete = { viewModel.removePersonality(index) }
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // 外貌
            item {
                ProfileSectionHeader(
                    title = "外貌信息（仅自述）",
                    count = draft.appearanceFacts.size,
                    max = UserProfileEditor.MAX_LIST,
                    canAdd = draft.appearanceFacts.size < UserProfileEditor.MAX_LIST && !saving,
                    onAdd = {
                        textDialog = TextDialogState("添加外貌信息", ProfileTextField.APPEARANCE, null, "")
                    }
                )
            }
            if (draft.appearanceFacts.isEmpty()) {
                item { ProfileEmptyHint("暂无外貌自述") }
            } else {
                itemsIndexed(draft.appearanceFacts, key = { i, v -> "ap-$i-$v" }) { index, text ->
                    TextItemRow(
                        text = text,
                        enabled = !saving,
                        onEdit = {
                            textDialog = TextDialogState("编辑外貌信息", ProfileTextField.APPEARANCE, index, text)
                        },
                        onDelete = { viewModel.removeText(ProfileTextField.APPEARANCE, index) }
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // 近期状态
            item {
                ProfileSectionHeader(
                    title = "近期状态",
                    count = draft.recentStates.size,
                    max = UserProfileEditor.MAX_RECENT,
                    canAdd = draft.recentStates.size < UserProfileEditor.MAX_RECENT && !saving,
                    onAdd = {
                        recentDialog = RecentDialogState(null, "", 30)
                    }
                )
            }
            if (draft.recentStates.isEmpty()) {
                item { ProfileEmptyHint("暂无近期状态") }
            } else {
                itemsIndexed(draft.recentStates, key = { i, s -> "rs-$i-${s.content}" }) { index, state ->
                    RecentRow(
                        state = state,
                        enabled = !saving,
                        onEdit = {
                            recentDialog = RecentDialogState(index, state.content, 30)
                        },
                        onDelete = { viewModel.removeRecent(index) }
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            // 已阻止学习（可折叠，空则不显示）
            if (!blocklist.isEmpty()) {
                item {
                    BlocklistSection(
                        blocklist = blocklist,
                        expanded = blocklistExpanded,
                        enabled = !saving,
                        onToggle = { blocklistExpanded = !blocklistExpanded },
                        onUnblock = { cat, textValue -> viewModel.unblock(cat, textValue) }
                    )
                }
            }

            // 危险操作
            item {
                Text(
                    "危险操作",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("清空画像")
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// —— UI 子组件 ——

@Composable
private fun ProfileSectionHeader(
    title: String,
    count: Int,
    max: Int,
    canAdd: Boolean,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("$count / $max", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onAdd, enabled = canAdd) {
            Icon(Icons.Default.Add, contentDescription = "添加")
        }
    }
}

@Composable
private fun ProfileEmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)
    )
}

@Composable
private fun TextItemRow(
    text: String,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        IconButton(onClick = onEdit, enabled = enabled) {
            Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PersonalityRow(
    trait: PersonalityTrait,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(trait.trait, style = MaterialTheme.typography.bodyMedium)
            Text(
                "置信度：${UserProfileEditor.confidenceLabel(trait.confidence)}（${(trait.confidence * 100).toInt()}%）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit, enabled = enabled) {
            Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun RecentRow(
    state: RecentState,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(state.content, style = MaterialTheme.typography.bodyMedium)
            Text(
                "到期：${state.expiresAt.ifBlank { "未设置" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit, enabled = enabled) {
            Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    val overLimit = value.length > UserProfileEditor.MAX_TEXT
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= UserProfileEditor.MAX_TEXT) value = it },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    isError = overLimit || value.isBlank(),
                    supportingText = {
                        Text("${value.length} / ${UserProfileEditor.MAX_TEXT}")
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.trim().isNotEmpty() && !overLimit
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun PersonalityEditDialog(
    initialTrait: String,
    initialConfidence: Float,
    onDismiss: () -> Unit,
    onConfirm: (String, Double) -> Unit
) {
    var trait by remember { mutableStateOf(initialTrait) }
    var confidence by remember { mutableFloatStateOf(initialConfidence.coerceIn(0f, 1f)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("性格倾向") },
        text = {
            Column {
                OutlinedTextField(
                    value = trait,
                    onValueChange = { if (it.length <= UserProfileEditor.MAX_TEXT) trait = it },
                    label = { Text("描述") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${trait.length} / ${UserProfileEditor.MAX_TEXT}") }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "置信度：${UserProfileEditor.confidenceLabel(confidence.toDouble())}（${(confidence * 100).toInt()}%）",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = confidence,
                    onValueChange = { confidence = it },
                    valueRange = 0f..1f
                )
                Text(
                    "这是可修正的推测，不是诊断。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trait, confidence.toDouble()) },
                enabled = trait.trim().isNotEmpty()
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun RecentEditDialog(
    initialContent: String,
    initialDays: Int,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit
) {
    var content by remember { mutableStateOf(initialContent) }
    var days by remember { mutableIntStateOf(initialDays) }
    val options = listOf(7, 30, 90, 365)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("近期状态") },
        text = {
            Column {
                OutlinedTextField(
                    value = content,
                    onValueChange = { if (it.length <= UserProfileEditor.MAX_TEXT) content = it },
                    label = { Text("状态内容") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${content.length} / ${UserProfileEditor.MAX_TEXT}") }
                )
                Spacer(Modifier.height(12.dp))
                Text("保留期限", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { d ->
                        FilterChip(
                            selected = days == d,
                            onClick = { days = d },
                            label = {
                                Text(
                                    when (d) {
                                        7 -> "7天"
                                        30 -> "30天"
                                        90 -> "90天"
                                        else -> "1年"
                                    }
                                )
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(content, days) },
                enabled = content.trim().isNotEmpty()
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}


@Composable
private fun BlocklistSection(
    blocklist: ProfileBlocklist,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onUnblock: (BlocklistCategory, String) -> Unit
) {
    val total = blocklist.totalCount()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("已阻止学习的内容", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("共 $total 条（保存后生效）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onToggle) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开"
            )
        }
    }
    if (expanded) {
        BlocklistCategoryRows("稳定信息", blocklist.stableFacts, BlocklistCategory.STABLE_FACTS, enabled, onUnblock)
        BlocklistCategoryRows("兴趣与偏好", blocklist.preferences, BlocklistCategory.PREFERENCES, enabled, onUnblock)
        BlocklistCategoryRows("沟通方式", blocklist.supportStyle, BlocklistCategory.SUPPORT_STYLE, enabled, onUnblock)
        BlocklistCategoryRows("外貌信息", blocklist.appearanceFacts, BlocklistCategory.APPEARANCE, enabled, onUnblock)
        BlocklistCategoryRows("近期状态", blocklist.recentStates, BlocklistCategory.RECENT_STATES, enabled, onUnblock)
        BlocklistCategoryRows("性格倾向", blocklist.personalityTraits, BlocklistCategory.PERSONALITY, enabled, onUnblock)
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun BlocklistCategoryRows(
    title: String,
    items: List<String>,
    category: BlocklistCategory,
    enabled: Boolean,
    onUnblock: (BlocklistCategory, String) -> Unit
) {
    if (items.isEmpty()) return
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
    items.forEach { textValue ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(textValue, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = { onUnblock(category, textValue) },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("解除阻止", fontSize = 12.sp) }
        }
    }
}

private data class TextDialogState(
    val title: String,
    val field: ProfileTextField,
    val index: Int?,
    val initial: String
)

private data class PersonalityDialogState(
    val index: Int?,
    val initialTrait: String,
    val initialConfidence: Float
)

private data class RecentDialogState(
    val index: Int?,
    val initialContent: String,
    val initialDays: Int
)
