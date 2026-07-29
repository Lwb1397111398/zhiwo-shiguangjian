package com.zhiwo.shiguangjian.data.ai

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object DateFormats {
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val DATE_TIME_ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    val DATE_TIME_DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val DATE_TIME_FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun nowDate(): String = LocalDateTime.now().format(DATE)
    fun nowDateTimeIso(): String = LocalDateTime.now().format(DATE_TIME_ISO)
    fun nowDateTimeDisplay(): String = LocalDateTime.now().format(DATE_TIME_DISPLAY)
}
