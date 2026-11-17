package com.clipnest.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快手解析：URL/作品 ID、页面状态提取、photo 对象归一化。
 *
 * 说明：真机网页（首页/搜索页）是 SPA，HTML 里没有作品数据；暂无可用 `v.kuaishou.com` 样例短链，
 * 因此这里用**合成**的 `__APOLLO_STATE__` 夹具（`kuaishou_photo_synthetic.json`），真机验证待样例链接。
 */
class KuaishouResolverTest {

    private fun loadFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        assertNotNull("缺少夹具：$name", stream)
        return stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private val stateHtml by lazy {
        "<html><head><script>window.__APOLLO_STATE__=${loadFixture("kuaishou_photo_synthetic.json")};</script></head></html>"
    }

    @Test
    fun `链接与作品 ID 识别`() {
        val resolver = KuaishouResolver()
        assertTrue(resolver.supports("https://v.kuaishou.com/abc123"))
        assertTrue(resolver.supports("https://www.kuaishou.com/short-video/3xabc123456"))
        assertTrue(resolver.supports("https://m.kuaishou.com/short-video/3xabc123456?fid=1"))
        assertFalse(resolver.supports("https://www.bilibili.com/video/BV18msM6zEqc"))
        assertFalse(resolver.supports("普通文本"))
        assertFalse(resolver.supports(""))

        assertEquals("3xabc123456", KuaishouParser.extractPhotoId("https://www.kuaishou.com/short-video/3xabc123456"))
        assertNull("短链本身没有 ID", KuaishouParser.extractPhotoId("https://v.kuaishou.com/abc123"))
        assertNull(KuaishouParser.extractPhotoId(null))
    }

    @Test
    fun `APOLLO_STATE 提取与 photo 归一化`() {
        val state = KuaishouParser.extractPageState(stateHtml)
        assertNotNull("应提取出 __APOLLO_STATE__", state)

        val work = KuaishouParser.parsePhoto(state!!, "3xabc123456", "test")
        assertNotNull(work)
        work!!
        assertTrue(work.ok)
        assertEquals("video", work.type)
        assertEquals("3xabc123456", work.workId)
        assertEquals("今天社牛，过来让我骑一下看看能带飞吗 #搞笑", work.title)
        assertEquals("雪柔睡不醒", work.author)
        assertEquals("photoUrl 优先于 manifest", "https://v.kuaishou.example.com/v.mp4", work.videoUrl)
        assertEquals("https://v.kuaishou.example.com/cover.jpg", work.coverUrl)
        assertEquals("test", work.source)
    }

    @Test
    fun `只有 manifest 时从 adaptationSet 取地址`() {
        val state = KuaishouParser.extractPageState(stateHtml)!!
        val work = KuaishouParser.parsePhoto(state, "3xdef789012", "test")!!
        assertTrue(work.ok)
        assertEquals("3xdef789012", work.workId)
        assertEquals("作者C", work.author)
        assertEquals("https://v.kuaishou.example.com/manifest2.mp4", work.videoUrl)
        assertEquals("https://v.kuaishou.example.com/cover2.jpg", work.coverUrl)
    }

    @Test
    fun `页面没有状态时解析返回 null`() {
        assertNull(KuaishouParser.extractPageState("<html>SPA 空壳</html>"))
        assertNull(KuaishouParser.extractPageState(""))
        val emptyState = ScriptJson.parseObject("""{"defaultClient":{}}""")!!
        assertNull(KuaishouParser.findPhoto(emptyState, "3xabc123456"))
        assertNull(KuaishouParser.parsePhoto(emptyState, "3xabc123456", "test"))
    }

    @Test
    fun `极小预算快速 timeout 不联网`() {
        val startedAt = System.currentTimeMillis()
        val work = KuaishouResolver().resolve(
            "https://www.kuaishou.com/short-video/3xabc123456",
            budgetMs = 100,
        )
        val elapsed = System.currentTimeMillis() - startedAt
        assertFalse(work.ok)
        assertEquals("timeout", work.code)
        assertTrue("应快速返回，实际 ${elapsed}ms", elapsed < 5_000)
    }
}