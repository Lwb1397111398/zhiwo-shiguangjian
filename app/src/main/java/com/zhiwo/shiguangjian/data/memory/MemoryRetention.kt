package com.zhiwo.shiguangjian.data.memory

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `superseded` 记忆的保留策略（纯函数，零 Android 依赖）。
 *
 * 背景：整理只把旧记忆置为 `superseded`，从不物理删；界面上又没有删除入口，
 * 用户看到的就是"整理过了但残存的记忆还在"。现在这些记忆集中到一个二级页，
 * 并且启动时只保留最近 [SUPERSEDED_KEEP] 条，更老的先归档成 JSON 再物理删。
 */
object MemoryRetention {

    /** 保留最近多少条已更正记忆（供"恢复"用），更旧的归档后删除 */
    const val SUPERSEDED_KEEP = 30

    /** 归档目录名（getExternalFilesDir 的 subfolder） */
    const val ARCHIVE_DIR = "migration_backup"

    private val gson = Gson()

    /**
     * 需要清掉的旧 `superseded` 记忆：按写入时间倒序保留最近 [keep] 条，其余返回。
     * createdAt 是 ISO 字符串，字典序即时间序；相同时间按 id 倒序保证结果稳定。
     */
    fun supersededToPrune(superseded: List<MemoryEntity>, keep: Int = SUPERSEDED_KEEP): List<MemoryEntity> {
        if (keep < 0) return superseded.sortedBy { it.createdAt }
        val sorted = superseded.sortedWith(
            compareByDescending<MemoryEntity> { it.createdAt }.thenByDescending { it.id }
        )
        return if (sorted.size <= keep) emptyList() else sorted.drop(keep)
    }

    /** 归档 JSON：整份可人工读回的数组，字段名固定，不依赖类名（R8 改名安全） */
    fun archiveJson(memories: List<MemoryEntity>): String = gson.toJson(toArchived(memories))

    /** 归档文件名：superseded_archive_<时间戳>.json */
    fun archiveFileName(timestampMillis: Long): String = "superseded_archive_$timestampMillis.json"

    /** 时间戳字符串（文件名用），与 [archiveFileName] 配套 */
    fun archiveTimestamp(millis: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(millis))

    /** 归档记录的行结构（仅供人工抢救查看，不参与反序列化） */
    data class Archived(
        @SerializedName("id") val id: Long,
        @SerializedName("content") val content: String,
        @SerializedName("status") val status: String,
        @SerializedName("createdAt") val createdAt: String,
        @SerializedName("occurredAt") val occurredAt: String,
        @SerializedName("note") val note: String?
    )

    fun toArchived(memories: List<MemoryEntity>): List<Archived> = memories.map {
        Archived(it.id, it.content, it.status, it.createdAt, it.occurredAt, it.note)
    }
}
