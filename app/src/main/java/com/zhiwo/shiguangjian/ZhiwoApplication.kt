package com.zhiwo.shiguangjian

import android.app.Application
import android.util.Log
import android.widget.Toast
import com.zhiwo.shiguangjian.data.ai.AiRepository
import com.zhiwo.shiguangjian.data.db.AppDatabase
import com.zhiwo.shiguangjian.notification.NotificationHelper
import java.io.PrintWriter
import java.io.StringWriter

class ZhiwoApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val aiRepo = AiRepository()

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.createNotificationChannels(this)

        // 全局异常捕获，记录崩溃日志
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val crashLog = "=== APP崩溃 ===\n${sw}"
            Log.e("ZhiwoApp", crashLog)
            // 持久化崩溃日志到文件
            try {
                val crashDir = getExternalFilesDir("crash_logs") ?: filesDir.resolve("crash_logs")
                if (!crashDir.exists()) crashDir.mkdirs()
                val crashFile = crashDir.resolve("crash_${System.currentTimeMillis()}.txt")
                crashFile.writeText(crashLog)
            } catch (_: Throwable) {
                // 写入失败不递归崩溃
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        lateinit var instance: ZhiwoApplication
            private set
    }
}
