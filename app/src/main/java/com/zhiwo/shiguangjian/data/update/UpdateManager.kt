package com.zhiwo.shiguangjian.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 更新流程状态机：UI（设置页分区 + 全局弹窗）都只读这一个 StateFlow */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionName: String) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val downloaded: Long, val total: Long) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * 应用自更新总管（单例）：
 * - 启动静默检查：24 小时节流，发现新版才进 [UpdateState.Available]（弹窗询问），
 *   检查失败/已是最新不留任何痕迹，不打扰用户；
 * - 手动检查：设置页「检查更新」按钮，结果（含失败原因）都展示在设置页；
 * - 下载：流式 + 进度回调；下载完成自动尝试唤起系统安装器，
 *   缺「安装未知应用」授权则引导用户去开，开完回来再点一次「安装更新」。
 */
object UpdateManager {

    private const val THROTTLE_FILE = "update_last_check.txt"
    private const val THROTTLE_MS = 24 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    fun localVersionCode(context: Context): Int {
        val ctx = context.applicationContext
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    fun localVersionName(context: Context): String {
        val ctx = context.applicationContext
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return pi.versionName ?: "?"
    }

    /** 启动静默检查（24h 节流）。失败或已是最新都静默回 Idle。 */
    fun maybeCheckOnStart(context: Context) {
        if (_state.value !is UpdateState.Idle) return
        val ctx = context.applicationContext
        scope.launch {
            val throttleFile = File(ctx.filesDir, THROTTLE_FILE)
            val last = if (throttleFile.exists()) throttleFile.readText().trim().toLongOrNull() ?: 0L else 0L
            if (System.currentTimeMillis() - last < THROTTLE_MS) return@launch
            val info = runCatching {
                withContext(Dispatchers.IO) { UpdateChecker.check() }
            }.getOrNull() ?: return@launch // 失败不打扰
            noteChecked(throttleFile)
            if (UpdateChecker.hasUpdate(info.versionCode, localVersionCode(ctx))) {
                _state.value = UpdateState.Available(info)
            }
        }
    }

    /** 手动检查（设置页按钮）：结果连同失败原因都会落到 state，由设置页展示 */
    fun checkNow(context: Context) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        val ctx = context.applicationContext
        scope.launch {
            _state.value = UpdateState.Checking
            runCatching { withContext(Dispatchers.IO) { UpdateChecker.check() } }
                .fold(
                    onSuccess = { info ->
                        noteChecked(File(ctx.filesDir, THROTTLE_FILE))
                        _state.value = if (UpdateChecker.hasUpdate(info.versionCode, localVersionCode(ctx))) {
                            UpdateState.Available(info)
                        } else {
                            UpdateState.UpToDate(info.versionName)
                        }
                    },
                    onFailure = { e ->
                        _state.value = UpdateState.Failed(
                            if (e is UpdateCheckException) e.message ?: "检查失败"
                            else "检查失败：${e.message ?: "未知错误"}"
                        )
                    }
                )
        }
    }

    /** 点了「立即更新」：下载 → 就绪 → 有权限就直接唤起安装器 */
    fun downloadAndInstall(context: Context, info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        val ctx = context.applicationContext
        scope.launch {
            _state.value = UpdateState.Downloading(info, 0, info.sizeBytes)
            runCatching {
                withContext(Dispatchers.IO) {
                    UpdateDownloader.download(ctx, info) { done, total ->
                        _state.value = UpdateState.Downloading(info, done, total)
                    }
                }
            }.fold(
                onSuccess = { apk ->
                    _state.value = UpdateState.ReadyToInstall(info, apk)
                    if (UpdateInstaller.canRequestInstall(ctx)) UpdateInstaller.install(ctx, apk)
                },
                onFailure = { e ->
                    _state.value = UpdateState.Failed(
                        "下载失败：${e.message ?: "网络中断"}，到设置页重新检查更新再试"
                    )
                }
            )
        }
    }

    /** 弹窗「安装更新」按钮：有权限直接装；没有就跳系统开关页 + 提示开完回来再点 */
    fun confirmInstall(context: Context) {
        val s = _state.value as? UpdateState.ReadyToInstall ?: return
        val ctx = context.applicationContext
        if (UpdateInstaller.canRequestInstall(ctx)) {
            UpdateInstaller.install(ctx, s.apk)
        } else {
            Toast.makeText(
                ctx, "请允许「安装未知应用」后，回来再点一次「安装更新」", Toast.LENGTH_LONG
            ).show()
            UpdateInstaller.requestInstallPermission(ctx)
        }
    }

    /** 用户点「以后再说」/关闭：回到空闲（已下载的包留着，下次检查同版本会复用） */
    fun dismiss() {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Idle
    }

    private fun noteChecked(file: File) {
        runCatching {
            file.writeText(System.currentTimeMillis().toString())
        }
    }
}
