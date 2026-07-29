package com.zhiwo.shiguangjian.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.ui.theme.*
import com.zhiwo.shiguangjian.ui.viewmodel.CategoryInfo
import com.zhiwo.shiguangjian.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onNavigateToOrganize: () -> Unit = {},
    onNavigateToSpecialDates: () -> Unit = {},
    onNavigateToUserProfile: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val apiBaseUrl by viewModel.apiBaseUrl.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val darkModePref by viewModel.darkModePref.collectAsState()
    val autoCalendarSync by viewModel.autoCalendarSync.collectAsState()
    val smartReminder by viewModel.smartReminder.collectAsState()
    val storageInfo by viewModel.storageInfo.collectAsState()
    val categories by viewModel.categories.collectAsState()

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var newCatName by remember { mutableStateOf("") }
    var newCatIcon by remember { mutableStateOf("📌") }
    var newCatColor by remember { mutableStateOf("#6B8E9F") }
    val categoryColorOptions = listOf("#6B8E9F", "#F7A8B8", "#98D8C8", "#FFD166", "#A78BFA", "#84A59D", "#999999")
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.READ_CALENDAR] == true &&
            results[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            viewModel.toggleAutoCalendarSync(true)
        } else {
            viewModel.toggleAutoCalendarSync(false)
            Toast.makeText(context, "日历权限未授权，自动同步已关闭", Toast.LENGTH_SHORT).show()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson ?: return@rememberLauncherForActivityResult
        pendingExportJson = null
        if (uri == null) {
            Toast.makeText(context, "导出已取消", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(context, "数据已导出", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 每次进入页面时刷新存储信息
    LaunchedEffect(Unit) {
        viewModel.refreshStorageInfo()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp))

        // ===== AI 接口配置 =====
        SectionTitle("AI 接口配置")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = apiBaseUrl,
                    onValueChange = { viewModel.updateApiBaseUrl(it) },
                    label = { Text("API Base URL") },
                    placeholder = { Text("例如: https://api.deepseek.com/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )
                Text("需包含完整路径，如 https://api.deepseek.com/v1",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { viewModel.updateApiKey(it) },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = modelName,
                    onValueChange = { viewModel.updateModelName(it) },
                    label = { Text("Model Name") },
                    placeholder = { Text("例如: deepseek-chat") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.saveApiConfig(
                                onSuccess = { testResult = "✅ 配置已保存" },
                                onError = { testResult = "❌ $it" }
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("保存配置", fontSize = 14.sp) }

                    OutlinedButton(
                        onClick = {
                            Toast.makeText(context, "开始测试...", Toast.LENGTH_SHORT).show()
                            testing = true
                            viewModel.testConnection { _, message ->
                                testing = false
                                testResult = message
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !testing
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else { Text("测试连接", fontSize = 14.sp) }
                    }
                }

                if (testResult.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(testResult, fontSize = 13.sp,
                        color = if (testResult.startsWith("✅")) Success else MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== 外观 =====
        SectionTitle("外观")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (darkModePref) {
                            "on" -> "已开启"
                            "off" -> "已关闭"
                            else -> "跟随系统"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = darkModePref == "off", onClick = { viewModel.setDarkModePref("off") }, label = { Text("亮色") })
                    FilterChip(selected = darkModePref == "auto", onClick = { viewModel.setDarkModePref("auto") }, label = { Text("跟随") })
                    FilterChip(selected = darkModePref == "on", onClick = { viewModel.setDarkModePref("on") }, label = { Text("暗色") })
                }
            }
        }

        // 日历与提醒
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📅 自动同步日历", style = MaterialTheme.typography.bodyLarge)
                        Text("有截止时间的任务自动写入系统日历", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = autoCalendarSync,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                viewModel.toggleAutoCalendarSync(false)
                                return@Switch
                            }
                            val hasReadPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.READ_CALENDAR
                            ) == PackageManager.PERMISSION_GRANTED
                            val hasWritePermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_CALENDAR
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasReadPermission && hasWritePermission) {
                                viewModel.toggleAutoCalendarSync(true)
                            } else {
                                calendarPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_CALENDAR,
                                        Manifest.permission.WRITE_CALENDAR
                                    )
                                )
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("⏰ 智能提醒", style = MaterialTheme.typography.bodyLarge)
                        Text("任务截止前15分钟提醒", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = smartReminder,
                        onCheckedChange = { viewModel.toggleSmartReminder(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== 分类管理 =====
        SectionTitle("分类管理")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                categories.forEachIndexed { index, cat ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(cat.icon, fontSize = 18.sp)
                        OutlinedTextField(
                            value = cat.name,
                            onValueChange = { newName ->
                                viewModel.updateCategory(index, cat.copy(name = newName))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                        )
                        // 颜色选择简化版
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(parseColor(cat.color))
                        )
                        if (!viewModel.isSystemCategory(cat.id)) {
                            IconButton(
                                onClick = { viewModel.removeCategory(index) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // 添加新分类
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newCatIcon,
                        onValueChange = { newCatIcon = it },
                        modifier = Modifier.width(50.dp),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    )
                    OutlinedTextField(
                        value = newCatName,
                        onValueChange = { newCatName = it },
                        placeholder = { Text("新分类名", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )
                    TextButton(onClick = {
                        if (newCatName.isBlank()) return@TextButton
                        viewModel.addCategory(newCatName.trim(), newCatIcon, newCatColor)
                        newCatName = ""
                        newCatIcon = "📌"
                    }) {
                        Text("添加", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categoryColorOptions.forEach { color ->
                        val selected = newCatColor == color
                        Box(
                            modifier = Modifier
                                .size(if (selected) 30.dp else 26.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(parseColor(color))
                                .clickable { newCatColor = color }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.saveCategories()
                        Toast.makeText(context, "分类设置已保存", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("保存分类设置") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== 用户画像 =====
        SectionTitle("用户画像")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "查看和纠正 AI 对你的了解",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onNavigateToUserProfile,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("管理用户画像")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== 重要日子 =====
        SectionTitle("重要日子")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("添加生日、纪念日等重要日子（支持农历），每天第一次打开应用的揭历卡片会提醒你。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onNavigateToSpecialDates,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Icon(Icons.Default.Cake, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("管理重要日子") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== 整理 =====
        SectionTitle("整理")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("整理已完成和过去的事情，提取重要信息到记忆中，然后选择性清理无用数据。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onNavigateToOrganize,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("开始整理") }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        if (exporting) return@OutlinedButton
                        exporting = true
                        coroutineScope.launch {
                            try {
                                pendingExportJson = viewModel.buildExportJson()
                                exportLauncher.launch("zhiwo_backup_${java.time.LocalDate.now()}.json")
                            } catch (e: Throwable) {
                                Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                exporting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !exporting
                ) { if (!exporting) Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    if (!exporting) Spacer(modifier = Modifier.width(4.dp))
                    Text(if (exporting) "正在准备导出..." else "导出所有数据") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== 存储状态 =====
        SectionTitle("存储状态")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                storageInfo?.let { info ->
                    StorageRow("记录", "${info.records} 条")
                    StorageRow("任务", "${info.tasks} 个")
                    StorageRow("标签", "${info.tags} 个")
                    StorageRow("💬 评价", "${info.reviews} 条")
                    StorageRow("🧠 记忆", "${info.memories} 条")
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Success, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("本地存储正常", style = MaterialTheme.typography.bodySmall, color = Success)
                    }
                }
            }
        }

        // ===== 应用信息 =====
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("所有数据仅存储在本地", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
fun StorageRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun parseColor(hex: String): androidx.compose.ui.graphics.Color {
    return try {
        val colorHex = hex.removePrefix("#")
        val full = if (colorHex.length == 6) "FF$colorHex" else colorHex
        androidx.compose.ui.graphics.Color(full.toLong(16))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }
}
