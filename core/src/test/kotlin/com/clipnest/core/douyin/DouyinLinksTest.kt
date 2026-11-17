package com.clipnest.core.douyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接/文案/作品 ID 识别（对应 Python `resolve.py` 第二节）。
 */
class DouyinLinksTest {

    private val shareText =
        "4.89 复制打开抖音，看看【想成为摄影糕手的图文作品】下次还要来陪我呀。出镜：@祈枝枝o.o # 王者荣... " +
            "https://v.douyin.com/depmmgQEbkU/ h@O.kP 03/16 :4pm lcA:/ "

    @Test
    fun `从整段分享文案提取链接`() {
        assertEquals("https://v.douyin.com/depmmgQEbkU/", DouyinLinks.extractUrl(shareText))
        assertEquals("https://v.douyin.com/abc/", DouyinLinks.extractUrl("看这个 https://v.douyin.com/abc/，好看"))
        assertEquals("https://v.douyin.com/abc/", DouyinLinks.extractUrl("「https://v.douyin.com/abc/」"))
        assertEquals("https://v.douyin.com/abc/", DouyinLinks.extractUrl("\"https://v.douyin.com/abc/\""))
        assertNull(DouyinLinks.extractUrl("这里没有任何链接"))
        assertNull(DouyinLinks.extractUrl(""))
        assertNull(DouyinLinks.extractUrl("ftp://example.com/a"))
    }

    @Test
    fun `提取作品 ID`() {
        assertEquals(
            "7689782250233187634",
            DouyinLinks.extractAwemeId("https://www.douyin.com/video/7689782250233187634"),
        )
        assertEquals(
            "7689482856784049259",
            DouyinLinks.extractAwemeId("https://www.iesdouyin.com/share/note/7689482856784049259/"),
        )
        assertEquals(
            "7689782250233187634",
            DouyinLinks.extractAwemeId("https://www.iesdouyin.com/share/video/7689782250233187634/"),
        )
        assertEquals("123456789", DouyinLinks.extractAwemeId("https://www.douyin.com/?modal_id=123456789"))
        assertEquals("7689782250233187634", DouyinLinks.extractAwemeId("  7689782250233187634  "))

        assertNull("短链里没有 ID，必须先跟随 302", DouyinLinks.extractAwemeId("https://v.douyin.com/abc123/"))
        assertNull("太短的数字不算", DouyinLinks.extractAwemeId("12345"))
        assertNull(DouyinLinks.extractAwemeId(""))
    }

    @Test
    fun `短链判定`() {
        assertTrue(DouyinLinks.isShortLink("https://v.douyin.com/abc123/"))
        assertTrue(DouyinLinks.isShortLink("https://v.iesdouyin.com/abc123"))
        assertTrue(DouyinLinks.isShortLink("https://iesdouyin.com/share/video/123/"))
        assertFalse("www.douyin.com 不是短链", DouyinLinks.isShortLink("https://www.douyin.com/video/123"))
        assertFalse(DouyinLinks.isShortLink("not a valid url"))
        assertFalse(DouyinLinks.isShortLink(""))
    }

    @Test
    fun `类型提示`() {
        assertEquals("note", DouyinLinks.kindHint("https://www.douyin.com/note/123"))
        assertEquals("note", DouyinLinks.kindHint("https://www.iesdouyin.com/share/slides/123/"))
        assertEquals("note", DouyinLinks.kindHint("https://www.douyin.com/share/note/123/"))
        assertEquals("video", DouyinLinks.kindHint("https://www.douyin.com/video/123"))
        assertEquals("video", DouyinLinks.kindHint("https://www.iesdouyin.com/share/video/123/"))
        assertNull("短链看不出类型", DouyinLinks.kindHint("https://v.douyin.com/abc123/"))
        assertNull(DouyinLinks.kindHint(""))
    }
}