package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.entity.TaskEntity

/**
 * adhoc 任务「最后期限」的唯一读法。
 *
 * 老数据把期限写在 `dueDate`（常带 " 09:00:00"），新编辑器写在 `endDate`。两者都有值时以 `endDate` 为准。
 * v14 删掉 `dueDate` 列时，**本文件是唯一需要改的地方**：删掉 [legacyDeadlineText]，把 [deadlineDate] 改成直接返回 `endDate`。
 */
val TaskEntity.deadlineDate: String
    get() = endDate.ifBlank { legacyDeadlineText }

private val TaskEntity.legacyDeadlineText: String
    get() = dueDate.take(10)

/** 期限日（含）之后即视为过期；无期限的 adhoc 只有 `scheduledDate` 当天算该做 */
fun adhocOverdueOn(task: TaskEntity, date: String): Boolean {
    val d = date.take(10)
    if (d.isBlank()) return false
    val deadline = task.deadlineDate.take(10)
    val scheduled = task.scheduledDate.take(10)
    return when {
        deadline.isNotBlank() -> d > deadline
        scheduled.isNotBlank() -> d > scheduled
        else -> false
    }
}
