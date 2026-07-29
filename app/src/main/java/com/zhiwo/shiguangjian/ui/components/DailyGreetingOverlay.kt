package com.zhiwo.shiguangjian.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.zhiwo.shiguangjian.ui.viewmodel.DailyGreetingInfo
import com.zhiwo.shiguangjian.ui.viewmodel.UpcomingSpecialDate
import kotlinx.coroutines.launch

/**
 * 每日揭历：每天第一次打开应用时的全屏卡片。
 * 封面是一张"日历页"，点击后向上翻起（台历翻页效果），露出今天的详细信息。
 */
@Composable
fun DailyGreetingOverlay(
    info: DailyGreetingInfo,
    onDismiss: () -> Unit,
    onManageDates: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val flip = remember { Animatable(0f) }          // 0 = 未揭开，1 = 已揭开
    var revealed by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    // 未揭开时封面轻微"呼吸"起伏，提示可以点击
    val hint by rememberInfiniteTransition(label = "hint").animateFloat(
        initialValue = 0f, targetValue = -4f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "hintFloat"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.92f)
                    )
                )
            )
            // 拦截点击，避免穿透到底层页面
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            // 底层：今日信息卡
            GreetingContentCard(
                info = info,
                visible = revealed,
                onDismiss = onDismiss,
                onManageDates = onManageDates
            )

            // 顶层：日历封面页，翻起后消失
            if (flip.value < 1f) {
                CalendarCoverPage(
                    info = info,
                    modifier = Modifier
                        .graphicsLayer {
                            rotationX = -150f * flip.value
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            cameraDistance = 16f * density.density
                            translationY = if (flip.value == 0f) hint * density.density else 0f
                            alpha = 1f - (flip.value * 1.1f).coerceAtMost(1f)
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (!revealed) {
                                revealed = true
                                scope.launch {
                                    flip.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
                                }
                            }
                        }
                )
            }
        }
    }
}

@Composable
private fun CalendarCoverPage(info: DailyGreetingInfo, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 36.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 台历顶部装订环
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(5) {
                    Box(
                        modifier = Modifier
                            .size(width = 10.dp, height = 22.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(5.dp)
                            )
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                info.dateText,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${info.weekdayText}${if (info.lunarText.isNotEmpty()) " · ${info.lunarText}" else ""}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(36.dp))
            Text(
                "轻触揭开今天 ↑",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun GreetingContentCard(
    info: DailyGreetingInfo,
    visible: Boolean,
    onDismiss: () -> Unit,
    onManageDates: () -> Unit
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0.35f,
        animationSpec = tween(600), label = "contentAlpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(contentAlpha),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(
                "${info.dateText} ${info.weekdayText}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (info.lunarText.isNotEmpty()) {
                Text(
                    info.lunarText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (info.festivals.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Celebration, contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp)
                    )
                    info.festivals.forEach { festival ->
                        AssistChip(onClick = {}, label = { Text(festival) })
                    }
                }
            }

            if (info.todaySpecials.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                info.todaySpecials.forEach { special ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                specialIcon(special.entity.type), contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                "今天是 ${special.entity.title}！",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            if (info.upcomingSpecials.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "即将到来",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                info.upcomingSpecials.forEach { special ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            specialIcon(special.entity.type), contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            special.entity.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${special.solarDateText} · 还有${special.daysUntil}天",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (info.calendarEvents.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "今日日程",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                info.calendarEvents.forEach { event ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Event, contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(event, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                enabled = visible
            ) { Text("开启今天") }
            TextButton(
                onClick = onManageDates,
                modifier = Modifier.fillMaxWidth(),
                enabled = visible
            ) { Text("管理重要日子") }
        }
    }
}

@Composable
private fun specialIcon(type: String) = when (type) {
    "birthday" -> Icons.Default.Cake
    "anniversary" -> Icons.Default.Favorite
    else -> Icons.Default.Event
}
