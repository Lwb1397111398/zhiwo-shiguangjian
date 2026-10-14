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
    /** 本次成功取到元数据的通道前缀（""=GitHub 直连）；下载 APK 优先复用同一条通道 */
    val sourcePrefix: String = "",
) {
    /**
     * 下载候选地址（按序尝试）。国内网络常解析不到 github.com/api.github.com（DNS 污染），
     * 所以查询成功走哪条、下载就优先走哪条，再直连、再其余镜像兜底。纯函数可单测。
     */
    fun downloadCandidates(): List<String> =
        (listOf(if (sourcePrefix.isBlank()) downloadUrl else sourcePrefix + downloadUrl, downloadUrl) +
            UpdateChecker.MIRROR_PREFIXES.map { it + downloadUrl }).distinct()
}

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
 * 网络层多源兜底（2026-10-14，老板手机实测 api.github.com DNS 解析失败）：
 * 直连 → gh-proxy.com → gh-proxy.org 依次尝试（后两家 API 透传实测可用；
 * ghfast.top / ghproxy.net 拒绝透传 API（403），只列入 APK 下载兜底）。
 *
 * 解析与比较是纯函数（可 JVM 单测），网络层只有 [check] 一个薄入口。
 */
object UpdateChecker {

    const val REPO = "Lwb1397111398/zhiwo-shiguangjian"
    const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"

    /** 国内加速镜像前缀（带尾斜杠）；拼 API 或 GitHub 原始 URL 均可直接前缀 */
    val MIRROR_PREFIXES = listOf("https://gh-proxy.com/", "https://gh-proxy.org/", "https://ghfast.top/")

    /** API 查询链：直连优先，两个透传镜像兜底。ghfast.top 透传 API 会 403，不入此链 */
    val API_SOURCES: List<Pair<String, String>> =
        listOf("", MIRROR_PREFIXES[0], MIRROR_PREFIXES[1]).map { it to it + LATEST_API }

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
     * 实际查一次最新发布：按 [API_SOURCES] 逐源尝试，任一源成功即返回（并记住该源前缀，
     * 供下载复用同一通道）。全部失败才抛 [UpdateCheckException]（文案已人话化）。
     * 公开仓库匿名可查；GitHub 强制要求 User-Agent 头，缺了直接 403。
     */
    fun check(): UpdateInfo {
        val reasons = mutableListOf<String>()
        var noReleaseCount = 0
        for ((prefix, url) in API_SOURCES) {
            try {
                val conn = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection)
                try {
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    when (val code = conn.responseCode) {
                        200 -> {
                            val json = conn.inputStream.bufferedReader().use { it.readText() }
                            val info = parseReleaseJson(json)
                            if (info != null) return info.copy(sourcePrefix = prefix)
                            reasons += "${label(prefix)}返回的内容格式不对（缺元数据或 APK）"
                        }
                        404 -> { reasons += "${label(prefix)}还没有发布"; noReleaseCount++ }
                        403 -> reasons += "${label(prefix)}拒绝访问（限流或不透传 API）"
                        else -> reasons += "${label(prefix)}返回 $code"
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: java.io.IOException) {
                reasons += "${label(prefix)}连不上（${e.message ?: e.javaClass.simpleName}）"
            }
        }
        // 所有源都报 404 才是真没发布；否则按网络问题处理
        if (noReleaseCount == API_SOURCES.size) {
            throw UpdateCheckException("云端还没有任何发布，等推送后 CI 自动构建完成（几分钟后重试）")
        }
        throw UpdateCheckException(
            "连不上更新服务（直连与加速镜像共 ${API_SOURCES.size} 条通道都失败）。" +
                "请检查手机网络后重试；最后一条原因：${reasons.lastOrNull() ?: "未知"}"
        )
    }

    private fun label(prefix: String): String = when (prefix) {
        "" -> "GitHub 直连"
        else -> "镜像 ${prefix.removePrefix("https://").removeSuffix("/")}"
    }
}
