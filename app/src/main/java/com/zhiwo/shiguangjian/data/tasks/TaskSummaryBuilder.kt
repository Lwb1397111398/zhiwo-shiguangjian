package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.AppDatabase
import com.zhiwo.shiguangjian.data.db.entity.DayOverrideEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import com.zhiwo.shiguangjian.data.festival.HolidayCalendar
import kotlinx.coroutines.flow.first

/**
 * 给 AI 评价/日记用的任务文本，以及周完成率。
 * 单独成文件的原因：完成态与"这天该不该做"的判断只允许有一份，出处是 TaskScheduleEngine；
 * 之前评价、日记、周统计各写了一份 when(taskType) 的复制品，会把"今天才勾上的任务"说成"过去每天都完成了"。
 */

data class SummaryLine(val content: String, val mark: String, val note: String)

/** 某一天的任务清单文本：✓ 做了 / ✗ 没做（带原因） / ○ 没记录 */
fun summaryForDate(
    date: String,
    tasks: List<TaskEntity>,
    occurrences: List<TaskOccurrenceEntity>,
    dayType: DayType?,
    planStatusById: Map<Long, String> = emptyMap(),
    goalStatusById: Map<Long, String> = emptyMap()
): List<SummaryLine> {
    val dayOccurrences = occurrences.filter { it.date == date }
    val plan = entriesFor(
        date = date,
        tasks = tasks,
        occurrences = dayOccurrences,
        dayType = dayType,
        planStatusById = planStatusById,
        goalStatusById = goalStatusById
    )
    val lines = (plan.planned + plan.overdue + plan.missed).distinctBy { it.task.id.toString() + it.date }
    return lines.mapNotNull { entry ->
        val occ = entry.occurrence
        when {
            occ?.status == "done" -> SummaryLine(entry.task.content, "✓", occ.note.ifBlank { "" })
            occ?.status == "not_done" -> SummaryLine(
                entry.task.content, "✗",
                listOf(occ.reasonCode, occ.reasonNote).filter { it.isNotBlank() }.joinToString("：")
            )
            entry.state == EntryState.DONE -> SummaryLine(entry.task.content, "✓", "")
            occ == null && entry.date.isNotBlank() && entry.date != date ->
                SummaryLine(entry.task.content, "○", "${entry.date} 漏做")
            else -> SummaryLine(entry.task.content, "○", "")
        }
    }
}

fun summaryText(lines: List<SummaryLine>): String =
    lines.joinToString("\n") { line ->
        val suffix = if (line.note.isBlank()) "" else "（${line.note}）"
        "${line.mark} ${line.content}$suffix"
    }

/** 一周里"排期成立的日子"总数与其中已打卡的数量 */
fun weekProgress(
    days: List<String>,
    tasks: List<TaskEntity>,
    occurrences: List<TaskOccurrenceEntity>,
    dayTypeOfDate: (String) -> DayType?
): Pair<Int, Int> {
    val doneKeys = occurrences.filter { it.status == "done" }.map { it.taskId to it.date }.toSet()
    var due = 0
    var done = 0
    tasks.forEach { task ->
        days.forEach { day ->
            // 临时任务只在它指定的那一天计数，否则一条周内的 adhoc 会贡献 7 个"应做"
            if (task.kind == "adhoc") {
                val anchor = task.scheduledDate.ifBlank { task.deadlineDate }
                if (anchor.isNotBlank() && day != anchor) return@forEach
            }
            if (!isScheduledOn(task, day, dayTypeOfDate(day))) return@forEach
            due++
            if (task.id to day in doneKeys) done++
        }
    }
    return due to done
}


/**
 * 评价 / 日记 / 周统计共用的读模型：把"某天的任务清单文本"和"一周完成率"算出来。
 * 判断本身仍然只在引擎里，这里只负责取数。
 */
class TaskSnapshotReader(private val db: AppDatabase) {

    private suspend fun overrides(): List<DayOverrideEntity> = db.dayOverrideDao().getAll().first()

    private fun resolver(overrides: List<DayOverrideEntity>): (String) -> DayType? = { date ->
        dayTypeOf(
            date,
            HolidayCalendar.holidaysOf(date.take(4).toIntOrNull() ?: 1970),
            HolidayCalendar.makeupWorkdaysOf(date.take(4).toIntOrNull() ?: 1970),
            overrides.firstOrNull { it.date == date }?.type
        )
    }

    suspend fun summaryFor(date: String): String {
        val tasks = db.taskDao().getAllTasksList()
        val occ = db.occurrenceDao().getForDate(date)
        val ov = overrides()
        return summaryText(
            summaryForDate(
                date = date, tasks = tasks, occurrences = occ,
                dayType = resolver(ov)(date),
                planStatusById = db.planDao().getAllPlans().first().associate { it.id to it.status },
                goalStatusById = db.goalDao().getAllGoals().first().associate { it.id to it.status }
            )
        )
    }

    suspend fun weekProgress(days: List<String>): Pair<Int, Int> =
        weekProgress(days, db.taskDao().getAllTasksList(), db.occurrenceDao().observeInRange("0000-01-01", "9999-12-31").first(), resolver(overrides()))
}
