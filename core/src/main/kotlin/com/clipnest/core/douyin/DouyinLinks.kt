package com.clipnest.core.douyin

import java.net.URI

/**
 * 抖音链接/分享文案/作品 ID 的识别工具（对应 Python `resolve.py` 第二节）。
 * 纯函数、无网络、不抛异常。
 */
object DouyinLinks {

    /** 分享文案里的 http(s) 链接：排除空白、中文与成对/句读标点（与 Python 正则一致）。 */
    private val URL_PATTERN = Regex("""https?://[^\s\u4e00-\u9fff"'<>，。！？、；：）】」]+""")

    /** 作品 ID 的四种常见写法（短链跳转后的最终 URL 同样适用）。 */
    private val ID_PATTERNS = listOf(
        Regex("""(?:iesdouyin|douyin)\.com/share/(?:video|note|slides)/(\d{6,25})""", RegexOption.IGNORE_CASE),
        Regex("""(?:iesdouyin|douyin)\.com/(?:video|note|slides)/(\d{6,25})""", RegexOption.IGNORE_CASE),
        Regex("""[?&](?:modal_id|aweme_id|item_ids)=(\d{6,25})""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(\d{6,25})\s*$"""),
    )

    /** 需要先跟随 302 才能拿到作品 ID 的短链域名。 */
    private val SHORT_LINK_HOSTS = listOf("v.douyin.com", "v.iesdouyin.com", "iesdouyin.com")

    /** 链接尾部可能粘上的句读/包裹标点（分享文案常见）。 */
    private const val URL_TRAILING_JUNK = ".,;:!?)]}>'\""

    /**
     * 从整段分享文案里提取第一条 http(s) 链接。
     * 例：「7.68 复制打开抖音，看看【作者的作品】... https://v.douyin.com/xxxx/ 」→ `https://v.douyin.com/xxxx/`
     */
    fun extractUrl(text: String): String? {
        if (text.isEmpty()) return null
        val match = URL_PATTERN.find(text) ?: return null
        val url = match.value.trimEnd { it in URL_TRAILING_JUNK }
        return url.ifEmpty { null }
    }

    /** 从链接、文案或纯数字里提取作品 ID（aweme_id）。 */
    fun extractAwemeId(text: String): String? {
        if (text.isEmpty()) return null
        for (pattern in ID_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** 是否 `v.douyin.com` 之类的短链（需要先跟随跳转）。 */
    fun isShortLink(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (host.isEmpty()) return false
        return SHORT_LINK_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * 从文本里猜作品类型：note/slides → 图文，video → 视频。
     * 只作为「先试哪个 kind」的提示；最终类型以数据里 images 是否为空为准。
     */
    fun kindHint(text: String): String? {
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        if (listOf("/note/", "/share/note/", "/slides/", "/share/slides/").any { it in lower }) return "note"
        if (listOf("/video/", "/share/video/").any { it in lower }) return "video"
        return null
    }
}