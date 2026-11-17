package com.clipnest.core.platform

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/** 平台解析共用的小工具：URL 识别、JS 内嵌 JSON 提取、总预算控制。 */
internal object PlatformLinks {

    /** 分享文案里的 http(s) 链接（排除空白、中文与句读标点，与抖音那套一致）。 */
    private val URL_PATTERN = Regex("""https?://[^\s\u4e00-\u9fff"'<>，。！？、；：）】」]+""")

    private const val URL_TRAILING_JUNK = ".,;:!?)]}>'\""

    fun extractUrl(text: String): String? {
        if (text.isEmpty()) return null
        val match = URL_PATTERN.find(text) ?: return null
        val url = match.value.trimEnd { it in URL_TRAILING_JUNK }
        return url.ifEmpty { null }
    }

    fun host(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** host 命中给定域名（含子域）。 */
    fun matchesHost(url: String, vararg domains: String): Boolean {
        val actual = host(url) ?: return false
        return domains.any { actual == it || actual.endsWith(".$it") }
    }

    /** http → https（平台图片/封面经常给 http）。 */
    fun toHttps(url: String?): String {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return ""
        return if (value.startsWith("http://", ignoreCase = true)) "https://" + value.substring(7) else value
    }
}

/** 从 HTML 里提取 `window.__XXX__` 这类内嵌 JSON（花括号配对扫描，跳过字符串/转义）。 */
internal object ScriptJson {

    fun extractAfter(html: String, marker: String): String? {
        if (html.isEmpty()) return null
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return null
        val start = html.indexOf('{', markerIndex)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until html.length) {
            val ch = html[index]
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
                        if (depth == 0) return html.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * 把 JS 页面里的 `undefined` 字面量换成 `null`（小红书 `__INITIAL_STATE__` 常见）。
     * 只替换字符串外的独立标识符，不会破坏正文里的 "undefined" 文本。
     */
    fun normalizeJsLiterals(raw: String): String {
        val builder = StringBuilder(raw.length)
        var index = 0
        var inString = false
        var escaped = false
        while (index < raw.length) {
            val ch = raw[index]
            if (inString) {
                builder.append(ch)
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                index++
                continue
            }
            if (ch == '"') {
                inString = true
                builder.append(ch)
                index++
                continue
            }
            if (raw.startsWith("undefined", index) &&
                !isIdentifierChar(raw.getOrNull(index - 1)) &&
                !isIdentifierChar(raw.getOrNull(index + 9))
            ) {
                builder.append("null")
                index += 9
                continue
            }
            builder.append(ch)
            index++
        }
        return builder.toString()
    }

    fun parseObject(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        val normalized = normalizeJsLiterals(raw)
        val parsed = runCatching { JsonParser.parseString(normalized) }.getOrNull() ?: return null
        return if (parsed.isJsonObject) parsed.asJsonObject else null
    }

    private fun isIdentifierChar(ch: Char?): Boolean =
        ch != null && (ch.isLetterOrDigit() || ch == '_' || ch == '$')
}

/** 单个平台解析的总预算控制（默认 60s，最小 1s）。 */
internal class ResolveDeadline(budgetMs: Long) {

    private val budget = when {
        budgetMs <= 0L -> 60_000L
        budgetMs < 1_000L -> 1_000L
        else -> budgetMs
    }
    private val deadlineNanos = System.nanoTime() + budget * 1_000_000L

    fun remainingMs(): Long = (deadlineNanos - System.nanoTime()) / 1_000_000L

    fun expired(): Boolean = remainingMs() <= 0L

    /** 单次请求超时：剩余预算留 1s 余量，夹在 [3s, maxMs]。 */
    fun requestTimeoutMs(maxMs: Int = 15_000): Int {
        val left = remainingMs() - 1_000L
        return left.coerceIn(3_000L, maxMs.toLong()).toInt()
    }
}