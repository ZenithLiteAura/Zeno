package com.clipnest.core

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出器：md / txt / csv / json。CSV 必须正确转义逗号、引号、换行。
 */
class ExporterTest {

    private val tricky = ClipItem(
        id = "e1",
        platform = "douyin",
        platformName = "抖音",
        ruleId = "douyin-share",
        author = "测试作者",
        workType = "图文作品",
        title = "标题,含\"引号\"\n第二行",
        url = "https://v.douyin.com/test01/",
        rate = "4.89",
        raw = "原文",
        capturedAt = 1_700_000_000_000L,
        starred = true,
    )

    private val second = ClipItem(
        id = "e2",
        platform = "bilibili",
        platformName = "哔哩哔哩",
        author = "搬运工",
        title = "B 站视频",
        url = "https://b23.tv/xyz",
        capturedAt = 1_700_003_600_000L,
    )

    private val items = listOf(tricky, second)

    @Test
    fun `Markdown 每条一个二级标题并含标题链接时间`() {
        val md = Exporter.toMarkdown(items)
        assertTrue(md.isNotEmpty())
        assertTrue(md.contains("# ClipNest 剪巢 · 收藏导出"))
        assertTrue("作者 · 平台", md.contains("## 测试作者 · 抖音"))
        assertTrue(md.contains("## 搬运工 · 哔哩哔哩"))
        assertTrue("含标题原文", md.contains("标题,含\"引号\""))
        assertTrue("含链接", md.contains("https://v.douyin.com/test01/"))
        assertTrue("含收录时间", md.contains("- 收录："))
        assertTrue("星标标注", md.contains("- 星标：是"))
        assertTrue("作者为空的兜底不崩", Exporter.toMarkdown(listOf(second.copy(author = ""))).isNotEmpty())
    }

    @Test
    fun `纯文本有编号、链接与平台`() {
        val txt = Exporter.toText(items)
        assertTrue(txt.isNotEmpty())
        assertTrue(txt.contains("[1]"))
        assertTrue(txt.contains("[2]"))
        assertTrue(txt.contains("https://b23.tv/xyz"))
        assertTrue(txt.contains("搬运工 · 哔哩哔哩"))
        assertTrue("星标用 ★ 标注", txt.contains("★"))
    }

    @Test
    fun `CSV 表头、行尾与特殊字符转义`() {
        val csv = Exporter.toCsv(items)
        val lines = csv.split("\r\n")
        assertEquals("作者,平台,类型,标题,链接,评分,收录时间,星标", lines[0])
        assertTrue("CSV 用 CRLF 行尾", csv.contains("\r\n"))
        assertTrue("逗号/引号/换行应加引号并翻倍引号", csv.contains("\"标题,含\"\"引号\"\"\n第二行\""))
        assertTrue("含链接", csv.contains("https://v.douyin.com/test01/"))
        assertTrue("星标 1/空", csv.contains(",1\r\n") || csv.endsWith(",1\r\n"))
        assertFalse("普通字段不应被引号包裹", csv.contains("\"搬运工\""))
        assertEquals("两行数据 + 表头 + 末尾空串", 4, lines.size)
    }

    @Test
    fun `JSON 是 camelCase 数组且可解析`() {
        val json = Exporter.toJson(items)
        val root = JsonParser.parseString(json)
        assertTrue(root.isJsonArray)
        val array = root.asJsonArray
        assertEquals(2, array.size())
        val first = array[0].asJsonObject
        assertEquals("e1", first.get("id").asString)
        assertEquals("https://v.douyin.com/test01/", first.get("url").asString)
        assertTrue(first.get("starred").asBoolean)
        assertEquals("抖音", first.get("platformName").asString)
        // 空的可空字段不输出（Gson 默认跳过 null）
        assertFalse(first.has("coverUrl"))
        assertFalse(first.has("meta"))
    }

    @Test
    fun `空列表导出仍是合法内容`() {
        assertTrue(Exporter.toMarkdown(emptyList()).isNotEmpty())
        assertTrue(Exporter.toText(emptyList()).isNotEmpty())
        assertTrue("CSV 至少保留表头", Exporter.toCsv(emptyList()).startsWith("作者,平台"))
        assertEquals("[]", Exporter.toJson(emptyList()).trim())
    }

    @Test
    fun `标题为空时用链接兜底`() {
        val item = second.copy(title = "")
        assertTrue(Exporter.toMarkdown(listOf(item)).contains("https://b23.tv/xyz"))
        assertTrue(Exporter.toText(listOf(item)).contains("https://b23.tv/xyz"))
    }

    @Test
    fun `收藏时间格式为 yyyy-MM-dd HH_mm`() {
        val md = Exporter.toMarkdown(listOf(second))
        val containsTime = Regex("""- 收录：\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").containsMatchIn(md)
        assertTrue("时间格式不对：$md", containsTime)
    }
}