package com.ZenithLiteAura.app.nezo.platform.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import javax.net.ssl.SSLException

/** 下载模块自己的「可直接展示给用户」的错误（error 文案原样进 DownloadTaskUi.error） */
internal class DownloadError(message: String) : Exception(message)

/** 下载模块的零散工具：路径、原子写、网络判断、扩展名/MIME、人类可读的单位与错误文案。 */
internal object DownloadSupport {

    private const val DIR_NAME = "nezo"

    fun dataDir(context: Context): File = File(context.filesDir, DIR_NAME).apply { runCatching { mkdirs() } }

    fun queueFile(context: Context): File = File(dataDir(context), "downloads.json")

    fun autoDownloadedFile(context: Context): File = File(dataDir(context), "auto_downloaded.json")

    /** 临时目录（ZIP 打包时按条目落盘，避免整文件进内存） */
    fun tempDir(context: Context): File =
        File(context.cacheDir, "download_tmp").apply { runCatching { mkdirs() } }

    fun writeAtomic(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { PlatformLog.warn("写文件失败（${file.name}）：${it.message}") }
    }

    fun readText(file: File): String? = runCatching {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    }.getOrNull()

    // ── 网络 ──────────────────────────────────────────────────────────────

    fun hasInternet(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    fun isWifiConnected(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    // ── 扩展名 / MIME ─────────────────────────────────────────────────────

    /** 从 URL 取扩展名（小写、不带点）；拿不到返回 "bin" */
    fun extensionOfUrl(url: String): String {
        val path = runCatching { url.substringBefore('?').substringBefore('#') }.getOrDefault(url)
        val segment = path.substringAfterLast('/')
        val ext = segment.substringAfterLast('.', "")
        return ext.lowercase(Locale.US).takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } } ?: "bin"
    }

    /** 嗅探失败时用 Content-Type 兜底 */
    fun extensionOfContentType(contentType: String?): String = when {
        contentType == null -> ""
        contentType.contains("jpeg", true) -> "jpg"
        contentType.contains("png", true) -> "png"
        contentType.contains("webp", true) -> "webp"
        contentType.contains("gif", true) -> "gif"
        contentType.contains("avif", true) -> "avif"
        contentType.contains("heic", true) || contentType.contains("heif", true) -> "heic"
        contentType.contains("mp4", true) -> "mp4"
        contentType.contains("quicktime", true) -> "mov"
        else -> ""
    }

    fun mimeOf(extension: String): String = when (extension.lowercase(Locale.US)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "heic", "heif" -> "image/heic"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    fun isVideoMime(mime: String): Boolean = mime.startsWith("video/")

    /** ZIP 内的条目名补齐扩展名（core 的 ZipPlan 可能不给扩展名） */
    fun ensureExtension(name: String, extension: String): String {
        if (name.isBlank()) return "file.$extension"
        val lastSegment = name.substringAfterLast('/')
        return if (lastSegment.contains('.')) name else "$name.$extension"
    }

    /** 从 URI/文件名猜 MIME（「打开」用） */
    fun mimeOfName(nameOrUri: String): String {
        val clean = nameOrUri.substringBefore('?').substringBefore('#')
        val ext = clean.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "zip" -> "application/zip"
            "mp4", "mov", "m4v" -> "video/$ext"
            "jpg", "jpeg", "png", "gif", "webp", "avif", "heic" -> "image/$ext"
            else -> "*/*"
        }
    }

    // ── 人类可读 ──────────────────────────────────────────────────────────

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        return when {
            kb < 1024 -> String.format(Locale.US, "%.0f KB", kb)
            kb < 1024 * 1024 -> String.format(Locale.US, "%.1f MB", kb / 1024)
            else -> String.format(Locale.US, "%.2f GB", kb / 1024 / 1024)
        }
    }

    fun formatSpeed(bytesPerSec: Long): String = formatSize(bytesPerSec) + "/s"

    /** 把异常收敛成一句用户能懂的中文（失败任务会显示在下载页） */
    fun errorMessage(t: Throwable): String = when (t) {
        is DownloadError -> t.message ?: "下载失败，请重试"
        is UnknownHostException -> "网络不可用，请检查网络后重试"
        is SocketTimeoutException -> "网络超时，请稍后重试"
        is SSLException -> "安全连接失败，请稍后重试"
        is java.io.FileNotFoundException -> "文件写入失败（存储空间不足？）"
        is java.io.IOException -> t.message?.takeIf { it.isNotBlank() }?.let { "下载失败：$it" } ?: "下载失败，请重试"
        else -> t.message?.takeIf { it.isNotBlank() }?.let { "下载失败：$it" } ?: "下载失败，请重试"
    }
}