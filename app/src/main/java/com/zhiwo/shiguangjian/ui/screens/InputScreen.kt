package com.zhiwo.shiguangjian.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.ui.theme.*
import com.zhiwo.shiguangjian.ui.viewmodel.InputViewModel

@Composable
fun InputScreen(
    onSaveSuccess: (Long) -> Unit = {},
    onCancel: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: InputViewModel = viewModel()
) {
    val context = LocalContext.current
    var textContent by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("") }
    val isConfigured by viewModel.isConfigured.collectAsState()
    val categories by viewModel.categories.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            Text("笺", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "写下一天的一角",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "想法、任务、情绪和灵感，都可以先安放在这里",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 分类选择（可选，不选则由 AI 自动判断）
        if (categories.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "分类",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(categories.size) { index ->
                        val cat = categories[index]
                        val isActive = selectedCategory == cat.id
                        val chipColor = try {
                            val colorHex = cat.color.removePrefix("#")
                            val full = if (colorHex.length == 6) "FF$colorHex" else colorHex
                            androidx.compose.ui.graphics.Color(full.toLong(16))
                        } catch (_: Exception) {
                            MaterialTheme.colorScheme.primary
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (isActive) chipColor else chipColor.copy(alpha = 0.12f))
                                .clickable {
                                    selectedCategory = if (isActive) "" else cat.id
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "${cat.icon}${cat.name}",
                                fontSize = 12.sp,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary else chipColor
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = textContent,
            onValueChange = { if (it.length <= 10000) textContent = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 400.dp)
                .animateContentSize(),
            placeholder = {
                Text(
                    text = if (textContent.isEmpty()) "此刻想记住什么？" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            textStyle = LocalTextStyle.current.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("取消")
            }
            Button(
                onClick = {
                    if (saving) return@Button
                    if (textContent.isBlank()) return@Button
                    if (!isConfigured) {
                        Toast.makeText(context, "请先配置 AI 接口", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    saving = true
                    viewModel.saveAndAnalyze(
                        content = textContent.trim(),
                        selectedCategory = selectedCategory,
                        onSuccess = { recordId ->
                            saving = false
                            textContent = ""
                            selectedCategory = ""
                            onSaveSuccess(recordId)
                        },
                        onError = { error ->
                            saving = false
                            Toast.makeText(context, "保存失败：$error", Toast.LENGTH_LONG).show()
                        }
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                enabled = textContent.isNotBlank() && !saving,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("保存并分析", fontSize = 16.sp)
                }
            }
        }

        // 配置提示
        if (!isConfigured) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToSettings() },
                colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Warning, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("请先配置AI接口才能使用分析功能，点击前往设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
