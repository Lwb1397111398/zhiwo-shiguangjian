package com.zhiwo.shiguangjian.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiwo.shiguangjian.data.tasks.DayInvestment
import com.zhiwo.shiguangjian.ui.components.HeatGrid
import com.zhiwo.shiguangjian.ui.components.LensDonut
import com.zhiwo.shiguangjian.ui.components.TrendBars
import com.zhiwo.shiguangjian.ui.components.lensColor
import com.zhiwo.shiguangjian.ui.components.lensMinutesText
import com.zhiwo.shiguangjian.ui.viewmodel.TimeLensViewModel
import java.time.LocalDate

/**
 * 全屏「时光」统计页：今日时间环 → 近 14 天趋势 → 本月热力。
 * 取数全部来自 TimeLensViewModel（TimeLensCalculator 纯函数），页内只做展示与点选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeLensScreen(
    onBack: () -> Unit = {},
    onOpenCalendarDay: (String) -> Unit = {},
    viewModel: TimeLensViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var highlightKey by remember { mutableStateOf<String?>(null) }
    var selectedTrendDate by remember { mutableStateOf(viewModel.today) }
    var heatMonth by remember { mutableStateOf(LocalDate.parse(viewModel.today).withDayOfMonth(1)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("时光", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
        }

        // ---- 板块一：今日时间环 ----
        item {
            SectionCard {
                Text("今天的时间花在哪", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                if (state.budget.plannedMinutes == 0 && state.budget.unboundedCount == 0) {
                    EmptyHint(if (state.loading) "载入中…" else "今天还没有排期——去安排页建一个任务吧")
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LensDonut(
                            slices = state.budget.slices,
                            diameter = 150.dp,
                            stroke = 24.dp,
                            centerTop = lensMinutesText(state.budget.plannedMinutes),
                            centerBottom = "计划投入",
                            highlightKey = highlightKey,
                            onClickKey = { key -> highlightKey = if (highlightKey == key) null else key }
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.budget.slices.forEachIndexed { i, slice ->
                                LedgerRow(
                                    color = lensColor(i),
                                    title = slice.title,
                                    detail = lensMinutesText(slice.minutes),
                                    dimmed = highlightKey != null && highlightKey != slice.key,
                                    onClick = { highlightKey = if (highlightKey == slice.key) null else slice.key }
                                )
                            }
                        }
                    }
                    if (state.budget.unboundedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("另有 ${state.budget.unboundedCount} 条未定时长的任务没进环",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "今天已记录实际投入 ${lensMinutesText(state.budget.loggedMinutes)}（勾任务时顺手填的分钟数会记在这里）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val highlighted = state.budget.slices.firstOrNull { it.key == highlightKey }
                    highlighted?.let { slice ->
                        Spacer(Modifier.height(10.dp))
                        Text(slice.title, style = MaterialTheme.typography.labelLarge, color = lensColor(
                            state.budget.slices.indexOf(slice)
                        ))
                        slice.items.forEach { item ->
                            Text(
                                (if (item.done) "✓ " else "· ") + item.content +
                                    if (item.minutes > 0) "（${item.minutes} 分钟）" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ---- 板块二：近 14 天趋势 ----
        item {
            SectionCard {
                Text("最近 14 天", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("浅柱 = 计划投入，深柱 = 已记录实际", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (state.trend.all { it.plannedMinutes == 0 && it.loggedMinutes == 0 }) {
                    EmptyHint("这段时间还没有排期")
                } else {
                    TrendBars(
                        days = state.trend,
                        selectedDate = selectedTrendDate,
                        barHeight = 64.dp,
                        onSelectDay = { selectedTrendDate = it }
                    )
                    Spacer(Modifier.height(10.dp))
                    state.trend.firstOrNull { it.date == selectedTrendDate }?.let { day ->
                        Text(
                            "${day.date} · 计划 ${lensMinutesText(day.plannedMinutes)}，" +
                                "实际 ${lensMinutesText(day.loggedMinutes)}，完成 ${day.doneCount}/${day.dueCount} 件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---- 板块三：本月热力 ----
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${heatMonth.year}年${heatMonth.monthValue}月 投入热力",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { heatMonth = heatMonth.minusMonths(1) }) { Text("‹ 上月") }
                    if (heatMonth.withDayOfMonth(1) < LocalDate.parse(viewModel.today).withDayOfMonth(1)) {
                        TextButton(onClick = { heatMonth = heatMonth.plusMonths(1) }) { Text("下月 ›") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val cells = remember(state.heat, heatMonth, viewModel.today) {
                    heatCells(heatMonth, viewModel.today, state.heat)
                }
                if (cells.all { it == null }) {
                    EmptyHint("这个月还没有数据")
                } else {
                    HeatGrid(cells = cells, onSelectDay = onOpenCalendarDay)
                    Spacer(Modifier.height(10.dp))
                    Text("颜色越深 = 当天记录的投入越多；浅黄 = 排了但还没记投入。点格子看那天的安排。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun LedgerRow(
    color: androidx.compose.ui.graphics.Color,
    title: String,
    detail: String,
    dimmed: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color.copy(alpha = if (dimmed) 0.3f else 1f)))
        Spacer(Modifier.width(8.dp))
        Text(
            title, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, modifier = Modifier.weight(1f),
            color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.onSurface
        )
        Text(
            detail, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp))
}

/** 本月格子：月初对齐星期一，未来天与月外补 null；数据按日期查表 */
private fun heatCells(month: LocalDate, today: String, data: List<DayInvestment>): List<DayInvestment?> {
    val byDate = data.associateBy { it.date }
    val todayDate = LocalDate.parse(today.take(10))
    val length = month.lengthOfMonth()
    val leading = (month.dayOfWeek.value + 6) % 7          // 周一=0
    val cells = ArrayList<DayInvestment?>(leading + length)
    repeat(leading) { cells += null }
    for (d in 1..length) {
        val date = month.withDayOfMonth(d)
        cells += if (date.isAfter(todayDate)) null else byDate[date.toString()]
    }
    // 补齐最后一行
    while (cells.size % 7 != 0) cells += null
    return cells
}
