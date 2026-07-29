package com.zhiwo.shiguangjian.data.festival

import com.zhiwo.shiguangjian.util.LunarCalendar
import java.util.Calendar

/**
 * 节日查询：内置公历节日、农历节日和规则节日（母亲节等），
 * 不依赖网络，配合系统日历事件一起在每日揭历中展示。
 */
object FestivalProvider {

    private val SOLAR_FESTIVALS = mapOf(
        "1-1" to "元旦",
        "2-14" to "情人节",
        "3-8" to "妇女节",
        "3-12" to "植树节",
        "4-1" to "愚人节",
        "5-1" to "劳动节",
        "5-4" to "青年节",
        "6-1" to "儿童节",
        "7-1" to "建党节",
        "8-1" to "建军节",
        "9-10" to "教师节",
        "10-1" to "国庆节",
        "12-24" to "平安夜",
        "12-25" to "圣诞节"
    )

    private val LUNAR_FESTIVALS = mapOf(
        "1-1" to "春节",
        "1-15" to "元宵节",
        "2-2" to "龙抬头",
        "5-5" to "端午节",
        "7-7" to "七夕节",
        "7-15" to "中元节",
        "8-15" to "中秋节",
        "9-9" to "重阳节",
        "12-8" to "腊八节",
        "12-23" to "小年"
    )

    /** 返回某公历日期的所有节日名 */
    fun festivalsFor(cal: Calendar): List<String> {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val result = mutableListOf<String>()

        SOLAR_FESTIVALS["$month-$day"]?.let { result.add(it) }

        val lunar = LunarCalendar.solarToLunar(year, month, day)
        if (lunar != null && !lunar.isLeapMonth) {
            LUNAR_FESTIVALS["${lunar.month}-${lunar.day}"]?.let { result.add(it) }
        }
        if (LunarCalendar.isLunarNewYearEve(year, month, day)) {
            result.add("除夕")
        }

        // 清明：通用公式，20 世纪常数 5.59，21 世纪常数 4.81
        if (month == 4) {
            val c = if (year < 2000) 5.59 else 4.81
            val qingming = (year % 100 * 0.2422 + c).toInt() - year % 100 / 4
            if (day == qingming) result.add("清明节")
        }

        // 规则节日
        val weekOfMonth = (day - 1) / 7 + 1  // 当月第几个"该星期几"
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        when {
            month == 5 && dayOfWeek == Calendar.SUNDAY && weekOfMonth == 2 -> result.add("母亲节")
            month == 6 && dayOfWeek == Calendar.SUNDAY && weekOfMonth == 3 -> result.add("父亲节")
            month == 11 && dayOfWeek == Calendar.THURSDAY && weekOfMonth == 4 -> result.add("感恩节")
        }

        return result
    }
}
