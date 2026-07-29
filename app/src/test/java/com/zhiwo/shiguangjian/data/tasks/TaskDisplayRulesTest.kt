package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDisplayRulesTest {

    @Test
    fun `daily task is only effectively completed on completion date`() {
        val task = task(taskType = "daily", isCompleted = true, dailyCompletionDate = "2026-07-25")

        assertFalse(isTaskEffectivelyCompleted(task, today = "2026-07-26"))
        assertTrue(isTaskEffectivelyCompleted(task, today = "2026-07-25"))
    }

    @Test
    fun `display date keeps daily tasks on today`() {
        val task = task(taskType = "daily", dueDate = "")

        assertEquals("2026-07-26", getTaskDisplayDate(task, today = "2026-07-26"))
    }

    @Test
    fun `display date parses one time task date`() {
        val task = task(taskType = "once", dueDate = "2026-07-28 09:00:00")

        assertEquals("2026-07-28", getTaskDisplayDate(task, today = "2026-07-26"))
    }

    private fun task(
        taskType: String,
        dueDate: String = "",
        isCompleted: Boolean = false,
        dailyCompletionDate: String? = null
    ) = TaskEntity(
        id = 1,
        recordId = 1,
        content = "任务",
        dueDate = dueDate,
        taskType = taskType,
        isCompleted = isCompleted,
        dailyCompletionDate = dailyCompletionDate,
        createdAt = "2026-07-26T10:00:00.000Z"
    )
}
