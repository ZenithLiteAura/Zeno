package com.clipnest.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命名规则 1–4、标题清洗（按 code point 截断）、同名去重（对应线上 §4.5 / §4.6）。
 */
class DownloadNamingTest {

    // ── 清洗 ────────────────────────────────────────────────────

    @Test
    fun `标题清洗替换非法字符并压缩空白`() {
        assertEquals("旅行日记_京都_秋季", DownloadNaming.sanitizeTitle("旅行日记:京都/秋季"))
        assertEquals("a_b_c_d_e_f_g_h_i", DownloadNaming.sanitizeTitle("a\\b:c*d?e\"f<g>h|i"))
        assertEquals("全角：保留", DownloadNaming.sanitizeTitle("全角：保留"))
        assertEquals("a b c", DownloadNaming.sanitizeTitle("  a \n b\t c  "))
        assertEquals("___", DownloadNaming.sanitizeTitle("///"))
        assertEquals("", DownloadNaming.sanitizeTitle(""))
        assertEquals("", DownloadNaming.sanitizeTitle("   "))
    }

    @Test
    fun `标题去掉首尾点防止路径穿越`() {
        assertEquals("旅行", DownloadNaming.sanitizeTitle("..旅行.."))
        assertEquals("旅行", DownloadNaming.sanitizeTitle("旅行 . . "))
        assertEquals("", DownloadNaming.sanitizeTitle(".."))
    }

    @Test
    fun `超长标题截断到 150`() {
        val title = DownloadNaming.sanitizeTitle("a".repeat(200))
        assertEquals(150, title.length)
    }

    @Test
    fun `超长标题按 code point 截断不会切断 emoji`() {
        // 每个 📸 是 1 个 code point / 2 个 UTF-16 char
        val emojiTitle = DownloadNaming.sanitizeTitle("📸".repeat(200))
        assertEquals("按 code point 截断到 150", 150, emojiTitle.codePointCount(0, emojiTitle.length))
        assertEquals("150 个 emoji 占 300 个 char", 300, emojiTitle.length)
        assertFalse(
            "末位不能是半个代理对",
            Character.isHighSurrogate(emojiTitle[emojiTitle.length - 1]),
        )

        val mixed = DownloadNaming.sanitizeTitle("a📸b".repeat(100))
        assertEquals(150, mixed.codePointCount(0, mixed.length))
        assertFalse(Character.isHighSurrogate(mixed[mixed.length - 1]))
    }

    // ── 命名规则 ────────────────────────────────────────────────

    @Test
    fun `规则 1 序号_标题`() {
        assertEquals(
            "01_旅行日记_京都_秋季.jpg",
            DownloadNaming.fileName(ZipNamingRule.SEQ_TITLE, 1, "旅行日记:京都/秋季", "", "jpg"),
        )
        assertEquals(
            "10_旅行日记.jpg",
            DownloadNaming.fileName(ZipNamingRule.SEQ_TITLE, 10, "旅行日记", "", "jpg"),
        )
        assertEquals(
            "100_旅行日记.jpg",
            DownloadNaming.fileName(ZipNamingRule.SEQ_TITLE, 100, "旅行日记", "", "jpg"),
        )
        assertEquals(
            "01_旅行日记.jpg",
            DownloadNaming.fileName(ZipNamingRule.SEQ_TITLE, 0, "旅行日记", "", "jpg"),
        )
    }

    @Test
    fun `规则 2 标题_分辨率（图片固定 HD）`() {
        assertEquals(
            "旅行日记_HD.jpg",
            DownloadNaming.fileName(ZipNamingRule.TITLE_RES, 1, "旅行日记", "HD", "jpg"),
        )
        assertEquals(
            "res 为空也回落 HD",
            "旅行日记_HD.jpg",
            DownloadNaming.fileName(ZipNamingRule.TITLE_RES, 1, "旅行日记", "", "jpg"),
        )
        assertEquals(
            "旅行日记_1080p.mp4",
            DownloadNaming.fileName(ZipNamingRule.TITLE_RES, 1, "旅行日记", "1080p", "mp4"),
        )
    }

    @Test
    fun `规则 3 仅标题 与 规则 4 仅序号`() {
        assertEquals(
            "旅行日记.jpg",
            DownloadNaming.fileName(ZipNamingRule.TITLE_ONLY, 1, "旅行日记", "HD", "jpg"),
        )
        assertEquals(
            "02.jpg",
            DownloadNaming.fileName(ZipNamingRule.SEQ_ONLY, 2, "旅行日记", "HD", "jpg"),
        )
    }

    @Test
    fun `扩展名归一与标题兜底`() {
        assertEquals(
            "01_旅行日记.jpg",
            DownloadNaming.fileName(ZipNamingRule.SEQ_TITLE, 1, "旅行日记", "HD", ".JPG"),
        )
        assertEquals(
            "01_旅行日记",
            DownloadNaming.fileName(ZipNamingRule.SEQ_TITLE, 1, "旅行日记", "HD", ""),
        )
        assertEquals(
            "标题为空用 file 兜底",
            "01_file.jpg",
            DownloadNaming.fileName(ZipNamingRule.SEQ_TITLE, 1, "   ", "HD", "jpg"),
        )
    }

    // ── 去重 ────────────────────────────────────────────────────

    @Test
    fun `同名去重从 _1 开始且插在扩展名前`() {
        val used = mutableSetOf<String>()

        assertEquals("旅行日记.jpg", DownloadNaming.dedupe("旅行日记.jpg", used))
        assertEquals("旅行日记_1.jpg", DownloadNaming.dedupe("旅行日记.jpg", used))
        assertEquals("旅行日记_2.jpg", DownloadNaming.dedupe("旅行日记.jpg", used))
        assertEquals("旅行日记_3.jpg", DownloadNaming.dedupe("旅行日记.jpg", used))
    }

    @Test
    fun `去重对无扩展名与 dotfile 也安全`() {
        val used = mutableSetOf<String>()
        assertEquals("noext", DownloadNaming.dedupe("noext", used))
        assertEquals("noext_1", DownloadNaming.dedupe("noext", used))

        val dotUsed = mutableSetOf(".hidden")
        assertEquals(".hidden_1", DownloadNaming.dedupe(".hidden", dotUsed))
    }

    @Test
    fun `去重集合由调用方共享`() {
        val used = mutableSetOf<String>()
        val names = listOf("旅行日记.jpg", "旅行日记.jpg", "旅行日记.jpg").map {
            DownloadNaming.dedupe(it, used)
        }
        assertEquals(listOf("旅行日记.jpg", "旅行日记_1.jpg", "旅行日记_2.jpg"), names)
        assertTrue(used.size == 3)
    }

    @Test
    fun `ZipNamingRule 与 1 到 4 映射`() {
        assertEquals(1, ZipNamingRule.SEQ_TITLE.number)
        assertEquals(2, ZipNamingRule.TITLE_RES.number)
        assertEquals(3, ZipNamingRule.TITLE_ONLY.number)
        assertEquals(4, ZipNamingRule.SEQ_ONLY.number)
        assertEquals(ZipNamingRule.SEQ_TITLE, ZipNamingRule.of(1))
        assertEquals(ZipNamingRule.TITLE_RES, ZipNamingRule.of(2))
        assertEquals(ZipNamingRule.TITLE_ONLY, ZipNamingRule.of(3))
        assertEquals(ZipNamingRule.SEQ_ONLY, ZipNamingRule.of(4))
        assertEquals("非法值回落规则 1", ZipNamingRule.SEQ_TITLE, ZipNamingRule.of(0))
        assertEquals(ZipNamingRule.SEQ_TITLE, ZipNamingRule.of(9))
    }
}