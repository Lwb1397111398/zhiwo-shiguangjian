package com.zhiwo.shiguangjian.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordCard(
    record: RecordEntity,
    tasks: List<TaskEntity> = emptyList(),
    tags: List<String> = emptyList(),
    onClick: () -> Unit = {},
    onTaskComplete: (Long) -> Unit = {},
    onTaskUncomplete: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember(record.id) { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .animateContentSize()
            .clickable { onClick() },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                CategoryChip(category = record.category)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = record.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis
            )

            if (record.content.length > 100) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(top = 4.dp)
                )
            }

            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tags.forEach { tag ->
                        TagChip(name = tag)
                    }
                }
            }

            if (tasks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                tasks.forEach { task ->
                    Box(
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (task.isCompleted) onTaskUncomplete(task.id)
                            else onTaskComplete(task.id)
                        }
                    ) {
                        TaskItem(task = task, clickable = false)
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryChip(category: String) {
    val (name, color) = getCategoryInfo(category)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = name,
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun TagChip(name: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = "#$name",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun getCategoryInfo(category: String): Pair<String, androidx.compose.ui.graphics.Color> {
    return when (category) {
        "todo" -> "待办事项" to Primary
        "goal" -> "目标设定" to Error
        "completed" -> "已完成" to Success
        "idea" -> "想法灵感" to Sage
        "emotion" -> "情绪记录" to Warning
        "question" -> "问题思考" to Lavender
        "study" -> "学习笔记" to Sage
        else -> "其他" to MaterialTheme.colorScheme.onSurfaceVariant
    }
}
