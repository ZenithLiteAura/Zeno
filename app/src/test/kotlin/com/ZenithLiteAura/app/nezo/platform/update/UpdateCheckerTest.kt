package com.ZenithLiteAura.app.nezo.platform.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检查的纯逻辑单测（不触网、不依赖 Android 运行时）：
 * 版本号解析 / 三段数字比较 / GitHub Release JSON 解析。
 */
class UpdateCheckerTest {

    // ── 版本号解析 ────────────────────────────────────────────────────────

    @Test
    fun `tag 前缀 v 与 V 都能去掉`() {
        assertEquals("1.2.0", parseTagVersion("v1.2.0"))
        assertEquals("1.2.0", parseTagVersion("V1.2.0"))
        assertEquals("1.2.0", parseTagVersion("1.2.0"))
        assertEquals("1.2.0", parseTagVersion("  v1.2.0  "))
        assertEquals("1.2.0", parseTagVersion("release-1.2.0"))
        assertEquals("", parseTagVersion("latest"))
        assertEquals("", parseTagVersion(""))
    }

    // ── 版本比较 ──────────────────────────────────────────────────────────

    @Test
    fun `三段数字比较`() {
        assertEquals(-1, compareVersions("1.2.0", "1.2.1"))
        assertEquals(1, compareVersions("1.2.1", "1.2.0"))
        assertEquals(0, compareVersions("1.2.0", "1.2.0"))
    }

    @Test
    fun `按数字而不是按字符串比较`() {
        // 字符串比较会得到 "1.10.0" < "1.9.9"，这里必须是 1.10.0 更大
        assertEquals(1, compareVersions("1.10.0", "1.9.9"))
        assertEquals(-1, compareVersions("2.0.0", "10.0.0"))
        assertEquals(1, compareVersions("10.0.0", "2.0.0"))
    }

    @Test
    fun `段数不同时按 0 补齐`() {
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertEquals(-1, compareVersions("1.2", "1.2.1"))
        assertEquals(1, compareVersions("1.2.1", "1.2"))
    }

    @Test
    fun `带 v 前缀也能比`() {
        assertEquals(1, compareVersions("v1.3.0", "1.2.0"))
        assertEquals(0, compareVersions("v1.2.0", "1.2.0"))
    }

    @Test
    fun `只有更高才算有更新`() {
        assertTrue(isNewer("1.2.1", "1.2.0"))
        assertFalse(isNewer("1.2.0", "1.2.0"))
        assertFalse(isNewer("1.1.9", "1.2.0"))
        // 当前版本比 Release 还高（灰度/内测包）不算更新
        assertFalse(isNewer("1.2.0", "1.3.0"))
    }

    // ── Release JSON 解析 ─────────────────────────────────────────────────

    private val sampleJson = """
        {
          "tag_name": "v1.3.0",
          "html_url": "https://github.com/ZenithLiteAura/Zeno/releases/tag/v1.3.0",
          "assets": [
            {
              "name": "checksums.txt",
              "browser_download_url": "https://example.com/checksums.txt"
            },
            {
              "name": "Nezo-1.3.0.apk",
              "browser_download_url": "https://github.com/ZenithLiteAura/Zeno/releases/download/v1.3.0/Nezo-1.3.0.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析最新 Release 的版本号 页面和 apk 地址`() {
        val info = parseLatestRelease(sampleJson)
        assertNotNull(info)
        requireNotNull(info)

        assertEquals("1.3.0", info.version)
        assertEquals("https://github.com/ZenithLiteAura/Zeno/releases/tag/v1.3.0", info.releaseUrl)
        assertEquals(
            "https://github.com/ZenithLiteAura/Zeno/releases/download/v1.3.0/Nezo-1.3.0.apk",
            info.apkUrl,
        )
    }

    @Test
    fun `没有 apk 资产时 apkUrl 为空但版本仍可用`() {
        val json = """
            {
              "tag_name": "1.4.0",
              "html_url": "https://github.com/ZenithLiteAura/Zeno/releases/tag/1.4.0",
              "assets": []
            }
        """.trimIndent()

        val info = parseLatestRelease(json)
        assertNotNull(info)
        requireNotNull(info)
        assertEquals("1.4.0", info.version)
        assertEquals("", info.apkUrl)
    }

    @Test
    fun `缺少 tag_name 或格式错误时返回 null`() {
        assertNull(parseLatestRelease("""{"html_url":"https://example.com"}"""))
        assertNull(parseLatestRelease("not a json"))
        assertNull(parseLatestRelease("[]"))
        assertNull(parseLatestRelease("""{"tag_name":123}"""))
        assertNull(parseLatestRelease(""))
    }

    @Test
    fun `assets 里只有部分字段时不影响版本解析`() {
        val json = """
            {
              "tag_name": "v2.0.0",
              "assets": [
                {"name": "Nezo-debug.apk"},
                {"browser_download_url": "https://example.com/no-name.apk"}
              ]
            }
        """.trimIndent()

        val info = parseLatestRelease(json)
        assertNotNull(info)
        requireNotNull(info)
        assertEquals("2.0.0", info.version)
        assertEquals("", info.releaseUrl)
        // 第一个 apk 没有下载地址 → 继续找；第二个没 name 不算 .apk 资产
        assertEquals("", info.apkUrl)
    }
}