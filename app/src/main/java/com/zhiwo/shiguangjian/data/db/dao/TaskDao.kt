package com.zhiwo.shiguangjian.data.db.dao

import androidx.room.*
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    suspend fun getAllTasksList(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE recordId = :recordId ORDER BY createdAt ASC")
    fun getTasksByRecordId(recordId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE parentGoalId = :goalId ORDER BY createdAt ASC")
    fun getTasksByParentGoalId(goalId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE taskType = :type ORDER BY createdAt ASC")
    fun getTasksByType(type: String): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(task: TaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTasks(tasks: List<TaskEntity>)

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Query("DELETE FROM tasks WHERE recordId = :recordId")
    suspend fun deleteTasksByRecordId(recordId: Long)

    @Query("DELETE FROM tasks WHERE parentGoalId = :goalId")
    suspend fun deleteTasksByParentGoalId(goalId: Long)

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun getTaskCount(): Int
}
