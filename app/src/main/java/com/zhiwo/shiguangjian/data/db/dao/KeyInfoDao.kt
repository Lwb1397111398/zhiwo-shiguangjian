package com.zhiwo.shiguangjian.data.db.dao

import androidx.room.*
import com.zhiwo.shiguangjian.data.db.entity.KeyInfoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyInfoDao {
    @Query("SELECT * FROM keyInfos ORDER BY id ASC")
    suspend fun getAllKeyInfos(): List<KeyInfoEntity>

    @Query("SELECT * FROM keyInfos WHERE recordId = :recordId ORDER BY id ASC")
    fun getKeyInfosByRecordId(recordId: Long): Flow<List<KeyInfoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKeyInfo(keyInfo: KeyInfoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKeyInfos(keyInfos: List<KeyInfoEntity>)

    @Delete
    suspend fun deleteKeyInfo(keyInfo: KeyInfoEntity)

    @Query("DELETE FROM keyInfos WHERE recordId = :recordId")
    suspend fun deleteKeyInfosByRecordId(recordId: Long)
}
