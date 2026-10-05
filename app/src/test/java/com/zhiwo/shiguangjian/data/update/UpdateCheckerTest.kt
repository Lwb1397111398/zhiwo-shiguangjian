package com.zhiwo.shiguangjian.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpdateChecker 纯函数（解析 + 版本比较）的单测，不联网。
 * CI 发的 Release 格式约定：描述第一行是元数据 HTML 注释，资产里有一个 .apk。
 */
class UpdateCheckerTest {

    private val apkAsset =
        """{"name": "zhiwo-shiguangjian.apk",
            "browser_download_url": "https://github.com/o/r/releases/download/latest/zhiwo-shiguangjian.apk",
            "size": 12345678}"""

    private fun releaseJson(body: String, assets: String): String =
        """{"tag_name": "latest", "name": "Latest", "body": "$body", "assets": [$assets]}"""

    @Test
    fun `正常解析 元数据+apk资产+说明`() {
        val json = releaseJson(
            body = "<!-- appupdate versionCode=21 versionName=1.1.21 -->\\n- 修复选星期只响一天\\n- 优化提醒",
            assets = apkAsset
        )
        val info = UpdateChecker.parseReleaseJson(json)!!
        assertEquals(21, info.versionCode)
        assertEquals("1.1.21", info.versionName)
        assertEquals(
            "https://github.com/o/r/releases/download/latest/zhiwo-shiguangjian.apk",
            info.downloadUrl
        )
        assertEquals(12345678L, info.sizeBytes)
        assertEquals("- 修复选星期只响一天\n- 优化提醒", info.notes)
    }

    @Test
    fun `versionName 允许连字符`() {
        val json = releaseJson(
            body = "<!-- appupdate versionCode=5 versionName=1.1.5-rc1 -->更新",
            assets = apkAsset
        )
        val info = UpdateChecker.parseReleaseJson(json)!!
        assertEquals("1.1.5-rc1", info.versionName)
        assertEquals("更新", info.notes)
    }

    @Test
    fun `缺元数据注释返回null`() {
        assertNull(UpdateChecker.parseReleaseJson(releaseJson("只有说明文字", apkAsset)))
    }

    @Test
    fun `versionCode不是数字返回null`() {
        assertNull(
            UpdateChecker.parseReleaseJson(
                releaseJson("<!-- appupdate versionCode=abc versionName=1.0.0 -->", apkAsset)
            )
        )
    }

    @Test
    fun `资产里没有apk返回null`() {
        val zip = """{"name": "src.zip", "browser_download_url": "https://x/src.zip", "size": 9}"""
        assertNull(UpdateChecker.parseReleaseJson(releaseJson("<!-- appupdate versionCode=2 versionName=1.0.2 -->", zip)))
    }

    @Test
    fun `body为空或assets缺失返回null`() {
        assertNull(UpdateChecker.parseReleaseJson("""{"tag_name": "latest"}"""))
    }

    @Test
    fun `多个资产时选中apk`() {
        val zip = """{"name": "src.zip", "browser_download_url": "https://x/src.zip", "size": 9}"""
        val json = releaseJson(
            "<!-- appupdate versionCode=3 versionName=1.0.3 -->",
            "$zip, $apkAsset"
        )
        assertEquals("zhiwo-shiguangjian.apk", UpdateChecker.parseReleaseJson(json)!!.downloadUrl.substringAfterLast('/'))
    }

    @Test
    fun `版本比较 只有远端更大才算有更新`() {
        assertTrue(UpdateChecker.hasUpdate(22, 21))
        assertFalse(UpdateChecker.hasUpdate(21, 21)) // 同版本不提示，但走下载复用可重装
        assertFalse(UpdateChecker.hasUpdate(20, 21))
    }
}
