package com.zhiwo.shiguangjian.data.db.dao

import androidx.room.*
import com.zhiwo.shiguangjian.data.db.entity.MomentPhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MomentPhotoDao {
    @Query("SELECT * FROM moment_photos WHERE date = :date ORDER BY createdAt ASC")
    fun observeByDate(date: String): Flow<List<MomentPhotoEntity>>

    @Query("SELECT * FROM moment_photos WHERE date = :date ORDER BY createdAt ASC")
    suspend fun getByDate(date: String): List<MomentPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: MomentPhotoEntity): Long

    @Query("DELETE FROM moment_photos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM moment_photos WHERE date = :date")
    fun observeCountByDate(date: String): Flow<Int>
}
