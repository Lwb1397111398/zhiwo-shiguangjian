package com.zhiwo.shiguangjian.data.db

import android.content.Context
import android.content.SharedPreferences

/** 数据库当前能不能用。门没开到 Ready 之前，界面不许拿库。 */
sealed interface DbState {
    data object Checking : DbState
    data object Ready : DbState
    data class UpgradeFailed(val info: String, val attempts: Int) : DbState
}

/**
 * 迁移失败的处理口径（老板 2026-09-22 红线：从现在起不许随便丢数据）。
 *
 * 以前 `ZhiwoApplication` 捕获迁移异常后直接备份+删库+杀进程，用户只会看见"记录全空了"。
 * 现在失败只记账、只告知；**清库必须由用户在失败页上打字确认后才会发生**。
 *
 * 全部用 `commit()`：进程可能马上被杀，`apply()` 来不及落盘。
 */
object DbGate {

    private const val FILE = "db_state"
    private const val KEY_FAILURE = "migrate_failed"
    private const val KEY_ATTEMPTS = "migrate_attempts"
    private const val KEY_REBUILD_CONFIRMED = "rebuild_confirmed"
    private const val KEY_REBUILT = "rebuilt_at"
    private const val KEY_OPENED_VERSION = "opened_version"

    /** 二次确认要打的字 */
    const val REBUILD_CONFIRM_TEXT = "清空重建"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun recordFailure(ctx: Context, from: Int, to: Int, error: Throwable): String {
        val attempts = prefs(ctx).getInt(KEY_ATTEMPTS, 0) + 1
        val detail = buildString {
            append(error.javaClass.simpleName)
            error.message?.let { append(": ").append(it.take(400)) }
        }
        val info = "数据库从 v$from 升到 v$to 没能完成（第 $attempts 次尝试）。原因：$detail"
        prefs(ctx).edit()
            .putString(KEY_FAILURE, info)
            .putInt(KEY_ATTEMPTS, attempts)
            .commit()
        return info
    }

    fun failureInfo(ctx: Context): String? = prefs(ctx).getString(KEY_FAILURE, null)

    /** 上次成功打开时数据库是哪个版本 —— 失败页要说"从哪升到哪"，异常里又不带这个信息 */
    fun lastOpenedVersion(ctx: Context): Int = prefs(ctx).getInt(KEY_OPENED_VERSION, 0)

    fun noteOpened(ctx: Context, version: Int) {
        prefs(ctx).edit().putInt(KEY_OPENED_VERSION, version).commit()
    }

    fun failureAttempts(ctx: Context): Int = prefs(ctx).getInt(KEY_ATTEMPTS, 0)

    /** 成功开库后必须清掉，否则"清空重建"之后会永远停在失败页 */
    fun clearFailure(ctx: Context) {
        prefs(ctx).edit().remove(KEY_FAILURE).remove(KEY_ATTEMPTS).commit()
    }

    fun requestRebuild(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_REBUILD_CONFIRMED, System.currentTimeMillis()).commit()
    }

    fun rebuildRequested(ctx: Context): Boolean = prefs(ctx).contains(KEY_REBUILD_CONFIRMED)

    fun takeRebuildRequest(ctx: Context) {
        prefs(ctx).edit().remove(KEY_REBUILD_CONFIRMED).commit()
    }

    fun markRebuilt(ctx: Context, backupPath: String) {
        prefs(ctx).edit().putString(KEY_REBUILT, "${System.currentTimeMillis()}|$backupPath").commit()
    }

    /** 返回 (时间戳, 备份目录)；没发生过重建则为 null */
    fun rebuiltNotice(ctx: Context): Pair<Long, String>? {
        val raw = prefs(ctx).getString(KEY_REBUILT, null) ?: return null
        val parts = raw.split('|', limit = 2)
        return parts[0].toLongOrNull()?.let { it to parts.getOrElse(1) { "" } }
    }

    fun clearRebuiltNotice(ctx: Context) {
        prefs(ctx).edit().remove(KEY_REBUILT).commit()
    }
}
