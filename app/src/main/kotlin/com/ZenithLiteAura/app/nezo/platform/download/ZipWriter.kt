package com.ZenithLiteAura.app.nezo.platform.download

import android.content.Context
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZIP 打包：直接写 MediaStore 输出流，条目一律 STORED（不压缩，图片/视频本来已压缩）。
 *
 * STORED 要求写入前就知道 size + crc，所以先把每个条目流式下载到临时文件（顺便算 CRC32），
 * 再顺序写进 zip —— 全程不把文件整体读进内存（单文件超长也只占磁盘）。
 * 图片最多 3 路并发下载，ZIP 条目顺序仍与规划顺序一致。
 */
internal object ZipWriter {

    private const val IMAGE_CONCURRENCY = 3

    /**
     * @param entry.zipPath core `ZipPlan.entries` 给的 ZIP 内完整路径（含文件夹前缀与占位扩展名）
     * @param entry.url     源地址
     */
    internal data class Entry(val zipPath: String, val url: String)

    internal suspend fun write(
        context: Context,
        output: OutputStream,
        entries: List<Entry>,
        downloader: Downloader,
        onEntryDone: () -> Unit,
        onDelta: (Long) -> Unit,
        isCanceled: () -> Boolean,
    ) {
        if (entries.isEmpty()) throw IOException("没有可下载的条目")

        val tempDir = File(DownloadSupport.tempDir(context), "zip_" + System.nanoTime()).apply { mkdirs() }
        try {
            val results = arrayOfNulls<Downloader.SavedFile>(entries.size)

            // ① 下载到临时目录（最多 3 路并发）
            coroutineScope {
                val semaphore = Semaphore(IMAGE_CONCURRENCY)
                entries.mapIndexed { index, entry ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            if (isCanceled()) throw CancellationException("下载已取消")
                            val dest = File(tempDir, "entry_$index.bin")
                            val saved = downloader.downloadToFile(entry.url, dest, onDelta, isCanceled)
                            results[index] = saved
                            onEntryDone()
                        }
                    }
                }.forEach { it.await() }
            }

            // ② 按原顺序写 ZIP（STORED）
            val zip = ZipOutputStream(BufferedOutputStream(output, 64 * 1024))
            zip.setMethod(ZipOutputStream.STORED)
            val buffer = ByteArray(64 * 1024)

            for ((index, entry) in entries.withIndex()) {
                if (isCanceled()) throw CancellationException("下载已取消")

                val saved = results[index] ?: throw IOException("条目下载失败")
                val extension = saved.extension.ifBlank { DownloadSupport.extensionOfUrl(entry.url) }
                val entryName = replaceExtension(entry.zipPath, extension)

                val zipEntry = ZipEntry(entryName).apply {
                    method = ZipOutputStream.STORED
                    size = saved.size
                    crc = saved.crc
                    time = System.currentTimeMillis()
                }
                zip.putNextEntry(zipEntry)

                val tempFile = File(tempDir, "entry_$index.bin")
                tempFile.inputStream().use { input ->
                    while (true) {
                        if (isCanceled()) throw CancellationException("下载已取消")
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }

            zip.finish()
            zip.flush()
            PlatformLog.info("ZIP 打包完成：${entries.size} 个条目")
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
    }

    /**
     * core 的 `ZipPlan.entries` 用 `.jpg` / `.mp4` 占位扩展名，
     * 这里按嗅探结果**替换**最后一个扩展名（`_1` 之类的去重后缀在扩展名之前，不会被破坏）。
     */
    internal fun replaceExtension(path: String, extension: String): String {
        if (extension.isBlank()) return path
        val slash = path.lastIndexOf('/')
        val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
        val name = if (slash >= 0) path.substring(slash + 1) else path
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        return dir + base + "." + extension
    }
}