package com.zhiwo.shiguangjian.data.db.dao

import androidx.room.*
import com.zhiwo.shiguangjian.data.db.entity.DayOverrideEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskOccurrenceDao {
    @Query("SELECT * FROM task_occurrences WHERE date = :date")
    fun observeForDate(date: String): Flow<List<TaskOccurrenceEntity>>

    @Query("SELECT * FROM task_occurrences WHERE date BETWEEN :from AND :to")
    fun observeInRange(from: String, to: String): Flow<List<TaskOccurrenceEntity>>

    @Query("SELECT * FROM task_occurrences WHERE date = :date")
    suspend fun getForDate(date: String): List<TaskOccurrenceEntity>

    @Query("SELECT * FROM task_occurrences WHERE taskId = :taskId AND date = :date")
    suspend fun findByTaskAndDate(taskId: Long, date: String): TaskOccurrenceEntity?

    @Query("SELECT COUNT(*) FROM task_occurrences WHERE taskId = :taskId")
    suspend fun countByTask(taskId: Long): Int

    /** 用 id=0 让自增主键生效；冲突即忽略，调用方按返回 -1 判定"已存在→改走 update" */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(occurrence: TaskOccurrenceEntity): Long

    @Update
    suspend fun update(occurrence: TaskOccurrenceEntity)

    @Query("DELETE FROM task_occurrences WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)
}

@Dao
interface DayOverrideDao {
    @Query("SELECT * FROM day_overrides")
    fun getAll(): Flow<List<DayOverrideEntity>>

    @Query("SELECT * FROM day_overrides WHERE date = :date")
    suspend fun get(date: String): DayOverrideEntity?

    @Upsert
    suspend fun upsert(override: DayOverrideEntity)

    @Query("DELETE FROM day_overrides WHERE date = :date")
    suspend fun clear(date: String)
}
