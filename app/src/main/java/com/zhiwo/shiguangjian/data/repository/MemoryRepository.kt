package com.zhiwo.shiguangjian.data.repository

import com.zhiwo.shiguangjian.data.db.dao.MemoryDao
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.memory.MemoryRetention
import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val memoryDao: MemoryDao) {

    fun getAllMemories(): Flow<List<MemoryEntity>> = memoryDao.getAllMemories()

    fun getActiveMemories(): Flow<List<MemoryEntity>> = memoryDao.getActiveMemories()

    /** 「已更正的记忆」二级页数据源 */
    fun getSupersededMemories(): Flow<List<MemoryEntity>> = memoryDao.getSupersededMemories()

    suspend fun getMemoryById(id: Long): MemoryEntity? = memoryDao.getMemoryById(id)

    suspend fun getMemoriesByIds(ids: List<Long>): List<MemoryEntity> = memoryDao.getMemoriesByIds(ids)

    suspend fun insertMemory(memory: MemoryEntity): Long = memoryDao.insertMemory(memory)

    suspend fun updateMemory(memory: MemoryEntity) = memoryDao.updateMemory(memory)

    suspend fun updateMemories(memories: List<MemoryEntity>) = memoryDao.updateMemories(memories)

    /** 物理删除：只允许显式动作（记忆页"彻底删除"、DELETE_MEMORY 提案、启动清理）走到这里 */
    suspend fun deleteMemory(id: Long) = memoryDao.deleteMemoryById(id)

    suspend fun deleteMemories(ids: List<Long>) = memoryDao.deleteMemoriesByIds(ids)

    suspend fun getMemoryCount(): Int = memoryDao.getMemoryCount()

    suspend fun getSupersededOnce(): List<MemoryEntity> = memoryDao.getSupersededMemoriesOnce()

    suspend fun getUnderReviewMemories(): List<MemoryEntity> = memoryDao.getUnderReviewMemories()

    /** 恢复被更正停用的记忆：清空取代链，重新生效 */
    suspend fun restoreMemory(id: Long, now: String) {
        val memory = memoryDao.getMemoryById(id) ?: return
        if (memory.status != "superseded") return
        memoryDao.updateMemory(
            memory.copy(status = "active", supersededBy = null, note = null, updatedAt = now)
        )
    }

    /** 只保留最近 [keep] 条已更正记忆，返回该归档并物理删掉的旧条目（写文件由调用方做） */
    suspend fun pruneSuperseded(keep: Int): List<MemoryEntity> {
        val all = memoryDao.getSupersededMemoriesOnce()
        val stale = MemoryRetention.supersededToPrune(all, keep)
        if (stale.isEmpty()) return emptyList()
        memoryDao.deleteMemoriesByIds(stale.map { it.id })
        return stale
    }

    /**
     * 容量清理：先淘汰最旧的 superseded，不够再淘汰最旧的 active（口径见 [MemoryDao.deleteOldestMemories]）。
     * under_review 永不参与淘汰。
     */
    suspend fun enforceMemoryLimit(maxCount: Int = 100) {
        val currentCount = memoryDao.getMemoryCount()
        if (currentCount > maxCount) {
            memoryDao.deleteOldestMemories(currentCount - maxCount)
        }
    }
}
