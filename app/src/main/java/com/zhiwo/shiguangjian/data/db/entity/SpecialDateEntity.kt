package com.zhiwo.shiguangjian.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户自定义的重要日子（生日、纪念日等），每年重复。
 * 支持公历和农历两种日期。
 */
@Entity(tableName = "special_dates")
data class SpecialDateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,               // 如"妈妈的生日"
    val month: Int,                  // 1-12（isLunar 时为农历月）
    val day: Int,                    // 1-31（isLunar 时为农历日）
    val year: Int? = null,           // null = 每年重复；非 null = 仅该年一次（公历年，如四六级报名）
    val isLunar: Boolean = false,
    val type: String = "birthday",   // birthday, anniversary, other
    val note: String? = null,
    val createdAt: String
)
