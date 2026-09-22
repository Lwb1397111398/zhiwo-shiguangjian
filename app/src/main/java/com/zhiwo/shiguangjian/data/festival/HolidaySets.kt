package com.zhiwo.shiguangjian.data.festival

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 节假日数据的唯一取用口：**内置公告优先**，公告没覆盖那一年才用系统日历读来的数据，
 * 两边都没有就返回空集 —— 让上层退到"按周末猜 + 页面明说在猜"，而不是假装知道。
 *
 * 两条硬规矩（都是质检指出的坑）：
 * - [of] **不查 ContentProvider**，只读缓存与内置数据。调用方（安排页的 combine、闹钟闸门判定）
 *   可能跑在主线程，同步查日历就是卡顿/ANR。
 * - [warm] 只有真读到东西才写缓存。以前用 `getOrPut`，第一次"没权限"返回的空集会被钉一整个进程寿命，
 *   用户当场点了"允许"也照样无效。
 */
object HolidaySets {

    private val cache = ConcurrentHashMap<Int, Pair<Set<String>, Set<String>>>()

    private fun builtin(year: Int): Pair<Set<String>, Set<String>> =
        HolidayCalendar.holidaysOf(year) to HolidayCalendar.makeupWorkdaysOf(year)

    fun of(ctx: Context?, year: Int): Pair<Set<String>, Set<String>> {
        if (HolidayCalendar.hasDataFor(year)) return builtin(year)
        return cache[year] ?: (emptySet<String>() to emptySet<String>())
    }

    /** 后台预热系统日历（内置公告覆盖的年份直接跳过） */
    suspend fun warm(ctx: Context, year: Int) = withContext(Dispatchers.IO) {
        if (HolidayCalendar.hasDataFor(year)) return@withContext
        val read = try {
            DeviceHolidaySource.read(ctx, year)
        } catch (e: Exception) {
            android.util.Log.w("HolidaySets", "$year 年预热节假日日历失败: ${e.message}")
            return@withContext
        }
        if (read.holidays.isNotEmpty() || read.makeupWorkdays.isNotEmpty()) {
            cache[year] = read.holidays to read.makeupWorkdays
            android.util.Log.i("HolidaySets", "$year 年节假日来源：《${read.calendarName}》 ${read.note}")
        } else {
            android.util.Log.i("HolidaySets", "$year 年没有可用的节假日来源：${read.note}")
        }
    }

    /** 授予日历权限之后调用，让下一次预热重新真读一遍 */
    fun invalidate() = cache.clear()

    /** 给设置页/提示语用的来源说明；只读缓存，不查库 */
    fun sourceLabel(ctx: Context?, year: Int): String = when {
        HolidayCalendar.hasDataFor(year) -> "内置公告（${HolidayCalendar.SOURCE}）"
        cache.containsKey(year) -> "系统日历（读到 ${cache[year]!!.first.size} 个休息日、${cache[year]!!.second.size} 个调休上班日）"
        ctx == null -> "未找到（按周末判定）"
        else -> DeviceHolidaySource.read(ctx, year).let {
            if (it.holidays.isEmpty() && it.makeupWorkdays.isEmpty())
                "没找到节假日日历（${it.note}）：按周末判定"
            else "系统日历《${it.calendarName}》：${it.note}"
        }
    }
}
