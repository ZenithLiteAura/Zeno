package com.clipnest.core.douyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实 SSR 夹具测试（Lead 在真机 curl 抓的两份分享页，存于 `src/test/resources/fixtures/`）。
 *
 * - 图文：`loaderData["note_(id)/page"].videoInfoRes.item_list[0]`，images 9 条；
 * - 视频：`loaderData["video_(id)/page"].videoInfoRes.item_list[0]`，`play_addr.uri` 是 video_id。
 */
class DouyinFixtureTest {

    private fun loadFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        assertNotNull("缺少测试夹具：/fixtures/$name", stream)
        return stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun `真实图文 SSR 解析（9 张图）`() {
        val html = loadFixture("share_note_7689482856784049259.html")
        assertTrue("夹具应是完整 SSR（>50KB）", html.length > 50_000)

        val work = DouyinParser.parseRouter(html)
        assertNotNull("应解析出作品", work)
        work!!

        assertTrue(work.ok)
        assertEquals("image", work.type)
        assertEquals("7689482856784049259", work.awemeId)
        assertEquals("Carlos光影实录", work.authorName)
        assertEquals("iesdouyin", work.source)
        assertEquals("图文作品应有 9 张图", 9, work.pics.size)
        assertEquals("图片直链不能重复", 9, work.pics.distinct().size)
        assertTrue("图片直链必须是 https", work.pics.all { it.startsWith("https://") })
        assertTrue("不能带水印图（download_url_list）", work.pics.none { it.contains("watermark") })
        assertEquals(work.pics.first(), work.coverUrl)
        assertTrue("标题应保留正文", work.title.startsWith("暖隅。"))
        assertTrue("标题不应有换行", !work.title.contains('\n') && !work.title.contains('\r'))
        assertTrue("标题应包含 emoji", work.title.contains("📸"))
        assertEquals("图文作品没有视频地址", "", work.videoUrl)
    }

    @Test
    fun `真实视频 SSR 解析（video_id 拼无水印地址）`() {
        val html = loadFixture("share_video_7689782250233187634.html")

        val work = DouyinParser.parseRouter(html)
        assertNotNull("应解析出作品", work)
        work!!

        assertTrue(work.ok)
        assertEquals("video", work.type)
        assertEquals("7689782250233187634", work.awemeId)
        assertEquals("雪柔睡不醒", work.authorName)
        assertTrue("视频作品不能有图片", work.pics.isEmpty())
        assertEquals(
            "https://aweme.snssdk.com/aweme/v1/play/?video_id=v0300fg10000darpehvog65mmmtgf4m0&ratio=1080p&line=0",
            work.videoUrl,
        )
        assertEquals("1080p", work.videoQuality)
        assertTrue("播放地址不能是 playwm（带水印）", !work.videoUrl.contains("playwm"))
        assertTrue("封面应为 https", work.coverUrl.startsWith("https://"))
        assertTrue("视频大小不能为负", work.videoSize >= 0)
        assertEquals("iesdouyin", work.source)
    }

    @Test
    fun `同一份 SSR 多次解析结果一致`() {
        val html = loadFixture("share_note_7689482856784049259.html")
        val first = DouyinParser.parseRouter(html)!!
        val second = DouyinParser.parseRouter(html)!!
        assertEquals(first.pics, second.pics)
        assertEquals(first.title, second.title)
        assertEquals(first.awemeId, second.awemeId)
        assertFalse(first.pics.isEmpty())
    }
}