package com.ZenithLiteAura.app.nezo.platform.download

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 文件落盘：
 * - Android 10+（API 29+）：MediaStore + `IS_PENDING`，图片 `Pictures/ClipNest/<标题>`、视频 `Movies/ClipNest`、ZIP `Download/ClipNest`；
 * - Android 8.0–9（API 26–28）：有 `WRITE_EXTERNAL_STORAGE` 就写公有目录并 `MediaScanner` 让相册可见；
 *   没权限则落 App 私有目录（`clipnest/downloads/`）并返回 FileProvider uri，可分享/打开。
 */
internal object MediaSaver {

    internal interface Sink {
        val output: OutputStream
        /** 成功：关闭流、让文件在系统「相册/文件」可见，返回可用于「打开」的 uri 字符串 */
        fun commit(): String
        /** 失败/取消：关闭流并清掉半成品 */
        fun abort()
    }

    private enum class Target { IMAGE, VIDEO, ZIP }

    fun createImage(context: Context, titleDir: String, displayName: String, mime: String): Sink =
        create(context, Target.IMAGE, titleDir, displayName, mime)

    fun createVideo(context: Context, displayName: String, mime: String): Sink =
        create(context, Target.VIDEO, "", displayName, mime)

    fun createZip(context: Context, displayName: String): Sink =
        create(context, Target.ZIP, "", displayName, "application/zip")

    private fun create(context: Context, target: Target, titleDir: String, displayName: String, mime: String): Sink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            modernSink(context, target, titleDir, displayName, mime)
        } else {
            legacySink(context, target, titleDir, displayName, mime)
        }

    // ── Android 10+ ───────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("Recycle")   // 输出流由 Sink.commit()/abort() 关闭，lint 看不到这条路径
    private fun modernSink(context: Context, target: Target, titleDir: String, displayName: String, mime: String): Sink {
        val resolver = context.contentResolver
        val collection = when (target) {
            Target.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Target.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Target.ZIP -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = when (target) {
            Target.IMAGE -> "Pictures/ClipNest/" + titleDir.ifBlank { "未命名" }
            Target.VIDEO -> "Movies/ClipNest"
            Target.ZIP -> "Download/ClipNest"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: throw IOException("媒体库创建文件失败（存储空间不足？）")
        val stream = resolver.openOutputStream(uri) ?: run {
            runCatching { resolver.delete(uri, null, null) }
            throw IOException("媒体库打开输出流失败")
        }

        return object : Sink {
            private var closed = false

            override val output: OutputStream = stream

            override fun commit(): String {
                if (closed) return uri.toString()
                closed = true
                runCatching { output.flush() }
                runCatching { output.close() }
                runCatching {
                    val clear = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(uri, clear, null, null)
                }
                PlatformLog.info("已写入媒体库：$relativePath/$displayName")
                return uri.toString()
            }

            override fun abort() {
                if (closed) return
                closed = true
                runCatching { output.close() }
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    // ── Android 8.0–9 ─────────────────────────────────────────────────────

    private fun legacySink(context: Context, target: Target, titleDir: String, displayName: String, mime: String): Sink {
        val hasWrite = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

        val publicFile = if (hasWrite) runCatching { publicFile(target, titleDir, displayName) }.getOrNull() else null
        val file = publicFile ?: privateFile(context, target, titleDir, displayName)
        val isPublic = publicFile != null

        file.parentFile?.runCatching { mkdirs() }
        val stream = FileOutputStream(file)

        return object : Sink {
            private var closed = false

            override val output: OutputStream = stream

            override fun commit(): String {
                if (closed) return ""
                closed = true
                runCatching { output.flush() }
                runCatching { output.close() }

                if (isPublic) {
                    runCatching {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.absolutePath),
                            arrayOf(mime),
                            null,
                        )
                    }
                }

                PlatformLog.info("已保存到${if (isPublic) "公有目录" else "应用私有目录"}：${file.absolutePath}")
                return fileProviderUri(context, file) ?: Uri.fromFile(file).toString()
            }

            override fun abort() {
                if (closed) return
                closed = true
                runCatching { output.close() }
                runCatching { file.delete() }
            }
        }
    }

    private fun publicFile(target: Target, titleDir: String, name: String): File {
        val root = when (target) {
            Target.IMAGE -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            Target.VIDEO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            Target.ZIP -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        val base = File(root, "ClipNest")
        val dir = if (target == Target.IMAGE) File(base, titleDir.ifBlank { "未命名" }) else base
        return File(dir, name)
    }

    private fun privateFile(context: Context, target: Target, titleDir: String, name: String): File {
        val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "clipnest/downloads")
        val dir = when (target) {
            Target.IMAGE -> File(File(base, "images"), titleDir.ifBlank { "未命名" })
            Target.VIDEO -> File(base, "videos")
            Target.ZIP -> File(base, "zip")
        }
        return File(dir, name)
    }

    private fun fileProviderUri(context: Context, file: File): String? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
    }.onFailure {
        PlatformLog.warn("FileProvider 取 uri 失败：${it.message}")
    }.getOrNull()
}