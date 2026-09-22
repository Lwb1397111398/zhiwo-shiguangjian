package com.zhiwo.shiguangjian.data.festival

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/**
 * 从系统日历里读节假日（老板提的思路：「直接和日历联动不就好了吗」）。
 *
 * 如实交代能力边界：安卓**没有**"每台手机都有节假日日历"这回事。原生/Google 账号可能同步一份
 * 「中国节假日」，厂商 ROM 各家不同，也可能一个都没有；而且 `Calendars` 表里根本没有 `OWNER_NAME`
 * 这一列（`ACCOUNT_NAME`/`OWNER_ACCOUNT` 才有），所以名字要 COALESCE 着取。
 * 因此这里只在**内置公告数据没覆盖这一年**时才来读，读不到就交回上层按周末猜并提示，绝不假装准。
 */
object DeviceHolidaySource {

    data class Read(
        val holidays: Set<String>,
        val makeupWorkdays: Set<String>,
        val calendarName: String?,
        val note: String
    ) {
        companion object {
            val EMPTY = Read(emptySet(), emptySet(), null, "未读取")
        }
    }

    private val NAME_HINT = Regex("""节假日|假期|中国节日|Holiday|holiday""")
    private val WORKDAY_HINT = Regex("""班|上班|调休|补班|补班日""")

    fun read(ctx: Context, year: Int): Read {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) return Read(emptySet(), emptySet(), null, "没有日历权限")

        return try {
            val calendars = mutableListOf<Triple<Long, String, Int>>()   // id, name, allDay 事件候选
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.OWNER_ACCOUNT,
                    CalendarContract.Calendars.VISIBLE
                ),
                "${CalendarContract.Calendars.VISIBLE} = 1", null, null
            ).use { c ->
                if (c != null) {
                    while (c.moveToNext()) {
                        val name = (0 until 3).map { idx ->
                            val column = when (idx) {
                                0 -> CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
                                1 -> CalendarContract.Calendars.ACCOUNT_NAME
                                else -> CalendarContract.Calendars.OWNER_ACCOUNT
                            }
                            val i = c.getColumnIndex(column)
                            if (i >= 0) c.getString(i) else null
                        }.firstOrNull { !it.isNullOrBlank() } ?: ""
                        if (NAME_HINT.containsMatchIn(name)) {
                            calendars += Triple(c.getLong(0), name, 0)
                        }
                    }
                }
            }
            if (calendars.isEmpty()) return Read(emptySet(), emptySet(), null, "手机里没有匹配的节假日日历")

            val start = "${year}-01-01".toDayMillis()
            val end = "${year + 1}-01-01".toDayMillis()
            val holidays = linkedSetOf<String>()
            val workdays = linkedSetOf<String>()
            val ids = calendars.joinToString(",") { it.first.toString() }
            ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.TITLE, CalendarContract.Events.ALL_DAY),
                "${CalendarContract.Events.CALENDAR_ID} IN ($ids) AND " +
                    "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?",
                arrayOf(start.toString(), end.toString()), null
            ).use { c ->
                if (c != null) {
                    val iStart = c.getColumnIndex(CalendarContract.Events.DTSTART)
                    val iTitle = c.getColumnIndex(CalendarContract.Events.TITLE)
                    val iAllDay = c.getColumnIndex(CalendarContract.Events.ALL_DAY)
                    while (c.moveToNext()) {
                        if (iAllDay >= 0 && c.getInt(iAllDay) != 1) continue      // 只认全天事件
                        val millis = if (iStart >= 0) c.getLong(iStart) else continue
                        val day = millis.toIsoDate()
                        val title = if (iTitle >= 0) c.getString(iTitle) ?: "" else ""
                        if (WORKDAY_HINT.containsMatchIn(title)) workdays += day else holidays += day
                    }
                }
            }
            Read(holidays, workdays, calendars.first().second, "读到 ${holidays.size} 个休息日 / ${workdays.size} 个调休上班日")
        } catch (e: Exception) {
            android.util.Log.w("DeviceHolidaySource", "读系统日历失败，按无数据降级: ${e.message}")
            Read(emptySet(), emptySet(), null, "读日历出错：${e.message?.take(80) ?: "未知"}")
        }
    }

    /** 只用来做日期换算，不依赖时区库 */
    private fun String.toDayMillis(): Long = try {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(this)?.time ?: 0L
    } catch (_: Exception) { 0L }

    private fun Long.toIsoDate(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(this))
}
