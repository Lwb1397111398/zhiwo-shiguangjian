package com.zhiwo.shiguangjian.data.repository

import com.zhiwo.shiguangjian.data.db.dao.MemoryDao
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val memoryDao: MemoryDao) {

    fun getAllMemories(): Flow<List<MemoryEntity>> = memoryDao.getAllMemories()

    suspend fun getMemoryById(id: Long): MemoryEntity? = memoryDao.getMemoryById(id)

    suspend fun insertMemory(memory: MemoryEntity): Long = memoryDao.insertMemory(memory)

    suspend fun updateMemory(memory: MemoryEntity) = memoryDao.updateMemory(memory)

    suspend fun deleteMemory(id: Long) = memoryDao.deleteMemoryById(id)

    suspend fun getMemoryCount(): Int = memoryDao.getMemoryCount()

    suspend fun enforceMemoryLimit(maxCount: Int = 100) {
        val currentCount = memoryDao.getMemoryCount()
        if (currentCount > maxCount) {
            memoryDao.deleteOldestMemories(currentCount - maxCount)
        }
    }
}
