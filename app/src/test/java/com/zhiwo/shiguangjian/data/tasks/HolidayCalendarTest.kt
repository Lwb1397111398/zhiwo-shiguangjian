package com.zhiwo.shiguangjian.data.tasks

import com.zhiwo.shiguangjian.data.festival.HolidayCalendar
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 节假日判定。期望值来自《国务院办公厅关于2026年部分节假日安排的通知》，见 docs 里的录入凭据文件 */
class HolidayCalendarTest {

    private fun dow(s: String) = LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).dayOfWeek.value

    @Test fun H01_已录入年份可查询() {
        assertTrue(HolidayCalendar.hasDataFor(2026))
        assertFalse(HolidayCalendar.hasDataFor(2027))
    }

    @Test fun H02_公告里的星期与推算一致() {
        assertEquals(4, dow("2026-01-01"))   // 元旦 周四
        assertEquals(7, dow("2026-02-15"))   // 春节始 周日
        assertEquals(1, dow("2026-02-23"))   // 春节末 周一
        assertEquals(6, dow("2026-04-04"))   // 清明 周六
        assertEquals(5, dow("2026-05-01"))   // 劳动节 周五
        assertEquals(5, dow("2026-06-19"))   // 端午 周五
        assertEquals(5, dow("2026-09-25"))   // 中秋 周五
        assertEquals(4, dow("2026-10-01"))   // 国庆 周四
    }

    @Test fun H03_放假日天数与公告一致() {
        assertEquals(33, HolidayCalendar.holidaysOf(2026).size)
        assertEquals(6, HolidayCalendar.makeupWorkdaysOf(2026).size)
    }

    @Test fun H04_国庆七天全部判为休息日() {
        for (d in 1..7) {
            val key = "2026-10-0$d"
            assertEquals(key, DayType.HOLIDAY, dayTypeOf(key, HolidayCalendar.holidaysOf(2026), HolidayCalendar.makeupWorkdaysOf(2026)))
        }
    }

    @Test fun H05_调休补班的周六判为工作日() {
        listOf("2026-02-14", "2026-02-28", "2026-05-09", "2026-10-10").forEach {
            assertEquals(it, DayType.WORKDAY,
                dayTypeOf(it, HolidayCalendar.holidaysOf(2026), HolidayCalendar.makeupWorkdaysOf(2026)))
        }
        assertEquals(DayType.WORKDAY,
            dayTypeOf("2026-01-04", HolidayCalendar.holidaysOf(2026), HolidayCalendar.makeupWorkdaysOf(2026)))
    }

    @Test fun H06_普通周末无内置标记时按周末判定() {
        assertEquals(DayType.HOLIDAY,
            dayTypeOf("2026-09-26", HolidayCalendar.holidaysOf(2026), HolidayCalendar.makeupWorkdaysOf(2026)))
        assertEquals(DayType.WORKDAY,
            dayTypeOf("2026-09-21", HolidayCalendar.holidaysOf(2026), HolidayCalendar.makeupWorkdaysOf(2026)))
    }

    @Test fun H07_用户覆盖优先于内置表() {
        assertEquals(DayType.WORKDAY, dayTypeOf("2026-10-01", HolidayCalendar.holidaysOf(2026),
            HolidayCalendar.makeupWorkdaysOf(2026), "workday"))
        assertEquals(DayType.HOLIDAY, dayTypeOf("2026-09-21", HolidayCalendar.holidaysOf(2026),
            HolidayCalendar.makeupWorkdaysOf(2026), "holiday"))
    }

    @Test fun H08_该年无数据时返回空不瞎猜() {
        assertNull(dayTypeOf("2027-10-01", HolidayCalendar.holidaysOf(2027), HolidayCalendar.makeupWorkdaysOf(2027)))
        // 但用户手动标记仍然生效
        assertEquals(DayType.HOLIDAY, dayTypeOf("2027-10-01", HolidayCalendar.holidaysOf(2027),
            HolidayCalendar.makeupWorkdaysOf(2027), "holiday"))
    }

    @Test fun H09_含节假日策略的任务在国庆不出现() {
        val t = com.zhiwo.shiguangjian.data.db.entity.TaskEntity(
            content = "背法条", createdAt = "2026-09-01T00:00:00.000Z",
            kind = "daily", dayPolicy = "workday_only"
        )
        assertFalse(isScheduledOn(t, "2026-10-01", DayType.HOLIDAY))
        assertTrue(isScheduledOn(t, "2026-10-08", DayType.WORKDAY))
    }

    @Test fun H10_只在休息日的任务国庆出现平时不出现() {
        val t = com.zhiwo.shiguangjian.data.db.entity.TaskEntity(
            content = "陪家人", createdAt = "2026-09-01T00:00:00.000Z",
            kind = "daily", dayPolicy = "holiday_only"
        )
        assertTrue(isScheduledOn(t, "2026-10-03", DayType.HOLIDAY))
        assertFalse(isScheduledOn(t, "2026-10-08", DayType.WORKDAY))
    }

    @Test fun H11_数据来源已标注() {
        assertTrue(HolidayCalendar.SOURCE.contains("2026"))
        assertTrue(HolidayCalendar.VERSION.isNotBlank())
    }
}
