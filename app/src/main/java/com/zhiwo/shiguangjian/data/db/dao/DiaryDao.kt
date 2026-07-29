package com.zhiwo.shiguangjian.data.db.dao

import androidx.room.*
import com.zhiwo.shiguangjian.data.db.entity.DiaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diaries ORDER BY date DESC")
    fun getAllDiaries(): Flow<List<DiaryEntity>>

    @Query("SELECT * FROM diaries WHERE date = :date LIMIT 1")
    suspend fun getDiaryByDate(date: String): DiaryEntity?

    @Query("SELECT * FROM diaries WHERE content LIKE '%' || :query || '%' ORDER BY date DESC")
    fun searchDiaries(query: String): Flow<List<DiaryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiary(diary: DiaryEntity): Long

    @Update
    suspend fun updateDiary(diary: DiaryEntity)

    @Query("DELETE FROM diaries WHERE id = :id")
    suspend fun deleteDiaryById(id: Long)

    @Query("SELECT COUNT(*) FROM diaries")
    suspend fun getDiaryCount(): Int

    @Query("UPDATE diaries SET exported = 1 WHERE id = :id")
    suspend fun markAsExported(id: Long)

    @Query("UPDATE diaries SET exported = 1 WHERE id IN (:ids)")
    suspend fun markAllAsExported(ids: List<Long>)

    @Query("DELETE FROM diaries WHERE exported = 1")
    suspend fun deleteExportedDiaries()

    @Query("SELECT COUNT(*) FROM diaries WHERE exported = 1")
    suspend fun getExportedCount(): Int
}
