package com.ZenithLiteAura.app.nezo.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.ZenithLiteAura.app.nezo.MainActivity
import com.ZenithLiteAura.app.nezo.R
import com.clipnest.core.ClipItem

/**
 * 通知渠道与「收录成功」提示。
 * 前台服务的常驻通知由 MonitorService 自己构建（要带按钮与状态）。
 */
object Notifier {

    const val CHANNEL_SERVICE = "clipnest.service"
    const val CHANNEL_CAPTURE = "clipnest.capture"
    const val CHANNEL_UPDATE = "clipnest.update"

    private const val REQ_OPEN = 2001
    private const val REQ_UPDATE = 2002
    private const val UPDATE_NOTIFICATION_ID = 3101

    fun ensureChannels(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "监听服务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "常驻的剪贴板监听服务（含一键收录按钮与悬浮球）"
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CAPTURE, "收录提示", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "成功收录分享链接时的提醒"
                    setShowBadge(true)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_UPDATE, "更新提示", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "发现 Nezo 新版本时提醒（点击打开 Release 页）"
                    setShowBadge(true)
                }
            )
        }
    }

    /** 系统通知开关 + 通知权限是否可用 */
    fun canNotify(context: Context): Boolean = runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(false)

    /** 打开主界面（各处复用的 PendingIntent） */
    fun openAppPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_OPEN,
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 收录成功提示（应用在前台时由 UI 自己提示，不调用这个） */
    fun notifyCaptured(context: Context, item: ClipItem) {
        if (!canNotify(context)) return

        ensureChannels(context)

        val title = "已收录 · ${item.platformName.ifBlank { "链接" }}"
        val body = listOf(item.author, item.title)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { item.url }

        runCatching {
            val notification = NotificationCompat.Builder(context, CHANNEL_CAPTURE)
                .setSmallIcon(R.drawable.ic_stat_clipnest)
                .setContentTitle(title)
                .setContentText(body.take(80))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(openAppPendingIntent(context))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build()
            safeNotify(context, captureId(item), notification)
        }.onFailure { PlatformLog.warn("收录通知发送失败：${it.message}") }
    }

    /** 发现新版本提示：标题「Nezo 有新版本 vX」，点击打开 Release 页 */
    fun notifyUpdate(context: Context, version: String, releaseUrl: String) {
        if (!canNotify(context)) {
            PlatformLog.info("通知未开启，跳过新版本通知（v$version）")
            return
        }

        ensureChannels(context)

        val url = releaseUrl.ifBlank { "https://github.com/ZenithLiteAura/Zeno/releases" }
        runCatching {
            val pendingIntent = PendingIntent.getActivity(
                context,
                REQ_UPDATE,
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_UPDATE)
                .setSmallIcon(R.drawable.ic_stat_clipnest)
                .setContentTitle("Nezo 有新版本 v$version")
                .setContentText("点此查看更新内容并下载")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            safeNotify(context, UPDATE_NOTIFICATION_ID, notification)
        }.onFailure { PlatformLog.warn("新版本通知发送失败：${it.message}") }
    }

    /**
     * 通知发送统一入口：先确认「通知开关 + 13+ 运行时权限」都满足再发。
     * （lint 的 MissingPermission 无法跨方法分析，所以这里显式声明并已自行校验）
     */
    @SuppressLint("MissingPermission")
    fun safeNotify(context: Context, id: Int, notification: Notification) {
        if (!canNotify(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure { PlatformLog.warn("通知发送失败：${it.message}") }
    }

    private fun captureId(item: ClipItem): Int = if (item.id.isEmpty()) 3001 else item.id.hashCode()
}