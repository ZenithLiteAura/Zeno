package com.clipnest.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 平台分发：按 host 选解析器；识别不出来 → unsupported（不联网）。 */
class PlatformResolversTest {

    @Test
    fun `按 host 分发到对应平台`() {
        val cases = mapOf(
            "4.89 复制打开抖音，看看【作者的作品】... https://v.douyin.com/depmmgQEbkU/" to "douyin",
            "https://www.douyin.com/video/7689782250233187634" to "douyin",
            "https://b23.tv/pigt3PQ" to "bilibili",
            "https://www.bilibili.com/video/BV18msM6zEqc" to "bilibili",
            "https://xhslink.com/do9xGe" to "xiaohongshu",
            "https://www.xiaohongshu.com/explore/6a9d0eb2000000002a024480?xsec_token=x" to "xiaohongshu",
            "https://v.kuaishou.com/abc123" to "kuaishou",
            "https://www.kuaishou.com/short-video/3xabc123456" to "kuaishou",
        )
        for ((text, expected) in cases) {
            assertEquals("识别错误：$text", expected, PlatformResolvers.pick(text)?.id)
        }
    }

    @Test
    fun `识别不出来的返回 null 且 resolve 给 unsupported`() {
        val unknown = listOf(
            "",
            "这是一段普通文本",
            "https://example.com/video/123",
            "https://www.youtube.com/watch?v=abc",
        )
        for (text in unknown) {
            assertNull("不应识别：$text", PlatformResolvers.pick(text))
        }
        val work = PlatformResolvers.resolve("https://example.com/video/123", 1_000)
        assertTrue(!work.ok)
        assertEquals("unsupported", work.code)
        assertTrue(work.message.isNotEmpty())
    }

    @Test
    fun `四个平台都不会在极小预算下联网卡死`() {
        val inputs = mapOf(
            "bilibili" to "https://www.bilibili.com/video/BV18msM6zEqc",
            "xiaohongshu" to "https://www.xiaohongshu.com/explore/6a9d0eb2000000002a024480?xsec_token=x&xsec_source=app_share",
            "kuaishou" to "https://www.kuaishou.com/short-video/3xabc123456",
        )
        for ((platform, text) in inputs) {
            val startedAt = System.currentTimeMillis()
            val work = PlatformResolvers.resolve(text, budgetMs = 100)
            val elapsed = System.currentTimeMillis() - startedAt
            assertEquals(platform, work.platform)
            assertTrue("$platform 应快速返回，实际 ${elapsed}ms", elapsed < 5_000)
            assertEquals("$platform 应是 timeout", "timeout", work.code)
        }
    }
}