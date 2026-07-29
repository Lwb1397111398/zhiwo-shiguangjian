package com.zhiwo.shiguangjian.util

import java.util.Calendar

/**
 * 农历换算工具（支持 1900-2049 年）。
 * 数据表为通用农历压缩编码：低 4 位为闰月月份（0 表示无闰月），
 * 中间 12 位为 1-12 月大小月（1=30 天，0=29 天），0x10000 位为闰月大小。
 */
object LunarCalendar {

    data class LunarDate(
        val year: Int,
        val month: Int,       // 1-12
        val day: Int,         // 1-30
        val isLeapMonth: Boolean
    ) {
        val monthName: String get() = (if (isLeapMonth) "闰" else "") + MONTH_NAMES[month - 1] + "月"
        val dayName: String get() = DAY_NAMES[day - 1]
        override fun toString(): String = "$monthName$dayName"
    }

    private val MONTH_NAMES = arrayOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    private val DAY_NAMES = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    )

    private const val BASE_YEAR = 1900
    private const val MAX_YEAR = 2049

    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2, // 1900-1909
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977, // 1910-1919
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, // 1920-1929
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950, // 1930-1939
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557, // 1940-1949
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, // 1950-1959
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, // 1960-1969
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b5a0, 0x195a6, // 1970-1979
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570, // 1980-1989
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0, // 1990-1999
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, // 2000-2009
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930, // 2010-2019
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530, // 2020-2029
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, // 2030-2039
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0  // 2040-2049
    )

    private fun leapMonth(year: Int): Int = LUNAR_INFO[year - BASE_YEAR] and 0xf

    private fun leapDays(year: Int): Int =
        if (leapMonth(year) == 0) 0
        else if (LUNAR_INFO[year - BASE_YEAR] and 0x10000 != 0) 30 else 29

    private fun monthDays(year: Int, month: Int): Int =
        if (LUNAR_INFO[year - BASE_YEAR] and (0x10000 shr month) != 0) 30 else 29

    private fun yearDays(year: Int): Int {
        var sum = 348
        var i = 0x8000
        while (i > 0x8) {
            if (LUNAR_INFO[year - BASE_YEAR] and i != 0) sum++
            i = i shr 1
        }
        return sum + leapDays(year)
    }

    /** 公历转农历；超出支持范围返回 null */
    fun solarToLunar(year: Int, month: Int, day: Int): LunarDate? {
        if (year < BASE_YEAR || year > MAX_YEAR) return null
        val base = Calendar.getInstance().apply {
            clear(); set(1900, 0, 31)
        }
        val target = Calendar.getInstance().apply {
            clear(); set(year, month - 1, day)
        }
        var offset = ((target.timeInMillis - base.timeInMillis) / 86400000L).toInt()
        if (offset < 0) return null

        var lunarYear = BASE_YEAR
        while (lunarYear <= MAX_YEAR && offset >= yearDays(lunarYear)) {
            offset -= yearDays(lunarYear)
            lunarYear++
        }
        if (lunarYear > MAX_YEAR) return null

        val leap = leapMonth(lunarYear)
        var lunarMonth = 1
        var isLeap = false
        while (lunarMonth <= 12) {
            val days = if (isLeap) leapDays(lunarYear) else monthDays(lunarYear, lunarMonth)
            if (offset < days) break
            offset -= days
            if (leap != 0 && lunarMonth == leap && !isLeap) {
                isLeap = true // 下一轮是闰月，月份数字不变
            } else {
                isLeap = false
                lunarMonth++
            }
        }
        return LunarDate(lunarYear, lunarMonth, offset + 1, isLeap)
    }

    /** 农历腊月最后一天（除夕）判断：明天是否为正月初一 */
    fun isLunarNewYearEve(year: Int, month: Int, day: Int): Boolean {
        val cal = Calendar.getInstance().apply {
            clear(); set(year, month - 1, day); add(Calendar.DAY_OF_MONTH, 1)
        }
        val tomorrow = solarToLunar(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        ) ?: return false
        return tomorrow.month == 1 && tomorrow.day == 1 && !tomorrow.isLeapMonth
    }

    /**
     * 从 fromCal（含当天）起查找下一个农历 month-day 对应的公历日期，
     * 最多向后找 400 天；找不到（如支持范围外）返回 null。
     */
    fun nextSolarDateOfLunar(lunarMonth: Int, lunarDay: Int, fromCal: Calendar): Calendar? {
        val cursor = fromCal.clone() as Calendar
        repeat(400) {
            val l = solarToLunar(
                cursor.get(Calendar.YEAR), cursor.get(Calendar.MONTH) + 1, cursor.get(Calendar.DAY_OF_MONTH)
            )
            if (l != null && !l.isLeapMonth && l.month == lunarMonth && l.day == lunarDay) {
                return cursor
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }
}
