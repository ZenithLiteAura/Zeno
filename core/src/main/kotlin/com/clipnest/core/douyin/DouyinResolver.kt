package com.clipnest.core.douyin

import com.clipnest.core.platform.MediaQuality
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 抖音作品解析器（Android 原生版，逐段移植自 `hellotik-douyin-clone/src/resolver/resolve.py`）。
 *
 * 链路：
 * 1. 从分享文案/链接里定位 aweme_id（短链先跟随 302）；
 * 2. `https://www.iesdouyin.com/share/{video|note}/{id}/` 分享页：**同一个 Session 最多连发 6 次、
 *    间隔 2s**，直到 `window._ROUTER_DATA` 里出现作品数据（首次请求只种 ttwid cookie）；
 * 3. `https://www.douyin.com/{note|video}/{id}` SEO 页 `ld+json` 兜底（只对图文作品有效）；
 * 4. 全部失败收敛成 `code/message`，绝不抛裸异常。
 *
 * Android 端没有 Playwright 浏览器兜底链路（Python 的链路 3），属于有意省略。
 */
class DouyinResolver {

    /**
     * 解析一段抖音分享文案 / 分享链接 / 纯作品 ID。
     *
     * @param textOrUrl 分享文案、分享链接或纯数字作品 ID
     * @param budgetMs 单次解析总预算（毫秒），默认 60s；`<=0` 用默认值，过小会夹到 1s
     * @return 结构化结果；失败时 `ok=false` 且带 `code`/`message`，不会抛裸异常
     */
    fun resolve(textOrUrl: String, budgetMs: Long = 60_000): DouyinWork {
        return try {
            resolveInternal(textOrUrl, budgetMs)
        } catch (error: Exception) {
            DouyinWork(
                ok = false,
                code = CODE_PARSE_FAILED,
                message = "解析过程出现未预期异常：${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    // ── 主流程 ───────────────────────────────────────────────────

    private fun resolveInternal(textOrUrl: String, budgetMs: Long): DouyinWork {
        val text = textOrUrl.trim()
        if (text.isEmpty()) {
            return notFound("输入为空：请提供抖音分享链接、分享文案或作品 ID")
        }

        val budget = normalizeBudget(budgetMs)
        val deadline = System.nanoTime() + budget * 1_000_000L
        val http = DouyinHttp()
        val failures = mutableListOf<String>()
        var httpResponses = 0
        var networkErrors = 0
        var hitTimeout = false

        // ── 步骤 1：定位作品 ID（短链先跟随 302） ──
        val url = DouyinLinks.extractUrl(text)
        var kindHint = DouyinLinks.kindHint(text)
        var awemeId = DouyinLinks.extractAwemeId(text)

        if (url != null && DouyinLinks.isShortLink(url) && remainingMs(deadline) > SHORT_LINK_MIN_REMAINING_MS) {
            val timeout = clampTimeout(remainingMs(deadline) - 5_000, 5_000, 20_000)
            try {
                val response = http.get(url, mobileHtmlHeaders(), timeout, followRedirects = true)
                httpResponses++
                awemeId = awemeId ?: DouyinLinks.extractAwemeId(response.finalUrl)
                kindHint = kindHint ?: DouyinLinks.kindHint(response.finalUrl)
            } catch (error: Exception) {
                networkErrors++
                failures += "短链跳转失败：${error.javaClass.simpleName}"
            }
            sleepBetweenRequests(deadline)
        }

        if (awemeId.isNullOrEmpty()) {
            return notFound(
                "无法从输入中识别抖音作品 ID：请确认是作品分享链接/分享文案，" +
                    "而不是作者主页、直播或合集链接",
            )
        }

        // ── 步骤 2：主力链路 iesdouyin 分享页（纯 HTTP） ──
        // 视频大小用 Range 探测（不下载本体）；预算不够就跳过探测，用接口里的 data_size
        val probe: (String) -> MediaProbeResult = { mediaUrl ->
            if (remainingMs(deadline) < 8_000) {
                MediaProbeResult(null, true)
            } else {
                val timeout = clampTimeout(remainingMs(deadline) - 5_000, 5_000, 15_000)
                val result = http.probeSize(mediaUrl, mediaHeaders(), timeout)
                MediaProbeResult(result.size, result.alive)
            }
        }

        val share = fetchShareItem(http, awemeId, deadline, kindHint, probe)
        httpResponses += share.httpResponses
        networkErrors += share.networkErrors
        share.work?.let { return it }
        if (share.reason.isNotEmpty()) failures += share.reason
        if (share.timedOut) hitTimeout = true

        // ── 步骤 3：SEO 页 ld+json（图文兜底） ──
        val seo = fetchSeoImagePost(http, awemeId, deadline)
        httpResponses += seo.httpResponses
        networkErrors += seo.networkErrors
        seo.work?.let { return it.copy(awemeId = awemeId) }
        failures += "SEO 页未取得图文数据"

        // ── 步骤 4：Android 端没有浏览器兜底 ──
        failures += "Android 端未启用浏览器兜底（无 Playwright）"

        // ── 步骤 5：汇总原因（超时单独给 code） ──
        if (remainingMs(deadline) <= 0 || hitTimeout) {
            return DouyinWork(
                ok = false,
                code = CODE_TIMEOUT,
                message = "解析超时（预算 ${budget / 1000} 秒）：" + failures.joinToString("；"),
            )
        }
        if (httpResponses == 0 && networkErrors > 0) {
            return DouyinWork(
                ok = false,
                code = CODE_NETWORK,
                message = "网络异常，无法连接抖音：" + failures.joinToString("；"),
            )
        }
        return DouyinWork(
            ok = false,
            code = CODE_PARSE_FAILED,
            message = "全部解析链路均失败：" + failures.joinToString("；"),
        )
    }

    // ── 分享页 ───────────────────────────────────────────────────

    private data class ShareOutcome(
        val work: DouyinWork?,
        val reason: String,
        val timedOut: Boolean,
        val httpResponses: Int,
        val networkErrors: Int,
    )

    /**
     * 同一个 Session 连发多次分享页请求，直到 `_ROUTER_DATA` 里出现作品数据。
     * 请求次数是两种 kind 共享的预算：无论哪条路径先出数据都命中，最坏也只花 6 次。
     */
    private fun fetchShareItem(
        http: DouyinHttp,
        awemeId: String,
        deadline: Long,
        kindHint: String?,
        probe: ((String) -> MediaProbeResult)?,
    ): ShareOutcome {
        val kinds = mutableListOf<String>()
        if (kindHint == "video" || kindHint == "note") kinds += kindHint
        for (kind in listOf("video", "note")) {
            if (kind !in kinds) kinds += kind
        }

        var lastReason = ""
        var httpResponses = 0
        var networkErrors = 0

        for (index in 0 until SHARE_MAX_REQUESTS) {
            // 留 3 秒余量：至少让一次请求发得出去
            if (remainingMs(deadline) < MIN_REMAINING_FOR_REQUEST_MS) {
                return ShareOutcome(
                    work = null,
                    reason = "解析总超时，尚未取得分享页数据",
                    timedOut = true,
                    httpResponses = httpResponses,
                    networkErrors = networkErrors,
                )
            }

            val kind = kinds[index % kinds.size]
            val pageUrl = SHARE_PAGE_TEMPLATE.format(kind, awemeId)
            val timeout = clampTimeout(remainingMs(deadline) - 3_000, 5_000, 15_000)

            val response = try {
                http.get(pageUrl, mobileHtmlHeaders(), timeout, followRedirects = true)
            } catch (error: Exception) {
                networkErrors++
                lastReason = "分享页请求异常：${error.javaClass.simpleName}: ${error.message}"
                sleepBetweenRequests(deadline)
                continue
            }

            httpResponses++
            when {
                response.statusCode == 403 -> {
                    return ShareOutcome(
                        work = null,
                        reason = "分享页返回 HTTP 403（疑似风控，请降低频率或稍后重试）",
                        timedOut = false,
                        httpResponses = httpResponses,
                        networkErrors = networkErrors,
                    )
                }
                response.statusCode != 200 -> {
                    lastReason = "分享页返回 HTTP ${response.statusCode}"
                    sleepBetweenRequests(deadline)
                }
                else -> {
                    val work = DouyinParser.parseRouter(response.body, probe)
                    if (work != null) {
                        return ShareOutcome(work, "", false, httpResponses, networkErrors)
                    }
                    lastReason = "分享页未返回作品数据（cookie 尚未种下，或作品已删除/受限）"
                    sleepBetweenRequests(deadline)
                }
            }
        }

        return ShareOutcome(
            work = null,
            reason = lastReason.ifEmpty { "分享页未返回作品数据" },
            timedOut = false,
            httpResponses = httpResponses,
            networkErrors = networkErrors,
        )
    }

    // ── SEO 兜底 ─────────────────────────────────────────────────

    private data class SeoOutcome(
        val work: DouyinWork?,
        val httpResponses: Int,
        val networkErrors: Int,
    )

    /** 用爬虫 UA 抓 SEO 页的 `ld+json`，只对图文作品有效（视频作品的 VideoObject 没有 contentUrl）。 */
    private fun fetchSeoImagePost(http: DouyinHttp, awemeId: String, deadline: Long): SeoOutcome {
        var httpResponses = 0
        var networkErrors = 0
        for (kind in listOf("note", "video")) {
            if (remainingMs(deadline) < MIN_REMAINING_FOR_REQUEST_MS) break
            val detailUrl = DETAIL_PAGE_TEMPLATE.format(kind, awemeId)
            val timeout = clampTimeout(remainingMs(deadline) - 3_000, 5_000, 15_000)
            val response = try {
                http.get(detailUrl, botHeaders(), timeout, followRedirects = true)
            } catch (_: Exception) {
                networkErrors++
                sleepBetweenRequests(deadline)
                continue
            }
            httpResponses++
            if (response.statusCode != 200) {
                sleepBetweenRequests(deadline)
                continue
            }
            val work = DouyinParser.parseLdJson(response.body)
            if (work != null) return SeoOutcome(work, httpResponses, networkErrors)
            sleepBetweenRequests(deadline)
        }
        return SeoOutcome(null, httpResponses, networkErrors)
    }

    // ── 小工具 ───────────────────────────────────────────────────

    private fun notFound(message: String): DouyinWork =
        DouyinWork(ok = false, code = CODE_NOT_FOUND, message = message)

    private fun remainingMs(deadline: Long): Long = (deadline - System.nanoTime()) / 1_000_000L

    private fun normalizeBudget(budgetMs: Long): Long = when {
        budgetMs <= 0L -> DEFAULT_BUDGET_MS
        budgetMs < MIN_BUDGET_MS -> MIN_BUDGET_MS
        else -> budgetMs
    }

    private fun clampTimeout(valueMs: Long, minMs: Int, maxMs: Int): Int =
        valueMs.coerceIn(minMs.toLong(), maxMs.toLong()).toInt()

    /** 请求间隔 2s 规避风控；但绝不允许把总预算睡穿。 */
    private fun sleepBetweenRequests(deadline: Long) {
        val wait = minOf(REQUEST_INTERVAL_MS, (remainingMs(deadline) - 3_000).coerceAtLeast(0))
        if (wait > 0) {
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun mobileHtmlHeaders(): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9",
    )

    private fun botHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BOT_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9",
    )

    /** 探测媒体大小用的请求头（移动 UA + 分享页 Referer）。 */
    private fun mediaHeaders(): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_UA,
        "Referer" to "https://www.iesdouyin.com/",
    )

    private companion object {
        const val DEFAULT_BUDGET_MS = 60_000L
        const val MIN_BUDGET_MS = 1_000L
        const val REQUEST_INTERVAL_MS = 2_000L
        const val SHARE_MAX_REQUESTS = 6
        const val MIN_REMAINING_FOR_REQUEST_MS = 3_000L
        const val SHORT_LINK_MIN_REMAINING_MS = 8_000L

        const val CODE_NOT_FOUND = "not_found"
        const val CODE_PARSE_FAILED = "parse_failed"
        const val CODE_TIMEOUT = "timeout"
        const val CODE_NETWORK = "network"

        // 分享页对移动 UA 才会返回 SSR 数据（桌面 UA 直接被 302 到 www）
        const val MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

        // 桌面 UA / 爬虫 UA（SEO 页对爬虫返回可索引的 SSR，含 ld+json）
        const val BOT_UA = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

        const val SHARE_PAGE_TEMPLATE = "https://www.iesdouyin.com/share/%s/%s/"
        const val DETAIL_PAGE_TEMPLATE = "https://www.douyin.com/%s/%s"
    }
}

// ============================================================
// 解析/归一化（internal：单测直接喂合成 _ROUTER_DATA 夹具，不联网）
// ============================================================

/** normalizeItem 里探测视频大小的回调结果。 */
internal data class MediaProbeResult(val size: Long?, val alive: Boolean)

/** 视频清晰度候选（对应 Python `build_video_candidates` 的元素）。 */
internal data class VideoCandidate(
    val res: String,
    val url: String,
    val size: Long,
    val watermark: Boolean,
)

internal object DouyinParser {

    private const val MAX_PROBE_CANDIDATES = 2
    private val URL_LIST_KEYS = listOf("url_list", "urlList", "urls")
    private val COVER_KEYS = listOf("cover", "origin_cover", "dynamic_cover", "cover_original_scale")

    /**
     * 从分享页 HTML 里取 `window._ROUTER_DATA` 并归一化。
     * @return 拿到作品数据时返回结果（可能 `ok=false, code=no_media`）；页面里没有数据返回 null
     */
    fun parseRouter(html: String, probe: ((String) -> MediaProbeResult)? = null): DouyinWork? {
        val json = extractJsonObjectAfter(html, "window._ROUTER_DATA") ?: return null
        val router = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        if (!router.isJsonObject) return null
        val item = findShareItem(router.asJsonObject) ?: return null
        return normalizeItem(item, "iesdouyin", probe)
    }

    /**
     * 从 HTML 里定位 [marker] 之后的第一个 JSON 对象并原样切出来。
     *
     * 用**花括号配对扫描**（字符串/转义要跳过），不要用正则：分享页里的 `_ROUTER_DATA`
     * 有十万字符，正则非贪婪匹配既慢又容易被字符串里的括号干扰。
     */
    fun extractJsonObjectAfter(text: String, marker: String): String? {
        if (text.isEmpty()) return null
        val markerIndex = text.indexOf(marker)
        if (markerIndex < 0) return null
        val start = text.indexOf('{', markerIndex)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    /** 已知结构 `loaderData[*][*].item_list[0]`；找不到再递归扫「有 aweme_id 且含 video/images」的对象。 */
    fun findShareItem(router: JsonObject): JsonObject? {
        val loaderData = router.optObject("loaderData")
        if (loaderData != null) {
            for ((_, pageValue) in loaderData.entrySet()) {
                if (!pageValue.isJsonObject) continue
                for ((_, value) in pageValue.asJsonObject.entrySet()) {
                    if (!value.isJsonObject) continue
                    val items = value.asJsonObject.get("item_list")
                    if (items != null && items.isJsonArray && items.asJsonArray.size() > 0) {
                        val first = items.asJsonArray.get(0)
                        if (first.isJsonObject) return first.asJsonObject
                    }
                }
            }
        }
        return findAwemeItem(router, 0)
    }

    /** 在嵌套 JSON 里递归查找带 aweme_id 的作品对象（结构兜底）。 */
    fun findAwemeItem(node: JsonElement?, depth: Int = 0): JsonObject? {
        if (node == null || node.isJsonNull || depth > 8) return null
        if (node.isJsonObject) {
            val obj = node.asJsonObject
            val hasAwemeId = obj.optString("aweme_id").isNotEmpty()
            if (hasAwemeId && (obj.has("video") || obj.has("images"))) return obj
            for ((_, value) in obj.entrySet()) {
                findAwemeItem(value, depth + 1)?.let { return it }
            }
        } else if (node.isJsonArray) {
            for (value in node.asJsonArray) {
                findAwemeItem(value, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** 作品对象 → [DouyinWork]（对应 Python `normalize_item`）。 */
    fun normalizeItem(
        item: JsonObject,
        source: String,
        probe: ((String) -> MediaProbeResult)? = null,
    ): DouyinWork {
        val awemeId = item.optString("aweme_id").trim()
        val authorName = item.optObject("author")?.optString("nickname")?.trim().orEmpty()
        val title = cleanTitle(item.optString("desc"))
        val pics = buildPics(item)

        // 类型判定严格按契约：pics 非空 → image，否则 video
        if (pics.isNotEmpty()) {
            return DouyinWork(
                ok = true,
                awemeId = awemeId,
                type = "image",
                title = title,
                authorName = authorName,
                pics = pics,
                coverUrl = pics.first(),
                source = source,
            )
        }

        val video = item.optObject("video")
        val candidates = if (video != null) buildVideoCandidates(video) else emptyList()
        if (candidates.isEmpty()) {
            return DouyinWork(
                ok = false,
                awemeId = awemeId,
                code = CODE_NO_MEDIA,
                message = "作品没有可用的图片或视频地址（可能已删除，或作者关闭了下载）",
            )
        }

        // bit_rate 里有多档时全部转成可选清晰度（从高到低）；为空则退回单候选
        val bitRateCandidates = if (video != null) buildBitRateCandidates(video) else emptyList()
        val orderedCandidates: List<VideoCandidate> =
            if (bitRateCandidates.isNotEmpty()) {
                bitRateCandidates.sortedByDescending { qualityRank(it.res) }
            } else {
                candidates
            }

        // 默认选中项也优先无水印（下载体验优先），但 qualities 展示顺序仍按清晰度从高到低。
        // 注意：bit_rate 为空时保持原有候选顺序（1080p play 接口 → 720p → origin → 水印），
        // 不能按 qualityRank 重排（origin 等占位标签会排到最前）。
        val probeOrder = if (bitRateCandidates.isNotEmpty()) {
            orderedCandidates.sortedWith(
                compareBy<VideoCandidate> { it.watermark }.thenByDescending { qualityRank(it.res) },
            )
        } else {
            orderedCandidates
        }
        var chosen = probeOrder.first()
        val probedSizes = HashMap<String, Long>()
        if (probe != null) {
            var probed = 0
            for (candidate in probeOrder) {
                if (probed >= MAX_PROBE_CANDIDATES) break
                val result = probe(candidate.url)
                probed++
                if (result.size != null) probedSizes[candidate.url] = result.size
                if (result.alive) {
                    chosen = candidate
                    break
                }
            }
        }

        val qualities: List<MediaQuality> = if (bitRateCandidates.isNotEmpty()) {
            orderedCandidates.map { candidate ->
                MediaQuality(
                    label = normalizeQualityLabel(candidate.res),
                    url = candidate.url,
                    size = probedSizes[candidate.url] ?: candidate.size,
                    watermark = candidate.watermark,
                )
            }
        } else {
            listOf(
                MediaQuality(
                    label = normalizeQualityLabel(chosen.res),
                    url = chosen.url,
                    size = probedSizes[chosen.url] ?: chosen.size,
                    watermark = chosen.watermark,
                ),
            )
        }
        val selected = qualities.firstOrNull { it.url == chosen.url } ?: qualities.first()

        return DouyinWork(
            ok = true,
            awemeId = awemeId,
            type = "video",
            title = title,
            authorName = authorName,
            videoUrl = selected.url,
            videoQuality = selected.label,
            videoSize = selected.size,
            qualities = qualities,
            selectedQuality = selected.label,
            coverUrl = if (video != null) firstCoverUrl(video) else "",
            source = source,
        )
    }

    /**
     * 图文作品图片直链：只取 `images[].url_list[0]`，按 `uri` 去重。
     * **不要**用 `download_url_list`（实测那一组带水印）。
     */
    fun buildPics(item: JsonObject): List<String> {
        val images = item.get("images") ?: return emptyList()
        if (!images.isJsonArray) return emptyList()

        val pics = ArrayList<String>()
        val seenIdentity = HashSet<String>()
        val seenUrl = HashSet<String>()
        for (element in images.asJsonArray) {
            if (!element.isJsonObject) continue
            val image = element.asJsonObject
            val url = firstUrlFromKeys(image, URL_LIST_KEYS) ?: continue
            // 同一张图有多个 CDN 域名，去重时用 uri 作为身份标识
            val identity = image.optString("uri").trim().ifEmpty { url }
            if (!seenIdentity.add(identity)) continue
            if (!seenUrl.add(url)) continue
            pics.add(url)
        }
        return pics
    }

    /**
     * 视频清晰度候选（对应 Python `build_video_candidates`），顺序：
     * ① `bit_rate[].play_addr`（契约首选） ② `play_addr.uri` 重构无水印地址
     * ③ `play_addr.url_list`（多带水印） ④ `download_addr.url_list`（兜底，带水印）
     */
    fun buildVideoCandidates(video: JsonObject): List<VideoCandidate> {
        val candidates = LinkedHashMap<String, VideoCandidate>()

        fun add(res: String, url: String?, size: Long, watermark: Boolean) {
            val normalized = normalizeHttpUrl(url) ?: return
            if (candidates.containsKey(normalized)) return
            candidates[normalized] = VideoCandidate(
                res = res,
                url = normalized,
                size = size,
                watermark = watermark || normalized.contains("playwm"),
            )
        }

        // ① bit_rate 各清晰度
        for (candidate in buildBitRateCandidates(video)) {
            add(candidate.res, candidate.url, candidate.size, candidate.watermark)
        }

        // ② play_addr.uri 重构无水印播放地址（实测分享页 SSR 没有 bit_rate）
        val playAddr = video.optObject("play_addr")
        val uri = playAddr?.optString("uri")?.trim().orEmpty()
        val playAddrDataSize = playAddr?.optLong("data_size") ?: 0L
        if (uri.isNotEmpty()) {
            val directUrl = normalizeHttpUrl(uri)
            if (directUrl != null) {
                // 图文作品的 play_addr.uri 实测是完整 URL（不是 video_id），直接当候选地址
                add("play", directUrl, playAddrDataSize, watermark = false)
            } else {
                // 视频作品：uri 是 video_id，拼无水印 play 接口（playwm 才是带水印）
                add("1080p", "$PLAY_API?video_id=$uri&ratio=1080p&line=0", playAddrDataSize, watermark = false)
                add("720p", "$PLAY_API?video_id=$uri&ratio=720p&line=0", 0, watermark = false)
                add("origin", "$PLAY_API?video_id=$uri&line=0", 0, watermark = false)
            }
        }

        // ③ / ④ 接口原始地址（多为带水印）
        if (playAddr != null) {
            for (url in allUrls(playAddr, URL_LIST_KEYS)) add("watermark", url, 0, watermark = false)
        }
        val downloadAddr = video.optObject("download_addr")
        if (downloadAddr != null) {
            for (url in allUrls(downloadAddr, URL_LIST_KEYS)) add("watermark", url, 0, watermark = true)
        }

        return candidates.values.toList()
    }

    /**
     * 只解析 `video.bit_rate[]`（多清晰度来源，从高到低排序由调用方再做）。
     * 每个 gear 取 `play_addr.url_list[0]`；没有 url_list 时用 `play_addr.uri`：
     * 完整 URL 直接用，`video_id` 拼无水印 play 接口。同一个 URL 只保留一次。
     */
    fun buildBitRateCandidates(video: JsonObject): List<VideoCandidate> {
        val bitRates = video.get("bit_rate") ?: return emptyList()
        if (!bitRates.isJsonArray) return emptyList()

        val candidates = ArrayList<VideoCandidate>()
        val seenUrls = HashSet<String>()
        var index = 0
        for (element in bitRates.asJsonArray) {
            index++
            if (!element.isJsonObject) continue
            val gear = element.asJsonObject
            val addr = gear.optObject("play_addr") ?: continue

            val resolution = qualityLabel(gear.optLong("width"), gear.optLong("height"))
            val gearName = gear.optString("gear_name").trim()
            val qualityType = gear.optString("quality_type").trim()
            val label = resolution ?: gearName.ifEmpty { qualityType.ifEmpty { "清晰度$index" } }

            var url = firstUrlFromKeys(addr, URL_LIST_KEYS)?.let { normalizeHttpUrl(it) }
            if (url == null) {
                val uri = addr.optString("uri").trim()
                if (uri.isNotEmpty()) {
                    url = normalizeHttpUrl(uri)
                        ?: if (QUALITY_LABEL_PATTERN.matches(label)) {
                            "$PLAY_API?video_id=$uri&ratio=$label&line=0"
                        } else {
                            "$PLAY_API?video_id=$uri&line=0"
                        }
                }
            }
            if (url == null || !seenUrls.add(url)) continue

            candidates.add(
                VideoCandidate(
                    res = label,
                    url = url,
                    size = addr.optLong("data_size"),
                    watermark = url.contains("playwm"),
                ),
            )
        }
        return candidates
    }

    /** 清晰度标签归一：`1080P` → `1080p`；内部占位（play/watermark/origin）与空值 → `原画`。 */
    fun normalizeQualityLabel(label: String): String {
        val value = label.trim()
        if (value.isEmpty()) return "原画"
        if (value.equals("play", ignoreCase = true) ||
            value.equals("watermark", ignoreCase = true) ||
            value.equals("origin", ignoreCase = true)
        ) {
            return "原画"
        }
        if (QUALITY_LABEL_PATTERN.matches(value)) return value.lowercase()
        return value
    }

    /** 清晰度排序权重：数字越大越清晰；`原画`/`4K` 优先，未知排最后。 */
    fun qualityRank(label: String): Int {
        val value = label.trim()
        QUALITY_LABEL_PATTERN.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return when {
            value.contains("4K", ignoreCase = true) || value.contains("2160") -> 2160
            value.contains("2K", ignoreCase = true) || value.contains("1440") -> 1440
            value.contains("原画") || value.contains("origin", ignoreCase = true) -> 9999
            else -> 0
        }
    }

    // ── SEO 兜底 ─────────────────────────────────────────────────

    /** 从 SEO 页 `ld+json` 里取图文作品（`@type=article` 的 `image` 列表）。 */
    fun parseLdJson(html: String): DouyinWork? {
        for (block in extractLdJsonBlocks(html)) {
            if (block.optString("@type") != "article") continue
            val images = block.get("image") ?: continue
            if (!images.isJsonArray || images.asJsonArray.size() == 0) continue
            val pics = images.asJsonArray
                .mapNotNull { firstUrl(it) }
                .distinct()
            if (pics.isEmpty()) continue
            return DouyinWork(
                ok = true,
                type = "image",
                title = block.optString("headline").trim(),
                pics = pics,
                coverUrl = pics.first(),
                source = "seo",
            )
        }
        return null
    }

    /** 取出页面里所有 `<script type="application/ld+json">` 的结构化数据。 */
    fun extractLdJsonBlocks(html: String): List<JsonObject> {
        if (html.isEmpty()) return emptyList()
        val blocks = ArrayList<JsonObject>()
        for (match in LD_JSON_SCRIPT.findAll(html)) {
            val raw = match.groupValues.getOrNull(1) ?: continue
            val parsed = runCatching { JsonParser.parseString(raw.trim()) }.getOrNull() ?: continue
            if (parsed.isJsonObject) blocks.add(parsed.asJsonObject)
        }
        return blocks
    }

    // ── JSON 取值小工具（缺字段/类型不符一律安全回落） ──

    private fun JsonObject.optObject(name: String): JsonObject? {
        val element = get(name) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    private fun JsonObject.optString(name: String): String {
        val element = get(name) ?: return ""
        if (!element.isJsonPrimitive) return ""
        return runCatching { element.asString }.getOrDefault("")
    }

    private fun JsonObject.optLong(name: String): Long {
        val element = get(name) ?: return 0L
        if (!element.isJsonPrimitive) return 0L
        return runCatching {
            val primitive = element.asJsonPrimitive
            when {
                primitive.isNumber -> primitive.asLong
                primitive.isString -> primitive.asString.toLongOrNull()
                    ?: primitive.asString.toDoubleOrNull()?.toLong()
                    ?: 0L
                else -> 0L
            }
        }.getOrDefault(0L)
    }

    /** 从 `{"url_list": [...]}` / 列表 / 字符串结构里取第一个可用 URL（http 统一升 https）。 */
    private fun firstUrl(node: JsonElement?): String? {
        if (node == null || node.isJsonNull) return null
        if (node.isJsonPrimitive) {
            val primitive = node.asJsonPrimitive
            return if (primitive.isString) normalizeHttpUrl(primitive.asString) else null
        }
        if (node.isJsonArray) {
            for (element in node.asJsonArray) firstUrl(element)?.let { return it }
            return null
        }
        if (node.isJsonObject) {
            val obj = node.asJsonObject
            for (key in URL_LIST_KEYS) {
                val child = obj.get(key) ?: continue
                firstUrl(child)?.let { return it }
            }
        }
        return null
    }

    private fun firstUrlFromKeys(obj: JsonObject, keys: List<String>): String? {
        for (key in keys) {
            val child = obj.get(key) ?: continue
            firstUrl(child)?.let { return it }
        }
        return null
    }

    /** 从 `{"url_list": [...]}` 结构里取出全部可用 URL（保序、去重）。 */
    private fun allUrls(node: JsonElement?, keys: List<String>): List<String> {
        val urls = ArrayList<String>()
        val seen = HashSet<String>()

        fun walk(value: JsonElement?) {
            if (value == null || value.isJsonNull) return
            if (value.isJsonPrimitive) {
                val primitive = value.asJsonPrimitive
                if (primitive.isString) {
                    normalizeHttpUrl(primitive.asString)?.let { if (seen.add(it)) urls.add(it) }
                }
                return
            }
            if (value.isJsonArray) {
                for (element in value.asJsonArray) walk(element)
                return
            }
            if (value.isJsonObject) {
                val obj = value.asJsonObject
                for (key in keys) obj.get(key)?.let { walk(it) }
            }
        }

        walk(node)
        return urls
    }

    private fun normalizeHttpUrl(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty()) return null
        if (value.startsWith("http://", ignoreCase = true)) return "https://" + value.substring(7)
        if (value.startsWith("https://", ignoreCase = true)) return value
        return null
    }

    private fun firstCoverUrl(video: JsonObject): String {
        for (key in COVER_KEYS) {
            val cover = video.optObject(key) ?: continue
            firstUrlFromKeys(cover, URL_LIST_KEYS)?.let { return it }
        }
        return ""
    }

    /** 标题：desc 去掉换行/多余空白。 */
    private fun cleanTitle(desc: String): String =
        if (desc.isEmpty()) "" else desc.trim().replace(WHITESPACE_RUN, " ").trim()

    /** 按分辨率给清晰度一个可读标签（短边优先，竖屏视频也能对上）。 */
    private fun qualityLabel(width: Long, height: Long): String? {
        if (width <= 0L || height <= 0L) return null
        val shortSide = minOf(width, height)
        for (standard in QUALITY_STANDARDS) {
            if (shortSide >= standard) return "${standard}p"
        }
        return "${shortSide}p"
    }

    /** 清晰度标签：1080p / 720P 这类「数字+p」。 */
    private val QUALITY_LABEL_PATTERN = Regex("""(\d{3,4})\s*[pP]""")

    private const val CODE_NO_MEDIA = "no_media"
    private const val PLAY_API = "https://aweme.snssdk.com/aweme/v1/play/"
    private val WHITESPACE_RUN = Regex("\\s+")
    private val QUALITY_STANDARDS = listOf(2160L, 1440L, 1080L, 720L, 540L, 480L, 360L)
    private val LD_JSON_SCRIPT =
        Regex("""<script[^>]*type\s*=\s*"application/ld\+json"[^>]*>(.*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL))
}