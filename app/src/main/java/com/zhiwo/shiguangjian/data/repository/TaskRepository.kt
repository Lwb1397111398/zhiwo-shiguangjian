package com.zhiwo.shiguangjian.data.repository

import com.zhiwo.shiguangjian.data.db.dao.TaskDao
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {

    fun getAllTasks(): Flow<List<TaskEntity>> = taskDao.getAllTasks()

    /**
     * 每日任务跨天重置（单一入口，应用启动时触发）：
     * 把"已勾选但完成日期不是今天且未真正完成"的 daily 任务重置为未完成。
     * 返回重置条数。
     */
    suspend fun resetDailyTasksForNewDay(today: String): Int {
        // 新模型按 kind 判定：每日固定（含每周型）与留白都要跨天复位；
        // taskType 分支只为兜住还没回填新列的存量数据
        val recurring = it@ { t: com.zhiwo.shiguangjian.data.db.entity.TaskEntity ->
            t.kind == "daily" || t.kind == "blank" || t.taskType == "daily" || t.taskType == "weekly"
        }
        val stale = taskDao.getAllTasksList().filter {
            recurring(it) && it.isCompleted && !it.isPermanentlyCompleted &&
                (it.dailyCompletionDate ?: "") != today
        }
        for (task in stale) {
            taskDao.updateTask(task.copy(isCompleted = false, completedAt = null, dailyCompletionDate = null))
        }
        return stale.size
    }

    fun getTasksByRecordId(recordId: Long): Flow<List<TaskEntity>> =
        taskDao.getTasksByRecordId(recordId)

    fun getTasksByParentGoalId(goalId: Long): Flow<List<TaskEntity>> =
        taskDao.getTasksByParentGoalId(goalId)

    suspend fun getTaskById(id: Long): TaskEntity? = taskDao.getTaskById(id)

    suspend fun insertTask(task: TaskEntity): Long = taskDao.insertTask(task)

    suspend fun updateTask(task: TaskEntity) = taskDao.updateTask(task)

    suspend fun deleteTasksByRecordId(recordId: Long) =
        taskDao.deleteTasksByRecordId(recordId)

    suspend fun getTaskCount(): Int = taskDao.getTaskCount()
}
