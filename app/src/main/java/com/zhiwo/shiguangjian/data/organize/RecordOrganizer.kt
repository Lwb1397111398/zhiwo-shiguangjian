package com.zhiwo.shiguangjian.data.organize

import androidx.room.withTransaction
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.ai.resolveCategory
import com.zhiwo.shiguangjian.data.db.entity.MemoryEntity
import com.zhiwo.shiguangjian.data.db.entity.RecordEntity
import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
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
                // 合并只搬运 recordId 指向：任务一律存活（老列 isCompleted 不算"结束"），
                // 闹钟一律不停——停铃会让每日任务明天哑火；真要结束它请走"结束任务"归档（生命周期设计 B10）
                val tasks = taskRepo.getTasksByRecordId(rec.id).first()
                tasks.forEach { task ->
                    taskRepo.updateTask(task.copy(recordId = primary.id))
                }
                recordRepo.deleteRecordKeepingTasks(rec.id)
            }
            true
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
            recordRepo.deleteRecordKeepingTasks(sourceId)
            true
        }
    }

    suspend fun applyToMemory(sourceId: Long, memoryContent: String): Boolean {
        // 转记忆 = 记录本体换去处（写 memories + 删 records），派生任务一律摘链存活、闹钟一律不停——
        // 旧代码把存活任务的铃全停了，任务还在安排页却从此不响，下次开机又复活（生命周期设计 B10）
        return app.database.withTransaction {
            val source = recordRepo.getRecordById(sourceId) ?: return@withTransaction false
            val now = DateFormats.nowDateTimeIso()
            memoryRepo.insertMemory(
                MemoryEntity(
                    content = memoryContent,
                    source = "organize",
                    createdAt = now,                       // 写库时间；淘汰按它排序，用记录老日期会让新记忆第一个被删
                    updatedAt = now,
                    occurredAt = source.createdAt.take(10)
                )
            )
            recordRepo.deleteRecordKeepingTasks(sourceId)
            true
        }
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
                        createdAt = now,
                        kind = "adhoc",
                        scheduledDate = today,
                        remindTime = "09:00"
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
                recordRepo.deleteRecordKeepingTasks(source.id)
            }
            // "待办并为目标"现在产出一个真目标行，老的目标记录通过 recordId 溯源
            val goalDao = app.database.goalDao()
            if (goalDao.getActiveGoals().first().none { it.recordId == primary.id }) {
                val goalId = goalDao.insert(
                    GoalEntity(title = goalTitle, description = primary.summary, recordId = primary.id, createdAt = now)
                )
                val linked = taskRepo.getTasksByRecordId(primary.id).first() +
                    taskRepo.getTasksByParentGoalId(primary.id).first()
                for (task in linked.distinctBy { it.id }) {
                    taskRepo.updateTask(task.copy(goalId = goalId))
                }
            }
            true
        }
    }
}
