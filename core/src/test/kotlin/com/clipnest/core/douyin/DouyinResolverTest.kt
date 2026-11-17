package com.clipnest.core.douyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器对外行为（**不发真实网络请求**）：
 * 输入校验、预算耗尽快速返回 timeout、错误只收敛成契约允许的 code、绝不抛裸异常。
 *
 * 真实联网链路见 `DouyinResolverLiveTest`（@Ignore，需要真机/可访问 douyin.com 的环境手动跑）。
 */
class DouyinResolverTest {

    private val resolver = DouyinResolver()
    private val allowedCodes = setOf("not_found", "parse_failed", "timeout", "network", "no_media")

    @Test
    fun `空输入返回 not_found 且不抛`() {
        val work = resolver.resolve("")
        assertFalse(work.ok)
        assertEquals("not_found", work.code)
        assertTrue(work.message.isNotEmpty())
    }

    @Test
    fun `非抖音文本返回 not_found`() {
        val work = resolver.resolve("这是一段普通的中文文本，没有抖音链接。")
        assertFalse(work.ok)
        assertEquals("not_found", work.code)
    }

    @Test
    fun `预算耗尽时快速返回 timeout 且不联网`() {
        val startedAt = System.currentTimeMillis()
        val work = resolver.resolve(
            "https://www.douyin.com/video/7689782250233187634",
            budgetMs = 100,
        )
        val elapsed = System.currentTimeMillis() - startedAt

        assertFalse(work.ok)
        assertEquals("timeout", work.code)
        assertTrue("应快速返回，实际 ${elapsed}ms", elapsed < 5_000)
        assertTrue("message 应有预算提示", work.message.contains("解析超时"))
    }

    @Test
    fun `短链但预算不足时不联网返回 not_found`() {
        val startedAt = System.currentTimeMillis()
        val work = resolver.resolve("看这个 https://v.douyin.com/abc123/", budgetMs = 100)
        val elapsed = System.currentTimeMillis() - startedAt

        assertFalse(work.ok)
        assertEquals("not_found", work.code)
        assertTrue("应快速返回，实际 ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `任意输入都只返回契约允许的 code`() {
        val inputs = listOf(
            "",
            "   ",
            "hello",
            "https://v.douyin.com/abc/",
            "https://example.com/video/123456789",
            "https://www.douyin.com/video/1",
            "7689782250233187634",
        )
        for (input in inputs) {
            val work = resolver.resolve(input, budgetMs = 100)
            assertFalse("输入「$input」不应成功", work.ok)
            assertTrue("输入「$input」的 code 非法：${work.code}", work.code in allowedCodes)
        }
    }

    @Test
    fun `纯作品 ID 但预算不足时返回 timeout 而不是崩溃`() {
        val work = resolver.resolve("7689782250233187634", budgetMs = 100)
        assertFalse(work.ok)
        assertEquals("timeout", work.code)
    }
}