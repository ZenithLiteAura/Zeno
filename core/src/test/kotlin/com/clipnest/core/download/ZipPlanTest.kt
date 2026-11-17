package com.clipnest.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZIP 条目规划：ZIP 文件名、目录名、条目路径 + 4 条命名规则 + 去重。
 */
class ZipPlanTest {

    private val pics = listOf(
        "https://example.com/1.webp",
        "https://example.com/2.webp",
        "https://example.com/3.webp",
    )

    @Test
    fun `ZIP 文件名`() {
        assertEquals("image_batch_旅行日记_京都_秋季.zip", ZipPlan.zipFileName("image", "旅行日记:京都/秋季"))
        assertEquals("video_batch_雪柔睡不醒.zip", ZipPlan.zipFileName("video", "雪柔睡不醒"))
        assertEquals("标题为空用目录名兜底", "image_batch_所有图片.zip", ZipPlan.zipFileName("image", ""))
        assertEquals("video_batch_所有视频.zip", ZipPlan.zipFileName("video", "   "))
        assertEquals("非法类型按 image 处理", "image_batch_x.zip", ZipPlan.zipFileName("photo", "x"))
    }

    @Test
    fun `ZIP 内目录名`() {
        assertEquals("所有图片", ZipPlan.folderName("image"))
        assertEquals("所有视频", ZipPlan.folderName("video"))
        assertEquals("所有图片", ZipPlan.folderName(""))
        assertEquals("所有视频", ZipPlan.folderName("VIDEO"))
    }

    @Test
    fun `规则 1 条目规划`() {
        val entries = ZipPlan.entries(pics, "", "旅行日记", ZipNamingRule.SEQ_TITLE)
        assertEquals(3, entries.size)
        assertEquals(
            listOf(
                "所有图片/01_旅行日记.jpg",
                "所有图片/02_旅行日记.jpg",
                "所有图片/03_旅行日记.jpg",
            ),
            entries.map { it.first },
        )
        assertEquals(pics, entries.map { it.second })
        assertTrue("Pair.first 是 ZIP 条目路径而不是 URL", entries.all { !it.first.startsWith("http") })
        assertTrue("Pair.second 是源 URL", entries.all { it.second.startsWith("https://") })
    }

    @Test
    fun `规则 2 和 3 在同作品多图时按 _1 _2 去重`() {
        val rule2 = ZipPlan.entries(pics, "", "旅行日记", ZipNamingRule.TITLE_RES)
        assertEquals(
            listOf(
                "所有图片/旅行日记_HD.jpg",
                "所有图片/旅行日记_HD_1.jpg",
                "所有图片/旅行日记_HD_2.jpg",
            ),
            rule2.map { it.first },
        )

        val rule3 = ZipPlan.entries(pics, "", "旅行日记", ZipNamingRule.TITLE_ONLY)
        assertEquals(
            listOf(
                "所有图片/旅行日记.jpg",
                "所有图片/旅行日记_1.jpg",
                "所有图片/旅行日记_2.jpg",
            ),
            rule3.map { it.first },
        )
    }

    @Test
    fun `规则 4 仅序号`() {
        val entries = ZipPlan.entries(pics, "", "旅行日记", ZipNamingRule.SEQ_ONLY)
        assertEquals(
            listOf("所有图片/01.jpg", "所有图片/02.jpg", "所有图片/03.jpg"),
            entries.map { it.first },
        )
    }

    @Test
    fun `视频条目规划`() {
        val entries = ZipPlan.entries(
            emptyList(),
            "https://aweme.snssdk.com/aweme/v1/play/?video_id=abc&ratio=1080p&line=0",
            "旅行日记",
            ZipNamingRule.SEQ_TITLE,
        )
        assertEquals(1, entries.size)
        assertEquals("所有视频/01_旅行日记.mp4", entries[0].first)
        assertTrue(entries[0].second.startsWith("https://aweme.snssdk.com/"))

        val rule3 = ZipPlan.entries(emptyList(), "https://example.com/v.mp4", "旅行日记", ZipNamingRule.TITLE_ONLY)
        assertEquals("所有视频/旅行日记.mp4", rule3[0].first)
    }

    @Test
    fun `图片优先且空 URL 被跳过`() {
        val withVideo = ZipPlan.entries(pics, "https://example.com/v.mp4", "标题", ZipNamingRule.SEQ_TITLE)
        assertEquals("有图片时忽略视频", 3, withVideo.size)
        assertTrue(withVideo.all { it.first.startsWith("所有图片/") })

        val withBlank = ZipPlan.entries(
            listOf("", "  ", "https://example.com/a.webp"),
            "",
            "标题",
            ZipNamingRule.SEQ_TITLE,
        )
        assertEquals(1, withBlank.size)
        assertEquals("所有图片/01_标题.jpg", withBlank[0].first)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(ZipPlan.entries(emptyList(), "", "标题", ZipNamingRule.SEQ_TITLE).isEmpty())
        assertTrue(ZipPlan.entries(emptyList(), "   ", "标题", ZipNamingRule.SEQ_TITLE).isEmpty())
    }

    @Test
    fun `标题非法字符与超长标题在条目里被清洗`() {
        val entries = ZipPlan.entries(pics.take(1), "", "a/b:c*?", ZipNamingRule.TITLE_ONLY)
        assertEquals("所有图片/a_b_c__.jpg", entries[0].first)
    }
}