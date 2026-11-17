package com.ZenithLiteAura.app.nezo.platform.download

import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import com.clipnest.core.download.MediaSniffer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

/**
 * 单文件流式下载器。
 *
 * - 双套请求头：优先 iPhone UA + `Referer: https://www.iesdouyin.com/`，失败换桌面 UA + `Referer: https://www.douyin.com/`；
 * - 每套头只试 1 次（合计 2 次），手动跟随重定向（最多 5 跳）；
 * - 先用响应头 512 字节做魔术字节嗅探定扩展名，再整体流式写出，**不把文件读进内存**；
 * - `Accept-Encoding: identity`：避免 gzip 让进度/长度失真。
 */
internal class Downloader {

    /** 已打开的连接：头部 512B 已读出用于嗅探，可继续流式读取余下内容。 */
    internal class Opened(
        val url: String,
        val connection: HttpURLConnection,
        val input: InputStream,
        val contentLength: Long,
        val head: ByteArray,
        val extension: String,
        val contentType: String,
    ) {
        fun closeQuietly() {
            runCatching { input.close() }
            runCatching { connection.disconnect() }
        }
    }

    /** 落到临时文件的结果（ZIP 用 STORED，需要 size + crc） */
    internal class SavedFile(
        val extension: String,
        val size: Long,
        val crc: Long,
        val contentType: String,
    )

    private enum class Profile(val label: String, val userAgent: String, val referer: String) {
        IPHONE(
            "iPhone",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
            "https://www.iesdouyin.com/",
        ),
        DESKTOP(
            "桌面",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "https://www.douyin.com/",
        ),
        BILIBILI(
            "B站",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "https://www.bilibili.com/",
        ),
        XHS(
            "小红书",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "https://www.xiaohongshu.com/",
        ),
        KUAISHOU(
            "快手",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "https://www.kuaishou.com/",
        ),
    }

    /** 按媒体 URL 的 host 选请求头顺序：各家 CDN 对 Referer 的挑剔程度不一样（B站没有 Referer 直接 403）。 */
    private fun profilesFor(url: String): List<Profile> {
        val u = url.lowercase()
        val primary = when {
            "bilivideo" in u || "bilibili" in u || "hdslb" in u || "akamaized" in u ||
                "mcdn" in u || "szbdyd" in u || "biliapi" in u -> listOf(Profile.BILIBILI, Profile.DESKTOP, Profile.IPHONE)

            "xhscdn" in u || "xiaohongshu" in u || "xhslink" in u -> listOf(Profile.XHS, Profile.DESKTOP, Profile.IPHONE)
            "kuaishou" in u || "kwaicdn" in u || "kspkg" in u -> listOf(Profile.KUAISHOU, Profile.DESKTOP, Profile.IPHONE)
            else -> listOf(Profile.IPHONE, Profile.DESKTOP)
        }
        // 兜底：仍失败就把所有请求头按序再试一遍（最多 5 次），避免 CDN 换域名后直接 403
        return (primary + Profile.entries).distinct()
    }

    companion object {
        private const val HEAD_SIZE = 512
        private const val BUFFER_SIZE = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
    }

    /** 打开媒体连接（含嗅探头）；两次都失败时抛最后一次异常。 */
    fun open(url: String): Opened {
        var lastError: Exception? = null
        runCatching { PlatformLog.info("下载媒体 host=${java.net.URI(url).host}") }
        for (profile in profilesFor(url)) {
            try {
                return openWith(url, profile)
            } catch (e: Exception) {
                lastError = e
                PlatformLog.warn("媒体打开失败（${profile.label}请求头）：${e.message}")
            }
        }
        throw lastError ?: IOException("无法打开媒体地址")
    }

    private fun openWith(rawUrl: String, profile: Profile): Opened {
        var currentUrl = rawUrl
        var redirects = 0

        while (true) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as? HttpURLConnection
                ?: throw IOException("不支持的地址：$currentUrl")

            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", profile.userAgent)
            conn.setRequestProperty("Referer", profile.referer)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            conn.setRequestProperty("Accept-Encoding", "identity")

            val code = conn.responseCode

            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                runCatching { conn.disconnect() }
                if (location.isNullOrBlank() || redirects >= MAX_REDIRECTS) {
                    throw IOException("重定向失败（HTTP $code）")
                }
                currentUrl = if (location.startsWith("http", ignoreCase = true)) {
                    location
                } else {
                    URL(url, location).toString()
                }
                redirects++
                continue
            }

            if (code !in 200..299) {
                runCatching { conn.disconnect() }
                throw IOException("HTTP $code")
            }

            val contentType = conn.contentType.orEmpty()
            if (contentType.contains("text/html", ignoreCase = true)) {
                runCatching { conn.disconnect() }
                throw IOException("返回的是网页而不是媒体文件")
            }

            val input = conn.inputStream
            val headBuffer = ByteArray(HEAD_SIZE)
            var headLength = 0
            while (headLength < HEAD_SIZE) {
                val read = input.read(headBuffer, headLength, HEAD_SIZE - headLength)
                if (read <= 0) break
                headLength += read
            }
            if (headLength <= 0) {
                runCatching { input.close() }
                runCatching { conn.disconnect() }
                throw IOException("响应内容为空")
            }

            val head = headBuffer.copyOf(headLength)
            val sniffed = runCatching { MediaSniffer.sniff(head) }.getOrDefault("unknown")
            val extension = when {
                sniffed.isNotBlank() && sniffed.lowercase() != "unknown" ->
                    sniffed.trimStart('.').lowercase()

                else -> DownloadSupport.extensionOfContentType(contentType)
                    .ifBlank { DownloadSupport.extensionOfUrl(currentUrl) }
            }
            if (sniffed.isBlank() || sniffed.lowercase() == "unknown") {
                PlatformLog.warn("嗅探未识别（contentType=$contentType），回落扩展名：$extension")
            }

            return Opened(
                url = currentUrl,
                connection = conn,
                input = input,
                contentLength = conn.contentLengthLong,
                head = head,
                extension = extension,
                contentType = contentType,
            )
        }
    }

    /**
     * 把已读头部的响应流式写入 out。
     * @param onDelta 每次回调「本次新增字节数」（调用方自己累加，便于算速度）
     */
    fun copyTo(
        opened: Opened,
        out: OutputStream,
        onDelta: (Long) -> Unit,
        isCanceled: () -> Boolean,
    ): Long {
        var total = 0L

        if (opened.head.isNotEmpty()) {
            out.write(opened.head)
            total += opened.head.size
            onDelta(opened.head.size.toLong())
        }

        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            if (isCanceled()) throw CancellationException("下载已取消")
            val read = try {
                opened.input.read(buffer)
            } catch (e: IOException) {
                if (isCanceled()) throw CancellationException("下载已取消")
                throw e
            }
            if (read < 0) break
            if (read == 0) continue
            out.write(buffer, 0, read)
            total += read
            onDelta(read.toLong())
        }

        out.flush()
        return total
    }

    /** 下载到临时文件（ZIP 条目前置步骤）：流式写盘并计算 CRC32，返回扩展名/大小/CRC。 */
    fun downloadToFile(
        url: String,
        dest: File,
        onDelta: (Long) -> Unit,
        isCanceled: () -> Boolean,
    ): SavedFile {
        dest.parentFile?.runCatching { mkdirs() }
        val opened = open(url)
        try {
            val crc = CRC32()
            FileOutputStream(dest, false).use { fileOut ->
                val out = object : OutputStream() {
                    override fun write(b: Int) {
                        crc.update(b)
                        fileOut.write(b)
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        crc.update(b, off, len)
                        fileOut.write(b, off, len)
                    }

                    override fun flush() {
                        fileOut.flush()
                    }
                }
                copyTo(opened, out, onDelta, isCanceled)
            }
            return SavedFile(
                extension = opened.extension,
                size = dest.length(),
                crc = crc.value,
                contentType = opened.contentType,
            )
        } catch (t: Throwable) {
            runCatching { dest.delete() }
            throw t
        } finally {
            opened.closeQuietly()
        }
    }
}