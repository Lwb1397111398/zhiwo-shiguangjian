package com.zhiwo.shiguangjian.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwo.shiguangjian.data.tasks.DayInvestment
import com.zhiwo.shiguangjian.data.tasks.LensSlice
import com.zhiwo.shiguangjian.ui.theme.*
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 时光透镜三件套（环 / 趋势柱 / 热力格）+ 完成小环。全部 Canvas/Box 自绘，零第三方图表库。
 * 环的段色按"时长降序后的位置"取固定调色板——卡片与全屏页同一归属必然同一颜色。
 */

private val LENS_PALETTE = listOf(
    Success, Warning, Lavender, Primary, Error, Sage,
    PrimaryLight, Color(0xFFC08552), Color(0xFF7C90A0), PrimaryDark
)

fun lensColor(index: Int): Color = LENS_PALETTE[index % LENS_PALETTE.size]

/** 分钟数的口语化：90 → "1.5小时"，45 → "45分钟" */
fun lensMinutesText(minutes: Int): String = when {
    minutes <= 0 -> "0 分钟"
    minutes < 60 -> "$minutes 分钟"
    else -> {
        val h = minutes / 60.0
        if (h * 10 % 10 == 0.0) "${h.toInt()} 小时" else String.format("%.1f 小时", h)
    }
}

/** 段色索引 → 颜色：按 slices 顺序取色，点击角度反查也走同一顺序 */
@Composable
fun LensDonut(
    slices: List<LensSlice>,
    modifier: Modifier = Modifier,
    diameter: Dp = 140.dp,
    stroke: Dp = 22.dp,
    centerTop: String = "",
    centerBottom: String = "",
    highlightKey: String? = null,
    onClickKey: ((String) -> Unit)? = null
) {
    val active = remember(slices) { slices.filter { it.minutes > 0 } }
    val total = active.sumOf { it.minutes }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(active.map { it.minutes }) {
        if (sweep.value < 0.2f) sweep.snapTo(0.2f)
        sweep.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
    }
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(diameter)
                .then(
                    if (onClickKey == null) Modifier else Modifier.pointerInput(active) {
                        detectTapGestures { offset ->
                            if (active.isEmpty() || total <= 0) return@detectTapGestures
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val dx = offset.x - cx
                            val dy = offset.y - cy
                            val outer = cx.coerceAtMost(cy)
                            val inner = outer - stroke.toPx()
                            val dist = hypot(dx, dy)
                            if (dist < inner || dist > outer) return@detectTapGestures
                            // drawArc 的 0° 在 3 点钟、顺时针；环从 12 点起画，所以换算 +90
                            var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            deg = (deg + 90f + 360f) % 360f
                            var acc = 0f
                            for (s in active) {
                                acc += 360f * s.minutes / total
                                if (deg <= acc) { onClickKey(s.key); return@detectTapGestures }
                            }
                            onClickKey(active.last().key)
                        }
                    }
                )
        ) {
            drawArc(track.copy(alpha = 0.35f), 0f, 360f, useCenter = false,
                style = Stroke(width = stroke.toPx()))
            if (total > 0) {
                var start = -90f
                val gap = if (active.size > 1) 3f else 0f
                active.forEachIndexed { i, s ->
                    val frac = s.minutes.toFloat() / total
                    val alpha = if (highlightKey == null || highlightKey == s.key) 1f else 0.25f
                    drawArc(
                        lensColor(i).copy(alpha = alpha),
                        startAngle = start + gap / 2f,
                        sweepAngle = (360f * frac - gap).coerceAtLeast(0f) * sweep.value,
                        useCenter = false,
                        style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
                    )
                    start += 360f * frac
                }
            }
        }
        if (centerTop.isNotBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerTop, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (centerBottom.isNotBlank()) {
                    Text(centerBottom, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 近 14 天双柱：淡宽柱=计划、深色细柱=已记录；底部星期字，选中日加粗+圆点 */
@Composable
fun TrendBars(
    days: List<DayInvestment>,
    selectedDate: String?,
    modifier: Modifier = Modifier,
    barHeight: Dp = 56.dp,
    onSelectDay: (String) -> Unit = {}
) {
    val maxVal = (days.maxOfOrNull { maxOf(it.plannedMinutes, it.loggedMinutes) } ?: 0).coerceAtLeast(30)
    val plannedColor = MaterialTheme.colorScheme.primary
    val topShape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        days.forEach { d ->
            val ph = barHeight * (d.plannedMinutes.toFloat() / maxVal)
            val lh = barHeight * (d.loggedMinutes.toFloat() / maxVal)
            val sel = d.date == selectedDate
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).clickable { onSelectDay(d.date) }
            ) {
                Box(
                    modifier = Modifier.height(barHeight).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (d.plannedMinutes > 0) {
                        Box(Modifier.width(if (d.loggedMinutes > 0) 16.dp else 12.dp).height(ph)
                            .clip(topShape).background(plannedColor.copy(alpha = 0.22f)))
                    } else if (d.loggedMinutes == 0) {
                        Box(Modifier.size(3.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                    }
                    if (d.loggedMinutes > 0) {
                        Box(Modifier.width(7.dp).height(lh).clip(topShape).background(Success))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    weekLabel(d.date),
                    fontSize = 10.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = if (sel) plannedColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Box(Modifier.size(4.dp).clip(CircleShape)
                    .background(if (sel) plannedColor else Color.Transparent))
            }
        }
    }
}

/** 月度热力格：7 列一排；null=不在本月/未来天；颜色深浅=已记录，浅黄=计划了但还没记投入 */
@Composable
fun HeatGrid(
    cells: List<DayInvestment?>,
    modifier: Modifier = Modifier,
    cellSize: Dp = 24.dp,
    onSelectDay: (String) -> Unit = {}
) {
    val maxVal = (cells.filterNotNull().maxOfOrNull { maxOf(it.loggedMinutes, it.plannedMinutes) } ?: 0)
        .coerceAtLeast(1)
    val shape = RoundedCornerShape(6.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cells.chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { cell ->
                    val color = when {
                        cell == null -> Color.Transparent
                        cell.loggedMinutes > 0 -> MaterialTheme.colorScheme.primary.copy(
                            alpha = 0.30f + 0.65f * cell.loggedMinutes / maxVal)
                        cell.plannedMinutes > 0 -> Warning.copy(alpha = 0.35f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                    Box(
                        modifier = Modifier.size(cellSize).clip(shape).background(color)
                            .then(if (cell != null) Modifier.clickable { onSelectDay(cell.date) } else Modifier)
                    )
                }
                repeat(7 - row.size) { Spacer(Modifier.size(cellSize)) }
            }
        }
    }
}

/** 安排页头部的完成小环：随完成数平滑转，全做完变金环（"今天圆满"的小仪式感） */
@Composable
fun DoneRing(
    done: Int,
    due: Int,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    strokeWidth: Dp = 3.dp
) {
    val frac by animateFloatAsState(
        targetValue = if (due <= 0) 0f else (done.toFloat() / due).coerceIn(0f, 1f),
        animationSpec = tween(500), label = "doneRing"
    )
    Canvas(modifier = modifier.size(size)) {
        val w = strokeWidth.toPx()
        val inset = w / 2f
        val area = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
        drawArc(Color.Black.copy(alpha = 0.08f), 0f, 360f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = area,
            style = Stroke(width = w))
        if (due > 0 && frac > 0f) {
            drawArc(if (done >= due) Warning else Success, -90f, 360f * frac, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = area,
                style = Stroke(width = w, cap = StrokeCap.Round))
        }
    }
}

private fun weekLabel(date: String): String =
    try {
        val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
        names[LocalDate.parse(date.take(10)).dayOfWeek.value - 1]
    } catch (_: Exception) {
        "·"
    }
