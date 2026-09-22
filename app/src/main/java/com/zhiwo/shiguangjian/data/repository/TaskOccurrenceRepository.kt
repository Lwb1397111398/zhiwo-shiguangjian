package com.zhiwo.shiguangjian.data.repository

import androidx.room.withTransaction
import com.zhiwo.shiguangjian.data.db.AppDatabase
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import com.zhiwo.shiguangjian.data.tasks.deadlineDate

/**
 * 打卡的唯一写入口。撤销完成写软标记 pending 而不是删行——用户填过的原因要留着。
 * 同一 (taskId,date) 永远一条：先查后改，不靠 REPLACE（自增主键下 REPLACE 根本不幂等）。
 */
class TaskOccurrenceRepository(private val db: AppDatabase) {

    private val dao = db.occurrenceDao()
    private val taskDao = db.taskDao()

    suspend fun setCheck(
        taskId: Long,
        date: String,
        status: String,
        reasonCode: String = "",
        reasonNote: String = "",
        actualMinutes: Int = 0,
        note: String = "",
        now: String
    ): TaskOccurrenceEntity = db.withTransaction {
        val existing = dao.findByTaskAndDate(taskId, date)
        val merged = existing?.copy(
            status = status,
            reasonCode = if (status == "done" && reasonCode.isEmpty()) existing.reasonCode else reasonCode,
            reasonNote = if (reasonCode.isEmpty() && status != "not_done") existing.reasonNote else reasonNote,
            actualMinutes = if (actualMinutes > 0) actualMinutes else existing.actualMinutes,
            note = if (note.isNotBlank()) note else existing.note,
            updatedAt = now
        ) ?: TaskOccurrenceEntity(
            taskId = taskId, date = date, status = status, reasonCode = reasonCode,
            reasonNote = reasonNote, actualMinutes = actualMinutes, note = note, createdAt = now
        )
        if (existing == null) {
            if (dao.insert(merged) < 0) {
                dao.findByTaskAndDate(taskId, date)?.let { dao.update(merged.copy(id = it.id)) }
            }
        } else {
            dao.update(merged)
        }
        merged
    }

    suspend fun occurrencesOn(date: String) = dao.observeForDate(date)

    suspend fun countByTask(taskId: Long) = dao.countByTask(taskId)
}

/**
 * v13~v14 过渡期的双写垫片：新列是真值，老列继续供老 UI / 闹钟 / 开机恢复取数。
 * v14 删老列时整个文件一起删。
 */
class TaskWriteBridge(private val db: AppDatabase) {

    private val taskDao = db.taskDao()
    private val occurrences = TaskOccurrenceRepository(db)

    suspend fun check(task: TaskEntity, date: String, now: String) {
        occurrences.setCheck(task.id, date, "done", now = now)
        taskDao.updateTask(task.copy(isCompleted = true, completedAt = now, dailyCompletionDate = date))
    }

    suspend fun uncheck(task: TaskEntity, date: String) {
        occurrences.setCheck(task.id, date, "pending", now = date)
        taskDao.updateTask(task.copy(isCompleted = false, completedAt = null, dailyCompletionDate = null))
    }

    suspend fun markNotDone(
        task: TaskEntity, date: String, reasonCode: String, reasonNote: String, now: String
    ) {
        occurrences.setCheck(task.id, date, "not_done", reasonCode, reasonNote, now = now)
    }

    /** 留白回填：除了打卡记录，还要镜像老列，否则日历页/记录卡片仍显示未完成 */
    suspend fun checkBlank(task: TaskEntity, date: String, note: String, minutes: Int, now: String) =
        db.withTransaction {
            occurrences.setCheck(task.id, date, "done", actualMinutes = minutes, note = note, now = now)
            taskDao.updateTask(task.copy(isCompleted = true, completedAt = now, dailyCompletionDate = date))
        }

    /** 老"真正完成"的等价动作：归档，且取消归档要能回到 active */
    suspend fun archive(task: TaskEntity) = taskDao.updateTask(
        task.copy(status = "archived", isPermanentlyCompleted = true)
    )

    suspend fun pause(task: TaskEntity) = taskDao.updateTask(task.copy(status = "paused"))

    suspend fun resume(task: TaskEntity) = taskDao.updateTask(task.copy(status = "active"))

    /**
     * 新建/编辑任务。反向映射是硬要求：闹钟与开机恢复只认老列
     * （SmartScheduleManager 读 taskType + 带时间的 dueDate；BootReceiver 遇到空 dueDate 直接跳过），
     * 只写新列的话用户新建的每日任务永远不会响。
     */
    suspend fun saveTask(task: TaskEntity): Long = db.withTransaction {
        val legacy = task.copy(
            taskType = when {
                task.kind == "daily" && task.repeatRule == "custom" -> "weekly"
                task.kind == "daily" || task.kind == "blank" -> "daily"
                else -> "once"
            },
            dueDate = legacyDueDate(task),
            parentGoalId = task.goalId?.let { goalId -> legacyRecordIdOfGoal(goalId) } ?: task.parentGoalId,
            isCompleted = task.isCompleted
        )
        if (legacy.id == 0L) taskDao.insertTask(legacy) else {
            taskDao.updateTask(legacy)
            legacy.id
        }
    }

    private suspend fun legacyRecordIdOfGoal(goalId: Long): Long? =
        db.goalDao().getById(goalId)?.recordId

    private fun legacyDueDate(task: TaskEntity): String {
        // 闹钟与开机恢复只认带时间的老 dueDate：每日/留白任务没有 dueDate 时用开始日（再退到创建日）当锚，
        // 否则新用户的每日任务永远不响
        val date = when (task.kind) {
            "adhoc" -> task.scheduledDate.ifBlank { task.deadlineDate }
            else -> task.dueDate.take(10).ifBlank {
                task.startDate.ifBlank { task.createdAt.take(10) }
            }
        }
        return when {
            date.isBlank() -> ""
            task.remindTime.isNotBlank() -> "$date ${task.remindTime}:00"
            task.dueDate.contains(' ') -> task.dueDate
            else -> date
        }
    }
}
