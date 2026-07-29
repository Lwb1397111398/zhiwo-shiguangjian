package com.zhiwo.shiguangjian.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.calendar.CalendarHelper
import com.zhiwo.shiguangjian.data.db.entity.SettingEntity
import com.zhiwo.shiguangjian.data.db.entity.SpecialDateEntity
import com.zhiwo.shiguangjian.data.festival.FestivalProvider
import com.zhiwo.shiguangjian.util.LunarCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 重要日子在最近一次出现时的展示信息 */
data class UpcomingSpecialDate(
    val entity: SpecialDateEntity,
    val daysUntil: Int,          // 0 = 今天
    val solarDateText: String    // 如 "8月3日"（农历日期换算后的公历）
)

/** 每日揭历卡片的数据 */
data class DailyGreetingInfo(
    val dateText: String,        // 7月26日
    val weekdayText: String,     // 星期六
    val lunarText: String,       // 农历六月初三
    val festivals: List<String>,
    val todaySpecials: List<UpcomingSpecialDate>,
    val upcomingSpecials: List<UpcomingSpecialDate>,   // 未来 30 天内
    val calendarEvents: List<String>                    // 今日系统日历事项
)

class SpecialDateViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhiwoApplication
    private val specialDateDao = app.database.specialDateDao()
    private val settingDao = app.database.settingDao()

    val specialDates: StateFlow<List<SpecialDateEntity>> = specialDateDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _greetingInfo = MutableStateFlow<DailyGreetingInfo?>(null)
    val greetingInfo: StateFlow<DailyGreetingInfo?> = _greetingInfo

    private val _showGreeting = MutableStateFlow(false)
    val showGreeting: StateFlow<Boolean> = _showGreeting

    companion object {
        private const val KEY_LAST_GREETING_DATE = "lastDailyGreetingDate"
        private const val TAG = "SpecialDateViewModel"
    }

    init {
        checkDailyGreeting()
    }

    /** 每天第一次进入应用时准备揭历数据 */
    private fun checkDailyGreeting() {
        viewModelScope.launch {
            try {
                val today = dateKey(Calendar.getInstance())
                val last = withContext(Dispatchers.IO) {
                    settingDao.getSettingValue(KEY_LAST_GREETING_DATE)
                }
                if (last != today) {
                    _greetingInfo.value = withContext(Dispatchers.IO) { buildGreetingInfo() }
                    _showGreeting.value = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "准备每日揭历失败", e)
            }
        }
    }

    fun dismissGreeting() {
        _showGreeting.value = false
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    settingDao.insertSetting(
                        SettingEntity(key = KEY_LAST_GREETING_DATE, value = dateKey(Calendar.getInstance()))
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "记录揭历日期失败", e)
            }
        }
    }

    fun addSpecialDate(title: String, month: Int, day: Int, isLunar: Boolean, type: String, year: Int? = null) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    specialDateDao.insert(
                        SpecialDateEntity(
                            title = title.trim(),
                            month = month,
                            day = day,
                            year = year,
                            isLunar = isLunar,
                            type = type,
                            createdAt = isoNow()
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "添加重要日子失败", e)
            }
        }
    }

    fun deleteSpecialDate(id: Long) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { specialDateDao.deleteById(id) }
            } catch (e: Exception) {
                Log.e(TAG, "删除重要日子失败", e)
            }
        }
    }

    /** 计算某个重要日子下一次出现的信息（用于列表页展示倒计时） */
    fun upcomingOf(entity: SpecialDateEntity): UpcomingSpecialDate? =
        nextOccurrence(entity, Calendar.getInstance())

    private suspend fun buildGreetingInfo(): DailyGreetingInfo {
        val now = Calendar.getInstance()
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)

        val lunar = LunarCalendar.solarToLunar(now.get(Calendar.YEAR), month, day)
        val weekdays = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

        val all = try {
            specialDateDao.getAllOnce()
        } catch (e: Exception) {
            Log.e(TAG, "读取重要日子失败", e)
            emptyList()
        }
        val occurrences = all.mapNotNull { nextOccurrence(it, now) }
        val (todayList, futureList) = occurrences.partition { it.daysUntil == 0 }

        return DailyGreetingInfo(
            dateText = "${month}月${day}日",
            weekdayText = weekdays[now.get(Calendar.DAY_OF_WEEK) - 1],
            lunarText = lunar?.let { "农历${it}" } ?: "",
            festivals = FestivalProvider.festivalsFor(now),
            todaySpecials = todayList,
            upcomingSpecials = futureList.filter { it.daysUntil <= 30 }.sortedBy { it.daysUntil },
            calendarEvents = queryTodayCalendarEvents(now)
        )
    }

    private fun nextOccurrence(entity: SpecialDateEntity, from: Calendar): UpcomingSpecialDate? {
        val today = from.clone() as Calendar
        today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0)

        // 仅一次的日子（如四六级报名）：固定在指定年份，过期返回 null
        if (entity.year != null) {
            val cal = today.clone() as Calendar
            cal.set(Calendar.YEAR, entity.year)
            cal.set(Calendar.MONTH, entity.month - 1)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.DAY_OF_MONTH, entity.day.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
            val days = ((cal.timeInMillis - today.timeInMillis) / 86400000L).toInt()
            if (days < 0) return null
            return UpcomingSpecialDate(
                entity = entity,
                daysUntil = days,
                solarDateText = "${entity.year}年${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
            )
        }

        val target: Calendar = if (entity.isLunar) {
            LunarCalendar.nextSolarDateOfLunar(entity.month, entity.day, today) ?: return null
        } else {
            val cal = today.clone() as Calendar
            cal.set(Calendar.MONTH, entity.month - 1)
            // 处理 2月29 等非法日期：取当月最大天数
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.DAY_OF_MONTH, entity.day.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
            if (cal.before(today)) {
                cal.add(Calendar.YEAR, 1)
                cal.set(Calendar.MONTH, entity.month - 1)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.DAY_OF_MONTH, entity.day.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
            }
            cal
        }
        val days = ((target.timeInMillis - today.timeInMillis) / 86400000L).toInt()
        return UpcomingSpecialDate(
            entity = entity,
            daysUntil = days,
            solarDateText = "${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日"
        )
    }

    private fun queryTodayCalendarEvents(now: Calendar): List<String> {
        val context = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        return try {
            val start = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
            CalendarHelper.queryEvents(context, start.timeInMillis, end.timeInMillis - 1)
                .map { it.title }
                .filter { it.isNotBlank() }
                .distinct()
                .take(6)
        } catch (e: Exception) {
            Log.e(TAG, "读取系统日历失败", e)
            emptyList()
        }
    }

    private fun dateKey(cal: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).format(Calendar.getInstance().time)
}
