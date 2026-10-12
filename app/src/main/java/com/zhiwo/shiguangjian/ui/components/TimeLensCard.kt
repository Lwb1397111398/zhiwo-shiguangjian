package com.zhiwo.shiguangjian.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhiwo.shiguangjian.data.tasks.DayInvestment
import com.zhiwo.shiguangjian.data.tasks.TodayBudget
import com.zhiwo.shiguangjian.ui.theme.Success

/**
 * 安排页顶部的「今日时间」胶囊卡：左迷你环、中计划/已记录、右较昨日趋势。
 * 点卡片进全屏统计页；点角标只折叠，展开时下方带近 14 天小柱图。
 */
@Composable
fun TimeLensCard(
    budget: TodayBudget,
    trend: List<DayInvestment>,
    loading: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val today = trend.lastOrNull()
    val yesterday = trend.dropLast(1).lastOrNull()
    val delta = (today?.loggedMinutes ?: 0) - (yesterday?.loggedMinutes ?: 0)

    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    }
                } else {
                    LensDonut(
                        slices = budget.slices,
                        diameter = 56.dp,
                        stroke = 10.dp,
                        centerTop = if (budget.plannedMinutes > 0) "" else "–"
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (loading) "今日时间 · 载入中" else "今日计划 ${lensMinutesText(budget.plannedMinutes)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!loading) {
                        Text(
                            "已记录 ${lensMinutesText(budget.loggedMinutes)}" +
                                if (budget.unboundedCount > 0) " · 另有 ${budget.unboundedCount} 条未定时长" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!loading && delta != 0) {
                    Text(
                        (if (delta > 0) "▲" else "▼") + " " + lensMinutesText(kotlin.math.abs(delta)) + " 比昨天",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (delta > 0) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开"
                    )
                }
            }
            if (expanded && trend.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TrendBars(days = trend, selectedDate = null, barHeight = 34.dp)
            }
        }
    }
}
