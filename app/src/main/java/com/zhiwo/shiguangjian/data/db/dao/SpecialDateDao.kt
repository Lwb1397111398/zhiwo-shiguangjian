package com.zhiwo.shiguangjian.data.db.dao

import androidx.room.*
import com.zhiwo.shiguangjian.data.db.entity.SpecialDateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpecialDateDao {
    @Query("SELECT * FROM special_dates ORDER BY month, day")
    fun getAll(): Flow<List<SpecialDateEntity>>

    @Query("SELECT * FROM special_dates ORDER BY month, day")
    suspend fun getAllOnce(): List<SpecialDateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(date: SpecialDateEntity): Long

    @Update
    suspend fun update(date: SpecialDateEntity)

    @Query("DELETE FROM special_dates WHERE id = :id")
    suspend fun deleteById(id: Long)
}
