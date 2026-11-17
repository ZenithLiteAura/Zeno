package com.clipnest.core.platform

import com.clipnest.core.douyin.DouyinHttp
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException

/**
 * 小红书解析。
 *
 * **真机验证情况（2026-11-17）**：短链跟随与风控判定已验证——`xhslink.cn/xhslink.com` 会 302 到
 * `xiaohongshu.com/discovery/item/<id>?xsec_token=...`；但**没有 token 或 token 过期**时，页面会落到
 * `/404/sec_...` 或 `/login`（实测即使带上 URL 里的 xsec_token 也会被拦）。因此
 * **没有真实 SSR 夹具**，happy path 按公开结构 `window.__INITIAL_STATE__.note.noteDetailMap[noteId].note`
 * 实现并用合成夹具单测；拿不到数据时如实返回 `parse_failed` + 中文原因，不假装成功。
 */
class XiaohongshuResolver : PlatformResolver {

    override val id: String = "xiaohongshu"

    override fun supports(text: String): Boolean {
        if (text.isBlank()) return false
        val url = PlatformLinks.extractUrl(text)
        if (url != null && PlatformLinks.matchesHost(url, "xiaohongshu.com", "xhslink.com", "xhslink.cn", "rednote.com")) {
            return true
        }
        return XiaohongshuParser.NOTE_ID.containsMatchIn(text)
    }

    override fun resolve(text: String, budgetMs: Long): MediaWork {
        val deadline = ResolveDeadline(budgetMs)
        val http = DouyinHttp()
        return try {
            val url = PlatformLinks.extractUrl(text)
            var pageUrl = url
            var noteId = XiaohongshuParser.extractNoteId(text) ?: XiaohongshuParser.extractNoteId(url)

            // 1) 短链跟随（必须保留最终 URL 的 xsec_token / xsec_source 参数）
            if (url != null && PlatformLinks.matchesHost(url, "xhslink.com", "xhslink.cn")) {
                if (deadline.remainingMs() < 2_000L) return timeout()
                val response = try {
                    http.get(url, mobileHeaders(), deadline.requestTimeoutMs())
                } catch (error: IOException) {
                    return if (deadline.expired()) timeout() else network("小红书短链跳转失败：${error.javaClass.simpleName}")
                }
                pageUrl = response.finalUrl
                noteId = noteId ?: XiaohongshuParser.extractNoteId(response.finalUrl)
                if (XiaohongshuParser.isBlockedUrl(response.finalUrl)) {
                    return parseFailed(blockedMessage(response.finalUrl))
                }
            }

            if (noteId == null) {
                return notFound("无法从输入中识别小红书笔记 ID：请使用笔记分享链接/分享文案（不是首页或用户主页）")
            }
            if (pageUrl == null || PlatformLinks.host(pageUrl)?.contains("xiaohongshu") != true) {
                // 只有笔记 ID 时拼详情页；没带 xsec_token 基本会被拦，但先试一次再如实报错
                pageUrl = "https://www.xiaohongshu.com/explore/$noteId"
            }
            if (XiaohongshuParser.isBlockedUrl(pageUrl)) return parseFailed(blockedMessage(pageUrl))
            if (deadline.remainingMs() < 2_000L) return timeout()

            // 2) 笔记页 SSR
            val response = try {
                http.get(pageUrl, mobileHeaders(), deadline.requestTimeoutMs())
            } catch (error: IOException) {
                return if (deadline.expired()) timeout() else network("小红书页面请求失败：${error.javaClass.simpleName}")
            }
            if (XiaohongshuParser.isBlockedUrl(response.finalUrl)) {
                return parseFailed(blockedMessage(response.finalUrl))
            }
            if (response.statusCode != 200) {
                return parseFailed("小红书页面返回 HTTP ${response.statusCode}（可能触发风控，请稍后再试）")
            }

            val state = XiaohongshuParser.extractInitialState(response.body)
                ?: return parseFailed(
                    "小红书页面未返回笔记数据（可能需要登录态或分享链接已过期）：" +
                        "请在 App 内打开笔记 → 分享 → 复制链接后重试",
                )
            val work = XiaohongshuParser.parseNote(state, noteId, "xiaohongshu-ssr")
                ?: return parseFailed(
                    "小红书页面里没有找到笔记详情（页面结构变化或笔记已删除/私密），请重新复制分享链接",
                )
            work
        } catch (error: Exception) {
            if (deadline.expired()) timeout()
            else parseFailed("小红书解析出现异常：${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun blockedMessage(url: String): String =
        if (url.contains("/login")) {
            "小红书要求登录：请在 App 内打开笔记 → 分享 → 复制链接后重试"
        } else {
            "小红书拦截了该链接（缺少或过期的 xsec_token）：请在 App 内打开笔记 → 分享 → 复制链接后重试"
        }

    private fun mobileHeaders(): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_UA,
        "Referer" to "https://www.xiaohongshu.com/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9",
    )

    private fun notFound(message: String) = MediaWork(
        ok = false,
        platform = "xiaohongshu",
        platformName = "小红书",
        code = "not_found",
        message = message,
    )

    private fun parseFailed(message: String) = MediaWork(
        ok = false,
        platform = "xiaohongshu",
        platformName = "小红书",
        code = "parse_failed",
        message = message,
    )

    private fun network(message: String) = MediaWork(
        ok = false,
        platform = "xiaohongshu",
        platformName = "小红书",
        code = "network",
        message = message,
    )

    private fun timeout() = MediaWork(
        ok = false,
        platform = "xiaohongshu",
        platformName = "小红书",
        code = "timeout",
        message = "小红书解析超时，请稍后重试",
    )

    private companion object {
        const val MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
    }
}

/**
 * 小红书页面/接口解析（fixture 见 `src/test/resources/fixtures/xiaohongshu_note_synthetic.json`，
 * 由于真机链接全部被风控拦截，这份是的合成夹具，结构按公开 SSR 结构编写）。
 */
internal object XiaohongshuParser {

    val NOTE_ID = Regex("""(?:explore|discovery/item|item)/([0-9a-f]{24})""", RegexOption.IGNORE_CASE)

    fun extractNoteId(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        return NOTE_ID.find(text)?.groupValues?.getOrNull(1)
    }

    /** `/404/sec_...`、`/login?...` 都是明确的失败页。 */
    fun isBlockedUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return url.contains("/404/") || url.contains("/404?") || url.contains("/login")
    }

    fun extractInitialState(html: String): JsonObject? {
        val raw = ScriptJson.extractAfter(html, "window.__INITIAL_STATE__")
            ?: ScriptJson.extractAfter(html, "__INITIAL_STATE__")
            ?: return null
        return ScriptJson.parseObject(raw)
    }

    /** 从 `__INITIAL_STATE__` 里取笔记对象（`note.noteDetailMap[id].note`，再退递归查找）。 */
    fun findNote(state: JsonObject, noteId: String): JsonObject? {
        val noteMap = state.objectOrNull("note")?.objectOrNull("noteDetailMap")
        if (noteMap != null) {
            val entry = noteMap.entrySet().firstOrNull { it.key == noteId }?.value
                ?: noteMap.entrySet().firstOrNull()?.value
            val note = entry?.takeIf { it.isJsonObject }?.asJsonObject?.objectOrNull("note")
            if (note != null) return note
        }
        return findNoteRecursively(state, noteId)
    }

    fun parseNote(state: JsonObject, noteId: String, source: String): MediaWork? {
        val note = findNote(state, noteId) ?: return null
        val id = note.stringOrNull("noteId")?.trim().orEmpty().ifEmpty { noteId }
        val desc = note.stringOrNull("desc").orEmpty().trim()
        val title = note.stringOrNull("title").orEmpty().trim().ifEmpty { desc.lineSequence().firstOrNull()?.trim().orEmpty() }
        val author = note.objectOrNull("user")?.stringOrNull("nickname").orEmpty().trim()

        val pics = buildPics(note)
        val videoUrl = buildVideoUrl(note)
        val type = when {
            pics.isNotEmpty() -> "image"
            videoUrl.isNotEmpty() -> "video"
            else -> return MediaWork(
                ok = false,
                platform = "xiaohongshu",
                platformName = "小红书",
                workId = id,
                code = "no_media",
                message = "小红书笔记没有可用的图片或视频地址（可能已删除，或内容受保护）",
                source = source,
            )
        }

        val cover = pics.firstOrNull()
            ?: note.objectOrNull("video")?.objectOrNull("image")?.let { imageUrl(it) }
            ?: ""

        return MediaWork(
            ok = true,
            platform = "xiaohongshu",
            platformName = "小红书",
            workId = id,
            type = type,
            title = title,
            author = author,
            pics = pics,
            videoUrl = videoUrl,
            coverUrl = cover,
            source = source,
        )
    }

    /** 图片：优先 `urlDefault`，其次 `infoList[].url`；去重保序。 */
    fun buildPics(note: JsonObject): List<String> {
        val list = note.get("imageList")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val pics = ArrayList<String>()
        val seen = HashSet<String>()
        for (element in list) {
            if (!element.isJsonObject) continue
            val url = imageUrl(element.asJsonObject) ?: continue
            if (seen.add(url)) pics.add(url)
        }
        return pics
    }

    /** 视频：`video.media.stream.h264[].masterUrl`（兜底 h265/av1 与 backupUrls）。 */
    fun buildVideoUrl(note: JsonObject): String {
        val stream = note.objectOrNull("video")?.objectOrNull("media")?.objectOrNull("stream") ?: return ""
        for (codec in listOf("h264", "h265", "av1")) {
            val variants = stream.get(codec)?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (element in variants) {
                if (!element.isJsonObject) continue
                val variant = element.asJsonObject
                variant.stringOrNull("masterUrl")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                val backups = variant.get("backupUrls")?.takeIf { it.isJsonArray }?.asJsonArray
                backups?.firstOrNull()
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
        }
        return ""
    }

    private fun imageUrl(image: JsonObject): String? {
        image.stringOrNull("urlDefault")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val infoList = image.get("infoList")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        for (element in infoList) {
            if (!element.isJsonObject) continue
            element.asJsonObject.stringOrNull("url")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return image.stringOrNull("urlPre")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun findNoteRecursively(node: JsonElement?, targetId: String, depth: Int = 0): JsonObject? {
        if (node == null || node.isJsonNull || depth > 8) return null
        if (node.isJsonObject) {
            val obj = node.asJsonObject
            val id = obj.stringOrNull("noteId")
            if (id == targetId && (obj.has("imageList") || obj.has("video"))) return obj
            for ((_, value) in obj.entrySet()) {
                findNoteRecursively(value, targetId, depth + 1)?.let { return it }
            }
        } else if (node.isJsonArray) {
            for (value in node.asJsonArray) {
                findNoteRecursively(value, targetId, depth + 1)?.let { return it }
            }
        }
        return null
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

    private fun JsonObject.arrayOrNull(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray
}