package com.zhiwo.shiguangjian.data.update

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection

/**
 * 下载更新 APK 到应用缓存目录。规则：
 * - 先写 .part 半截文件，下载完校验字节数与远端一致才改名——永远不留能误装的半截包；
 * - 已存在同版本且长度一致的完整包直接复用，不重下（断点后续装、装失败了重试都省流量）；
 * - 换新版本时清掉旧版本残留。
 */
object UpdateDownloader {

    private const val BUFFER = 64 * 1024

    fun apkFile(context: Context, versionCode: Int): File {
        val dir = File(context.applicationContext.cacheDir, "updates").apply { mkdirs() }
        return File(dir, "update-$versionCode.apk")
    }

    /**
     * 流式下载，[onProgress] 回调 (已下载字节, 总字节；总长未知时为 -1)。
     * 完成返回正式 APK 文件。失败抛 [IOException]，.part 残留会在下次重试时覆盖。
     */
    fun download(context: Context, info: UpdateInfo, onProgress: (Long, Long) -> Unit): File {
        val dest = apkFile(context, info.versionCode)
        // 同版本完整包复用：长度对得上就当之前下完了
        if (dest.exists() && info.sizeBytes > 0 && dest.length() == info.sizeBytes) return dest
        // 清旧版本（cacheDir 系统会兜底清，但同进程内留着白占空间）
        dest.parentFile?.listFiles()?.filter { it != dest }?.forEach { it.delete() }
        val part = File(dest.path + ".part")

        val conn = (java.net.URI(info.downloadUrl).toURL().openConnection() as HttpURLConnection)
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true // GitHub 资产实际落在 objects.githubusercontent.com
            conn.setRequestProperty("User-Agent", "zhiwo-shiguangjian-app")
            val code = conn.responseCode
            if (code != 200) throw IOException("下载失败：GitHub 返回 $code")
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            // 不留半截包：长度对得上才转正
            if (total > 0 && done != total) {
                part.delete()
                throw IOException("下载不完整（$done / $total 字节），重试一次吧")
            }
            if (!part.renameTo(dest)) {
                // 极端情况（跨卷等）用 copy 兜底
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            return dest
        } finally {
            conn.disconnect()
        }
    }
}
