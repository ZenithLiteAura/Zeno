package com.clipnest.core

/**
 * 文本与链接规范化（与 Windows 原型 C# TextUtil 对齐）。
 * 纯函数、无状态、不抛异常。
 */
object TextUtil {

    private val WHITESPACE_RUN = Regex("\\s+")

    /** 整段丢弃的跟踪参数名（抖音/小红书/微博分享链接上的常见噪声） */
    private val TRACKING_PARAMS = setOf(
        "spm", "spm_id_from", "from", "from_source", "share_source", "share_medium",
        "share_plat", "share_session_id", "share_tag", "share_from", "share_token",
        "is_from_webapp", "sender_device", "web_id", "vd_source", "refer", "ref",
        "referrer", "channel", "source", "timestamp", "unique_k",
    )

    /** 链接两端常见的包裹字符（分享文案会带上） */
    private val URL_WRAP = charArrayOf(
        '<', '>', '"', '\'', '「', '」', '“', '”', '（', '）', '【', '】',
    )

    /** 链接尾部常见的标点（不属于链接） */
    private val URL_TAIL_JUNK = charArrayOf(
        '，', '。', '；', '！', '？', '、', '）', '】', '》', '」', '”', '…',
        ',', ';', '!', '?', ')', ']', '}', '"', '\'',
    )

    /** 去掉零宽字符/不可见符、统一换行、全角空格归一、trim。 */
    fun normalize(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            i++
            when {
                c == '\r' -> {
                    // \r\n 与单独的 \r 都统一成 \n
                    if (i < raw.length && raw[i] == '\n') i++
                    sb.append('\n')
                }
                c == '\u3000' || c == '\u00A0' -> sb.append(' ')
                isInvisible(c) -> Unit
                c.code < 0x20 && c != '\n' && c != '\t' -> Unit
                c == '\u007F' -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString().trim()
    }

    /**
     * 链接规范化：去不可见符、去包裹标点、去 utm_ 前缀与 spm / share_ 等跟踪参数、
     * host 小写、去 fragment；保留路径大小写与末尾斜杠（抖音短链靠它）。
     * 解析不出来时返回清理后的原文。
     */
    fun normalizeUrl(url: String?): String {
        var s = normalize(url)
        if (s.isEmpty()) return ""
        s = s.trim(*URL_WRAP)
        s = s.trimEnd(*URL_TAIL_JUNK)
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            return s
        }
        return try {
            val uri = java.net.URI(s)
            val scheme = uri.scheme?.lowercase() ?: return s
            val host = uri.host?.lowercase() ?: return s
            val path = uri.rawPath ?: ""
            val query = filterQuery(uri.rawQuery)
            val port = if (uri.port > 0 && uri.port != defaultPort(scheme)) ":${uri.port}" else ""
            buildString {
                append(scheme).append("://").append(host)
                append(port)
                append(path)
                if (query.isNotEmpty()) append('?').append(query)
            }
        } catch (_: Exception) {
            s
        }
    }

    /**
     * 单行字段清理：去不可见符、压空白、超长截断加省略号（与 C# CleanField 一致）。
     *
     * 截断按 **code point 边界**回退：emoji 是代理对，直接按 UTF-16 char 切会切出
     * 半个字符（显示成 ），所以发现切点前一位是高位代理时回退一位。
     */
    fun compact(s: String?, max: Int = 240): String {
        if (s.isNullOrEmpty()) return ""
        if (max <= 0) return ""
        val v = normalize(s).replace(WHITESPACE_RUN, " ").trim()
        if (v.isEmpty()) return ""
        if (v.length <= max) return v

        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(v[end - 1])) end--
        val head = v.substring(0, end).trimEnd()
        return head + "…"
    }

    private fun filterQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrEmpty()) return ""
        return rawQuery.split('&')
            .filter { part ->
                if (part.isBlank()) return@filter false
                val key = part.substringBefore('=').lowercase()
                key.isNotEmpty() && key !in TRACKING_PARAMS && !key.startsWith("utm_")
            }
            .joinToString("&")
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "http") 80 else 443

    /** 零宽 / 方向控制 / 软连字符等不可见字符 */
    private fun isInvisible(c: Char): Boolean =
        c == '\u00AD' || c == '\u180E' ||
            c in '\u200B'..'\u200F' ||
            c in '\u202A'..'\u202E' ||
            c in '\u2060'..'\u2064' ||
            c in '\u2066'..'\u2069' ||
            c == '\uFEFF'
}