package com.zhiwo.shiguangjian.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户把某一天手动标成"班"或"休"，优先级高于内置法定节假日数据。
 */
@Entity(tableName = "day_overrides", primaryKeys = ["date"])
data class DayOverrideEntity(
    val date: String,       // yyyy-MM-dd
    val type: String,       // workday | holiday
    val createdAt: String
)
