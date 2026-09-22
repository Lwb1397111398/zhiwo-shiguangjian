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
    val exported: Boolean = false,  // 是否已导出
    /** 生成时依据的记录 ID 列表（JSON 数组文本），供后续"依据新事实重新生成"与溯源 */
    val sourceRecordIds: String? = null,
    /** 生成版本号：每次重新生成 +1，用于区分新旧版本 */
    val generationVersion: Int = 1,
    /** 用户是否手动编辑过正文；编辑过的内容不允许被自动重新生成覆盖 */
    val isUserEdited: Boolean = false
)
