package com.zhiwo.shiguangjian.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "diaries", indices = [Index(value = ["date"], unique = true)])
data class DiaryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: String,          // "yyyy-MM-dd"，同一天唯一
    val content: String,       // 日记正文
    val mood: String,          // 情绪标签
    val createdAt: String,     // ISO 时间戳
    val exported: Boolean = false  // 是否已导出
)
