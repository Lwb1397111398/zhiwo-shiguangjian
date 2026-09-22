package com.zhiwo.shiguangjian.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 计划：一段时间内的具体安排（"9 月：民法精讲 + 每周 2 套真题"），挂在目标之下。
 */
@Entity(
    tableName = "plans",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("goalId")]
)
data class PlanEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val goalId: Long? = null,
    val title: String,
    val description: String = "",
    val startDate: String = "",
    val endDate: String = "",        // "" = 未设截止
    val status: String = "active",   // active | paused | done | dropped
    val recordId: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: String,
    val updatedAt: String = ""
)
