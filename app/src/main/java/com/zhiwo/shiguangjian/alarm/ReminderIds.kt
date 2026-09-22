package com.zhiwo.shiguangjian.alarm

/**
 * 任务闹钟码的唯一换算。
 *
 * v13 及以前用 `t + 10000`，且"明日提醒"取 `+1` —— 于是任务 t 的第二枪正好等于任务 t+1 的主码，
 * 取消一个任务会顺手掐掉邻居的提醒（`AlarmScheduler.cancelTaskAlarm` 还会固定多取消 `alarmId+1`）。
 * 现在一个任务只有一个码，且整段基址抬高，与固定闹钟的 1001~1004、旧公式的码段都不相交。
 */
object ReminderIds {

    /** 任务闹钟基址：固定闹钟与旧公式的码都落在它下面 */
    const val TASK_BASE = 1_000_000

    private const val MAX_TASK_ID: Long = Int.MAX_VALUE.toLong() - TASK_BASE

    fun of(taskId: Long): Int {
        require(taskId in 0..MAX_TASK_ID) { "taskId 超出闹钟码可编码范围: $taskId" }
        return TASK_BASE + taskId.toInt()
    }

    /** 反解闹钟码所属任务；非任务闹钟（固定 4 个、旧码段）返回 null */
    fun taskIdOf(alarmId: Int): Long? =
        if (alarmId in TASK_BASE..Int.MAX_VALUE) (alarmId - TASK_BASE).toLong() else null

    /** v13 及以前遗留的码，重新注册一个任务时要一并清掉，否则新旧两把闹钟同时响 */
    fun legacyIds(taskId: Long): List<Int> {
        if (taskId < 0 || taskId > Int.MAX_VALUE.toLong() - 10001) return emptyList()
        return listOf((taskId + 10000).toInt(), (taskId + 10001).toInt())
    }
}
