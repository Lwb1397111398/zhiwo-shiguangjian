package com.zhiwo.shiguangjian.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("recordId"),
        Index("parentGoalId")
    ]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val recordId: Long? = null,
    val parentGoalId: Long? = null,
    val content: String,
    val dueDate: String = "",
    val taskType: String = "once",  // once, daily, weekly, goal
    val isCompleted: Boolean = false,
    val completedAt: String? = null,
    val dailyCompletionDate: String? = null,
    val isPermanentlyCompleted: Boolean = false,  // 每日任务"真正完成"标记，完成后不再每日刷新
    val calendarEventId: Long? = null,
    val createdAt: String
)
