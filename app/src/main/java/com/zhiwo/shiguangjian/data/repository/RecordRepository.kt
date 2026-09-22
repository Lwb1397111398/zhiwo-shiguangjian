package com.zhiwo.shiguangjian.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.zhiwo.shiguangjian.data.db.dao.*
import com.zhiwo.shiguangjian.data.db.entity.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class RecordRepository(
    private val database: RoomDatabase,
    private val recordDao: RecordDao,
    private val taskDao: TaskDao,
    private val tagDao: TagDao,
    private val keyInfoDao: KeyInfoDao
) {
    fun getAllRecords(): Flow<List<RecordEntity>> = recordDao.getAllRecords()

    fun getRecordsByCategory(category: String): Flow<List<RecordEntity>> =
        recordDao.getRecordsByCategory(category)

    fun searchRecords(query: String): Flow<List<RecordEntity>> =
        recordDao.searchRecords(query)

    suspend fun getRecordById(id: Long): RecordEntity? = recordDao.getRecordById(id)

    suspend fun getRecordsByDate(dateStr: String): List<RecordEntity> {
        val start = "${dateStr}T00:00:00.000Z"
        val end = "${dateStr}T23:59:59.999Z"
        return recordDao.getRecordsByDateRange(start, end)
    }

    suspend fun insertRecord(record: RecordEntity): Long = recordDao.insertRecord(record)

    suspend fun updateRecord(record: RecordEntity) = recordDao.updateRecord(record)

    /** 删记录并保留其任务（任务摘链，打卡史不动）——整理/合并/转记忆一律走这条 */
    suspend fun deleteRecordKeepingTasks(id: Long) {
        database.withTransaction {
            taskDao.detachTasksFromRecord(id)
            tagDao.deleteRecordTagsByRecordId(id)
            keyInfoDao.deleteKeyInfosByRecordId(id)
            recordDao.deleteRecordById(id)
            tagDao.deleteOrphanTags()
        }
    }

    suspend fun getTasksForRecord(recordId: Long): List<TaskEntity> =
        taskDao.getTasksByRecordId(recordId).first()

    suspend fun getTagsForRecord(recordId: Long): List<TagEntity> {
        val tagIds = tagDao.getTagIdsForRecord(recordId)
        return tagIds.mapNotNull { tagDao.getTagById(it) }
    }

    suspend fun getKeyInfosForRecord(recordId: Long): List<KeyInfoEntity> =
        keyInfoDao.getKeyInfosByRecordId(recordId).first()

    suspend fun addTagToRecord(recordId: Long, tagName: String, color: String = "#6B8E9F") {
        var tag = tagDao.getTagByName(tagName)
        val tagId = tag?.id ?: tagDao.insertTag(TagEntity(name = tagName, color = color))
        tagDao.insertRecordTagCrossRef(RecordTagCrossRef(recordId, tagId))
    }

    suspend fun removeTagFromRecord(recordId: Long, tagId: Long) {
        tagDao.deleteRecordTagCrossRef(recordId, tagId)
        if (tagDao.getRecordTagCount(tagId) == 0) {
            tagDao.deleteTagById(tagId)
        }
    }

    suspend fun getRecordCount(): Int = recordDao.getRecordCount()
}
