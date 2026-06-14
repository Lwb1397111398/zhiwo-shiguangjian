package com.zhiwo.shiguangjian.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import java.util.TimeZone

data class CalendarEvent(
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val startTime: Long,
    val endTime: Long,
    val allDay: Boolean = false
)

object CalendarHelper {

    private const val TAG = "CalendarHelper"

    /**
     * 获取主日历 ID
     */
    private fun getPrimaryCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )

        // 第一次查询：查找主日历
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val isPrimary = cursor.getInt(1)
                if (isPrimary == 1) return id
            }
        }

        // 如果没有主日历，返回第一个日历
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }

        return null
    }

    /**
     * 添加日历事件（含系统提醒）
     */
    fun addEvent(context: Context, event: CalendarEvent): Long? {
        val calId = getPrimaryCalendarId(context) ?: run {
            Log.e(TAG, "未找到可用日历")
            return null
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, event.startTime)
            put(CalendarContract.Events.DTEND, event.endTime)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
        }

        return try {
            val uri: Uri? = context.contentResolver.insert(
                CalendarContract.Events.CONTENT_URI, values
            )
            val eventId = uri?.lastPathSegment?.toLongOrNull()
            if (eventId != null) {
                // 添加系统日历提醒（提前 15 分钟）
                addReminder(context, eventId, 15)
                Log.d(TAG, "日历事件已添加: id=$eventId（含提醒）")
            }
            eventId
        } catch (e: Exception) {
            Log.e(TAG, "添加日历事件失败", e)
            null
        }
    }

    /**
     * 为日历事件添加提醒
     */
    private fun addReminder(context: Context, eventId: Long, minutesBefore: Int) {
        try {
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutesBefore)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
            Log.d(TAG, "日历提醒已添加: eventId=$eventId, ${minutesBefore}分钟前")
        } catch (e: Exception) {
            Log.e(TAG, "添加日历提醒失败", e)
        }
    }

    /**
     * 查询日历事件
     */
    fun queryEvents(
        context: Context,
        startTime: Long,
        endTime: Long
    ): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY
        )

        // 使用重叠检测：事件与查询窗口有交集即可
        val selection = "${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DTEND} >= ?"
        val selectionArgs = arrayOf(endTime.toString(), startTime.toString())

        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                events.add(
                    CalendarEvent(
                        id = it.getLong(0),
                        title = it.getString(1) ?: "",
                        description = it.getString(2) ?: "",
                        startTime = it.getLong(3),
                        endTime = it.getLong(4),
                        allDay = it.getInt(5) == 1
                    )
                )
            }
        }

        return events
    }

    /**
     * 删除日历事件
     */
    fun deleteEvent(context: Context, eventId: Long): Boolean {
        return try {
            val deleteUri = ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, eventId
            )
            val rows = context.contentResolver.delete(deleteUri, null, null)
            Log.d(TAG, "日历事件已删除: id=$eventId, rows=$rows")
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "删除日历事件失败", e)
            false
        }
    }

    /**
     * 更新日历事件
     */
    fun updateEvent(context: Context, event: CalendarEvent): Boolean {
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.DTSTART, event.startTime)
            put(CalendarContract.Events.DTEND, event.endTime)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        return try {
            val updateUri = ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, event.id
            )
            val rows = context.contentResolver.update(updateUri, values, null, null)
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "更新日历事件失败", e)
            false
        }
    }
}
