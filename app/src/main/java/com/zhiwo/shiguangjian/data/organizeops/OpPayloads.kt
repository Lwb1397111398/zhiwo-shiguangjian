package com.zhiwo.shiguangjian.data.organizeops

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest

// ========== Op 类型与状态 ==========

object OpType {
    const val SAVE_MEMORY = "SAVE_MEMORY"
    const val DELETE_SOURCE = "DELETE_SOURCE"
    const val EVOLVE_MEMORY = "EVOLVE_MEMORY"

    /** 合并多条记忆为一条（旧行置 superseded 指向新行，不物理删） */
    const val MERGE_MEMORY = "MERGE_MEMORY"

    /** 拆分一条记忆为多条 */
    const val SPLIT_MEMORY = "SPLIT_MEMORY"

    /** 物理删除一条记忆——整理链里**唯一**允许物理删记忆的动作 */
    const val DELETE_MEMORY = "DELETE_MEMORY"

    /**
     * 批次执行顺序：先改现有记忆（演化/合并/拆分），再存新记忆，最后删记忆、删源数据。
     * 注意：新增 op 类型必须同时改这里与 [OpValidator.decide]，漏一处该类型就静默失效。
     */
    fun executionOrder(): List<String> = listOf(
        EVOLVE_MEMORY, MERGE_MEMORY, SPLIT_MEMORY, DELETE_MEMORY, SAVE_MEMORY, DELETE_SOURCE
    )
}

object OpStatus {
    const val PENDING = "PENDING"
    const val APPLIED = "APPLIED"
    const val DISMISSED = "DISMISSED"
    const val FAILED = "FAILED"
    const val STALE = "STALE"
    /** 前置依赖未全部成功，禁止执行（防"源删了记忆没存"） */
    const val BLOCKED = "BLOCKED"

    /** 非终态：还需要用户处理的提案（跨批次常驻区取这些） */
    val UNFINISHED = listOf(PENDING, FAILED, STALE, BLOCKED)
}

// ========== Payload 模型（payloadJson 的结构契约） ==========
// 这些类会被 Gson 反射读写并持久化进 organize_ops.payloadJson，
// 字段名一律用 @SerializedName 钉死，R8 改名后旧提案才不至于解析成半截数据。

/** 存入一条记忆 */
data class SaveMemoryPayload(
    @SerializedName("content") val content: String,
    /** 来源日期：写入时落到 memories.occurredAt，createdAt 用真实时间 */
    @SerializedName("sourceDate") val sourceDate: String = ""
)

/** 删除源记录/评价（内容哈希优先做 STALE 校验，版本快照为降级手段） */
data class DeleteSourcePayload(
    @SerializedName("label") val label: String = "",
    @SerializedName("recordIds") val recordIds: List<Long> = emptyList(),
    @SerializedName("reviewIds") val reviewIds: List<Long> = emptyList(),
    @SerializedName("recordVersions") val recordVersions: Map<Long, String> = emptyMap(),  // id -> updatedAt（降级校验）
    @SerializedName("reviewVersions") val reviewVersions: Map<Long, String> = emptyMap(),
    @SerializedName("recordHashes") val recordHashes: Map<Long, String> = emptyMap(),    // id -> md5(title + content)
    @SerializedName("reviewHashes") val reviewHashes: Map<Long, String> = emptyMap(),    // id -> md5(content)
    /** 用户明示"只删不存"（双重确认后）：执行时跳过依赖检查，哈希/存在性校验照常 */
    @SerializedName("forceDelete") val forceDelete: Boolean = false
)

/** 记忆演化：旧记忆停用，新表述入库 */
data class EvolveMemoryPayload(
    @SerializedName("memoryId") val memoryId: Long,
    @SerializedName("oldText") val oldText: String,
    @SerializedName("newText") val newText: String
)

/** 合并多条记忆：新行 active，旧行全部 superseded 且 supersededBy 指向新行 */
data class MergeMemoryPayload(
    @SerializedName("memoryIds") val memoryIds: List<Long> = emptyList(),
    @SerializedName("mergedContent") val mergedContent: String = "",
    /** memoryId -> 提案时该内容指纹；执行前重读不一致即 STALE */
    @SerializedName("contentHashes") val contentHashes: Map<Long, String> = emptyMap()
)

/** 拆分一条记忆为多条：新行全部 active，原行 superseded 指向第一条 */
data class SplitMemoryPayload(
    @SerializedName("memoryId") val memoryId: Long = 0,
    @SerializedName("parts") val parts: List<String> = emptyList(),
    @SerializedName("contentHash") val contentHash: String = ""
)

/** 物理删除一条记忆（用户显式动作，唯一允许物理删的整理动作） */
data class DeleteMemoryPayload(
    @SerializedName("memoryId") val memoryId: Long = 0,
    @SerializedName("contentHash") val contentHash: String = ""
)

object OpPayloads {
    private val gson = Gson()

    fun encode(payload: Any): String = gson.toJson(payload)

    fun decodeSaveMemory(json: String): SaveMemoryPayload? =
        runCatching { gson.fromJson(json, SaveMemoryPayload::class.java) }.getOrNull()

    fun decodeDeleteSource(json: String): DeleteSourcePayload? =
        runCatching { gson.fromJson(json, DeleteSourcePayload::class.java) }.getOrNull()

    fun decodeEvolveMemory(json: String): EvolveMemoryPayload? =
        runCatching { gson.fromJson(json, EvolveMemoryPayload::class.java) }.getOrNull()

    fun decodeMergeMemory(json: String): MergeMemoryPayload? =
        runCatching { gson.fromJson(json, MergeMemoryPayload::class.java) }.getOrNull()

    fun decodeSplitMemory(json: String): SplitMemoryPayload? =
        runCatching { gson.fromJson(json, SplitMemoryPayload::class.java) }.getOrNull()

    fun decodeDeleteMemory(json: String): DeleteMemoryPayload? =
        runCatching { gson.fromJson(json, DeleteMemoryPayload::class.java) }.getOrNull()

    /** dependsOn 列表编解码（实体列存 JSON 数组文本） */
    fun encodeDependsOn(opIds: List<String>): String = gson.toJson(opIds)

    fun decodeDependsOn(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 内容指纹：MD5 取前 16 个十六进制位，用于 STALE 判定（避免 updatedAt 误报） */
    fun contentHash(vararg parts: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(parts.joinToString("|") { it }.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
