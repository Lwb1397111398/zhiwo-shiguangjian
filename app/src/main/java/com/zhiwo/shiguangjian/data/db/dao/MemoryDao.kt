package com.zhiwo.shiguangjian.data.db.dao

import androidx.room.*
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    /** 主列表：按写入时间倒序（createdAt 现在是真实写库时间，刚整理出的记忆排最上） */
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun getAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE status = 'active' ORDER BY createdAt DESC")
    fun getActiveMemories(): Flow<List<MemoryEntity>>

    /** 已更正（被取代）的记忆：二级页展示 + 启动时按保留条数清理 */
    @Query("SELECT * FROM memories WHERE status = 'superseded' ORDER BY createdAt DESC")
    fun getSupersededMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE status = 'superseded' ORDER BY createdAt DESC")
    suspend fun getSupersededMemoriesOnce(): List<MemoryEntity>

    /** 卡在 under_review 的记忆（对账孤儿检测用） */
    @Query("SELECT * FROM memories WHERE status = 'under_review'")
    suspend fun getUnderReviewMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getMemoryById(id: Long): MemoryEntity?

    @Query("SELECT * FROM memories WHERE id IN (:ids)")
    suspend fun getMemoriesByIds(ids: List<Long>): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Update
    suspend fun updateMemories(memories: List<MemoryEntity>)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM memories WHERE id IN (:ids)")
    suspend fun deleteMemoriesByIds(ids: List<Long>)

    @Query("DELETE FROM memories WHERE status = 'superseded'")
    suspend fun deleteAllSuperseded()

    /** 容量口径：只数生效中的记忆（历史上把 superseded 也数进来，等于白白挤掉有效记忆） */
    @Query("SELECT COUNT(*) FROM memories WHERE status = 'active'")
    suspend fun getMemoryCount(): Int

    /**
     * 容量清理：跳过 under_review（有待确认建议指向的记忆不能被静默挤掉）；
     * 淘汰顺序 = 先最旧的 superseded，不够再淘汰最旧的 active。
     */
    @Query("""DELETE FROM memories WHERE id IN (
        SELECT id FROM memories WHERE status = 'active'
        ORDER BY CASE WHEN status = 'superseded' THEN 0 ELSE 1 END ASC, createdAt ASC LIMIT :count
    )""")
    suspend fun deleteOldestMemories(count: Int)

    /** 主列表/统计用的分状态计数 */
    @Query("SELECT COUNT(*) FROM memories WHERE status = :status")
    suspend fun getCountByStatus(status: String): Int
}
