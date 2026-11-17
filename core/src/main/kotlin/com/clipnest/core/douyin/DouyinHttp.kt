package com.clipnest.core.douyin

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 抖音 HTTP 客户端（对应 Python `requests.Session` 的用法）。
 *
 * - 基于 [HttpURLConnection]，**每个实例一个 [CookieManager]**：分享页要靠首次响应种下的
 *   `ttwid` cookie，后续请求才会返回带作品数据的 SSR（实测关键点）。
 * - 手动跟随重定向（而不是用 HttpURLConnection 自带的），这样每一跳的 `Set-Cookie`
 *   都能收进 CookieManager；短链跟随最终 URL 也能拿到。
 * - [probeSize] 用 `Range: bytes=0-0` 只取 1 字节，从 `Content-Range` 里读总大小，不下载媒体本体。
 *
 * 网络异常统一抛 [IOException]（由调用方收敛成结构化错误），本类内部不吞异常。
 */
class DouyinHttp {

    data class Response(
        val statusCode: Int,
        val finalUrl: String,
        val body: String,
        val headers: Map<String, List<String>>,
    )

    data class Probe(val size: Long?, val alive: Boolean)

    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    /** GET 请求（可跟随重定向）。 */
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15_000,
        followRedirects: Boolean = true,
    ): Response {
        val raw = execute(url, headers, timeoutMs, followRedirects, readBody = true)
        return Response(
            statusCode = raw.statusCode,
            finalUrl = raw.finalUrl,
            body = String(raw.bodyBytes, Charsets.UTF_8),
            headers = raw.headers,
        )
    }

    /**
     * 探测媒体直链大小（不下载内容）。
     * 返回 `alive=false` 表示链接不可用（4xx/5xx/网络异常）；`size=null` 表示可用但拿不到长度。
     */
    fun probeSize(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15_000,
        range: String = "bytes=0-0",
    ): Probe = try {
        val raw = execute(url, headers + ("Range" to range), timeoutMs, followRedirects = true, readBody = false)
        if (raw.statusCode == 200 || raw.statusCode == 206) {
            val total = headerValue(raw.headers, "Content-Range")
                ?.let { CONTENT_RANGE_TOTAL.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
            if (total != null) {
                Probe(total, true)
            } else {
                val contentLength = headerValue(raw.headers, "Content-Length")?.trim()?.toLongOrNull()
                if (raw.statusCode == 200 && contentLength != null) Probe(contentLength, true) else Probe(null, true)
            }
        } else {
            Probe(null, false)
        }
    } catch (_: Exception) {
        Probe(null, false)
    }

    // ── 内部实现 ─────────────────────────────────────────────────

    private class RawResponse(
        val statusCode: Int,
        val finalUrl: String,
        val bodyBytes: ByteArray,
        val headers: Map<String, List<String>>,
    )

    private fun execute(
        rawUrl: String,
        headers: Map<String, String>,
        timeoutMs: Int,
        followRedirects: Boolean,
        readBody: Boolean,
    ): RawResponse {
        var currentUrl = rawUrl
        var hops = 0
        while (true) {
            val connection = openConnection(currentUrl, headers, timeoutMs)
            try {
                val status = connection.responseCode
                val responseHeaders = normalizeHeaders(connection.headerFields)
                storeCookies(currentUrl, responseHeaders)

                val location = headerValue(responseHeaders, "Location")
                if (followRedirects && status in REDIRECT_CODES && !location.isNullOrBlank()) {
                    connection.disconnect()
                    hops++
                    if (hops > MAX_REDIRECTS) throw IOException("重定向次数过多（>$MAX_REDIRECTS）")
                    currentUrl = resolveLocation(currentUrl, location)
                    continue
                }

                val body = if (readBody) readBodyBytes(connection, status) else ByteArray(0)
                connection.disconnect()
                return RawResponse(status, currentUrl, body, responseHeaders)
            } catch (error: IOException) {
                connection.disconnect()
                throw error
            } catch (error: RuntimeException) {
                connection.disconnect()
                throw error
            }
        }
    }

    private fun openConnection(url: String, headers: Map<String, String>, timeoutMs: Int): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = timeoutMs.coerceAtLeast(1)
        connection.readTimeout = timeoutMs.coerceAtLeast(1)
        // 不主动要压缩：HttpURLConnection 不会自动解压，统一要 identity 最稳
        connection.setRequestProperty("Accept-Encoding", "identity")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            cookieHeader(url)?.let { connection.setRequestProperty("Cookie", it) }
        }
        return connection
    }

    private fun readBodyBytes(connection: HttpURLConnection, status: Int): ByteArray {
        val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
        if (stream == null) return ByteArray(0)
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (total < MAX_BODY_BYTES) {
                val read = input.read(buffer, 0, minOf(buffer.size, MAX_BODY_BYTES - total))
                if (read <= 0) break
                out.write(buffer, 0, read)
                total += read
            }
            out.toByteArray()
        }
    }

    private fun cookieHeader(url: String): String? = try {
        cookieManager.get(URI(url), emptyMap())["Cookie"]
            ?.filter { it.isNotBlank() }
            ?.joinToString("; ")
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun storeCookies(url: String, headers: Map<String, List<String>>) {
        try {
            val setCookies = headers.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
            if (setCookies.isEmpty()) return
            cookieManager.put(URI(url), mapOf("Set-Cookie" to setCookies))
        } catch (_: Exception) {
            // cookie 解析失败不影响本次请求
        }
    }

    private fun normalizeHeaders(fields: Map<String?, MutableList<String>>?): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        fields?.forEach { (name, values) -> if (name != null) result[name] = values }
        return result
    }

    private fun headerValue(headers: Map<String, List<String>>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    private fun resolveLocation(currentUrl: String, location: String): String = try {
        URL(URL(currentUrl), location.trim()).toString()
    } catch (_: Exception) {
        location.trim()
    }

    private companion object {
        const val MAX_REDIRECTS = 10
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val CONTENT_RANGE_TOTAL = Regex("""/(\d+)\s*$""")
    }
}