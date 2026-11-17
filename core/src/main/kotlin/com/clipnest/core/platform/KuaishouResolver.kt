package com.clipnest.core.platform

import com.clipnest.core.douyin.DouyinHttp
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import java.util.ArrayDeque

/**
 * 快手解析（**尚未真机验证 happy path**）。
 *
 * 2026-11-17 真机 curl 实测：`www.kuaishou.com`、`/new-reco`、`/search/video` 都是 SPA，
 * 首页 350KB HTML 里没有 `__APOLLO_STATE__` / `__NEXT_DATA__` / photo 数据（网页端需要登录）；
 * 目前也没有可用的 `v.kuaishou.com` 样例短链。因此这里按公开结构实现：
 * 短链 302 → `www.kuaishou.com/short-video/<id>` → 页面内嵌 `window.__APOLLO_STATE__`
 * （或 `__NEXT_DATA__`）→ 递归找 photo 对象 → `caption` / `photoUrl` / `manifest.adaptationSet[].representation[].url`。
 * 拿不到数据时**如实返回** `parse_failed` + 中文原因，不假装成功。
 */
class KuaishouResolver : PlatformResolver {

    override val id: String = "kuaishou"

    override fun supports(text: String): Boolean {
        if (text.isBlank()) return false
        val url = PlatformLinks.extractUrl(text)
        if (url != null && PlatformLinks.matchesHost(url, "v.kuaishou.com", "kuaishou.com", "kuaishou.cn")) {
            return true
        }
        return KuaishouParser.PHOTO_ID.containsMatchIn(text)
    }

    override fun resolve(text: String, budgetMs: Long): MediaWork {
        val deadline = ResolveDeadline(budgetMs)
        val http = DouyinHttp()
        return try {
            val url = PlatformLinks.extractUrl(text)
            var pageUrl = url
            var photoId = KuaishouParser.extractPhotoId(text) ?: KuaishouParser.extractPhotoId(url)

            if (url != null && PlatformLinks.matchesHost(url, "v.kuaishou.com")) {
                if (deadline.remainingMs() < 2_000L) return timeout()
                val response = try {
                    http.get(url, mobileHeaders(), deadline.requestTimeoutMs())
                } catch (error: IOException) {
                    return if (deadline.expired()) timeout() else network("快手短链跳转失败：${error.javaClass.simpleName}")
                }
                pageUrl = response.finalUrl
                photoId = photoId ?: KuaishouParser.extractPhotoId(response.finalUrl)
            }

            if (photoId == null) {
                return notFound("无法从输入中识别快手作品 ID：请使用作品分享链接/分享文案")
            }
            if (pageUrl == null || PlatformLinks.host(pageUrl)?.contains("kuaishou") != true) {
                pageUrl = "https://www.kuaishou.com/short-video/$photoId"
            }
            if (deadline.remainingMs() < 2_000L) return timeout()

            val response = try {
                http.get(pageUrl, mobileHeaders(), deadline.requestTimeoutMs())
            } catch (error: IOException) {
                return if (deadline.expired()) timeout() else network("快手页面请求失败：${error.javaClass.simpleName}")
            }
            if (response.statusCode != 200) {
                return parseFailed("快手页面返回 HTTP ${response.statusCode}（可能触发风控，请稍后再试）")
            }

            val state = KuaishouParser.extractPageState(response.body)
                ?: return parseFailed(
                    "快手页面没有返回作品数据（网页端需要登录/风控拦截）：" +
                        "请在快手 App 内保存，或稍后重试",
                )
            val work = KuaishouParser.parsePhoto(state, photoId, "kuaishou-web")
                ?: return parseFailed(
                    "快手页面结构变化或作品已删除，未找到作品数据；请重新复制分享链接",
                )
            work
        } catch (error: Exception) {
            if (deadline.expired()) timeout()
            else parseFailed("快手解析出现异常：${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun mobileHeaders(): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_UA,
        "Referer" to "https://www.kuaishou.com/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9",
    )

    private fun notFound(message: String) = MediaWork(
        ok = false,
        platform = "kuaishou",
        platformName = "快手",
        code = "not_found",
        message = message,
    )

    private fun parseFailed(message: String) = MediaWork(
        ok = false,
        platform = "kuaishou",
        platformName = "快手",
        code = "parse_failed",
        message = message,
    )

    private fun network(message: String) = MediaWork(
        ok = false,
        platform = "kuaishou",
        platformName = "快手",
        code = "network",
        message = message,
    )

    private fun timeout() = MediaWork(
        ok = false,
        platform = "kuaishou",
        platformName = "快手",
        code = "timeout",
        message = "快手解析超时，请稍后重试",
    )

    private companion object {
        const val MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
    }
}

/**
 * 快手页面解析（fixture：`src/test/resources/fixtures/kuaishou_photo_synthetic.json`，
 * 真机没有拿到 SSR，这份是按公开 `__APOLLO_STATE__` 结构编写的合成夹具）。
 */
internal object KuaishouParser {

    val PHOTO_ID = Regex("""/(?:short-video|photo|fw/photo|f)/([A-Za-z0-9_-]{6,})""")

    private val STATE_MARKERS = listOf(
        "window.__APOLLO_STATE__",
        "__APOLLO_STATE__",
        "window.__NEXT_DATA__",
        "__NEXT_DATA__",
    )

    fun extractPhotoId(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        return PHOTO_ID.find(text)?.groupValues?.getOrNull(1)
    }

    fun extractPageState(html: String): JsonObject? {
        for (marker in STATE_MARKERS) {
            val raw = ScriptJson.extractAfter(html, marker) ?: continue
            ScriptJson.parseObject(raw)?.let { return it }
        }
        return null
    }

    /** 在 `__APOLLO_STATE__` 里递归找 photo 对象（优先 id 匹配，退回第一个像作品的对象）。 */
    fun findPhoto(state: JsonObject, photoId: String): JsonObject? {
        val queue = ArrayDeque<JsonElement>()
        queue.add(state)
        var fallback: JsonObject? = null
        var visited = 0
        while (queue.isNotEmpty() && visited < 20_000) {
            visited++
            val node = queue.removeFirst()
            if (node.isJsonObject) {
                val obj = node.asJsonObject
                if (looksLikePhoto(obj)) {
                    val id = obj.stringOrNull("id") ?: obj.stringOrNull("photoId")
                    if (id == photoId) return obj
                    if (fallback == null) fallback = obj
                }
                for ((_, value) in obj.entrySet()) queue.add(value)
            } else if (node.isJsonArray) {
                for (value in node.asJsonArray) queue.add(value)
            }
        }
        return fallback
    }

    fun parsePhoto(state: JsonObject, photoId: String, source: String): MediaWork? {
        val photo = findPhoto(state, photoId) ?: return null
        val id = photo.stringOrNull("id")?.trim().orEmpty().ifEmpty { photoId }
        val title = photo.stringOrNull("caption")?.trim().orEmpty()
            .ifEmpty { "快手作品" }
        val author = photo.stringOrNull("userName")?.trim().orEmpty()
            .ifEmpty { photo.objectOrNull("user")?.stringOrNull("name").orEmpty().trim() }
        val videoUrl = findVideoUrl(photo)
        if (videoUrl.isEmpty()) {
            return MediaWork(
                ok = false,
                platform = "kuaishou",
                platformName = "快手",
                workId = id,
                code = "no_media",
                message = "快手作品没有拿到视频地址（网页端需要登录/风控）：请在快手 App 内存档或重新复制分享链接",
                source = source,
            )
        }
        val cover = photo.stringOrNull("coverUrl")?.trim().orEmpty()
            .ifEmpty { photo.stringOrNull("coverUrls")?.trim().orEmpty() }
            .ifEmpty { photo.objectOrNull("cover")?.let { firstStringUrl(it) }.orEmpty() }
        return MediaWork(
            ok = true,
            platform = "kuaishou",
            platformName = "快手",
            workId = id,
            type = "video",
            title = title,
            author = author,
            videoUrl = videoUrl,
            coverUrl = PlatformLinks.toHttps(cover),
            source = source,
        )
    }

    fun findVideoUrl(photo: JsonObject): String {
        photo.stringOrNull("photoUrl")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        photo.stringOrNull("photoH265Url")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val manifest = photo.objectOrNull("manifest")
        if (manifest != null) {
            val adaptation = manifest.get("adaptationSet")?.takeIf { it.isJsonArray }?.asJsonArray
            adaptation?.forEach { set ->
                if (!set.isJsonObject) return@forEach
                val reps = set.asJsonObject.get("representation")?.takeIf { it.isJsonArray }?.asJsonArray
                reps?.forEach { rep ->
                    if (!rep.isJsonObject) return@forEach
                    rep.asJsonObject.stringOrNull("url")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        }
        photo.objectOrNull("photo")?.let { inner ->
            inner.stringOrNull("photoUrl")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private fun looksLikePhoto(obj: JsonObject): Boolean =
        obj.has("photoUrl") || obj.has("manifest") || obj.has("caption") ||
            (obj.has("id") && obj.has("userName"))

    private fun firstStringUrl(obj: JsonObject): String {
        for (key in listOf("url", "urlDefault", "coverUrl")) {
            obj.stringOrNull(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val list = obj.get("urlList")?.takeIf { it.isJsonArray }?.asJsonArray
        return list?.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? {
        val element = get(name) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }
}