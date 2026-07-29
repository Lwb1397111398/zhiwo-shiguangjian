package com.zhiwo.shiguangjian.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.ui.theme.*

@Composable
fun TaskItem(
    task: TaskEntity,
    onComplete: () -> Unit = {},
    onUncomplete: () -> Unit = {},
    onPermanentlyComplete: () -> Unit = {},  // 真正完成（仅每日任务可用）
    showDate: Boolean = false,
    modifier: Modifier = Modifier,
    clickable: Boolean = true,
    isEffectivelyCompleted: Boolean = task.isCompleted  // 允许外部覆盖"有效完成"状态
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isEffectivelyCompleted) Success.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        label = "taskBg"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .then(
                if (clickable) Modifier.clickable {
                    if (isEffectivelyCompleted) onUncomplete() else onComplete()
                } else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isEffectivelyCompleted) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (isEffectivelyCompleted) Success else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.content,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Normal,
                textDecoration = if (isEffectivelyCompleted) TextDecoration.LineThrough else TextDecoration.None,
                color = if (isEffectivelyCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            if (showDate && task.dueDate.isNotBlank()) {
                Text(
                    text = task.dueDate.take(10),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 任务类型标签 + 每日任务"真正完成"按钮
        val typeLabel = when (task.taskType) {
            "daily" -> "每日"
            "weekly" -> "每周"
            "goal" -> "目标"
            else -> ""
        }
        if (typeLabel.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(PrimaryLight.copy(alpha = 0.26f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = typeLabel,
                    fontSize = 10.sp,
                    color = PrimaryDark,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        // 每日任务显示"真正完成"按钮
        if (task.taskType == "daily" && !task.isPermanentlyCompleted) {
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = onPermanentlyComplete,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.DoneAll,
                    contentDescription = "真正完成",
                    tint = Success,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
