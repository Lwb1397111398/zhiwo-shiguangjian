package com.zhiwo.shiguangjian.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reviews")
data class ReviewEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,  // "daily" or "weekly"
    val date: String,
    val content: String,
    val message: String = "",
    val createdAt: String
)
