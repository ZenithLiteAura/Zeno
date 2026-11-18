package com.clipnest.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小红书解析：URL/笔记 ID、风控页判定、`__INITIAL_STATE__` 提取与图文/视频归一化。
 *
 * 说明：真机链接目前全部被小红书风控拦截（无 token/过期 token → `/404/sec_...`），
 * 因此 SSR 夹具是**合成**的（`xiaohongshu_note_synthetic.json`，按公开结构编写），真机 live 验证待样例链接。
 */
class XiaohongshuResolverTest {

    private fun loadFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        assertNotNull("缺少夹具：$name", stream)
        return stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private val stateHtml by lazy {
        "<html><head><script>window.__INITIAL_STATE__=${loadFixture("xiaohongshu_note_synthetic.json")};</script></head></html>"
    }

    @Test
    fun `链接与笔记 ID 识别`() {
        val resolver = XiaohongshuResolver()
        assertTrue(resolver.supports("https://xhslink.com/do9xGe"))
        assertTrue(resolver.supports("https://xhslink.cn/o/AxnRePgIokn"))
        assertTrue(resolver.supports("https://www.xiaohongshu.com/explore/6a9d0eb2000000002a024480?xsec_token=x"))
        assertTrue(resolver.supports("https://www.xiaohongshu.com/discovery/item/6a9d0eb2000000002a024480?xsec_source=app_share"))
        assertFalse(resolver.supports("https://www.bilibili.com/video/BV18msM6zEqc"))
        assertFalse(resolver.supports("普通文本"))
        assertFalse(resolver.supports(""))

        assertEquals("6a9d0eb2000000002a024480", XiaohongshuParser.extractNoteId("https://www.xiaohongshu.com/explore/6a9d0eb2000000002a024480?xsec_token=x"))
        assertEquals("6a9d0eb2000000002a024480", XiaohongshuParser.extractNoteId("https://www.xiaohongshu.com/discovery/item/6a9d0eb2000000002a024480"))
        assertNull(XiaohongshuParser.extractNoteId("https://xhslink.com/do9xGe"))
        assertNull(XiaohongshuParser.extractNoteId(null))
    }

    @Test
    fun `风控页判定`() {
        assertTrue(XiaohongshuParser.isBlockedUrl("https://www.xiaohongshu.com/404/sec_QHBAHXPV?source=xhs_sec_server"))
        assertTrue(XiaohongshuParser.isBlockedUrl("https://www.xiaohongshu.com/login?redirectPath=x"))
        assertFalse(XiaohongshuParser.isBlockedUrl("https://www.xiaohongshu.com/explore/6a9d0eb2000000002a024480?xsec_token=x"))
        assertFalse(XiaohongshuParser.isBlockedUrl(null))
    }

    @Test
    fun `__INITIAL_STATE__ 提取与图文笔记归一化`() {
        val state = XiaohongshuParser.extractInitialState(stateHtml)
        assertNotNull("应提取出 INITIAL_STATE（含 undefined 也要能解析）", state)

        val noteId = "6a9d0eb2000000002a024480"
        val work = XiaohongshuParser.parseNote(state!!, noteId, "test")
        assertNotNull(work)
        work!!
        assertTrue(work.ok)
        assertEquals("image", work.type)
        assertEquals(noteId, work.workId)
        assertEquals("暖隅。奶油暖光里的居家碎片。", work.title)
        assertEquals("Carlos光影实录", work.author)
        assertEquals(
            listOf(
                "https://sns-img.example.com/a.webp",
                "https://sns-img.example.com/b.webp",
                "https://sns-img.example.com/c.webp",
            ),
            work.pics,
        )
        assertEquals("封面取第一张图", work.pics.first(), work.coverUrl)
        assertEquals("test", work.source)
        assertTrue("图文作品没有清晰度列表", work.qualities.isEmpty())
        assertEquals("", work.selectedQuality)
    }

    @Test
    fun `视频笔记归一化`() {
        val state = XiaohongshuParser.extractInitialState(stateHtml)!!
        val noteId = "111111111111111111111111"
        val work = XiaohongshuParser.parseNote(state, noteId, "test")!!
        assertTrue(work.ok)
        assertEquals("video", work.type)
        assertEquals(noteId, work.workId)
        assertEquals("视频笔记标题", work.title)
        assertEquals("作者B", work.author)
        assertEquals("https://sns-video.example.com/v.mp4", work.videoUrl)
        assertTrue(work.pics.isEmpty())
        assertEquals("小红书单清晰度", 1, work.qualities.size)
        assertEquals("原画", work.qualities[0].label)
        assertEquals(work.videoUrl, work.qualities[0].url)
        assertEquals("原画", work.selectedQuality)
        assertEquals("原画", work.videoQuality)
    }

    @Test
    fun `没有数据时返回 null 而不是崩溃`() {
        assertNull(XiaohongshuParser.extractInitialState("<html>没有状态</html>"))
        assertNull(XiaohongshuParser.extractInitialState(""))
        val empty = ScriptJson.parseObject("""{"note":{}}""")!!
        assertNull(XiaohongshuParser.findNote(empty, "6a9d0eb2000000002a024480"))
    }

    @Test
    fun `极小预算快速 timeout 不联网`() {
        val startedAt = System.currentTimeMillis()
        val work = XiaohongshuResolver().resolve(
            "https://www.xiaohongshu.com/explore/6a9d0eb2000000002a024480?xsec_token=x&xsec_source=app_share",
            budgetMs = 100,
        )
        val elapsed = System.currentTimeMillis() - startedAt
        assertFalse(work.ok)
        assertEquals("timeout", work.code)
        assertTrue("应快速返回，实际 ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `只有笔记 ID 时也能识别`() {
        val work = XiaohongshuResolver().resolve(
            "https://www.xiaohongshu.com/explore/6a9d0eb2000000002a024480",
            budgetMs = 100,
        )
        assertFalse(work.ok)
        assertEquals("timeout", work.code)
    }
}