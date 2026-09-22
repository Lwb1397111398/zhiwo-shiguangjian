package com.zhiwo.shiguangjian.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 目标：长期尺度（"今年通过法考"）。recordId 只作溯源，不建外键——删记录不得牵连目标。
 */
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val startDate: String = "",      // yyyy-MM-dd，"" = 未设
    val targetDate: String = "",     // 截止日，"" = 长期
    val status: String = "active",   // active | achieved | abandoned
    val recordId: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: String,
    val updatedAt: String = ""
)
