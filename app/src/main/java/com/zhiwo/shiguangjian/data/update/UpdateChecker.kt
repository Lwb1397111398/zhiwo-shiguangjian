package com.zhiwo.shiguangjian.data.update

import com.google.gson.JsonParser

/**
 * 远端最新发布的信息（由 [UpdateChecker.parseReleaseJson] 从 GitHub API 响应解析而来）。
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** 更新说明：Release 描述去掉第一行元数据注释后的正文 */
    val notes: String,
)

/** 检查更新失败，message 是可直接展示给人看的原因 */
class UpdateCheckException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 检查更新：查 GitHub 公开仓库的 latest Release。
 *
 * 链路约定（与 .github/workflows/build-release.yml 对齐）：
 * Release tag 固定为 latest，描述第一行是元数据 HTML 注释：
 * `<!-- appupdate versionCode=21 versionName=1.1.21 -->`
 * 资产固定为一个 .apk。仓库是公开的，匿名即可访问，无需令牌。
 *
 * 解析与比较是纯函数（可 JVM 单测），网络层只有 [check] 一个薄入口。
 */
object UpdateChecker {

    const val REPO = "Lwb1397111398/zhiwo-shiguangjian"
    const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"

    private const val USER_AGENT = "zhiwo-shiguangjian-app"

    /** 描述第一行的元数据注释，versionName 允许字母数字点连字符 */
    private val META_REGEX =
        Regex("""<!--\s*appupdate\s+versionCode=(\d+)\s+versionName=([A-Za-z0-9.\-]+)\s*-->""")

    /** 远端 versionCode 更大才算有更新；相同/更小都视为已是最新（允许同版本重装走下载复用） */
    fun hasUpdate(remoteVersionCode: Int, localVersionCode: Int): Boolean =
        remoteVersionCode > localVersionCode

    /**
     * 解析 GitHub releases/latest 的 JSON 响应。格式不对返回 null，由调用方报人话错误。
     */
    fun parseReleaseJson(json: String): UpdateInfo? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val body = root.get("body")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val meta = META_REGEX.find(body) ?: return null
            val versionCode = meta.groupValues[1].toInt()
            val versionName = meta.groupValues[2]
            val assets = root.getAsJsonArray("assets") ?: return null
            val apk = assets.firstOrNull { el ->
                el.isJsonObject && el.asJsonObject.get("name")?.asString?.endsWith(".apk") == true
            }?.asJsonObject ?: return null
            val url = apk.get("browser_download_url")?.asString ?: return null
            val size = apk.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1L
            // 展示用正文：去掉元数据注释本身，剩下的人话说明
            val notes = body.replace(META_REGEX, "").trim()
            UpdateInfo(versionCode, versionName, url, size, notes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 实际查一次 GitHub。失败抛 [UpdateCheckException]（文案已人话化）。
     * 公开仓库匿名可查；GitHub 强制要求 User-Agent 头，缺了直接 403。
     */
    fun check(): UpdateInfo {
        val conn = (java.net.URI(LATEST_API).toURL().openConnection() as java.net.HttpURLConnection)
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            when (val code = conn.responseCode) {
                200 -> {
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    return parseReleaseJson(json)
                        ?: throw UpdateCheckException("GitHub 上的发布格式不对（缺元数据或 APK），等 CI 重跑一次再试")
                }
                404 -> throw UpdateCheckException("云端还没有任何发布，等推送后 CI 自动构建完成（几分钟后重试）")
                403 -> throw UpdateCheckException("触发了 GitHub 限流（匿名每小时 60 次），过一会儿再试")
                else -> throw UpdateCheckException("GitHub 返回了 $code，稍后再试")
            }
        } catch (e: UpdateCheckException) {
            throw e
        } catch (e: java.io.IOException) {
            throw UpdateCheckException("连不上 GitHub，请检查网络后重试", e)
        } finally {
            conn.disconnect()
        }
    }
}
