package com.zhiwo.shiguangjian.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一天一次的执行记录：完成/未完成原因/留白回填都写在这里。
 * 唯一索引 (taskId,date) 是"同一天同一任务只有一条"的判据；撤销完成走软标记 pending，不删行。
 */
@Entity(
    tableName = "task_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["taskId", "date"], unique = true),
        Index("taskId")
    ]
)
data class TaskOccurrenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long,
    val date: String,                 // yyyy-MM-dd
    val status: String = "pending",   // done | not_done | pending
    val reasonCode: String = "",      // no_time|tired|sick|forgot|changed|skipped_blank|other
    val reasonNote: String = "",
    val actualMinutes: Int = 0,
    val note: String = "",            // 留白任务回填"这段时间做了什么"
    val createdAt: String,
    val updatedAt: String = ""
)
