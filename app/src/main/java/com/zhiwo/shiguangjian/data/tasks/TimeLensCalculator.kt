package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 时光统计（今日时间环 / 逐日投入 / 月度热力）的唯一计算口径。
 * 纯函数：不读时钟、不读库、不依赖 Android。"某天该不该做"仍然只问 TaskScheduleEngine，
 * 这里只把引擎给出的当天条目换算成分钟并按归属聚合。
 *
 * 口径纪律（与 ProgressCalculator 同一精神）：
 * - 一件任务只算给一个直接归属：有计划算计划，没计划有目标算目标，两个都没有算"未归属"；
 * - 留白任务没填时长按 60 分钟计（产品约定）；时长填 0 = "不限"，不进环、只计数；
 * - 实际分钟只认打卡行里的 actualMinutes（完成勾选/留白回填写的真值），没有就是 0。
 */

/** 环上的一段里的一条任务 */
data class LensItem(
    val content: String,
    val minutes: Int,          // 0 = 未定时长
    val done: Boolean
)

/** 环上的一段：一个归属（计划/目标/未归属）在今日的份额 */
data class LensSlice(
    val key: String,           // "plan-{id}" | "goal-{id}" | "free"
    val title: String,
    val minutes: Int,          // 该归属今日计划分钟合计（不含未定时长的任务）
    val items: List<LensItem>
)

/** 某一天的"时间透镜"快照：环 + 两个总数 */
data class TodayBudget(
    val date: String,
    val slices: List<LensSlice>,
    val plannedMinutes: Int,   // 环的总量 = 各段之和
    val unboundedCount: Int,   // 今天还排着 N 条"未定时长"任务（不进环，如实提示）
    val loggedMinutes: Int     // 今天已记的实际投入
)

/** 逐日投入：计划 vs 实际，外加件数（趋势柱与热力图共用） */
data class DayInvestment(
    val date: String,
    val plannedMinutes: Int,
    val loggedMinutes: Int,
    val dueCount: Int,
    val doneCount: Int
)

private val LENS_ISO = DateTimeFormatter.ISO_LOCAL_DATE

private fun lensDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return try {
        LocalDate.parse(value.trim().take(10), LENS_ISO)
    } catch (_: Exception) {
        null
    }
}

/** 排在这一天的这条任务计划花多少分钟：0 表示"不限"，由调用方归入 unboundedCount */
fun lensPlannedMinutes(task: TaskEntity): Int = when {
    task.kind.trim().lowercase() == "blank" -> if (task.durationMinutes > 0) task.durationMinutes else 60
    else -> task.durationMinutes
}

/** 归属判定：计划优先；计划行已被删（查不到）时退回任务上的 goalId */
private fun lensOwner(
    task: TaskEntity,
    planById: Map<Long, PlanEntity>,
    goalById: Map<Long, GoalEntity>
): Pair<String, String> {
    task.planId?.let { pid ->
        val plan = planById[pid]
        if (plan != null) return "plan-$pid" to plan.title.ifBlank { "未命名计划" }
        val goalId = task.goalId ?: plan?.goalId
        goalId?.let { gid -> goalById[gid]?.let { return "goal-$gid" to it.title.ifBlank { "未命名目标" } } }
        return "plan-$pid" to "计划 $pid"
    }
    task.goalId?.let { gid ->
        goalById[gid]?.let { return "goal-$gid" to it.title.ifBlank { "未命名目标" } }
        return "goal-$gid" to "目标 $gid"
    }
    return "free" to "未归属"
}

/**
 * 某天的时间透镜：走引擎 entriesFor 拿当天全部条目（含已完成），聚成环。
 * plans/goals 传全表即可，状态门控由引擎自己做（暂停的算不进来）。
 */
fun todayBudget(
    date: String,
    tasks: List<TaskEntity>,
    occurrences: List<TaskOccurrenceEntity>,
    dayType: DayType?,
    plans: List<PlanEntity>,
    goals: List<GoalEntity>
): TodayBudget {
    val planById = plans.associateBy { it.id }
    val goalById = goals.associateBy { it.id }
    val day = entriesFor(
        date = date,
        tasks = tasks,
        occurrences = occurrences,
        dayType = dayType,
        planStatusById = plans.associate { it.id to it.status },
        goalStatusById = goals.associate { it.id to it.status }
    )

    val buckets = LinkedHashMap<String, MutableList<LensItem>>()
    val titles = HashMap<String, String>()
    val minutesByKey = HashMap<String, Int>()
    var unbounded = 0

    day.planned.forEach { entry ->
        val (key, title) = lensOwner(entry.task, planById, goalById)
        val minutes = lensPlannedMinutes(entry.task).coerceAtLeast(0)
        if (minutes == 0) unbounded++
        buckets.getOrPut(key) { mutableListOf() } += LensItem(
            content = entry.task.content.ifBlank { "未命名" },
            minutes = minutes,
            done = entry.state == EntryState.DONE
        )
        titles[key] = title
        minutesByKey[key] = (minutesByKey[key] ?: 0) + minutes
    }

    val slices = buckets.entries
        .sortedByDescending { minutesByKey[it.key] ?: 0 }
        .map { (key, items) ->
            LensSlice(
                key = key,
                title = titles[key] ?: "未归属",
                minutes = minutesByKey[key] ?: 0,
                items = items
            )
        }

    val logged = occurrences
        .filter { it.date == date && it.status == "done" }
        .sumOf { it.actualMinutes.coerceAtLeast(0) }

    return TodayBudget(
        date = date,
        slices = slices,
        plannedMinutes = minutesByKey.values.sum(),
        unboundedCount = unbounded,
        loggedMinutes = logged
    )
}

/** 逐日投入序列（含首尾）。跨度上限 400 天，超出从 to 往前截。 */
fun dailyInvestment(
    from: String,
    to: String,
    tasks: List<TaskEntity>,
    occurrences: List<TaskOccurrenceEntity>,
    dayTypeOfDate: (String) -> DayType?,
    plans: List<PlanEntity>,
    goals: List<GoalEntity>
): List<DayInvestment> {
    val end = lensDate(to) ?: return emptyList()
    val requestedStart = lensDate(from) ?: return emptyList()
    val start = if (java.time.temporal.ChronoUnit.DAYS.between(requestedStart, end) > 400)
        end.minusDays(400) else requestedStart
    if (start.isAfter(end)) return emptyList()

    val planById = plans.associateBy { it.id }
    val goalById = goals.associateBy { it.id }
    val planStatusById = plans.associate { it.id to it.status }
    val goalStatusById = goals.associate { it.id to it.status }

    val actualByDay = HashMap<String, Int>()
    occurrences.forEach { o ->
        if (o.status == "done" && o.actualMinutes > 0) {
            actualByDay[o.date] = (actualByDay[o.date] ?: 0) + o.actualMinutes
        }
    }

    val out = ArrayList<DayInvestment>()
    var cursor = start
    while (!cursor.isAfter(end)) {
        val key = cursor.format(LENS_ISO)
        val day = entriesFor(key, tasks, occurrences, dayTypeOfDate(key), planStatusById, goalStatusById)
        val plannedMinutes = day.planned.sumOf { lensPlannedMinutes(it.task).coerceAtLeast(0) }
        out += DayInvestment(
            date = key,
            plannedMinutes = plannedMinutes,
            loggedMinutes = actualByDay[key] ?: 0,
            dueCount = day.dueCount,
            doneCount = day.doneCount
        )
        cursor = cursor.plusDays(1)
    }
    return out
}
