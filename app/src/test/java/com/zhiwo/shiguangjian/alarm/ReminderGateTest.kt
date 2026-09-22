package com.zhiwo.shiguangjian.alarm

import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import com.zhiwo.shiguangjian.data.festival.HolidayCalendar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-a/F-b：闹钟响之前的那道判定。
 * 每日重复闹钟本身不懂"只在工作日做""隔三天做""今天已经勾过了"，这些全靠这里挡。
 * 2026-09-26 是周六、09-28 是周一。
 */
class ReminderGateTest {

    private fun task(
        id: Long = 1,
        kind: String = "daily",
        status: String = "active",
        dayPolicy: String = "all",
        repeatRule: String = "everyday",
        scheduledDate: String = "",
        endDate: String = "",
        remindTime: String = "08:00",
        planId: Long? = null,
        goalId: Long? = null
    ) = TaskEntity(
        id = id, content = "c$id", createdAt = "2026-09-01T00:00:00.000Z",
        kind = kind, status = status, dayPolicy = dayPolicy, repeatRule = repeatRule,
        startDate = "2026-09-01", endDate = endDate, scheduledDate = scheduledDate,
        remindTime = remindTime, planId = planId, goalId = goalId
    )

    private fun occ(taskId: Long, date: String, status: String) =
        TaskOccurrenceEntity(id = taskId, taskId = taskId, date = date, status = status, createdAt = date)

    private fun gate(
        t: TaskEntity,
        today: String,
        occurrence: TaskOccurrenceEntity? = null,
        overrideType: String? = null,
        year: Int = 2026,
        planStatus: String? = null,
        goalStatus: String? = null
    ) = ReminderGate.shouldRemind(
        task = t, today = today, occurrence = occurrence, overrideType = overrideType,
        holidays = HolidayCalendar.holidaysOf(year), makeupWorkdays = HolidayCalendar.makeupWorkdaysOf(year),
        planStatus = planStatus, goalStatus = goalStatus
    )

    @Test fun G01_暂停与归档的任务不响() {
        assertEquals(false, gate(task(status = "paused"), "2026-09-28"))
        assertEquals(false, gate(task(status = "archived"), "2026-09-28"))
        assertEquals(true, gate(task(status = "active"), "2026-09-28"))
    }

    @Test fun G02_只在工作日做的任务周六不响周一响() {
        val t = task(dayPolicy = "workday_only")
        assertEquals(false, gate(t, "2026-09-26"))
        assertEquals(true, gate(t, "2026-09-28"))
    }

    @Test fun G03_手动标了班就按班算_压过周末判定() {
        val t = task(dayPolicy = "workday_only")
        assertEquals(true, gate(t, "2026-09-26", overrideType = "workday"))
        val rest = task(dayPolicy = "holiday_only")
        assertEquals(true, gate(rest, "2026-09-28", overrideType = "holiday"))
    }

    @Test fun G04_今天已经勾过就不催_记了没做仍然催() {
        val t = task()
        assertEquals(false, gate(t, "2026-09-28", occurrence = occ(1, "2026-09-28", "done")))
        assertEquals(true, gate(t, "2026-09-28", occurrence = occ(1, "2026-09-28", "not_done")))
        // 昨天的打卡不能顶掉今天的提醒
        assertEquals(true, gate(t, "2026-09-28", occurrence = occ(1, "2026-09-27", "done")))
    }

    @Test fun G05_临时任务只在它那天响() {
        val adhoc = task(kind = "adhoc", scheduledDate = "2026-09-30")
        assertEquals(false, gate(adhoc, "2026-09-28"))
        assertEquals(true, gate(adhoc, "2026-09-30"))
        assertEquals(false, gate(adhoc, "2026-10-01"))
    }

    @Test fun G06_所属计划暂停时不响() {
        val t = task(planId = 7)
        assertEquals(false, gate(t, "2026-09-28", planStatus = "paused"))
        assertEquals(true, gate(t, "2026-09-28", planStatus = "active"))
    }

    @Test fun G07_没有节假日数据的年份按周末猜而不是集体失响() {
        val t = task(dayPolicy = "workday_only")
        assertEquals(true, gate(t, "2027-01-04", year = 2027))
        assertEquals(false, gate(t, "2027-01-02", year = 2027))
    }

    @Test fun G09_临时任务当天但计划已暂停时不响_不能提前返回绕过判定() {
        val adhoc = task(kind = "adhoc", scheduledDate = "2026-09-28", planId = 7)
        assertEquals(false, gate(adhoc, "2026-09-28", planStatus = "paused"))
        assertEquals(true, gate(adhoc, "2026-09-28", planStatus = "active"))
        // workday_only 的临时任务落在休息日也不该响
        val weekendAdhoc = task(kind = "adhoc", scheduledDate = "2026-09-26", dayPolicy = "workday_only")
        assertEquals(false, gate(weekendAdhoc, "2026-09-26"))
    }

    @Test fun G08_空日期不响() {
        assertEquals(false, gate(task(), ""))
    }
}
