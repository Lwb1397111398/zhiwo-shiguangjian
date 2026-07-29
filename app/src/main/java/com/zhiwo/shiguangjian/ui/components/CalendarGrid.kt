package com.zhiwo.shiguangjian.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun CalendarGrid(
    tasks: List<TaskEntity>,
    selectedDate: String,
    onSelectDate: (String) -> Unit,
    onMonthChange: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val calendar = remember { Calendar.getInstance() }
    var currentYear by remember { mutableStateOf(calendar.get(Calendar.YEAR)) }
    var currentMonth by remember { mutableStateOf(calendar.get(Calendar.MONTH)) }

    val daysInMonth = remember(currentYear, currentMonth) {
        val cal = Calendar.getInstance().apply {
            set(currentYear, currentMonth, 1)
        }
        cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    val firstDayOfWeek = remember(currentYear, currentMonth) {
        val cal = Calendar.getInstance().apply {
            set(currentYear, currentMonth, 1)
        }
        (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一为0
    }

    val taskDates = remember(tasks) {
        tasks.mapNotNull { task ->
            if (task.dueDate.isBlank()) return@mapNotNull null
            try {
                val date = try {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(task.dueDate)
                } catch (_: Exception) {
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(task.dueDate)
                }
                date?.let { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(it) }
            } catch (_: Exception) {
                task.dueDate.take(10)
            }
        }.toSet()
    }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // 月份导航
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (currentMonth == 0) {
                    currentMonth = 11
                    currentYear--
                } else {
                    currentMonth--
                }
                onMonthChange(currentYear, currentMonth)
            }) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "上一月", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "${currentYear}年${currentMonth + 1}月",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = {
                if (currentMonth == 11) {
                    currentMonth = 0
                    currentYear++
                } else {
                    currentMonth++
                }
                onMonthChange(currentYear, currentMonth)
            }) {
                Icon(Icons.Default.ChevronRight, contentDescription = "下一月", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 星期标题
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                Text(
                    text = day,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 日期网格
        val totalCells = firstDayOfWeek + daysInMonth
        val rows = (totalCells + 6) / 7

        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0..6) {
                    val cellIndex = row * 7 + col
                    val day = cellIndex - firstDayOfWeek + 1

                    if (day in 1..daysInMonth) {
                        val dateStr = String.format(
                            "%04d-%02d-%02d",
                            currentYear, currentMonth + 1, day
                        )
                        val isSelected = dateStr == selectedDate
                        val hasTask = dateStr in taskDates
                        val isToday = dateStr == String.format(
                            "%04d-%02d-%02d",
                            Calendar.getInstance().get(Calendar.YEAR),
                            Calendar.getInstance().get(Calendar.MONTH) + 1,
                            Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                        else -> androidx.compose.ui.graphics.Color.Transparent
                                    }
                                )
                                .clickable { onSelectDate(dateStr) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$day",
                                    fontSize = 14.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                                if (hasTask) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                            )
                                    )
                                }
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
