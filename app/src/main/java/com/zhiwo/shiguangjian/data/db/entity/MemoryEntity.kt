package com.zhiwo.shiguangjian.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val source: String = "",  // "review", "organize", "manual", "reconcile"
    val createdAt: String,
    val updatedAt: String,
    /** 生命周期：active 生效中；superseded 已被更正/取代，不再注入任何 prompt；under_review 存在待确认冲突 */
    val status: String = "active",
    /** 取代本条记忆的新记忆 id（若有） */
    val supersededBy: Long? = null,
    /** 更正原因，供"已更正"列表展示 */
    val note: String? = null,
    /** 触发本次记忆写入/更正的来源记录 id（对账溯源用） */
    val sourceRecordId: Long? = null,
    /** 记忆所描述的日期（来自哪条记录/整理项）；createdAt 只表示"什么时候写进库"，两者混用过导致新整理的记忆被上限挤掉 */
    val occurredAt: String = ""
)
