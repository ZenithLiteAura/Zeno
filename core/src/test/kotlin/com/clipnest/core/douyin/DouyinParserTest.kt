package com.clipnest.core.douyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合成 `_ROUTER_DATA` 夹具测试（不联网）：
 * 花括号配对扫描、video / note、缺 item_list 时递归兜底、空 url_list 等边界。
 */
class DouyinParserTest {

    private fun htmlWithRouter(routerJson: String): String =
        "<html><head><script>window._ROUTER_DATA = $routerJson;</script></head><body></body></html>"

    // ── 花括号配对扫描 ──────────────────────────────────────────

    @Test
    fun `花括号配对扫描能跳过字符串里的括号与转义`() {
        val json = """{"a":"x}y \"z\" {not-a-brace}","b":{"c":1},"d":[1,2,{"e":true}]}"""
        val html = "前缀 window._ROUTER_DATA = $json; 后缀 {其它}"
        assertEquals(json, DouyinParser.extractJsonObjectAfter(html, "window._ROUTER_DATA"))
    }

    @Test
    fun `找不到 marker 或括号不闭合时返回 null`() {
        assertNull(DouyinParser.extractJsonObjectAfter("没有任何标记", "window._ROUTER_DATA"))
        assertNull(DouyinParser.extractJsonObjectAfter("window._ROUTER_DATA = {\"a\":1", "window._ROUTER_DATA"))
        assertNull(DouyinParser.extractJsonObjectAfter("", "window._ROUTER_DATA"))
    }

    @Test
    fun `页面里没有 _ROUTER_DATA 或 JSON 非法时返回 null`() {
        assertNull(DouyinParser.parseRouter("<html><body>没有数据</body></html>"))
        assertNull(DouyinParser.parseRouter("<script>window._ROUTER_DATA = {oops}</script>"))
        assertNull(DouyinParser.parseRouter(""))
    }

    // ── 视频作品 ────────────────────────────────────────────────

    private val videoRouter = """
        {
          "loaderData": {
            "video_(id)/page": {
              "videoInfoRes": {
                "item_list": [
                  {
                    "aweme_id": "7689782250233187634",
                    "desc": "今天是社牛，过来让我骑一下看看能带飞吗~ #Dou来江山",
                    "author": {"nickname": "雪柔睡不醒", "sec_uid": "MS4w"},
                    "video": {
                      "play_addr": {
                        "uri": "v0300fg10000darpehvog65mmmtgf4m0",
                        "data_size": 5001477,
                        "url_list": ["http://example.com/playwm/play.mp4"]
                      },
                      "cover": {"url_list": ["https://example.com/cover.jpg"]}
                    }
                  }
                ]
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `视频作品归一化`() {
        val work = DouyinParser.parseRouter(htmlWithRouter(videoRouter))
        assertTrue("应解析成功", work != null)
        work!!
        assertTrue(work.ok)
        assertEquals("video", work.type)
        assertEquals("7689782250233187634", work.awemeId)
        assertEquals("雪柔睡不醒", work.authorName)
        assertEquals("今天是社牛，过来让我骑一下看看能带飞吗~ #Dou来江山", work.title)
        assertEquals(
            "https://aweme.snssdk.com/aweme/v1/play/?video_id=v0300fg10000darpehvog65mmmtgf4m0&ratio=1080p&line=0",
            work.videoUrl,
        )
        assertEquals("1080p", work.videoQuality)
        assertEquals("接口 data_size 作为探测前的兜底大小", 5001477L, work.videoSize)
        assertEquals("https://example.com/cover.jpg", work.coverUrl)
        assertTrue(work.pics.isEmpty())
        assertEquals("iesdouyin", work.source)
    }

    @Test
    fun `play_addr uri 是完整 URL 时直接使用不拼 video_id`() {
        val router = """
            {
              "loaderData": {
                "video_(id)/page": {
                  "videoInfoRes": {
                    "item_list": [
                      {
                        "aweme_id": "7689482856784049259",
                        "desc": "完整 URL 的 play_addr",
                        "author": {"nickname": "作者"},
                        "video": {
                          "play_addr": {
                            "uri": "https://sf6-cdn-tos.douyinstatic.com/obj/demo.mp4",
                            "data_size": 123456,
                            "url_list": ["https://example.com/playwm/demo.mp4"]
                          }
                        }
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val work = DouyinParser.parseRouter(htmlWithRouter(router))!!
        assertTrue(work.ok)
        assertEquals("video", work.type)
        assertEquals("https://sf6-cdn-tos.douyinstatic.com/obj/demo.mp4", work.videoUrl)
        assertEquals("非清晰度标签留空", "", work.videoQuality)
        assertEquals(123456L, work.videoSize)
    }

    // ── 图文作品 ────────────────────────────────────────────────

    private val noteRouter = """
        {
          "loaderData": {
            "note_(id)/page": {
              "videoInfoRes": {
                "item_list": [
                  {
                    "aweme_id": "7689482856784049259",
                    "desc": "暖隅。奶油暖光里的居家碎片。\n捕捉独处时  松弛鲜活的状态。📸",
                    "author": {"nickname": "Carlos光影实录", "sec_uid": "MS4w"},
                    "images": [
                      {
                        "uri": "img-a",
                        "url_list": ["https://example.com/a.webp"],
                        "download_url_list": ["https://example.com/a-watermark.webp"],
                        "width": 1440,
                        "height": 2158
                      },
                      {
                        "uri": "img-a",
                        "url_list": ["https://example.com/a-copy.webp"]
                      },
                      {
                        "uri": "img-c",
                        "urlList": ["http://example.com/c.jpg"]
                      },
                      {
                        "uri": "img-d",
                        "download_url_list": ["https://example.com/d-watermark.webp"]
                      }
                    ],
                    "video": {}
                  }
                ]
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `图文作品只取 url_list 且按 uri 去重`() {
        val work = DouyinParser.parseRouter(htmlWithRouter(noteRouter))
        assertTrue(work != null)
        work!!
        assertTrue(work.ok)
        assertEquals("image", work.type)
        assertEquals("7689482856784049259", work.awemeId)
        assertEquals("Carlos光影实录", work.authorName)
        assertEquals("标题里的换行/多空格应压成单空格", "暖隅。奶油暖光里的居家碎片。 捕捉独处时 松弛鲜活的状态。📸", work.title)
        // img-a 的重复项去重；只有 download_url_list 的 img-d 不收录；http 自动升级 https
        assertEquals(listOf("https://example.com/a.webp", "https://example.com/c.jpg"), work.pics)
        assertEquals("https://example.com/a.webp", work.coverUrl)
        assertTrue("不能出现水印图", work.pics.none { it.contains("watermark") })
        assertEquals("", work.videoUrl)
        assertEquals("", work.videoQuality)
        assertEquals("iesdouyin", work.source)
    }

    @Test
    fun `缺 item_list 时递归兜底找作品对象`() {
        val router = """
            {
              "loaderData": {
                "note_(id)/page": {
                  "someWrapper": {
                    "nested": {
                      "aweme_id": "7689482856784049259",
                      "desc": "嵌套结构兜底",
                      "author": {"nickname": "嵌套作者"},
                      "images": [{"uri": "x", "url_list": ["https://example.com/x.webp"]}]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val work = DouyinParser.parseRouter(htmlWithRouter(router))
        assertTrue("应通过递归找到作品", work != null)
        assertEquals("7689482856784049259", work!!.awemeId)
        assertEquals("嵌套作者", work.authorName)
        assertEquals(listOf("https://example.com/x.webp"), work.pics)
    }

    @Test
    fun `item_list 为空数组时返回 null（页面只有布局配置）`() {
        val router = """{"loaderData": {"video_(id)/page": {"videoInfoRes": {"item_list": []}}}}"""
        assertNull(DouyinParser.parseRouter(htmlWithRouter(router)))
    }

    @Test
    fun `空 url_list 且没有视频时返回 no_media`() {
        val router = """
            {
              "loaderData": {
                "note_(id)/page": {
                  "videoInfoRes": {
                    "item_list": [
                      {
                        "aweme_id": "111111111111111111",
                        "desc": "空图作品",
                        "author": {"nickname": "作者"},
                        "images": [{"uri": "img-empty", "url_list": []}]
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val work = DouyinParser.parseRouter(htmlWithRouter(router))
        assertTrue(work != null)
        work!!
        assertFalse(work.ok)
        assertEquals("no_media", work.code)
        assertTrue(work.message.isNotEmpty())
        assertTrue(work.pics.isEmpty())
    }

    // ── SEO ld+json 兜底 ─────────────────────────────────────────

    @Test
    fun `SEO ld+json 取 article 的 image 列表`() {
        val html = """
            <html><head>
            <script type="application/ld+json">
              {"@type":"article","headline":"暖隅。奶油暖光","image":["https://example.com/a.webp",{"url_list":["http://example.com/b.jpg"]}]}
            </script>
            </head></html>
        """.trimIndent()

        val work = DouyinParser.parseLdJson(html)
        assertTrue(work != null)
        work!!
        assertTrue(work.ok)
        assertEquals("image", work.type)
        assertEquals("暖隅。奶油暖光", work.title)
        assertEquals(listOf("https://example.com/a.webp", "https://example.com/b.jpg"), work.pics)
        assertEquals("https://example.com/a.webp", work.coverUrl)
        assertEquals("seo", work.source)
    }

    @Test
    fun `SEO 页只有 VideoObject 或空 image 时不命中`() {
        val onlyVideo = """<script type="application/ld+json">{"@type":"VideoObject","name":"视频"}</script>"""
        assertNull(DouyinParser.parseLdJson(onlyVideo))
        val emptyImages = """<script type="application/ld+json">{"@type":"article","image":[]}</script>"""
        assertNull(DouyinParser.parseLdJson(emptyImages))
        assertNull(DouyinParser.parseLdJson("没有 ld+json"))
    }
}