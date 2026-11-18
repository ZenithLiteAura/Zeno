package com.clipnest.core.platform

import com.clipnest.core.douyin.DouyinWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 抖音适配器：platform 识别 + `DouyinWork → MediaWork` 映射（不联网）。 */
class DouyinPlatformResolverTest {

    private val sample =
        "4.89 复制打开抖音，看看【想成为摄影糕手的图文作品】下次还要来陪我呀。出镜：@祈枝枝o.o # 王者荣... " +
            "https://v.douyin.com/depmmgQEbkU/ h@O.kP 03/16 :4pm lcA:/"

    @Test
    fun `抖音链接识别`() {
        val resolver = DouyinPlatformResolver()
        assertTrue(resolver.supports(sample))
        assertTrue(resolver.supports("https://www.douyin.com/video/7689782250233187634"))
        assertTrue(resolver.supports("https://v.douyin.com/abc123/"))
        assertFalse(resolver.supports("https://www.bilibili.com/video/BV18msM6zEqc"))
        assertFalse(resolver.supports("https://xhslink.com/do9xGe"))
        assertFalse(resolver.supports(""))
    }

    @Test
    fun `DouyinWork 映射成 MediaWork`() {
        val source = DouyinWork(
            ok = true,
            awemeId = "7689782250233187634",
            type = "image",
            title = "暖隅。奶油暖光里的居家碎片。",
            authorName = "Carlos光影实录",
            pics = listOf("https://example.com/a.webp"),
            videoUrl = "",
            videoQuality = "",
            videoSize = 0,
            coverUrl = "https://example.com/a.webp",
            source = "iesdouyin",
        )
        val mapped = source.toMediaWork()
        assertTrue(mapped.ok)
        assertEquals("douyin", mapped.platform)
        assertEquals("抖音", mapped.platformName)
        assertEquals(source.awemeId, mapped.workId)
        assertEquals(source.type, mapped.type)
        assertEquals(source.title, mapped.title)
        assertEquals(source.authorName, mapped.author)
        assertEquals(source.pics, mapped.pics)
        assertEquals(source.coverUrl, mapped.coverUrl)
        assertEquals(source.source, mapped.source)
        assertEquals("", mapped.code)
    }

    @Test
    fun `失败结果 code 与 message 原样映射`() {
        val timeout = DouyinWork(ok = false, code = "timeout", message = "解析超时")
        val mapped = timeout.toMediaWork()
        assertFalse(mapped.ok)
        assertEquals("timeout", mapped.code)
        assertEquals("解析超时", mapped.message)
        assertEquals("douyin", mapped.platform)
    }

    @Test
    fun `多清晰度 qualities 映射进 MediaWork`() {
        val source = DouyinWork(
            ok = true,
            awemeId = "7000000000000000001",
            type = "video",
            title = "多清晰度测试",
            videoUrl = "https://example.com/1080.mp4",
            videoQuality = "1080p",
            videoSize = 5_000_000,
            qualities = listOf(
                MediaQuality("1080p", "https://example.com/1080.mp4", 5_000_000),
                MediaQuality("720p", "https://example.com/720.mp4", 2_000_000),
                MediaQuality("水印版", "https://example.com/playwm/x.mp4", 6_000_000, watermark = true),
            ),
            selectedQuality = "1080p",
            source = "iesdouyin",
        )
        val mapped = source.toMediaWork()
        assertEquals(source.qualities, mapped.qualities)
        assertEquals("1080p", mapped.selectedQuality)
        assertEquals("1080p", mapped.videoQuality)
        assertEquals("https://example.com/1080.mp4", mapped.videoUrl)
        assertTrue(mapped.qualities.last().watermark)
    }
}