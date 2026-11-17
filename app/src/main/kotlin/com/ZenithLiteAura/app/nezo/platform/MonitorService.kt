package com.ZenithLiteAura.app.nezo.platform

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ZenithLiteAura.app.nezo.MainActivity
import com.ZenithLiteAura.app.nezo.R
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import com.ZenithLiteAura.app.nezo.platform.shizuku.ShizukuClipClient
import com.clipnest.core.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：常驻通知（状态 + 一键收录 + 暂停/继续 + 打开Nezo），并负责保活悬浮球。
 *
 * 注意：Android 10+ 后台不允许读剪贴板，所以这个服务本身不读剪贴板，
 * 它提供的是「入口」：通知按钮/悬浮球 → 拉起 QuickCaptureActivity 抢一次前台再读。
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_START = "com.ZenithLiteAura.app.nezo.action.START"
        const val ACTION_STOP = "com.ZenithLiteAura.app.nezo.action.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.ZenithLiteAura.app.nezo.action.TOGGLE_PAUSE"

        private const val NOTIFICATION_ID = 1001
        private const val REQ_OPEN = 1101
        private const val REQ_CAPTURE = 1102
        private const val REQ_TOGGLE = 1103
        private const val REQ_RESTART = 1104

        @Volatile
        private var alive = false

        /** 启动（已在前台服务中时等于再发一次指令，用于刷新状态） */
        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java).setAction(ACTION_START)
            // minSdk 26：一律走 startForegroundService；失败（后台启动被拒）只记日志
            runCatching { context.startForegroundService(intent) }
                .onFailure { PlatformLog.warn("启动监听服务失败：${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MonitorService::class.java)) }
                .onFailure { PlatformLog.warn("停止监听服务失败：${it.message}") }
        }

        fun isRunning(context: Context): Boolean {
            if (alive) return true
            return runCatching {
                @Suppress("DEPRECATION")
                val manager = context.getSystemService(ActivityManager::class.java) ?: return@runCatching false
                @Suppress("DEPRECATION")
                manager.getRunningServices(Int.MAX_VALUE).any {
                    it.service.className == MonitorService::class.java.name
                }
            }.getOrDefault(false)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var settingsJob: Job? = null
    private var bubble: FloatingBubble? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        runCatching { ClipRepo.init(applicationContext) }
        Notifier.ensureChannels(this)

        val foregroundOk = startForegroundCompat(buildNotification())

        // 前台服务没起来（后台启动被系统拒绝 / specialUse 声明异常）时主动收尾，
        // 否则系统会在 5 秒后以「did not then call Service.startForeground()」崩掉进程。
        if (!foregroundOk) {
            PlatformLog.error("监听服务未能进入前台，主动停止避免系统崩溃")
            alive = false
            stopSelf()
            return
        }

        settingsJob = scope.launch {
            ClipRepo.settings.drop(1).collect {
                runCatching { updateNotification() }
                runCatching { syncBubble() }
                runCatching { ShizukuClipClient.sync(applicationContext) }
            }
        }
        runCatching { syncBubble() }

        // 有系统权限（root 装成 priv-app）时，后台也能真监听；没有就什么都不做
        runCatching { BackgroundClipMonitor.start(this) }

        // Shizuku 真·后台监听：设置里开了且已装/已授权才连；否则只记录状态，应用内监听照旧
        runCatching { ShizukuClipClient.init(applicationContext) }
        runCatching { ShizukuClipClient.sync(applicationContext) }

        PlatformLog.info("前台服务已启动（后台剪贴板权限=${BackgroundClipMonitor.hasPermission(this)}）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                // ACTION_START / 系统重启（intent 为 null）都刷新一次状态
                runCatching { startForegroundCompat(buildNotification()) }
                runCatching { updateNotification() }
                runCatching { syncBubble() }
            }
        }
        return START_STICKY
    }

    /**
     * 用户从最近任务里划掉应用：HyperOS 可能顺手把进程也清了。
     * 这里挂一个 1.5s 后的 AlarmManager 自恢复（用 getForegroundService 直起前台服务，
     * 避免 Android 12+ 后台启动前台服务被拒）。用户在设置里「强行停止」则系统会取消该闹钟，属系统限制。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val settings = settingsSnapshot()
        PlatformLog.info("任务被移除：autostartService=${settings.autostartService}")
        if (settings.autostartService) scheduleSelfRestart()
    }

    private fun scheduleSelfRestart() {
        runCatching {
            val alarm = getSystemService(AlarmManager::class.java) ?: return@runCatching
            val intent = Intent(this, MonitorService::class.java).setAction(ACTION_START)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pending = PendingIntent.getForegroundService(this, REQ_RESTART, intent, flags)
            val triggerAt = System.currentTimeMillis() + 1500L
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            PlatformLog.info("已安排 1.5s 后自恢复前台服务")
        }.onFailure { PlatformLog.warn("安排自恢复失败：${it.message}") }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false
        runCatching { BackgroundClipMonitor.stop() }
        runCatching { ShizukuClipClient.stop() }
        settingsJob?.cancel()
        settingsJob = null
        runCatching { bubble?.hide() }
        bubble = null
        scope.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        PlatformLog.info("前台服务已停止")
        super.onDestroy()
    }

    // ── 通知 ──────────────────────────────────────────────────────────────

    private fun startForegroundCompat(notification: Notification): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.onFailure { PlatformLog.error("startForeground 失败", it) }.getOrDefault(false)
    }

    private fun updateNotification() {
        runCatching { Notifier.safeNotify(this, NOTIFICATION_ID, buildNotification()) }
            .onFailure { PlatformLog.warn("刷新服务通知失败：${it.message}") }
    }

    private fun settingsSnapshot(): AppSettings =
        runCatching { ClipRepo.settings.value }.getOrDefault(AppSettings())

    private fun buildNotification(): Notification {
        val settings = settingsSnapshot()
        val monitoring = settings.monitor

        val title = if (monitoring) "Nezo正在监听剪贴板" else "Nezo已暂停监听"
        val text = if (monitoring) {
            "复制抖音/小红书等分享链接会自动收录；也可用系统分享或悬浮球一键收录"
        } else {
            "自动收录已暂停，点「继续」恢复监听"
        }

        val openIntent = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val captureIntent = PendingIntent.getActivity(
            this,
            REQ_CAPTURE,
            Intent(this, QuickCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleIntent = PendingIntent.getService(
            this,
            REQ_TOGGLE,
            Intent(this, MonitorService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, Notifier.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_clipnest)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    text + "\n\n系统限制：Android 10 起，应用只有在「前台」时才能读剪贴板，" +
                        "所以离开Nezo后请用「分享到Nezo」或桌面悬浮球收录。"
                )
            )
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stat_clipnest, "一键收录", captureIntent)
            .addAction(R.drawable.ic_stat_clipnest, if (monitoring) "暂停" else "继续", toggleIntent)
            .addAction(R.drawable.ic_stat_clipnest, "打开Nezo", openIntent)
            .build()
    }

    // ── 状态变化 ──────────────────────────────────────────────────────────

    private fun togglePause() {
        runCatching {
            val next = ClipRepo.updateSettings { it.copy(monitor = !it.monitor) }
            PlatformLog.info("通知栏切换监听：monitor=${next.monitor}")
        }.onFailure { PlatformLog.error("切换监听失败", it) }

        runCatching { updateNotification() }
    }

    /** 悬浮球跟随 settings.bubble + 悬浮窗权限 */
    private fun syncBubble() {
        val settings = settingsSnapshot()
        val wanted = settings.bubble && PermissionHelper.hasOverlay(this)

        if (!wanted) {
            bubble?.hide()
            bubble = null
            return
        }

        if (bubble?.isShowing == true) return

        val created = FloatingBubble(
            context = this,
            onTap = { launchQuickCapture() },
            onLongPress = { launchMain() },
        )
        bubble = if (created.show()) created else null
    }

    private fun launchQuickCapture() {
        runCatching {
            startActivity(
                Intent(this, QuickCaptureActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                }
            )
        }.onFailure { PlatformLog.warn("悬浮球拉起一键收录失败：${it.message}") }
    }

    private fun launchMain() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }.onFailure { PlatformLog.warn("悬浮球打开主界面失败：${it.message}") }
    }
}