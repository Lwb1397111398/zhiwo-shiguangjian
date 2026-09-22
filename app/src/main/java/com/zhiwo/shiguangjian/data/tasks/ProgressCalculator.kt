package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * "目标 / 计划在一段时间里推进了多少"的唯一实现。
 * 纯函数：不读时钟、不读库、不依赖 Android；某天该不该做仍然只问 TaskScheduleEngine，这里只做累计。
 */

/** 一段区间内的进度。percent = -1 表示"这段时间一件都没排"，UI 必须显示"暂无排期"而不是 0% */
data class ProgressSpan(
    val due: Int,
    val done: Int,
    val missed: Int,
    val percent: Int,
    val streak: Int,
    val rangeStart: String,
    val rangeEnd: String,
    /** 区间超过 366 天时只算最近 366 天，rangeStart 即被抬后的实际下界 */
    val truncated: Boolean
)

/** 进度归属：一件任务只算给一个直接归属，goal 通过其下计划汇总，绝不双算 */
sealed interface ProgressOwner {
    data class OfPlan(val planId: Long) : ProgressOwner
    data class OfGoal(val goalId: Long) : ProgressOwner
}

private val PROGRESS_ISO = DateTimeFormatter.ISO_LOCAL_DATE

private fun progressDay(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return try {
        LocalDate.parse(value.trim().take(10), PROGRESS_ISO)
    } catch (_: Exception) {
        null
    }
}

/** 一个归属在窗口内的累计器：byDay[日期] = [当天排期数, 其中完成数] */
private class OwnerBucket {
    var due = 0
    var done = 0
    var missed = 0
    val byDay = HashMap<String, IntArray>()

    fun add(date: String, isDone: Boolean, isMissed: Boolean) {
        due++
        if (isDone) done++
        if (isMissed) missed++
        val row = byDay.getOrPut(date) { IntArray(2) }
        row[0]++
        if (isDone) row[1]++
    }
}

/** 归属判定：有 plan 只算 plan（并顺带算进 plan 的父 goal），没 plan 有 goal 才算 goal，两个都空是自由任务 */
private fun ownersOf(task: TaskEntity, planById: Map<Long, PlanEntity>): List<ProgressOwner> {
    val planId = task.planId
    if (planId != null) {
        val plan = planById[planId]
        // 父目标以计划为准：计划已脱离目标时，其任务不得再算进旧目标（脏层级双算）
        val parentGoalId = if (plan != null) plan.goalId else task.goalId
        return listOfNotNull(
            ProgressOwner.OfPlan(planId),
            parentGoalId?.let { ProgressOwner.OfGoal(it) }
        )
    }
    return task.goalId?.let { listOf(ProgressOwner.OfGoal(it)) } ?: emptyList()
}

/** 连续"该做且全做完"的天数：没排期的天不打断，排了没做才归零；只在统计窗口内回溯 */
private fun bucketStreak(bucket: OwnerBucket, end: LocalDate, floor: LocalDate): Int {
    var cursor = end
    var streak = 0
    while (!cursor.isBefore(floor)) {
        val row = bucket.byDay[cursor.format(PROGRESS_ISO)]
        if (row == null || row[0] == 0) {
            cursor = cursor.minusDays(1)
            continue
        }
        if (row[1] >= row[0]) {
            streak++
            cursor = cursor.minusDays(1)
        } else {
            break
        }
    }
    return streak
}

fun progressOf(
    tasks: List<TaskEntity>,
    plans: List<PlanEntity>,
    goals: List<GoalEntity>,
    occurrences: List<TaskOccurrenceEntity>,
    from: String,
    to: String,
    dayTypeOfDate: (String) -> DayType?
): Map<ProgressOwner, ProgressSpan> {
    val end = progressDay(to) ?: return emptyMap()
    val requestedStart = progressDay(from) ?: return emptyMap()
    val truncated = ChronoUnit.DAYS.between(requestedStart, end) > ScheduleConstants.PROGRESS_MAX_RANGE_DAYS
    val start = if (truncated) end.minusDays(ScheduleConstants.PROGRESS_MAX_RANGE_DAYS - 1) else requestedStart
    if (start.isAfter(end)) return emptyMap()
    val startKey = start.format(PROGRESS_ISO)
    val endKey = end.format(PROGRESS_ISO)

    val planById = plans.associateBy { it.id }
    val planStatusById = plans.associate { it.id to it.status }
    val goalStatusById = goals.associate { it.id to it.status }

    // 每个计划/目标都得有条目，哪怕一件任务都没排：这样详情页能显示"暂无排期"
    val buckets = LinkedHashMap<ProgressOwner, OwnerBucket>()
    plans.forEach { buckets[ProgressOwner.OfPlan(it.id)] = OwnerBucket() }
    goals.forEach { buckets[ProgressOwner.OfGoal(it.id)] = OwnerBucket() }

    // 日型一天只判一次（引擎里 workday_only 每天都要问）
    val days = ArrayList<Pair<String, DayType?>>()
    run {
        var cursor = start
        while (!cursor.isAfter(end)) {
            val key = cursor.format(PROGRESS_ISO)
            days += key to dayTypeOfDate(key)
            cursor = cursor.plusDays(1)
        }
    }

    val occByTaskDay = HashMap<String, TaskOccurrenceEntity>(occurrences.size)
    val doneByTask = HashSet<Long>()
    val recordedByTask = HashSet<Long>()
    occurrences.forEach { o ->
        occByTaskDay["${o.taskId}#${o.date}"] = o
        if (o.status == "done") doneByTask += o.taskId
        if (o.status == "done" || o.status == "not_done") recordedByTask += o.taskId
    }

    tasks.forEach { task ->
        val ownerBuckets = ownersOf(task, planById).mapNotNull { buckets[it] }
        if (ownerBuckets.isEmpty()) return@forEach      // 自由任务不进任何进度
        val planStatus = task.planId?.let { planStatusById[it] }
        val goalStatus = task.goalId?.let { goalStatusById[it] }

        if (task.kind.trim().lowercase() == "adhoc") {
            val dayKey = progressDay(task.scheduledDate)?.format(PROGRESS_ISO) ?: return@forEach
            if (dayKey < startKey || dayKey > endKey) return@forEach
            // 临时任务不经过 isScheduledOn（引擎的 adhoc 分支只看日期），这里补上层状态判断：
            // 否则计划一暂停、目标一放弃，它下面的临时任务还在算"应做"
            if (task.status.trim().lowercase() != "active") return@forEach
            if (planStatus != null && planStatus != "active") return@forEach
            if (goalStatus != null && goalStatus != "active") return@forEach
            val isDone = task.id in doneByTask
            val isMissed = task.id !in recordedByTask && dayKey < endKey   // 那天已经过去还没任何记录
            ownerBuckets.forEach { it.add(dayKey, isDone, isMissed) }
            return@forEach
        }

        // 扫描下界交给引擎的 effectiveStart：早于任务生效日的一天都不算，否则凭空造出历史漏做
        val floorKey = effectiveStart(task)?.format(PROGRESS_ISO)
        for ((key, dayType) in days) {
            if (floorKey != null && key < floorKey) continue
            if (!isScheduledOn(task, key, dayType, planStatus, goalStatus)) continue
            val occ = occByTaskDay["${task.id}#$key"]
            val isDone = occ?.status == "done"
            val hasReason = occ?.status == "not_done"
            // 有 not_done = 用户已经记过原因，不再算漏做；pending 是撤销完成的软标记，等同没有记录
            // 区间末端那天通常就是今天：还没过完，只免"漏做"，不免"应做"（adhoc 分支同理）
            val isMissed = !isDone && !hasReason && key < endKey
            ownerBuckets.forEach { it.add(key, isDone, isMissed) }
        }
    }

    return buckets.mapValues { (_, bucket) ->
        ProgressSpan(
            due = bucket.due,
            done = bucket.done,
            missed = bucket.missed,
            percent = if (bucket.due == 0) -1
            else (bucket.done * 100f / bucket.due).roundToInt().coerceIn(0, 100),
            streak = bucketStreak(bucket, end, start),
            rangeStart = startKey,
            rangeEnd = endKey,
            truncated = truncated
        )
    }
}
