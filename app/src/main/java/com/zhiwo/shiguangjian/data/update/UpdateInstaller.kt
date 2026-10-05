package com.zhiwo.shiguangjian.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 唤起系统安装器安装下载好的 APK。
 * - APK 经 FileProvider（authority = ${applicationId}.fileprovider，paths 里只开了
 *   cache/updates 子目录）给系统临时读权限；
 * - Android 8+ 要求「安装未知应用」授权：没有就先跳系统开关页，用户开完回来再点一次安装。
 */
object UpdateInstaller {

    fun canRequestInstall(context: Context): Boolean =
        context.applicationContext.packageManager.canRequestPackageInstalls()

    /** 跳到系统「允许安装未知应用」的开关页（本应用的） */
    fun requestInstallPermission(context: Context) {
        val ctx = context.applicationContext
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /** 唤起系统安装器；由系统弹确认页，用户确认后真正安装 */
    fun install(context: Context, apk: File) {
        val ctx = context.applicationContext
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            )
        ctx.startActivity(intent)
    }
}
