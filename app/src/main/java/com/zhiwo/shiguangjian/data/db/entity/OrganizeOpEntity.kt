package com.zhiwo.shiguangjian.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 整理页两阶段操作：Plan 阶段只写本表（不碰业务表），用户审核后 Apply 阶段逐条执行。
 * payload 自包含（含源 ID 与版本快照），进程被杀后重开仍可继续审核与执行。
 */
@Entity(tableName = "organize_ops")
data class OrganizeOpEntity(
    @PrimaryKey
    val id: String,             // UUID
    val batchId: String,        // 同一次"生成提案"产生的操作属于一个批次
    val type: String,           // SAVE_MEMORY / DELETE_SOURCE / EVOLVE_MEMORY
    val status: String,         // PENDING / APPLIED / DISMISSED / FAILED / STALE
    /** 执行用载荷（用户可编辑，编辑后成为唯一执行来源） */
    val payloadJson: String,
    /** AI 原始建议（用于"恢复原建议"与对比） */
    val previewJson: String? = null,
    /** 源数据版本快照（校验源是否被改过） */
    val sourceVersion: String? = null,
    /** 依赖的前置操作 id 列表（JSON 数组文本）。如 DELETE_SOURCE 依赖同批的 SAVE_MEMORY：
     *  前置未全部 APPLIED 时本条不执行，防止"源删了记忆没存"造成数据丢失 */
    val dependsOn: String? = null,
    val checked: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val appliedAt: Long? = null,
    val error: String? = null
)
