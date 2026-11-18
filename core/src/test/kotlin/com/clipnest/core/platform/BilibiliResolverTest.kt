package com.clipnest.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B 站解析：URL/ID 识别 + 真机抓的 view/playurl 响应夹具（`src/test/resources/fixtures/bilibili_*.json`）。
 */
class BilibiliResolverTest {

    private fun loadFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        assertNotNull("缺少夹具：$name", stream)
        return stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private val viewJson by lazy { loadFixture("bilibili_view_BV18msM6zEqc.json") }
    private val playJson by lazy { loadFixture("bilibili_playurl_BV18msM6zEqc.json") }

    @Test
    fun `链接与视频号识别`() {
        val resolver = BilibiliResolver()
        assertTrue(resolver.supports("https://b23.tv/pigt3PQ"))
        assertTrue(resolver.supports("https://www.bilibili.com/video/BV18msM6zEqc"))
        assertTrue(resolver.supports("看看这个 BV18msM6zEqc 视频"))
        assertTrue(resolver.supports("https://www.bilibili.com/video/av12345"))
        assertFalse(resolver.supports("https://v.douyin.com/abc123/"))
        assertFalse(resolver.supports("https://www.xiaohongshu.com/explore/6a9d0eb2000000002a024480"))
        assertFalse(resolver.supports(""))

        assertEquals("BV18msM6zEqc", BilibiliParser.extractBvid("https://www.bilibili.com/video/BV18msM6zEqc?spm=x"))
        assertNull("b23 短码不是 BV 号，要跟随后才拿得到", BilibiliParser.extractBvid("【标题】 https://b23.tv/pigt3PQ"))
        assertNull(BilibiliParser.extractBvid("没有视频号"))
        assertEquals(12345L, BilibiliParser.extractAid("https://www.bilibili.com/video/av12345"))
    }

    @Test
    fun `真机 view 响应解析`() {
        val view = BilibiliParser.parseView(viewJson)
        assertNotNull(view)
        view!!
        assertEquals("BV18msM6zEqc", view.bvid)
        assertEquals("梅耶·马斯克带你走进无限暖暖3.0", view.title)
        assertEquals("无限暖暖", view.author)
        assertTrue("封面应是完整 URL", view.pic.startsWith("http"))
        assertEquals("cid 应解析出来", 43668736078L, view.cid)
    }

    @Test
    fun `真机 playurl 响应解析`() {
        val play = BilibiliParser.parsePlayUrl(playJson)
        assertNotNull(play)
        play!!
        assertTrue("播放地址应是 https", play.url.startsWith("https://"))
        assertEquals("durl[0].size", 24_962_335L, play.size)
        assertEquals("qn=64 对应 720p", "720p", play.quality)
    }

    @Test
    fun `接口错误码与垃圾 JSON 返回 null`() {
        assertNull(BilibiliParser.parseView("""{"code":-404,"message":"啥都木有","ttl":1}"""))
        assertNull(BilibiliParser.parsePlayUrl("""{"code":-404,"message":"啥都木有"}"""))
        assertNull(BilibiliParser.parseView("not a json"))
        assertNull(BilibiliParser.parsePlayUrl(""))
    }

    @Test
    fun `清晰度数字映射`() {
        assertEquals("360p", BilibiliParser.qualityLabel(16))
        assertEquals("720p", BilibiliParser.qualityLabel(64))
        assertEquals("1080p", BilibiliParser.qualityLabel(80))
        assertEquals("4K", BilibiliParser.qualityLabel(120))
        assertEquals("", BilibiliParser.qualityLabel(9999))
    }

    @Test
    fun `多档 playurl 合并：降序、按 URL 去重、label 归一`() {
        val plays = listOf(
            BilibiliPlayInfo("https://example.com/720.mp4", 2_000_000, "720p"),
            BilibiliPlayInfo("https://example.com/1080-60.mp4", 6_000_000, "1080p60"),
            BilibiliPlayInfo("https://example.com/1080.mp4", 5_000_000, "1080p"),
            BilibiliPlayInfo("https://example.com/1080.mp4", 5_000_000, "1080p"), // 重复 URL
            BilibiliPlayInfo("", 0, "720p"), // 空 URL 跳过
        )
        val qualities = BilibiliParser.mergeQualities(plays)
        assertEquals("同 URL 去重 + 空 URL 跳过", 3, qualities.size)
        assertEquals(listOf("1080p60", "1080p", "720p"), qualities.map { it.label })
        assertEquals(6_000_000L, qualities.first().size)
        assertTrue(
            "排序权重不升",
            qualities.map { BilibiliParser.qualityRank(it.label) }.let { it == it.sortedDescending() },
        )

        assertEquals("1080p", BilibiliParser.normalizeLabel("1080P"))
        assertEquals("原画", BilibiliParser.normalizeLabel(""))
    }

    @Test
    fun `单档 fixture 在 qualities 里也只有一条`() {
        val play = BilibiliParser.parsePlayUrl(playJson)!!
        val qualities = BilibiliParser.mergeQualities(listOf(play))
        assertEquals(1, qualities.size)
        assertEquals("720p", qualities[0].label)
        assertEquals(play.size, qualities[0].size)
    }

    @Test
    fun `极小预算快速 timeout 不联网`() {
        val startedAt = System.currentTimeMillis()
        val work = BilibiliResolver().resolve("https://www.bilibili.com/video/BV18msM6zEqc", budgetMs = 100)
        val elapsed = System.currentTimeMillis() - startedAt
        assertFalse(work.ok)
        assertEquals("timeout", work.code)
        assertTrue("应快速返回，实际 ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `无视频号返回 not_found`() {
        val work = BilibiliResolver().resolve("https://www.bilibili.com/space/123456", budgetMs = 5_000)
        assertFalse(work.ok)
        assertEquals("not_found", work.code)
    }
}