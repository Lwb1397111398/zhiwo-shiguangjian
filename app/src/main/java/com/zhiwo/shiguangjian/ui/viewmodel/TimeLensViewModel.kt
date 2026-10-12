package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.entity.DayOverrideEntity
import com.zhiwo.shiguangjian.data.db.entity.GoalEntity
import com.zhiwo.shiguangjian.data.db.entity.PlanEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskEntity
import com.zhiwo.shiguangjian.data.db.entity.TaskOccurrenceEntity
import com.zhiwo.shiguangjian.data.festival.HolidaySets
import com.zhiwo.shiguangjian.data.tasks.DayInvestment
import com.zhiwo.shiguangjian.data.tasks.DayType
import com.zhiwo.shiguangjian.data.tasks.TodayBudget
import com.zhiwo.shiguangjian.data.tasks.dailyInvestment
import com.zhiwo.shiguangjian.data.tasks.dayTypeOrWeekend
import com.zhiwo.shiguangjian.data.tasks.todayBudget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/**
 * 时光统计页与安排页「今日时间」卡片的数据装配：
 * 只取数、只调 TimeLensCalculator 的纯函数，不写任何排期/口径判断（唯一出处在 data/tasks）。
 */
class TimeLensViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as ZhiwoApplication).database

    val today: String = DateFormats.nowDate()

    private data class LensSources(
        val tasks: List<TaskEntity> = emptyList(),
        val occurrences: List<TaskOccurrenceEntity> = emptyList(),
        val overrides: List<DayOverrideEntity> = emptyList(),
        val plans: List<PlanEntity> = emptyList(),
        val goals: List<GoalEntity> = emptyList()
    )

    private val sources: StateFlow<LensSources> = combine(
        db.taskDao().getAllTasks(),
        db.occurrenceDao().observeInRange("1970-01-01", "9999-12-31"),
        db.dayOverrideDao().getAll(),
        db.planDao().getAllPlans(),
        db.goalDao().getAllGoals()
    ) { tasks, occ, overrides, plans, goals -> LensSources(tasks, occ, overrides, plans, goals) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LensSources())

    data class TimeLensUiState(
        val today: String = "",
        val budget: TodayBudget = TodayBudget(date = "", slices = emptyList(), plannedMinutes = 0, unboundedCount = 0, loggedMinutes = 0),
        /** 最近 14 天逐日投入（含今天），趋势柱用 */
        val trend: List<DayInvestment> = emptyList(),
        /** 本月 1 号到今天的逐日投入，热力图用；当月剩余天数由界面补空格 */
        val heat: List<DayInvestment> = emptyList(),
        val loading: Boolean = true
    )

    val state: StateFlow<TimeLensUiState> = sources.map { src ->
        val overrideByDate = src.overrides.associate { it.date to it.type }
        // 与安排页同一判定：公告优先，公告没覆盖这一年才读系统日历
        fun dayType(date: String): DayType {
            val year = date.take(4).toIntOrNull() ?: LocalDate.now().year
            val (holidays, makeup) = HolidaySets.of(getApplication(), year)
            return dayTypeOrWeekend(date, holidays, makeup, overrideByDate[date])
        }
        val todayDate = LocalDate.parse(today)
        TimeLensUiState(
            today = today,
            budget = todayBudget(today, src.tasks, src.occurrences, dayType(today), src.plans, src.goals),
            trend = dailyInvestment(
                todayDate.minusDays(13).toString(), today,
                src.tasks, src.occurrences, { dayType(it) }, src.plans, src.goals
            ),
            heat = dailyInvestment(
                todayDate.withDayOfMonth(1).toString(), today,
                src.tasks, src.occurrences, { dayType(it) }, src.plans, src.goals
            ),
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimeLensUiState(today = today))
}
