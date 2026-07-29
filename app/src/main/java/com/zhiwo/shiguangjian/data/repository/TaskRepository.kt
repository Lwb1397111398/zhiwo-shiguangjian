package com.zhiwo.shiguangjian.data.repository

import com.zhiwo.shiguangjian.data.db.dao.TaskDao
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {

    fun getAllTasks(): Flow<List<TaskEntity>> = taskDao.getAllTasks()

    fun getTasksByRecordId(recordId: Long): Flow<List<TaskEntity>> =
        taskDao.getTasksByRecordId(recordId)

    fun getTasksByParentGoalId(goalId: Long): Flow<List<TaskEntity>> =
        taskDao.getTasksByParentGoalId(goalId)

    fun getTasksByType(type: String): Flow<List<TaskEntity>> =
        taskDao.getTasksByType(type)

    suspend fun getTaskById(id: Long): TaskEntity? = taskDao.getTaskById(id)

    suspend fun insertTask(task: TaskEntity): Long = taskDao.insertTask(task)

    suspend fun insertTasks(tasks: List<TaskEntity>) = taskDao.insertTasks(tasks)

    suspend fun updateTask(task: TaskEntity) = taskDao.updateTask(task)

    suspend fun deleteTask(id: Long) = taskDao.deleteTaskById(id)

    suspend fun deleteTasksByRecordId(recordId: Long) =
        taskDao.deleteTasksByRecordId(recordId)

    suspend fun deleteTasksByParentGoalId(goalId: Long) =
        taskDao.deleteTasksByParentGoalId(goalId)

    suspend fun getTaskCount(): Int = taskDao.getTaskCount()
}
