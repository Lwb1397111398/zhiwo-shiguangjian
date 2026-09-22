package com.zhiwo.shiguangjian.data.db.dao

import androidx.room.*
import com.zhiwo.shiguangjian.data.db.entity.OrganizeOpEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OrganizeOpDao {

    @Query("SELECT * FROM organize_ops WHERE batchId = :batchId ORDER BY createdAt ASC")
    fun getOpsByBatch(batchId: String): Flow<List<OrganizeOpEntity>>

    @Query("SELECT * FROM organize_ops WHERE batchId = :batchId ORDER BY createdAt ASC")
    suspend fun getOpsByBatchOnce(batchId: String): List<OrganizeOpEntity>

    @Query("SELECT * FROM organize_ops WHERE id = :id")
    suspend fun getOpById(id: String): OrganizeOpEntity?

    @Query("SELECT * FROM organize_ops WHERE id IN (:ids)")
    suspend fun getOpsByIds(ids: List<String>): List<OrganizeOpEntity>

    /**
     * 跨批次的非终态提案（PENDING / FAILED / STALE / BLOCKED）：
     * 上一批遗留的提案必须一直可见、可继续执行，不能因为"界面只认最新批次"就永埋地下。
     */
    @Query("""SELECT * FROM organize_ops
        WHERE status IN ('PENDING', 'FAILED', 'STALE', 'BLOCKED')
        ORDER BY batchId ASC, createdAt ASC""")
    fun getUnfinishedOps(): Flow<List<OrganizeOpEntity>>

    @Query("""SELECT * FROM organize_ops
        WHERE status IN ('PENDING', 'FAILED', 'STALE', 'BLOCKED')
        ORDER BY batchId ASC, createdAt ASC""")
    suspend fun getUnfinishedOpsOnce(): List<OrganizeOpEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOps(ops: List<OrganizeOpEntity>)

    @Update
    suspend fun updateOp(op: OrganizeOpEntity)

    @Query("DELETE FROM organize_ops WHERE batchId = :batchId AND status IN ('APPLIED', 'DISMISSED')")
    suspend fun clearFinishedOps(batchId: String)

    /** 跨批次清理全部已完结提案（整理页「清理已完结」） */
    @Query("DELETE FROM organize_ops WHERE status IN ('APPLIED', 'DISMISSED')")
    suspend fun clearAllFinishedOps()

    @Query("SELECT COUNT(DISTINCT batchId) FROM organize_ops")
    suspend fun getBatchCount(): Int

    @Query("SELECT * FROM organize_ops ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestOp(): OrganizeOpEntity?
}
