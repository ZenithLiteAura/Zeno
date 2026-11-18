package com.ZenithLiteAura.app.nezo.platform.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.ZenithLiteAura.app.nezo.MainActivity
import com.ZenithLiteAura.app.nezo.R
import com.ZenithLiteAura.app.nezo.platform.Notifier

/**
 * 下载通知：
 * - 进度通知挂在「下载」渠道（低优先级、常驻、带取消）；
 * - 完成/失败走另一个渠道（默认优先级，会响），完成可点开文件、失败可一键重试。
 */
internal object DownloadNotifier {

    const val CHANNEL_PROGRESS = "nezo.download"
    const val CHANNEL_RESULT = "nezo.download.result"

    const val PROGRESS_NOTIFICATION_ID = 4101

    private const val REQ_CANCEL = 4201
    private const val REQ_OPEN_APP = 4202
    private const val REQ_RESULT_BASE = 4400

    fun ensureChannels(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "下载进度", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示正在下载/打包的任务进度"
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, "下载结果", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "下载完成或失败时提醒"
                    setShowBadge(true)
                }
            )
        }
    }

    /**
     * 前台服务用的常驻进度通知。
     * @param job 正在跑的任务（null = 还在排队/准备）
     */
    fun buildProgressNotification(context: Context, job: DownloadJob?, queued: Int): Notification {
        val title = when {
            job != null -> "正在下载 · ${job.title.ifBlank { "抖音作品" }.take(24)}"
            queued > 0 -> "排队中（$queued 个任务）"
            else -> "准备下载…"
        }

        val text = when {
            job != null -> {
                val done = job.done.coerceAtLeast(0)
                val total = job.total.coerceAtLeast(0)
                val countPart = when (job.kind) {
                    DownloadKind.IMAGE_ZIP, DownloadKind.IMAGE -> "$done/$total 张"
                    else -> if (done >= 1) "1/1" else "0/1"
                }
                val speed = DownloadSupport.formatSpeed(job.speedPerSec)
                val size = DownloadSupport.formatSize(job.bytes)
                "$countPart · $speed · $size"
            }

            queued > 0 -> "点「取消」可清空队列"
            else -> ""
        }

        val openAppIntent = PendingIntent.getActivity(
            context,
            REQ_OPEN_APP,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            pendingFlags(),
        )

        val cancelIntent = PendingIntent.getService(
            context,
            REQ_CANCEL,
            Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL),
            pendingFlags(),
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_clipnest)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stat_clipnest, "取消", cancelIntent)

        if (job != null && job.total > 0) {
            builder.setProgress(job.total, job.done.coerceIn(0, job.total), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    /** 任务收尾（完成/失败）时的结果通知；取消不发通知 */
    fun notifyResult(context: Context, job: DownloadJob) {
        when (job.state) {
            DownloadState.DONE -> notifyDone(context, job)
            DownloadState.FAILED -> notifyFailed(context, job)
            else -> Unit
        }
    }

    fun cancelProgress(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID)
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun notifyDone(context: Context, job: DownloadJob) {
        val title = job.title.ifBlank { "抖音作品" }
        val target = openSavedIntent(context, job) ?: openAppIntent(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_clipnest)
            .setContentTitle("下载完成")
            .setContentText("$title 已保存")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title 已保存，点这里打开。"))
            .setContentIntent(target)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        Notifier.safeNotify(context, resultId(job, 1), notification)
    }

    private fun notifyFailed(context: Context, job: DownloadJob) {
        val title = job.title.ifBlank { "抖音作品" }
        val reason = job.error.ifBlank { "下载失败，请重试" }
        val retryIntent = PendingIntent.getService(
            context,
            REQ_RESULT_BASE + (job.id.hashCode() and 0x0FFF),
            Intent(context, DownloadService::class.java)
                .setAction(DownloadService.ACTION_RETRY)
                .putExtra(DownloadService.EXTRA_TASK_ID, job.id),
            pendingFlags(),
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_clipnest)
            .setContentTitle("下载失败")
            .setContentText("$title：$reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$reason"))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .addAction(R.drawable.ic_stat_clipnest, "重试", retryIntent)

        // 兜底：失败时给一个「用浏览器打开原链接」，用户至少还能自己保存
        originalLink(job)?.let { link ->
            runCatching {
                val view = Intent(Intent.ACTION_VIEW, link.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.addAction(
                    R.drawable.ic_stat_clipnest,
                    "浏览器打开",
                    PendingIntent.getActivity(
                        context,
                        REQ_RESULT_BASE + 500 + (job.id.hashCode() and 0x0FFF),
                        view,
                        pendingFlags(),
                    ),
                )
            }
        }

        Notifier.safeNotify(context, resultId(job, 2), builder.build())
    }

    /** 作品原链接（失败兜底用）：图文走 note，视频走 video */
    private fun originalLink(job: DownloadJob): String? {
        if (job.awemeId.isBlank()) return null
        val path = when (job.kind) {
            DownloadKind.IMAGE_ZIP, DownloadKind.IMAGE -> "note"
            else -> "video"
        }
        return "https://www.douyin.com/$path/${job.awemeId}"
    }

    private fun openSavedIntent(context: Context, job: DownloadJob): PendingIntent? {
        if (job.savedUri.isBlank()) return null
        return runCatching {
            val uri = job.savedUri.toUri()
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DownloadSupport.mimeOfName(job.savedUri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            PendingIntent.getActivity(context, REQ_RESULT_BASE + 1 + (job.id.hashCode() and 0x0FFF), intent, pendingFlags())
        }.getOrNull()
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_OPEN_APP,
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        pendingFlags(),
    )

    private fun resultId(job: DownloadJob, kind: Int): Int =
        5000 + kind * 1000 + (job.id.hashCode() and 0x03FF)

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}