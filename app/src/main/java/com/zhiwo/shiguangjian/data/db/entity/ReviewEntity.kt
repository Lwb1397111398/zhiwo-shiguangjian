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
    val createdAt: String,
    /** 生成时依据的记录 ID 列表（JSON 数组文本），供重新生成与溯源 */
    val sourceRecordIds: String? = null,
    /** 生成版本号：每次重新生成 +1 */
    val generationVersion: Int = 1,
    /** 用户是否手动编辑过；编辑过的内容不允许被自动重新生成覆盖 */
    val isUserEdited: Boolean = false
)
