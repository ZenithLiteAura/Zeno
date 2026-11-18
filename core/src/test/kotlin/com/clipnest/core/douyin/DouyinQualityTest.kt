package com.clipnest.core.douyin

import com.clipnest.core.platform.MediaQuality
import com.clipnest.core.platform.MediaWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「更多清晰度」：bit_rate 多档 → qualities 列表（顺序 / 去重 / watermark 标记 / 默认选中），
 * 以及向后兼容（qualities 为空时 videoUrl 仍然可用）。
 */
class DouyinQualityTest {

    private fun loadFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        assertNotNull("缺少夹具：$name", stream)
        return stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun htmlWithRouter(routerJson: String): String =
        "<html><head><script>window._ROUTER_DATA = $routerJson;</script></head></html>"

    @Test
    fun `bit_rate 多档解析成 qualities：降序、去重、watermark 标记`() {
        val html = htmlWithRouter(loadFixture("douyin_bitrate_multi.json"))
        val work = DouyinParser.parseRouter(html)
        assertNotNull(work)
        work!!
        assertTrue(work.ok)
        assertEquals("video", work.type)

        val qualities = work.qualities
        assertEquals("重复 URL 应去重（720P 出现两次）", 4, qualities.size)
        assertEquals("URL 不能重复", qualities.size, qualities.map { it.url }.distinct().size)

        // 从高到低：1080p → 720p → 540p → 未知档
        assertEquals(
            listOf("1080p", "720p", "540p", "未知档水印"),
            qualities.map { it.label },
        )
        assertEquals("默认选中最高清晰度", "1080p", work.selectedQuality)
        assertEquals("videoQuality 与 selectedQuality 同值", "1080p", work.videoQuality)
        assertEquals("videoUrl 等于默认选中项", qualities.first().url, work.videoUrl)
        assertEquals("1080p 大小取 data_size", 5_000_000L, qualities.first().size)

        val watermark = qualities.filter { it.watermark }
        assertEquals("应恰好有一条 playwm 候选被标记为水印", 1, watermark.size)
        assertTrue(watermark[0].url.contains("playwm"))
        assertEquals("水印候选排最后（未知清晰度）", qualities.last().url, watermark[0].url)
    }

    @Test
    fun `真实视频 SSR 夹具也带 qualities 且默认选中项在列表里`() {
        val html = loadFixture("share_video_7689782250233187634.html")
        val work = DouyinParser.parseRouter(html)
        assertNotNull(work)
        work!!

        assertTrue("视频作品 qualities 不应为空", work.qualities.isNotEmpty())
        assertTrue("selectedQuality 必须属于 qualities", work.qualities.any { it.label == work.selectedQuality })
        assertEquals("默认选中项就是 videoUrl", work.qualities.first { it.label == work.selectedQuality }.url, work.videoUrl)
        // 该作品 SSR 的 bit_rate 为空，退回单候选（play_addr.uri 拼的 1080p）
        assertEquals(1, work.qualities.size)
        assertEquals("1080p", work.qualities[0].label)
        assertEquals(work.videoUrl, work.qualities[0].url)

        // 顺序要求：清晰度权重不升
        val ranks = work.qualities.map { DouyinParser.qualityRank(it.label) }
        assertEquals(ranks.sortedDescending(), ranks)
    }

    @Test
    fun `图文作品没有 qualities`() {
        val html = loadFixture("share_note_7689482856784049259.html")
        val work = DouyinParser.parseRouter(html)!!
        assertEquals("image", work.type)
        assertTrue(work.qualities.isEmpty())
        assertEquals("", work.selectedQuality)
    }

    @Test
    fun `向后兼容：qualities 为空时 videoUrl 仍然可用`() {
        // 旧调用/旧数据直接构造，不传 qualities
        val old = DouyinWork(ok = true, type = "video", videoUrl = "https://example.com/v.mp4")
        assertTrue(old.qualities.isEmpty())
        assertEquals("", old.selectedQuality)
        assertEquals("https://example.com/v.mp4", old.videoUrl)

        val genericOld = MediaWork(ok = true, type = "video", videoUrl = "https://example.com/v.mp4")
        assertTrue(genericOld.qualities.isEmpty())
        assertEquals("", genericOld.selectedQuality)
        assertEquals("https://example.com/v.mp4", genericOld.videoUrl)
    }

    @Test
    fun `清晰度标签归一与权重`() {
        assertEquals("1080p", DouyinParser.normalizeQualityLabel("1080P"))
        assertEquals("720p", DouyinParser.normalizeQualityLabel("720p"))
        assertEquals("原画", DouyinParser.normalizeQualityLabel(""))
        assertEquals("水印备选", DouyinParser.normalizeQualityLabel("水印备选"))

        assertTrue(DouyinParser.qualityRank("1080p") > DouyinParser.qualityRank("720p"))
        assertTrue(DouyinParser.qualityRank("4K") > DouyinParser.qualityRank("1080p"))
        assertTrue(DouyinParser.qualityRank("原画") > DouyinParser.qualityRank("1080p"))
        assertEquals(0, DouyinParser.qualityRank("未知档"))
    }

    @Test
    fun `MediaQuality 默认 watermark false 且 size 0`() {
        val quality = MediaQuality(label = "720p", url = "https://example.com/v.mp4")
        assertFalse(quality.watermark)
        assertEquals(0L, quality.size)
    }
}