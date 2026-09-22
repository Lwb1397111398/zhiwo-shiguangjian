package com.zhiwo.shiguangjian.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 任务定义。"今天做没做"不在这张表上，而在 task_occurrences（一天一条）。
 *
 * v13 起新老字段并存：taskType/isCompleted/dailyCompletionDate/isPermanentlyCompleted/parentGoalId
 * 是为"老 UI 照常工作"保留的过渡字段，只允许 TaskWriteBridge 写。
 * v14（FK 改 SET_NULL + 回填 goalId）没有删它们：全库读者还没清完，删列排在下游改造之后（见计划 v6 §4）。
 * 新字段才是真值。
 */
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            // v14 起改为 SET_NULL：删掉一条记录不该把它派生的任务一起带走（以前靠 deleteRecordKeepingTasks 绕）
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("recordId"),
        Index("parentGoalId"),
        Index("goalId"),
        Index("planId")
    ]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val recordId: Long? = null,
    val parentGoalId: Long? = null,
    val content: String,
    val dueDate: String = "",
    val taskType: String = "once",
    val isCompleted: Boolean = false,
    val completedAt: String? = null,
    val dailyCompletionDate: String? = null,
    val isPermanentlyCompleted: Boolean = false,
    val calendarEventId: Long? = null,
    val createdAt: String,

    // ===== v13 新模型 =====
    val kind: String = "adhoc",              // daily 每日固定 | adhoc 临时一次 | blank 每日留白
    val repeatRule: String = "everyday",     // everyday | weekdays | custom | interval
    val weekdaysCsv: String = "",            // "1,3,5"，ISO 周一=1，仅 custom
    val intervalDays: Int = 1,               // 仅 interval
    val dayPolicy: String = "all",           // all | workday_only | holiday_only
    val startDate: String = "",              // 生效区间起，"" = 立即
    val endDate: String = "",                // 生效区间止，"" = 长期
    val scheduledDate: String = "",          // adhoc 专用：哪天开始显示，"" = 创建当天起
    val remindTime: String = "",             // "HH:mm"，"" = 不提醒
    val durationMinutes: Int = 0,            // 0 = 不限
    val goalId: Long? = null,
    val planId: Long? = null,
    val status: String = "active",           // active | paused | archived
    val colorIndex: Int = 0,
    val sortOrder: Int = 0
)
