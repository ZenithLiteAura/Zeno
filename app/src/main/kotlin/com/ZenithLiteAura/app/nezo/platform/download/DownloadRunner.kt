package com.ZenithLiteAura.app.nezo.platform.download

import android.content.Context
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import com.clipnest.core.download.DownloadNaming
import com.clipnest.core.download.ZipNamingRule
import com.clipnest.core.download.ZipPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 单个下载任务的执行器（在 IO 线程跑）：
 * - image_zip：全部图片 → `Download/Nezo/image_batch_*.zip`
 * - video_zip：视频 → `Download/Nezo/video_batch_*.zip`
 * - image / video：单文件直接进相册/视频库
 *
 * 失败一律抛异常，由 [DownloadQueue] 收敛成 `state=failed + error`。
 */
internal object DownloadRunner {

    suspend fun run(context: Context, job: DownloadJob) = withContext(Dispatchers.IO) {
        val reporter = ProgressReporter(job, DownloadQueue.get(context))
        when (job.kind) {
            DownloadKind.IMAGE_ZIP -> runImageZip(context, job, reporter)
            DownloadKind.VIDEO_ZIP -> runVideoZip(context, job, reporter)
            DownloadKind.IMAGE -> runSingleImage(context, job, reporter)
            DownloadKind.VIDEO -> runSingleVideo(context, job, reporter)
        }
        reporter.flush()
    }

    // ── 图片 ZIP ──────────────────────────────────────────────────────────

    private suspend fun runImageZip(context: Context, job: DownloadJob, reporter: ProgressReporter) {
        if (job.pics.isEmpty()) throw IOException("这个作品没有图片")

        val title = DownloadNaming.sanitizeTitle(job.title.ifBlank { "抖音图文" })
        val rule = ZipNamingRule.of(job.rule)
        val entries = toEntries(ZipPlan.entries(job.pics, "", title, rule))
        if (entries.isEmpty()) throw IOException("图片列表为空")

        job.total = entries.size
        val zipName = ZipPlan.zipFileName("image", title)
        PlatformLog.info("开始打包图片 ZIP：$zipName（${entries.size} 张）")

        val sink = MediaSaver.createZip(context, zipName)
        try {
            ZipWriter.write(
                context = context,
                output = sink.output,
                entries = entries,
                downloader = Downloader(),
                onEntryDone = { reporter.fileDone() },
                onDelta = { reporter.addBytes(it) },
                isCanceled = { job.cancelRequested },
            )
            job.savedUri = sink.commit()
        } catch (t: Throwable) {
            sink.abort()
            throw t
        }
    }

    // ── 视频 ZIP ──────────────────────────────────────────────────────────

    private suspend fun runVideoZip(context: Context, job: DownloadJob, reporter: ProgressReporter) {
        if (job.videoUrl.isBlank()) throw IOException("这个作品没有可下载的视频地址")

        val title = DownloadNaming.sanitizeTitle(job.title.ifBlank { "抖音视频" })
        val rule = ZipNamingRule.of(job.rule)
        val entries = toEntries(ZipPlan.entries(emptyList(), job.videoUrl, title, rule))
        if (entries.isEmpty()) throw IOException("视频地址为空")

        job.total = entries.size
        val zipName = ZipPlan.zipFileName("video", title)
        PlatformLog.info("开始打包视频 ZIP：$zipName")

        val sink = MediaSaver.createZip(context, zipName)
        try {
            ZipWriter.write(
                context = context,
                output = sink.output,
                entries = entries,
                downloader = Downloader(),
                onEntryDone = { reporter.fileDone() },
                onDelta = { reporter.addBytes(it) },
                isCanceled = { job.cancelRequested },
            )
            job.savedUri = sink.commit()
        } catch (t: Throwable) {
            sink.abort()
            throw t
        }
    }

    // ── 单张图片 ──────────────────────────────────────────────────────────

    private fun runSingleImage(context: Context, job: DownloadJob, reporter: ProgressReporter) {
        val url = job.pics.firstOrNull() ?: throw IOException("没有图片地址")
        val title = DownloadNaming.sanitizeTitle(job.title.ifBlank { "抖音图文" })
        val rule = ZipNamingRule.of(job.rule)

        val downloader = Downloader()
        val opened = downloader.open(url)
        try {
            val extension = opened.extension.ifBlank { DownloadSupport.extensionOfUrl(url) }
            val name = DownloadNaming.fileName(rule, job.seq, title, "HD", extension)
            job.total = 1

            val sink = MediaSaver.createImage(context, title, name, DownloadSupport.mimeOf(extension))
            try {
                downloader.copyTo(
                    opened = opened,
                    out = sink.output,
                    onDelta = { reporter.addBytes(it) },
                    isCanceled = { job.cancelRequested },
                )
                job.savedUri = sink.commit()
                reporter.fileDone()
            } catch (t: Throwable) {
                sink.abort()
                throw t
            }
        } finally {
            opened.closeQuietly()
        }
    }

    // ── 单个视频 ──────────────────────────────────────────────────────────

    private fun runSingleVideo(context: Context, job: DownloadJob, reporter: ProgressReporter) {
        val url = job.videoUrl
        if (url.isBlank()) throw IOException("没有视频地址")
        val title = DownloadNaming.sanitizeTitle(job.title.ifBlank { "抖音视频" })
        val rule = ZipNamingRule.of(job.rule)
        val quality = job.videoQuality.ifBlank { "HD" }

        val downloader = Downloader()
        val opened = downloader.open(url)
        try {
            val extension = opened.extension.ifBlank { DownloadSupport.extensionOfUrl(url) }
            val name = DownloadNaming.fileName(rule, 1, title, quality, extension)
            job.total = 1

            val sink = MediaSaver.createVideo(context, name, DownloadSupport.mimeOf(extension))
            try {
                downloader.copyTo(
                    opened = opened,
                    out = sink.output,
                    onDelta = { reporter.addBytes(it) },
                    isCanceled = { job.cancelRequested },
                )
                job.savedUri = sink.commit()
                reporter.fileDone()
            } catch (t: Throwable) {
                sink.abort()
                throw t
            }
        } finally {
            opened.closeQuietly()
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    /** core 给的是 (ZIP 内路径, 源 URL)；这里做一次兜底识别，顺序写反也能跑 */
    private fun toEntries(pairs: List<Pair<String, String>>): List<ZipWriter.Entry> =
        pairs.mapNotNull { (first, second) ->
            val firstIsUrl = first.startsWith("http", ignoreCase = true)
            val secondIsUrl = second.startsWith("http", ignoreCase = true)
            when {
                secondIsUrl && !firstIsUrl -> ZipWriter.Entry(zipPath = first, url = second)
                firstIsUrl && !secondIsUrl -> ZipWriter.Entry(zipPath = second, url = first)
                else -> null   // 两个都是 URL / 都不是 URL：数据异常，跳过该条目
            }
        }

    /** 进度汇总：字节数多线程累加，写入秒级限流，避免刷爆 StateFlow */
    private class ProgressReporter(
        private val job: DownloadJob,
        private val queue: DownloadQueue,
    ) {
        private val bytes = AtomicLong(0L)
        private val files = AtomicLong(0L)
        private val startedAt = System.currentTimeMillis()
        private var lastPublishAt = 0L

        fun addBytes(delta: Long) {
            if (delta <= 0) return
            val total = bytes.addAndGet(delta)
            job.bytes = total
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            job.speedPerSec = total * 1000L / elapsed
            publishIfDue()
        }

        fun fileDone() {
            job.done = files.incrementAndGet().toInt()
            publishIfDue(force = true)
        }

        fun flush() {
            publishIfDue(force = true)
        }

        private fun publishIfDue(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPublishAt < 500L) return
            lastPublishAt = now
            queue.progressChanged(job)
        }
    }
}