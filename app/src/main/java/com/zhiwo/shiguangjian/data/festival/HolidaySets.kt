package com.zhiwo.shiguangjian.data.festival

import android.content.Context

/**
 * 节假日数据的唯一取用口：**内置公告优先**，只有公告没覆盖那一年才去读系统日历，
 * 两边都没有就返回空集 —— 让上层退到"按周末猜 + 页面明说在猜"，而不是假装知道。
 */
object HolidaySets {

    private val cache = mutableMapOf<Int, Pair<Set<String>, Set<String>>>()

    fun of(ctx: Context?, year: Int): Pair<Set<String>, Set<String>> {
        if (HolidayCalendar.hasDataFor(year)) {
            return HolidayCalendar.holidaysOf(year) to HolidayCalendar.makeupWorkdaysOf(year)
        }
        if (ctx == null) return emptySet<String>() to emptySet()
        return cache.getOrPut(year) {
            val read = DeviceHolidaySource.read(ctx, year)
            android.util.Log.i(
                "HolidaySets",
                "${year} 年没有内置公告数据，改用系统日历：${read.calendarName ?: "无匹配"} —— ${read.note}"
            )
            read.holidays to read.makeupWorkdays
        }
    }

    /** 用户手动授予日历权限、或切换年份后调用 */
    fun invalidate() = cache.clear()

    /** 给设置页/提示语用的来源说明 */
    fun sourceLabel(ctx: Context?, year: Int): String = when {
        HolidayCalendar.hasDataFor(year) -> "内置公告（${HolidayCalendar.SOURCE}，v${HolidayCalendar.VERSION}）"
        else -> {
            val read = if (ctx == null) DeviceHolidaySource.Read.EMPTY else DeviceHolidaySource.read(ctx, year)
            if (read.holidays.isEmpty() && read.makeupWorkdays.isEmpty())
                "没找到节假日日历（${read.note}）：按周末判定"
            else "系统日历《${read.calendarName}》：${read.note}"
        }
    }
}
