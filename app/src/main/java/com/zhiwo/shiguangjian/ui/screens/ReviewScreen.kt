package com.zhiwo.shiguangjian.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.ui.theme.*
import com.zhiwo.shiguangjian.ui.viewmodel.ReviewViewModel

@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel = viewModel(),
    onNavigateToMemories: () -> Unit = {},
    onNavigateToDiary: () -> Unit = {}
) {
    val context = LocalContext.current
    val reviews by viewModel.reviews.collectAsState()
    val isConfigured by viewModel.isConfigured.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var userInput by remember { mutableStateOf("") }
    var generatingDaily by remember { mutableStateOf(false) }
    var generatingWeekly by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("每日评价", style = MaterialTheme.typography.headlineMedium)
            Row {
                TextButton(onClick = onNavigateToMemories) {
                    Text("我的记忆", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                }
                TextButton(onClick = onNavigateToDiary) {
                    Text("我的日记", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                }
            }
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("每日评价", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("每周报告", modifier = Modifier.padding(12.dp))
            }
        }

        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("今天过得怎么样？", fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = userInput,
                                onValueChange = { userInput = it },
                                modifier = Modifier.fillMaxWidth().height(120.dp),
                                placeholder = { Text("写下你今天的感受...", fontSize = 14.sp) },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (!isConfigured) {
                                        Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    generatingDaily = true
                                    viewModel.generateDailyReview(userInput) { result, error ->
                                        generatingDaily = false
                                        if (result != null) {
                                            userInput = ""
                                            Toast.makeText(context, "评价已生成", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, error ?: "生成失败，请检查AI配置", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !generatingDaily,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                if (generatingDaily) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.surface, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(if (generatingDaily) "生成中..." else "生成今日评价")
                            }
                        }
                    }
                }

                val dailyReviews = reviews.filter { it.type == "daily" }
                if (dailyReviews.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("历史评价", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                items(dailyReviews, key = { it.id }) { review ->
                    val mainText = getMainText(review.content)
                    val blessing = getBlessing(review.content)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).animateContentSize(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(review.date, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.weight(1f))
                                if (review.isUserEdited) {
                                    Text("已编辑", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                } else if (review.generationVersion > 1) {
                                    Text("v${review.generationVersion}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                                TextButton(
                                    onClick = {
                                        if (!isConfigured) {
                                            Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "按最新记忆重新生成中...", Toast.LENGTH_SHORT).show()
                                            viewModel.regenerateDailyReview(review.id) { ok, msg ->
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) { Text("重新生成", fontSize = 11.sp) }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(mainText, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp)
                            if (blessing.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Text("🌟 今日寄语", fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(blessing, fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface, lineHeight = 20.sp,
                                            fontStyle = FontStyle.Italic)
                                    }
                                }
                            }
                            if (review.message.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outline))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("💬 ${review.message}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (!isConfigured) {
                            Toast.makeText(context, "请先配置AI接口", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        generatingWeekly = true
                        viewModel.generateWeeklyReview { result, error ->
                            generatingWeekly = false
                            if (result != null) {
                                Toast.makeText(context, "周报已生成", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, error ?: "生成失败，请检查AI配置", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !generatingWeekly,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (generatingWeekly) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.surface, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (generatingWeekly) "生成中..." else "生成本周报告")
                }

                Spacer(modifier = Modifier.height(16.dp))

                val weeklyReviews = reviews.filter { it.type == "weekly" }
                if (weeklyReviews.isNotEmpty()) {
                    Text("历史周报", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                }

                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(weeklyReviews, key = { it.id }) { review ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).animateContentSize(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("周报 · ${review.date}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(review.content, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun getMainText(text: String): String {
    val blessingIndex = text.indexOf("🌟今日寄语")
    if (blessingIndex == -1) {
        val altIndex = text.indexOf("🌟 今日寄语")
        return if (altIndex == -1) text else text.substring(0, altIndex).trim()
    }
    return text.substring(0, blessingIndex).trim()
}

fun getBlessing(text: String): String {
    val regex = Regex("🌟\\s*今日寄语[：:]\\s*(.*)", RegexOption.DOT_MATCHES_ALL)
    val match = regex.find(text)
    return match?.groupValues?.get(1)?.trim() ?: ""
}
