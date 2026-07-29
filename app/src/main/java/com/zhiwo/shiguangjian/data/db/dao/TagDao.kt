package com.zhiwo.shiguangjian.data.db.dao

import androidx.room.*
import com.zhiwo.shiguangjian.data.db.entity.TagEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordTagCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getTagById(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getTagByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecordTagCrossRef(crossRef: RecordTagCrossRef)

    @Query("SELECT * FROM recordTags ORDER BY recordId ASC, tagId ASC")
    suspend fun getAllRecordTagCrossRefs(): List<RecordTagCrossRef>

    @Query("SELECT tagId FROM recordTags WHERE recordId = :recordId")
    suspend fun getTagIdsForRecord(recordId: Long): List<Long>

    @Query("SELECT tagId FROM recordTags WHERE recordId = :recordId")
    fun getTagIdsForRecordFlow(recordId: Long): Flow<List<Long>>

    @Query("SELECT recordId FROM recordTags WHERE tagId = :tagId")
    suspend fun getRecordIdsForTag(tagId: Long): List<Long>

    @Query("DELETE FROM recordTags WHERE recordId = :recordId AND tagId = :tagId")
    suspend fun deleteRecordTagCrossRef(recordId: Long, tagId: Long)

    @Query("DELETE FROM recordTags WHERE recordId = :recordId")
    suspend fun deleteRecordTagsByRecordId(recordId: Long)

    @Query("SELECT COUNT(*) FROM recordTags WHERE tagId = :tagId")
    suspend fun getRecordTagCount(tagId: Long): Int

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTagById(tagId: Long)

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun getTagCount(): Int

    @Query("DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tagId FROM recordTags)")
    suspend fun deleteOrphanTags()
}
