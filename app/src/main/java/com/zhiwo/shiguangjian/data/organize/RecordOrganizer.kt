package com.zhiwo.shiguangjian.data.organize

import androidx.room.withTransaction
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.alarm.SmartScheduleManager
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.ai.resolveCategory
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.repository.MemoryRepository
import com.zhiwo.shiguangjian.data.repository.RecordRepository
import com.zhiwo.shiguangjian.data.repository.TaskRepository
import kotlinx.coroutines.flow.first

class RecordOrganizer(
    private val app: ZhiwoApplication,
    private val recordRepo: RecordRepository,
    private val taskRepo: TaskRepository,
    private val memoryRepo: MemoryRepository
) {
    suspend fun applyRecordMerge(sourceIds: List<Long>, mergedTitle: String, mergedCategory: String): Boolean {
        val completedTasksToCancel = mutableListOf<TaskEntity>()
        val applied = app.database.withTransaction {
            val sourceRecords = sourceIds.mapNotNull { recordRepo.getRecordById(it) }
            if (sourceRecords.size < 2) return@withTransaction false

            val mergedContent = sourceRecords.joinToString("\n") { it.content }
            val primary = sourceRecords.first()
            val now = DateFormats.nowDateTimeIso()
            val earliestCreatedAt = sourceRecords.minOf { it.createdAt }
            recordRepo.updateRecord(
                primary.copy(
                    title = mergedTitle,
                    content = mergedContent,
                    category = resolveCategory(mergedCategory),
                    createdAt = earliestCreatedAt,
                    updatedAt = now
                )
            )
            sourceRecords.drop(1).forEach { rec ->
                val tasks = taskRepo.getTasksByRecordId(rec.id).first()
                tasks.filter { !it.isCompleted }.forEach { task ->
                    taskRepo.updateTask(task.copy(recordId = primary.id))
                }
                completedTasksToCancel += tasks.filter { it.isCompleted }
                recordRepo.deleteRecord(rec.id)
            }
            true
        }
        if (applied) {
            completedTasksToCancel.forEach { task ->
                SmartScheduleManager.cancelAlarm(app, task.id, task.calendarEventId)
            }
        }
        return applied
    }

    suspend fun applyRecordSplit(sourceId: Long, splits: List<Pair<String, String>>): Boolean {
        return app.database.withTransaction {
            val source = recordRepo.getRecordById(sourceId) ?: return@withTransaction false
            val now = DateFormats.nowDateTimeIso()
            val newRecordIds = mutableListOf<Long>()
            splits.forEach { (title, category) ->
                val id = recordRepo.insertRecord(
                    RecordEntity(
                        title = title,
                        content = source.content,
                        category = resolveCategory(category),
                        summary = source.summary,
                        createdAt = source.createdAt,
                        updatedAt = now
                    )
                )
                newRecordIds.add(id)
            }
            if (newRecordIds.isNotEmpty()) {
                val sourceTasks = taskRepo.getTasksByRecordId(sourceId).first()
                sourceTasks.forEach { task ->
                    taskRepo.updateTask(task.copy(recordId = newRecordIds.first()))
                }
            }
            recordRepo.deleteRecord(sourceId)
            true
        }
    }

    suspend fun applyToMemory(sourceId: Long, memoryContent: String): Boolean {
        val tasksToCancel = mutableListOf<TaskEntity>()
        val applied = app.database.withTransaction {
            val source = recordRepo.getRecordById(sourceId) ?: return@withTransaction false
            val now = DateFormats.nowDateTimeIso()
            memoryRepo.insertMemory(
                MemoryEntity(
                    content = memoryContent,
                    source = "organize",
                    createdAt = source.createdAt,
                    updatedAt = now
                )
            )
            tasksToCancel += taskRepo.getTasksByRecordId(sourceId).first()
            recordRepo.deleteRecord(sourceId)
            true
        }
        if (applied) {
            tasksToCancel.forEach { task -> SmartScheduleManager.cancelAlarm(app, task.id, task.calendarEventId) }
        }
        return applied
    }

    suspend fun applyGoalToTodos(sourceId: Long, todos: List<String>): Boolean {
        return app.database.withTransaction {
            val source = recordRepo.getRecordById(sourceId) ?: return@withTransaction false
            val now = DateFormats.nowDateTimeIso()
            val today = DateFormats.nowDate()
            recordRepo.updateRecord(source.copy(category = "todo", title = source.title, updatedAt = now))
            todos.forEach { todoContent ->
                taskRepo.insertTask(
                    TaskEntity(
                        recordId = source.id,
                        content = todoContent,
                        taskType = "once",
                        dueDate = "$today 09:00:00",
                        createdAt = now
                    )
                )
            }
            true
        }
    }

    suspend fun applyTodosToGoal(sourceIds: List<Long>, goalTitle: String): Boolean {
        return app.database.withTransaction {
            val sources = sourceIds.mapNotNull { recordRepo.getRecordById(it) }
            if (sources.isEmpty()) return@withTransaction false

            val now = DateFormats.nowDateTimeIso()
            val primary = sources.first()
            val earliestCreatedAt = sources.minOf { it.createdAt }
            recordRepo.updateRecord(
                primary.copy(
                    title = goalTitle,
                    category = "goal",
                    createdAt = earliestCreatedAt,
                    updatedAt = now
                )
            )
            sources.drop(1).forEach { source ->
                val sourceTasks = taskRepo.getTasksByRecordId(source.id).first()
                sourceTasks.filter { !it.isCompleted }.forEach { task ->
                    taskRepo.updateTask(task.copy(recordId = primary.id, parentGoalId = primary.id))
                }
                recordRepo.deleteRecord(source.id)
            }
            true
        }
    }
}
