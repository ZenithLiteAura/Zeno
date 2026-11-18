package com.clipnest.core.platform

import com.clipnest.core.douyin.DouyinHttp
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

/**
 * B 站解析（已用真机 curl 验证：b23.tv 302 + view/playurl 公开接口）。
 *
 * 链路：
 * 1. 从分享文案/链接里取 BV 号（或 av 号）；b23.tv 短链先跟随 302；
 * 2. `GET https://api.bilibili.com/x/web-interface/view?bvid=...` → 标题/作者/封面/cid；
 * 3. `GET https://api.bilibili.com/x/player/playurl?bvid=&cid=&qn=64&fnval=1` → `data.durl[0]`
 *    （真机上带桌面 UA + Referer 即可拿到 720p mp4；失败再换移动 UA + `platform=html5&high_quality=1`）。
 */
class BilibiliResolver : PlatformResolver {

    override val id: String = "bilibili"

    override fun supports(text: String): Boolean {
        if (text.isBlank()) return false
        val url = PlatformLinks.extractUrl(text)
        if (url != null && PlatformLinks.matchesHost(url, "b23.tv", "bilibili.com")) return true
        return BilibiliParser.BVID.containsMatchIn(text) || BilibiliParser.AVID.containsMatchIn(text)
    }

    override fun resolve(text: String, budgetMs: Long): MediaWork {
        val deadline = ResolveDeadline(budgetMs)
        val http = DouyinHttp()
        return try {
            var url = PlatformLinks.extractUrl(text)
            var bvid = BilibiliParser.extractBvid(text)
            var aid = if (bvid == null) BilibiliParser.extractAid(text) else null

            // 1) 短链跟随拿 BV 号
            if (bvid == null && aid == null && url != null && PlatformLinks.matchesHost(url, "b23.tv")) {
                if (deadline.expired()) return timeout()
                val response = try {
                    http.get(url, desktopHeaders(), deadline.requestTimeoutMs())
                } catch (error: IOException) {
                    return if (deadline.expired()) timeout() else network("b23.tv 跳转失败：${error.javaClass.simpleName}")
                }
                url = response.finalUrl
                bvid = BilibiliParser.extractBvid(url)
                if (bvid == null) aid = BilibiliParser.extractAid(url)
            }

            if (bvid == null && aid == null) {
                return notFound("无法从输入中识别 B 站视频号（BV 号或 av 号）：请确认是视频分享链接/文案")
            }
            // 预算不足 2s 就直接超时，不再发起网络请求
            if (deadline.remainingMs() < 2_000L) return timeout()

            // 2) 视频信息
            val viewUrl = if (bvid != null) {
                "$BILI_API/web-interface/view?bvid=$bvid"
            } else {
                "$BILI_API/web-interface/view?aid=$aid"
            }
            val viewResponse = try {
                http.get(viewUrl, desktopHeaders(), deadline.requestTimeoutMs())
            } catch (error: IOException) {
                return if (deadline.expired()) timeout() else network("B 站接口请求失败：${error.javaClass.simpleName}")
            }
            if (viewResponse.statusCode != 200) {
                return parseFailed("B 站接口返回 HTTP ${viewResponse.statusCode}（可能触发风控，请稍后重试）")
            }
            val view = BilibiliParser.parseView(viewResponse.body)
                ?: return parseFailed("B 站接口未返回视频信息（视频可能已删除/地区限制/接口风控）")
            val cid = view.cid
            if (cid <= 0L) return noMedia("B 站接口未返回 cid，无法获取视频地址")

            // 3) 播放地址：最多请求 3 档（120/80/64），失败的跳过；合并去重后从高到低
            if (deadline.remainingMs() < 2_000L) return timeout()
            val plays = ArrayList<BilibiliPlayInfo>()
            for (qn in QUALITY_REQUESTS) {
                if (deadline.remainingMs() < 2_000L) break
                requestPlayUrl(http, view.bvid, cid, desktopHeaders(), "", qn, deadline)?.let { plays.add(it) }
            }
            var qualities = BilibiliParser.mergeQualities(plays)
            if (qualities.isEmpty() && deadline.remainingMs() >= 2_000L) {
                // 三档全失败时补一次移动端 html5 兜底（仅当还有预算）
                requestPlayUrl(
                    http,
                    view.bvid,
                    cid,
                    mobileHeaders(),
                    "&platform=html5&high_quality=1",
                    64,
                    deadline,
                )?.let { plays.add(it) }
                qualities = BilibiliParser.mergeQualities(plays)
            }
            if (qualities.isEmpty()) {
                return noMedia(
                    "B 站视频地址需要登录/大会员或接口受限，请在浏览器打开 " +
                        "https://www.bilibili.com/video/${view.bvid}",
                )
            }
            val selected = qualities.first()

            MediaWork(
                ok = true,
                platform = "bilibili",
                platformName = "哔哩哔哩",
                workId = view.bvid,
                type = "video",
                title = view.title,
                author = view.author,
                videoUrl = selected.url,
                videoQuality = selected.label,
                videoSize = selected.size,
                qualities = qualities,
                selectedQuality = selected.label,
                coverUrl = PlatformLinks.toHttps(view.pic),
                source = "bilibili-api",
            )
        } catch (error: Exception) {
            if (deadline.expired()) timeout()
            else parseFailed("B 站解析出现异常：${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun requestPlayUrl(
        http: DouyinHttp,
        bvid: String,
        cid: Long,
        headers: Map<String, String>,
        extraQuery: String,
        qn: Int,
        deadline: ResolveDeadline,
    ): BilibiliPlayInfo? {
        if (deadline.expired()) return null
        return try {
            val url = "$BILI_API/player/playurl?bvid=$bvid&cid=$cid&qn=$qn&fnval=1$extraQuery"
            val response = http.get(url, headers, deadline.requestTimeoutMs())
            if (response.statusCode != 200) null else BilibiliParser.parsePlayUrl(response.body)
        } catch (_: Exception) {
            null
        }
    }

    private fun desktopHeaders(): Map<String, String> = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Referer" to "https://www.bilibili.com/",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
    )

    private fun mobileHeaders(): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_UA,
        "Referer" to "https://www.bilibili.com/",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
    )

    private fun notFound(message: String) =
        MediaWork(ok = false, platform = "bilibili", platformName = "哔哩哔哩", code = "not_found", message = message)

    private fun parseFailed(message: String) =
        MediaWork(ok = false, platform = "bilibili", platformName = "哔哩哔哩", code = "parse_failed", message = message)

    private fun noMedia(message: String) =
        MediaWork(ok = false, platform = "bilibili", platformName = "哔哩哔哩", code = "no_media", message = message)

    private fun network(message: String) =
        MediaWork(ok = false, platform = "bilibili", platformName = "哔哩哔哩", code = "network", message = message)

    private fun timeout() = MediaWork(
        ok = false,
        platform = "bilibili",
        platformName = "哔哩哔哩",
        code = "timeout",
        message = "B 站解析超时，请稍后重试",
    )

    private companion object {
        const val BILI_API = "https://api.bilibili.com/x"

        /** 最多请求 3 档清晰度（失败的跳过）。 */
        val QUALITY_REQUESTS = listOf(120, 80, 64)
        const val MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

internal data class BilibiliViewInfo(
    val bvid: String,
    val title: String,
    val author: String,
    val pic: String,
    val cid: Long,
)

internal data class BilibiliPlayInfo(
    val url: String,
    val size: Long,
    val quality: String,
)

/** 解析 B 站接口 JSON（真机 fixture：`src/test/resources/fixtures/bilibili_*.json`）。 */
internal object BilibiliParser {

    val BVID = Regex("BV[0-9A-Za-z]{10}")
    val AVID = Regex("""\bav(\d+)\b""", RegexOption.IGNORE_CASE)

    private val QUALITY_FROM_TEXT = Regex("""(\d{3,4})\s*[pP]""")

    fun extractBvid(text: String): String? = BVID.find(text)?.value

    fun extractAid(text: String): Long? =
        AVID.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    fun parseView(json: String): BilibiliViewInfo? {
        val data = openData(json) ?: return null
        val pages = data.get("pages")?.takeIf { it.isJsonArray }?.asJsonArray
        val firstPage = pages?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        return BilibiliViewInfo(
            bvid = data.stringOrNull("bvid").orEmpty(),
            title = data.stringOrNull("title").orEmpty().trim(),
            author = data.objectOrNull("owner")?.stringOrNull("name").orEmpty().trim(),
            pic = data.stringOrNull("pic").orEmpty(),
            cid = firstPage?.longOrNull("cid") ?: 0L,
        ).takeIf { it.bvid.isNotEmpty() || it.cid > 0L }
    }

    fun parsePlayUrl(json: String): BilibiliPlayInfo? {
        val data = openData(json) ?: return null
        val durl = data.get("durl")?.takeIf { it.isJsonArray }?.asJsonArray
        val first = durl?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val url = first.stringOrNull("url").orEmpty().trim()
        if (url.isEmpty()) return null
        val qualityNumber = data.intOrNull("quality") ?: 0
        var quality = qualityLabel(qualityNumber)
        if (quality.isEmpty()) quality = labelFromSupportFormats(data, qualityNumber).orEmpty()
        if (quality.isEmpty()) {
            val description = data.get("accept_description")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.firstOrNull()
                ?.let { if (it.isJsonPrimitive) it.asString else "" }
                .orEmpty()
            quality = QUALITY_FROM_TEXT.find(description)?.groupValues?.getOrNull(1)?.let { "${it}p" }.orEmpty()
        }
        return BilibiliPlayInfo(
            url = url.replace("\\u0026", "&"),
            size = first.longOrNull("size") ?: 0L,
            quality = quality,
        )
    }

    /** 清晰度数字 → 可读标签（拿不到返回空串）。 */
    fun qualityLabel(quality: Int): String = when (quality) {
        16 -> "360p"
        32 -> "480p"
        64 -> "720p"
        74 -> "720p60"
        80 -> "1080p"
        112 -> "1080p+"
        116 -> "1080p60"
        120 -> "4K"
        125 -> "HDR"
        else -> ""
    }

    /**
     * 多档 playurl 响应 → 可选清晰度列表：按 URL 去重、按清晰度从高到低、同 label 只保留一条。
     */
    fun mergeQualities(plays: List<BilibiliPlayInfo>): List<MediaQuality> {
        val byUrl = LinkedHashMap<String, MediaQuality>()
        for (play in plays) {
            val url = play.url.trim()
            if (url.isEmpty()) continue
            byUrl.putIfAbsent(
                url,
                MediaQuality(label = normalizeLabel(play.quality), url = url, size = play.size),
            )
        }
        return byUrl.values
            .sortedByDescending { qualityRank(it.label) }
            .distinctBy { it.label }
    }

    /** 清晰度标签归一：`1080P` → `1080p`；未知 → `原画`。 */
    fun normalizeLabel(label: String): String {
        val value = label.trim()
        if (value.isEmpty()) return "原画"
        if (QUALITY_FROM_TEXT.matches(value)) return value.lowercase()
        return value
    }

    /** 清晰度排序权重：数字越大越清晰；`4K`/`原画` 置顶，未知排最后。 */
    fun qualityRank(label: String): Int {
        val value = label.trim()
        QUALITY_FROM_TEXT.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return when {
            value.contains("4K", ignoreCase = true) || value.contains("2160") -> 2160
            value.contains("2K", ignoreCase = true) || value.contains("1440") -> 1440
            value.contains("原画") || value.contains("origin", ignoreCase = true) -> 9999
            else -> 0
        }
    }

    private fun labelFromSupportFormats(data: JsonObject, quality: Int): String? {
        val formats = data.get("support_formats")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        for (element in formats) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            val formatQuality = obj.intOrNull("quality") ?: continue
            if (formatQuality != quality) continue
            val description = obj.stringOrNull("new_description")
                ?: obj.stringOrNull("display_desc")
                ?: continue
            QUALITY_FROM_TEXT.find(description)?.groupValues?.getOrNull(1)?.let { return "${it}p" }
        }
        return null
    }

    private fun openData(json: String): JsonObject? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        if ((obj.intOrNull("code") ?: return null) != 0) return null
        return obj.objectOrNull("data")
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val element: JsonElement = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }

    private fun JsonObject.intOrNull(name: String): Int? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asInt }.getOrNull()
    }

    private fun JsonObject.longOrNull(name: String): Long? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asLong }.getOrNull()
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? {
        val element = get(name) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }
}