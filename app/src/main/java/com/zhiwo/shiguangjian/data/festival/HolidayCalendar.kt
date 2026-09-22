package com.zhiwo.shiguangjian.data.festival

/**
 * 法定节假日与调休上班日。
 *
 * 录入纪律：只写经核对的《国务院办公厅关于 X 年部分节假日安排的通知》原文日期，
 * 查不到的年份一律留空（hasDataFor=false），由调用方决定"按周末判定并提示"，绝不凭印象补数据。
 * 用户手动标记（day_overrides 表）优先级高于本表。
 */
object HolidayCalendar {

    /** year -> 放假日集合（yyyy-MM-dd） */
    private val HOLIDAYS: Map<Int, Set<String>> = mapOf(
        2026 to     setOf(
        "2026-01-01",
        "2026-01-02",
        "2026-01-03",
        "2026-02-15",
        "2026-02-16",
        "2026-02-17",
        "2026-02-18",
        "2026-02-19",
        "2026-02-20",
        "2026-02-21",
        "2026-02-22",
        "2026-02-23",
        "2026-04-04",
        "2026-04-05",
        "2026-04-06",
        "2026-05-01",
        "2026-05-02",
        "2026-05-03",
        "2026-05-04",
        "2026-05-05",
        "2026-06-19",
        "2026-06-20",
        "2026-06-21",
        "2026-09-25",
        "2026-09-26",
        "2026-09-27",
        "2026-10-01",
        "2026-10-02",
        "2026-10-03",
        "2026-10-04",
        "2026-10-05",
        "2026-10-06",
        "2026-10-07"
    )
    )

    /** year -> 调休上班日集合（周末要上班的那几天） */
    private val MAKEUP_WORKDAYS: Map<Int, Set<String>> = mapOf(
        2026 to     setOf(
        "2026-01-04",
        "2026-02-14",
        "2026-02-28",
        "2026-05-09",
        "2026-09-20",
        "2026-10-10"
    )
    )

    /** 数据来源见 docs/迭代计划/2026-09-21-任务体系与记忆整理重构/节假日公告-2026.md */
    const val SOURCE = "国务院办公厅关于2026年部分节假日安排的通知（2025-11-04 发布），2026-09-21 录入"
    const val VERSION = "2026-1104"

    fun holidaysOf(year: Int): Set<String> = HOLIDAYS[year].orEmpty()

    fun makeupWorkdaysOf(year: Int): Set<String> = MAKEUP_WORKDAYS[year].orEmpty()

    fun hasDataFor(year: Int): Boolean = HOLIDAYS.containsKey(year)

    fun coveredYears(): Set<Int> = HOLIDAYS.keys
}
