package com.zhiwo.shiguangjian.data.db.dao

import androidx.room.*
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Query("SELECT * FROM records ORDER BY createdAt DESC")
    fun getAllRecords(): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getRecordById(id: Long): RecordEntity?

    @Query("SELECT * FROM records WHERE id = :id")
    fun getRecordByIdFlow(id: Long): Flow<RecordEntity?>

    @Query("SELECT * FROM records WHERE category = :category ORDER BY createdAt DESC")
    fun getRecordsByCategory(category: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE createdAt BETWEEN :startTime AND :endTime ORDER BY createdAt DESC")
    suspend fun getRecordsByDateRange(startTime: String, endTime: String): List<RecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: RecordEntity): Long

    @Update
    suspend fun updateRecord(record: RecordEntity)

    @Delete
    suspend fun deleteRecord(record: RecordEntity)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun deleteRecordById(id: Long)

    @Query("SELECT COUNT(*) FROM records")
    suspend fun getRecordCount(): Int

    @Query("SELECT * FROM records WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchRecords(query: String): Flow<List<RecordEntity>>
}
