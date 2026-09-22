package com.zhiwo.shiguangjian.data.db.dao

import androidx.room.*
import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals ORDER BY status DESC, sortOrder ASC, id ASC")
    fun getAllGoals(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE status = 'active' ORDER BY sortOrder ASC, id ASC")
    fun getActiveGoals(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: Long): GoalEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans ORDER BY status DESC, COALESCE(startDate,'') DESC, id ASC")
    fun getAllPlans(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE goalId = :goalId ORDER BY sortOrder ASC, id ASC")
    fun getPlansByGoal(goalId: Long): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun getById(id: Long): PlanEntity?

    @Query("SELECT COUNT(*) FROM plans WHERE goalId = :goalId")
    suspend fun countByGoal(goalId: Long): Int

    @Query("UPDATE plans SET goalId = NULL WHERE goalId = :goalId")
    suspend fun detachFromGoal(goalId: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(plan: PlanEntity): Long

    @Update
    suspend fun update(plan: PlanEntity)

    @Query("DELETE FROM plans WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM plans WHERE goalId = :goalId")
    suspend fun deleteByGoal(goalId: Long)
}
