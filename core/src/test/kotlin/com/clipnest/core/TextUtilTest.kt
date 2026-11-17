package com.clipnest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本与链接规范化：不可见字符、换行/全角空格、跟踪参数、尾部标点、大小写、fragment。
 */
class TextUtilTest {

    @Test
    fun `normalize 去掉零宽字符并统一换行`() {
        assertEquals("ab c\nd", TextUtil.normalize("  a\u200Bb\u3000c\r\nd  "))
        assertEquals("ab", TextUtil.normalize("a\u200Bb\u200D\u2060"))
        assertEquals("abc", TextUtil.normalize("\uFEFFabc"))
        assertEquals("ab", TextUtil.normalize("a\u00ADb"))
    }

    @Test
    fun `normalize 处理空值、空白、全角空格与控制字符`() {
        assertEquals("", TextUtil.normalize(null))
        assertEquals("", TextUtil.normalize(""))
        assertEquals("", TextUtil.normalize("   \r\n\t  "))
        assertEquals("a b", TextUtil.normalize("a\u00A0b"))
        assertEquals("a b", TextUtil.normalize("a\u3000b"))
        assertEquals("ab", TextUtil.normalize("a\u0001b"))
        assertEquals("a\nb", TextUtil.normalize("a\r\nb"))
        assertEquals("a\nb", TextUtil.normalize("a\rb"))
    }

    @Test
    fun `normalizeUrl 去掉跟踪参数与包裹标点`() {
        assertEquals(
            "https://v.douyin.com/depmmgQEbkU/",
            TextUtil.normalizeUrl(" https://v.douyin.com/depmmgQEbkU/?utm_source=abc&spm=xyz "),
        )
        assertEquals(
            "https://example.com/a?keep=1",
            TextUtil.normalizeUrl("https://example.com/a?share_source=copy&vd_source=xx&keep=1"),
        )
        assertEquals("https://v.douyin.com/abc/", TextUtil.normalizeUrl("https://v.douyin.com/abc/，"))
        assertEquals("https://v.douyin.com/abc/", TextUtil.normalizeUrl("<https://v.douyin.com/abc/>"))
        assertEquals("https://example.com/a", TextUtil.normalizeUrl("“https://example.com/a”"))
    }

    @Test
    fun `normalizeUrl host 小写、路径大小写保留、去 fragment`() {
        assertEquals("https://example.com/Path/X", TextUtil.normalizeUrl("https://Example.COM/Path/X"))
        assertEquals("https://example.com/docs", TextUtil.normalizeUrl("https://example.com/docs#section"))
        assertEquals("https://example.com/x", TextUtil.normalizeUrl("https://example.com:443/x"))
        assertEquals("http://example.com/x", TextUtil.normalizeUrl("http://example.com:80/x"))
    }

    @Test
    fun `normalizeUrl 保留有意义的 query`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc123&t=10",
            TextUtil.normalizeUrl("https://www.youtube.com/watch?v=abc123&utm_medium=share&t=10"),
        )
        assertEquals(
            "https://www.bilibili.com/video/BV1xx?p=2",
            TextUtil.normalizeUrl("https://www.bilibili.com/video/BV1xx?p=2&spm_id_from=333"),
        )
    }

    @Test
    fun `normalizeUrl 非 http 与空值`() {
        assertEquals("", TextUtil.normalizeUrl(null))
        assertEquals("", TextUtil.normalizeUrl(""))
        assertEquals("v.douyin.com/abc", TextUtil.normalizeUrl("v.douyin.com/abc"))
        assertEquals("随便一段文字", TextUtil.normalizeUrl("随便一段文字"))
    }

    @Test
    fun `compact 压空白并截断加省略号`() {
        assertEquals("a b c", TextUtil.compact("  a   b\nc  ", 100))
        assertEquals("abc", TextUtil.compact("abc", 100))
        assertEquals("", TextUtil.compact(null))
        assertEquals("", TextUtil.compact("   ", 100))

        val truncated = TextUtil.compact("a".repeat(300), 240)
        assertEquals(240, truncated.length)
        assertTrue("截断应加省略号", truncated.endsWith("…"))
    }

    @Test
    fun `compact 超长截断不会切开 emoji 代理对`() {
        // 200 个 📸 = 200 个 code point / 400 个 UTF-16 char
        val emojiTitle = "📸".repeat(200)
        val truncated = TextUtil.compact(emojiTitle, 240)

        assertTrue("长度不能超过上限，实际 ${truncated.length}", truncated.length <= 240)
        assertTrue("截断应加省略号", truncated.endsWith("…"))
        assertFalse(
            "末位不能是半个代理对",
            Character.isHighSurrogate(truncated[truncated.length - 1]),
        )
        assertNoUnpairedSurrogate(truncated)
        // 119 个完整 emoji + 省略号
        assertEquals(120, truncated.codePointCount(0, truncated.length))
    }

    @Test
    fun `compact 小上限下也不会切坏 emoji`() {
        val truncated = TextUtil.compact("📸".repeat(10), 10)
        assertTrue(truncated.length <= 10)
        assertTrue(truncated.endsWith("…"))
        assertFalse(Character.isHighSurrogate(truncated[truncated.length - 1]))
        assertNoUnpairedSurrogate(truncated)

        // 单个 emoji 没超限就不动
        assertEquals("📸", TextUtil.compact("📸", 240))
    }

    @Test
    fun `compact 中英混排截断也保持完整字符`() {
        val mixed = "抖音标题📸".repeat(100)
        val truncated = TextUtil.compact(mixed, 150)
        assertTrue(truncated.length <= 150)
        assertTrue(truncated.endsWith("…"))
        assertNoUnpairedSurrogate(truncated)
    }

    /** 检查字符串里没有孤立代理项（codePointAt 返回代理区值就说明是半个字符）。 */
    private fun assertNoUnpairedSurrogate(text: String) {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            assertFalse(
                "位置 $index 出现孤立代理项（半个 emoji）",
                codePoint in 0xD800..0xDFFF,
            )
            index += Character.charCount(codePoint)
        }
    }
}