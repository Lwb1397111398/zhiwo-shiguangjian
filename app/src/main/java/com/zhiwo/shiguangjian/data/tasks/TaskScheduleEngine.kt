package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.MIGRATION_DATE_COLUMN
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * "给定某天，哪些任务出现在面前、以什么状态出现"的唯一实现。
 * 日历页、安排页、日记、评价、AI 摘要一律调这里，不得自己判断。
 * 纯函数：不读时钟、不读库、不依赖 Android。
 */

enum class DayType { WORKDAY, HOLIDAY }

/** 计入当天应做数的四个段 */
enum class EntrySection { DAILY_FIXED, DAILY_BLANK, ADHOC_TODAY, MISC_TODO, OVERDUE, MISSED, UNFILLED }

enum class EntryState { DUE, DONE, NOT_DONE }

data class DayEntry(
    val task: TaskEntity,
    val occurrence: TaskOccurrenceEntity?,
    val state: EntryState,
    val section: EntrySection,
    /** MISSED/UNFILLED 指向的是哪一天（当天条目为空串），补打卡时要落到那一天 */
    val date: String = ""
)

data class DayPlanResult(
    /** 当天应做（含已完成的）：四个段之一，分母在这里，不随打卡进度变化 */
    val planned: List<DayEntry>,
    val overdue: List<DayEntry>,
    val missed: List<DayEntry>,
    val unfilled: List<DayEntry>,
    val miscCount: Int,
    val issues: List<String>
) {
    val done: List<DayEntry> get() = planned.filter { it.state == EntryState.DONE }
    /** 完成率分母不含"杂项待办（来自记录）"——这类老数据没有打卡载体，永远拉低完成率 */
    val dueCount: Int get() = planned.count { it.section != EntrySection.MISC_TODO }
    val doneCount: Int get() = done.count { it.section != EntrySection.MISC_TODO }
}

object ScheduleConstants {
    const val MISSED_LOOKBACK_DAYS = 30
    const val UNFILLED_WINDOW_DAYS = 7
    const val PROGRESS_MAX_RANGE_DAYS = 366L
    const val OWNERSHIP_BACKFILL_DAYS = 7
}

private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

internal fun engineDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return try {
        LocalDate.parse(value.trim().take(10), ISO)
    } catch (_: Exception) {
        null
    }
}

private fun isoDayOfWeek(date: LocalDate) = date.dayOfWeek.value   // 周一=1 … 周日=7

/** 扫描下界：迁移之前的历史不算"漏做"，否则首次上线会凭空造出几十条假缺口 */
fun effectiveStart(task: TaskEntity): LocalDate? {
    val start = engineDate(task.startDate) ?: engineDate(task.createdAt) ?: return null
    val floor = engineDate(MIGRATION_DATE_COLUMN) ?: start
    return if (start.isAfter(floor)) start else floor
}

/**
 * overrideType 来自用户手标的"班/休"，优先级最高；内置数据缺失的年份返回 null（=无法判定），
 * 由调用方决定停用节假日策略，而不是按周末瞎猜。
 */
fun dayTypeOf(
    date: String,
    holidays: Set<String> = emptySet(),
    makeupWorkdays: Set<String> = emptySet(),
    overrideType: String? = null
): DayType? {
    when (overrideType?.trim()?.lowercase()) {
        "workday" -> return DayType.WORKDAY
        "holiday" -> return DayType.HOLIDAY
    }
    val d = engineDate(date) ?: return null
    val key = d.format(ISO)
    if (key in holidays) return DayType.HOLIDAY
    if (key in makeupWorkdays) return DayType.WORKDAY
    if (holidays.isEmptyForYear(d.year)) return null
    return if (isoDayOfWeek(d) <= 5) DayType.WORKDAY else DayType.HOLIDAY
}

private fun Set<String>.isEmptyForYear(year: Int): Boolean = none { it.startsWith("$year-") }

/**
 * 安排页 / 日历页 / 日记与评价取数必须走同一个函数。
 * 之前三处各自决定"该年没节假日数据时怎么办"（一处按周末猜、两处判不排），
 * 结果同一天在两个页面看到的集合不一样。猜的时候要把"在猜"这件事显示出来（holidayWarning）。
 */
fun dayTypeOrWeekend(
    date: String,
    holidays: Set<String>,
    makeupWorkdays: Set<String>,
    overrideType: String? = null
): DayType = dayTypeOf(date, holidays, makeupWorkdays, overrideType) ?: run {
    val d = engineDate(date) ?: return DayType.WORKDAY
    if (d.dayOfWeek.value <= 5) DayType.WORKDAY else DayType.HOLIDAY
}

/** 重复规则是否命中这一天（不含节假日策略） */
fun matchesRepeatRule(task: TaskEntity, date: LocalDate): Boolean {
    return when (task.repeatRule.trim().lowercase()) {
        "weekdays" -> isoDayOfWeek(date) <= 5
        "custom" -> {
            val days = task.weekdaysCsv.split(',', ';', ' ', '\n')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..7 }
                .toSet()
            if (days.isEmpty()) true else isoDayOfWeek(date) in days
        }
        "interval" -> {
            // 锚点用任务的原始起始日，不能用被迁移日抬过的 effectiveStart：否则老任务的节奏会被整体打乱
            val anchor = engineDate(task.startDate) ?: engineDate(task.createdAt) ?: return true
            val step = if (task.intervalDays < 1) 1 else task.intervalDays
            val days = java.time.temporal.ChronoUnit.DAYS.between(anchor, date)
            days >= 0 && days % step == 0L
        }
        else -> true   // everyday，以及任何未知值
    }
}

/** 这一天该不该做这件事：区间 + 重复规则 + 节假日策略 + 所属计划/目标是否还在进行 */
fun isScheduledOn(
    task: TaskEntity,
    date: String,
    dayType: DayType?,
    planStatus: String? = null,
    goalStatus: String? = null
): Boolean {
    val d = engineDate(date) ?: return false
    if (task.status.trim().lowercase() != "active") return false
    if (planStatus != null && planStatus != "active") return false
    if (goalStatus != null && goalStatus != "active") return false
    engineDate(task.startDate)?.let { if (d.isBefore(it)) return false }
    engineDate(task.endDate)?.let { if (d.isAfter(it)) return false }
    if (effectiveStart(task)?.let { d.isBefore(it) } == true) return false

    val policy = task.dayPolicy.trim().lowercase()
    if (policy != "all") {
        if (dayType == null) return false       // 该年无节假日数据：策略无法判定，宁可不排
        val wantsWorkday = policy == "workday_only"
        val isWorkday = dayType == DayType.WORKDAY
        if (wantsWorkday != isWorkday) return false
    }
    return when (task.kind.trim().lowercase()) {
        "daily", "blank" -> matchesRepeatRule(task, d)
        else -> true
    }
}

/** 这一天的完成判据：有 done occurrence 且日期对得上。老列不参与——双写期两边都会写 */
fun isEffectivelyDone(
    occurrence: TaskOccurrenceEntity?,
    date: String
): Boolean = occurrence != null && occurrence.status == "done" && occurrence.date == date

fun entriesFor(
    date: String,
    tasks: List<TaskEntity>,
    occurrences: List<TaskOccurrenceEntity>,
    dayType: DayType?,
    planStatusById: Map<Long, String> = emptyMap(),
    goalStatusById: Map<Long, String> = emptyMap(),
    miscTodoCount: Int = 0
): DayPlanResult {
    val d = engineDate(date) ?: return DayPlanResult(emptyList(), emptyList(), emptyList(), emptyList(), 0, emptyList())
    val occByTask = occurrences.filter { it.date == date }.associateBy { it.taskId }
    val issues = mutableListOf<String>()
    val planned = mutableListOf<DayEntry>()
    val overdue = mutableListOf<DayEntry>()
    val done = mutableListOf<DayEntry>()

    tasks.forEach { task ->
        val kind = task.kind.trim().lowercase()
        val occ = occByTask[task.id]
        val planStatus = task.planId?.let { planStatusById[it] }
        val goalStatus = task.goalId?.let { goalStatusById[it] }

        if (kind == "adhoc") {
            val scheduled = engineDate(task.scheduledDate)
            val due = engineDate(task.deadlineDate)
            val isDone = isEffectivelyDone(occ, date)
            when {
                isDone -> done += DayEntry(task, occ, EntryState.DONE, EntrySection.ADHOC_TODAY)
                scheduled == null -> if (due == null || !d.isAfter(due)) {
                    planned += DayEntry(task, occ, stateOf(occ), EntrySection.ADHOC_TODAY)
                } else overdue += DayEntry(task, occ, EntryState.DUE, EntrySection.OVERDUE)
                due != null && d.isAfter(due) -> overdue += DayEntry(task, occ, EntryState.DUE, EntrySection.OVERDUE)
                d.isBefore(scheduled) -> Unit
                d.isAfter(scheduled) -> overdue += DayEntry(task, occ, EntryState.DUE, EntrySection.OVERDUE)
                !isScheduledOn(task, date, dayType, planStatus, goalStatus) -> Unit
                else -> planned += DayEntry(task, occ, stateOf(occ), EntrySection.ADHOC_TODAY)
            }
            return@forEach
        }

        if (!isScheduledOn(task, date, dayType, planStatus, goalStatus)) return@forEach
        val section = if (kind == "blank") EntrySection.DAILY_BLANK else EntrySection.DAILY_FIXED
        if (kind == "blank" && occ == null && engineDate(task.startDate) == null && engineDate(task.createdAt) == null) {
            issues += "BLANK_NO_START#${task.id}"
        }
        when (occ?.status) {
            "done" -> done += DayEntry(task, occ, EntryState.DONE, section)
            "not_done" -> planned += DayEntry(task, occ, EntryState.NOT_DONE, section)
            else -> planned += DayEntry(task, occ, EntryState.DUE, section)
        }
    }

    val allPlanned = planned.sortedWith(compareBy({ it.section.ordinal }, { it.task.sortOrder }, { it.task.id }))
    return DayPlanResult(
        planned = allPlanned + done.sortedWith(compareBy({ it.section.ordinal }, { it.task.sortOrder }, { it.task.id })),
        overdue = overdue.sortedBy { it.task.sortOrder },
        missed = emptyList(),
        unfilled = emptyList(),
        miscCount = miscTodoCount,
        issues = issues
    )
}

private fun stateOf(occ: TaskOccurrenceEntity?): EntryState = when (occ?.status) {
    "done" -> EntryState.DONE
    "not_done" -> EntryState.NOT_DONE
    else -> EntryState.DUE
}

/** 漏做：区间内"该做却没有当天记录"的每日/留白任务；只回看 lookback 天且不早于任务生效日 */
fun missedOn(
    tasks: List<TaskEntity>,
    occurrences: List<TaskOccurrenceEntity>,
    fromDate: String,
    lookbackDays: Int = ScheduleConstants.MISSED_LOOKBACK_DAYS,
    dayTypeOfDate: (String) -> DayType?,
    planStatusById: Map<Long, String> = emptyMap(),
    goalStatusById: Map<Long, String> = emptyMap()
): List<DayEntry> {
    val today = engineDate(fromDate) ?: return emptyList()
    val occKeys = occurrences.filter { it.status == "done" || it.status == "not_done" }
        .map { it.taskId to it.date }.toSet()
    val out = mutableListOf<DayEntry>()
    tasks.filter { it.kind.trim().lowercase() in setOf("daily", "blank") }.forEach { task ->
        val start = effectiveStart(task) ?: return@forEach
        for (i in 1 until lookbackDays) {
            val day = today.minusDays(i.toLong())
            if (day.isBefore(start)) break
            val key = day.format(ISO)
            if (!isScheduledOn(
                    task, key, dayTypeOfDate(key),
                    task.planId?.let { planStatusById[it] },
                    task.goalId?.let { goalStatusById[it] }
                )
            ) continue
            if (task.id to key in occKeys) continue
            out += DayEntry(task, null, EntryState.DUE, EntrySection.MISSED, key)
        }
    }
    return out.sortedWith(compareBy({ it.task.sortOrder }, { it.task.id }))
}

/** 连续打卡天数：被规则跳过的日子不打断，"该做没做"才归 0 */
fun streakOf(
    task: TaskEntity,
    occurrences: List<TaskOccurrenceEntity>,
    today: String,
    dayTypeOfDate: (String) -> DayType?
): Int {
    val done = occurrences.filter { it.taskId == task.id && it.status == "done" }.map { it.date }.toSet()
    val start = effectiveStart(task) ?: return 0
    var cursor = engineDate(today) ?: return 0
    if (cursor.isBefore(start)) return 0
    var streak = 0
    while (!cursor.isBefore(start)) {
        val key = cursor.format(ISO)
        val scheduled = isScheduledOn(task, key, dayTypeOfDate(key))
        if (!scheduled) {
            if (cursor == engineDate(today)) { cursor = cursor.minusDays(1); continue }
            cursor = cursor.minusDays(1); continue
        }
        if (key in done) { streak++; cursor = cursor.minusDays(1); continue }
        break
    }
    return streak
}

/** 下一次该做的日期（闹钟与开机恢复用；无解返回 null 表示不该再提醒） */
fun nextScheduledDate(
    task: TaskEntity,
    from: String,
    horizonDays: Int = 400,
    dayTypeOfDate: (String) -> DayType? = { null }
): String? {
    var cursor = engineDate(from) ?: return null
    val limit = cursor.plusDays(horizonDays.toLong())
    while (!cursor.isAfter(limit)) {
        val key = cursor.format(ISO)
        if (isScheduledOn(task, key, dayTypeOfDate(key))) return key
        cursor = cursor.plusDays(1)
    }
    return null
}
