package com.zhiwo.shiguangjian

import android.app.Application
import android.util.Log
import android.widget.Toast
import com.zhiwo.shiguangjian.data.ai.AiRepository
import com.zhiwo.shiguangjian.data.db.AppDatabase
import com.zhiwo.shiguangjian.data.memory.MemoryRetention
import com.zhiwo.shiguangjian.notification.NotificationHelper
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.launch

class ZhiwoApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val aiRepo = AiRepository()

    /** 应用级协程域：页面销毁后仍需完成的轻量后台任务（如保存记录后的记忆对账） */
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** 记忆对账服务：全应用共享单例 */
    val memoryReconciler by lazy {
        com.zhiwo.shiguangjian.data.memory.MemoryReconcileService(
            memoryRepo = com.zhiwo.shiguangjian.data.repository.MemoryRepository(database.memoryDao()),
            settingsRepo = com.zhiwo.shiguangjian.data.repository.SettingsRepository(database.settingDao()),
            aiRepo = aiRepo
        )
    }

    /** 日记重生成共享服务：日记页与记忆页（历史纠错扫描）共用 */
    val diaryRegenerator by lazy {
        com.zhiwo.shiguangjian.data.diary.DiaryRegenerator(
            diaryRepo = com.zhiwo.shiguangjian.data.repository.DiaryRepository(database.diaryDao()),
            recordRepo = com.zhiwo.shiguangjian.data.repository.RecordRepository(
                database, database.recordDao(), database.taskDao(),
                database.tagDao(), database.keyInfoDao()
            ),
            taskRepo = com.zhiwo.shiguangjian.data.repository.TaskRepository(database.taskDao()),
            memoryRepo = com.zhiwo.shiguangjian.data.repository.MemoryRepository(database.memoryDao()),
            settingsRepo = com.zhiwo.shiguangjian.data.repository.SettingsRepository(database.settingDao()),
            secureSettingsRepo = com.zhiwo.shiguangjian.data.repository.SecureSettingsRepository(this),
            aiRepo = aiRepo,
            snapshotReader = com.zhiwo.shiguangjian.data.tasks.TaskSnapshotReader(database)
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.createNotificationChannels(this)

        // 预热数据库：正式版无破坏性迁移兜底，迁移失败时备份原库文件后重建，
        // 避免直接闪退（原数据保留为 .migration_backup_ 时间戳文件，可人工抢救）
        appScope.launch {
            try {
                database.openHelper.writableDatabase
                // 每日任务跨天重置（单一入口；原 CalendarViewModel Flow 副作用已删除）
                val reset = com.zhiwo.shiguangjian.data.repository.TaskRepository(database.taskDao())
                    .resetDailyTasksForNewDay(com.zhiwo.shiguangjian.data.ai.DateFormats.nowDate())
                if (reset > 0) android.util.Log.i("ZhiwoApp", "每日任务跨天重置 $reset 条")
                pruneSupersededMemories()
            } catch (e: Throwable) {
                android.util.Log.e("ZhiwoApp", "数据库打开/迁移失败，备份并重建", e)
                try {
                    AppDatabase.rebuild(this@ZhiwoApplication)
                    // rebuild 造的是新实例，而本类的 database 是 by lazy —— 继续用会拿到已被删文件的旧句柄。
                    // 唯一安全的做法是记录原因后重启进程，让下次冷启动拿干净单例。
                    android.util.Log.w("ZhiwoApp", "已重建数据库，原库已备份，重启进程以使用新库")
                    getSharedPreferences("db_state", MODE_PRIVATE).edit()
                        .putString("rebuilt_at", System.currentTimeMillis().toString()).commit()
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                } catch (e2: Throwable) {
                    android.util.Log.e("ZhiwoApp", "数据库重建也失败", e2)
                }
            }
        }

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

    /**
     * 已更正（superseded）记忆的启动清理：只保留最近 [MemoryRetention.SUPERSEDED_KEEP] 条，
     * 更旧的**先归档成 JSON**（getExternalFilesDir("migration_backup")/superseded_archive_<时间戳>.json）
     * 再物理删——整理本身从不删记忆，不清理就会一直堆在那儿，用户看到的就是"整理完还有残存记忆"。
     * 归档写不成功就不删，宁可多留也不丢数据。
     */
    private suspend fun pruneSupersededMemories() {
        val repo = com.zhiwo.shiguangjian.data.repository.MemoryRepository(database.memoryDao())
        val stale = MemoryRetention.supersededToPrune(repo.getSupersededOnce())
        if (stale.isEmpty()) return
        try {
            val dir = getExternalFilesDir(MemoryRetention.ARCHIVE_DIR)
                ?: filesDir.resolve(MemoryRetention.ARCHIVE_DIR)
            if (!dir.exists()) dir.mkdirs()
            File(dir, MemoryRetention.archiveFileName(System.currentTimeMillis()))
                .writeText(MemoryRetention.archiveJson(stale))
        } catch (e: Throwable) {
            Log.e("ZhiwoApp", "已更正记忆归档失败，本轮跳过清理", e)
            return
        }
        try {
            repo.deleteMemories(stale.map { it.id })
            Log.i("ZhiwoApp", "已归档并清理 ${stale.size} 条超期的已更正记忆（保留最近 ${MemoryRetention.SUPERSEDED_KEEP} 条）")
        } catch (e: Throwable) {
            Log.e("ZhiwoApp", "已更正记忆清理失败（归档文件已生成）", e)
        }
    }
}
