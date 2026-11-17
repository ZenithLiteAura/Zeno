package com.clipnest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 规则引擎：9 条内置规则、3 条抖音样例逐字段断言、噪声 0 命中、
 * 其它平台规则、generic-link 开关与兜底约束、previewAll 去重、超时保护。
 */
class RuleEngineTest {

    private val engine = RuleEngine()

    @Test
    fun `内置规则共 9 条且顺序固定`() {
        val ids = engine.rules().map { it.id }
        assertEquals(
            listOf(
                "douyin-share",
                "douyin-legacy",
                "douyin-plain",
                "xiaohongshu",
                "kuaishou",
                "bilibili",
                "weibo",
                "youtube",
                "generic-link",
            ),
            ids,
        )
    }

    @Test
    fun `generic-link 默认关闭、其它 8 条默认开启`() {
        val rules = engine.rules()
        assertEquals(9, rules.size)
        rules.forEach { rule ->
            if (rule.id == "generic-link") {
                assertFalse("generic-link 必须默认关闭", rule.enabled)
            } else {
                assertTrue("${rule.id} 必须默认开启", rule.enabled)
            }
        }
    }

    @Test
    fun `3 条抖音样例逐字段完全正确`() {
        assertEquals(3, TestData.SAMPLES.size)
        TestData.SAMPLES.forEachIndexed { index, line ->
            val expected = TestData.EXPECTED[index]
            val result = engine.tryCapture(line, "clipboard")

            assertTrue("样例#${index + 1} 必须命中", result.matched)
            val item = result.item
            assertNotNull("样例#${index + 1} item 不能为空", item)
            item!!
            val rule = result.rule

            assertEquals("douyin-share", rule?.id)
            assertEquals("douyin", item.platform)
            assertEquals("抖音", item.platformName)
            assertEquals("样例#${index + 1} rate", expected.rate, item.rate)
            assertEquals("样例#${index + 1} author", expected.author, item.author)
            assertEquals("样例#${index + 1} workType", expected.workType, item.workType)
            assertEquals("样例#${index + 1} title", expected.title, item.title)
            assertEquals("样例#${index + 1} url", expected.url, item.url)
            assertEquals("样例#${index + 1} extra", expected.extra, item.extra)
            assertEquals("样例#${index + 1} source", "clipboard", item.source)
            assertEquals("样例#${index + 1} raw 必须是原文", line, item.raw)
            assertTrue("样例#${index + 1} capturedAt 应接近当前时间", item.capturedAt > 0)
            assertTrue("样例#${index + 1} id 应为 32 位 hex", item.id.matches(Regex("[0-9a-f]{32}")))
            assertFalse("样例#${index + 1} 默认未加星", item.starred)
        }
    }

    @Test
    fun `抖音标题已剥掉链接与尾部口令噪声`() {
        TestData.SAMPLES.forEachIndexed { index, line ->
            val item = engine.tryCapture(line).item!!
            assertFalse("样例#${index + 1} 标题不能含 http", item.title.contains("http", ignoreCase = true))
            assertFalse("样例#${index + 1} 标题不能含 @ 口令", item.title.contains("h@O.kP"))
            assertFalse("样例#${index + 1} 标题不能含尾部日期噪声", item.title.contains(":4pm") || item.title.contains("6pm"))
            assertTrue("样例#${index + 1} 标题应保留正文", item.title.length >= 10)
        }
    }

    @Test
    fun `13 类噪声文本 0 命中`() {
        assertEquals(13, TestData.NOISE.size)
        TestData.NOISE.forEachIndexed { index, text ->
            val result = engine.tryCapture(text)
            assertFalse("噪声#${index + 1} 不应命中：$text", result.matched)
        }
    }

    @Test
    fun `嵌入样例与 I Download 类似 txt 逐字一致`() {
        // 本机存在原始样例文件时校验一次；其它机器/CI 没有该文件则跳过。
        val file = File("I:\\Download\\类似.txt")
        if (!file.isFile) return
        val lines = file.readLines(Charsets.UTF_8)
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
        assertEquals("样例文件应有 3 行有效文本", 3, lines.size)
        assertEquals(TestData.SAMPLE_1, lines[0])
        assertEquals(TestData.SAMPLE_2, lines[1])
        assertEquals(TestData.SAMPLE_3, lines[2])
    }

    @Test
    fun `空白与超短文本不命中`() {
        listOf("", "   ", "\r\n\t", "a", "看看").forEach { text ->
            val result = engine.tryCapture(text)
            assertFalse("「$text」不应命中", result.matched)
        }
    }

    @Test
    fun `抖音旧版口令与纯短链`() {
        val legacy = engine.tryCapture(
            "7.94 复制此链接，打开Dou音搜索，直接观看视频！ https://v.douyin.com/legacy01/",
        )
        assertTrue(legacy.matched)
        assertEquals("douyin-legacy", legacy.rule?.id)
        assertEquals("7.94", legacy.item?.rate)
        assertEquals("https://v.douyin.com/legacy01/", legacy.item?.url)

        val plain = engine.tryCapture("https://v.douyin.com/plain01/")
        assertTrue(plain.matched)
        assertEquals("douyin-plain", plain.rule?.id)
        assertEquals("https://v.douyin.com/plain01/", plain.item?.url)
        assertTrue("纯短链也要有兜底标题", plain.item!!.title.isNotEmpty())
    }

    @Test
    fun `其它 6 个平台规则都能命中`() {
        listOf(
            PlatformCase(
                "发现一篇好笔记！周末出片圣地 http://xhslink.com/a/AbCdEf，复制本条信息打开小红书",
                "xiaohongshu",
                "http://xhslink.com/a/AbCdEf",
            ),
            PlatformCase("快手这个视频真不错 https://v.kuaishou.com/AbCdEf 复制打开快手", "kuaishou", "https://v.kuaishou.com/AbCdEf"),
            PlatformCase("【测试标题】 https://b23.tv/AbCdEf 哔哩哔哩", "bilibili", "https://b23.tv/AbCdEf"),
            PlatformCase("转发微博 https://weibo.com/1234567890/AbCdEf 来自微博", "weibo", "https://weibo.com/1234567890/AbCdEf"),
            PlatformCase("New video https://youtu.be/AbCdEfGhIjK", "youtube", "https://youtu.be/AbCdEfGhIjK"),
            PlatformCase(
                "【测试标题】 https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333",
                "bilibili",
                "https://www.bilibili.com/video/BV1xx411c7mD",
            ),
        ).forEach { case ->
            val result = engine.tryCapture(case.text)
            assertTrue("应命中 ${case.ruleId}：${case.text}", result.matched)
            assertEquals(case.ruleId, result.rule?.id)
            assertEquals(case.url, result.item?.url)
            assertTrue("${case.ruleId} 标题非空", result.item!!.title.isNotEmpty())
        }
    }

    @Test
    fun `generic-link 停用时普通链接不收录，启用后收录`() {
        assertFalse("默认关闭时不能命中", engine.tryCapture("https://example.com/docs/start").matched)

        engine.setRuleEnabled("generic-link", true)
        val matched = engine.tryCapture("README 在这里 https://example.com/docs/start", "manual")
        assertTrue("启用后应命中", matched.matched)
        assertEquals("generic-link", matched.rule?.id)
        assertEquals("https://example.com/docs/start", matched.item?.url)
        assertEquals("manual", matched.item?.source)
        assertTrue("标题非空", matched.item!!.title.isNotEmpty())

        // 无链接 / 太短一律不命中
        assertFalse(engine.tryCapture("这是一段普通的中文文本，没有链接").matched)
        assertFalse(engine.tryCapture("abc").matched)
        assertFalse(engine.tryCapture("http://").matched)
    }

    @Test
    fun `抖音样例优先于 generic-link`() {
        engine.setRuleEnabled("generic-link", true)
        val result = engine.tryCapture(TestData.SAMPLE_1)
        assertEquals("douyin-share", result.rule?.id)
    }

    @Test
    fun `previewAll 只返回命中项并按规范化 url 去重`() {
        val preview = engine.previewAll(TestData.SAMPLE_1)
        assertEquals("同一条抖音链接只保留优先级最高的一条", 1, preview.size)
        assertEquals("douyin-share", preview[0].rule?.id)
        assertEquals(TestData.EXPECTED[0].url, preview[0].item?.url)

        val twoLinks = "抖音 https://v.douyin.com/two01/ 和 B 站 https://b23.tv/twoB"
        val preview2 = engine.previewAll(twoLinks)
        assertEquals(2, preview2.size)
        assertEquals("douyin-plain", preview2[0].rule?.id)
        assertEquals("bilibili", preview2[1].rule?.id)

        assertEquals(0, engine.previewAll("这里没有任何链接的普通文本").size)
        assertEquals(0, engine.previewAll("").size)
    }

    @Test
    fun `停用 douyin-share 后回退到 douyin-plain`() {
        engine.setRuleEnabled("douyin-share", false)
        val result = engine.tryCapture(TestData.SAMPLE_1)
        assertTrue("仍应命中（走兜底规则）", result.matched)
        assertTrue(result.rule?.id != "douyin-share")
        assertEquals("douyin-plain", result.rule?.id)
    }

    @Test
    fun `rules 返回快照，修改不影响已获取的列表`() {
        val before = engine.rules()
        engine.setRuleEnabled("douyin-share", false)
        assertTrue("旧快照仍为启用", before.first { it.id == "douyin-share" }.enabled)
        assertFalse("新列表已停用", engine.rules().first { it.id == "douyin-share" }.enabled)
    }

    @Test
    fun `未知规则 id 不抛异常`() {
        engine.setRuleEnabled("no-such-rule", false)
        assertEquals(9, engine.rules().size)
    }

    @Test
    fun `非法正则规则被跳过而不是崩溃`() {
        val broken = RuleEngine(
            listOf(
                CaptureRule(
                    id = "broken",
                    name = "坏正则",
                    platform = "generic",
                    platformName = "链接",
                    pattern = "([unclosed",
                ),
            ),
        )
        val result = broken.tryCapture("任何文本")
        assertFalse(result.matched)
    }

    @Test
    fun `超长无链接文本不会卡死`() {
        val text = "普通文本".repeat(20_000) // 8 万字符，无任何链接
        val startedAt = System.currentTimeMillis()
        val result = engine.tryCapture(text)
        val elapsed = System.currentTimeMillis() - startedAt
        assertFalse(result.matched)
        assertTrue("超长文本应在 5s 内返回，实际 ${elapsed}ms", elapsed < 5_000)
    }


    @Test
    fun `iesdouyin share 长参数链接也能识别`() {
        // 真机反馈的漏抓样例：短链换成 iesdouyin.com/share/video/<id>?大量参数
        val text = "5.66 复制打开抖音，看看【19ong^的作品】无不良影响！# jhh摇 # 无不良影响 # 萌妹... " +
            "https://www.iesdouyin.com/share/video/7696457187804429962/?region=CN&mid=7689782840435346186" +
            "&u_code=19g387a5i&did=MS4wLjABAAAAUtUbEKDwb-kHntZcJf0gmZExqTTwKrxBLdEaNVdxOvM" +
            "&iid=MS4wLjABAAAAAvRRhvYOihVAnqxl5h9HZ1qcGa_EP46l5DL2m2PqLJH2KB-FjiKUJpVfBzvFeZ0k" +
            "&with_sec_did=1&video_share_track_ver=&titleType=title&share_sign=9RUWr5OJ1qbZ_2HoE3U17EBiarJP8YU8BIZdl5H686M-" +
            "&share_version=400800&ts=1794889448&from_aid=1128&from_ssr=1" +
            "&utm_source=copy&utm_campaign=client_share&utm_medium=android&app=aweme :5pm 10/27 dAg:/ Z@Z.zt"
        val result = engine.tryCapture(text)
        assertTrue("该样例必须命中（真机漏抓过）", result.matched)
        assertEquals("douyin-share", result.rule?.id)
        assertEquals("19ong^", result.item?.author)
        assertTrue("url 应保留 iesdouyin 分享页", result.item?.url?.contains("iesdouyin.com/share/video/7696457187804429962") == true)
    }

    @Test
    fun `带平台特征词的长文本不会卡死`() {
        val text = "douyin.com " + "x".repeat(50_000)
        val startedAt = System.currentTimeMillis()
        val result = engine.tryCapture(text)
        val elapsed = System.currentTimeMillis() - startedAt
        assertFalse(result.matched)
        assertTrue("长文本应受 250ms 超时保护，实际 ${elapsed}ms", elapsed < 5_000)
    }
}

private data class PlatformCase(
    val text: String,
    val ruleId: String,
    val url: String,
)