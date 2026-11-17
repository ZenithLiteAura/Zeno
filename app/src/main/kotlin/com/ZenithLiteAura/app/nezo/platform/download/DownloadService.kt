package com.ZenithLiteAura.app.nezo.platform.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载前台服务（`dataSync`）：
 * 拉起后串行执行队列里的任务，进度写进常驻通知（`X/Y · 速度`），带「取消」按钮；
 * 任务全部跑完 + 空闲一小会儿后自己 `stopSelf()`。
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_RUN = "com.ZenithLiteAura.app.nezo.download.action.RUN"
        const val ACTION_CANCEL = "com.ZenithLiteAura.app.nezo.download.action.CANCEL"
        const val ACTION_RETRY = "com.ZenithLiteAura.app.nezo.download.action.RETRY"
        const val EXTRA_TASK_ID = "com.ZenithLiteAura.app.nezo.download.extra.TASK_ID"

        private const val IDLE_WAIT_MS = 1200L
        private const val PROGRESS_THROTTLE_MS = 400L

        @Volatile
        private var alive = false

        /** 启动/唤醒下载服务（队列里有活就干活）。后台启动被拒时只记日志，任务留在队列里等下次。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadService::class.java).setAction(ACTION_RUN),
                )
            }.onFailure { PlatformLog.warn("启动下载服务失败：${it.message}") }
        }

        fun isRunning(): Boolean = alive
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var drainJob: Job? = null
    private var observeJob: Job? = null

    private val queue: DownloadQueue get() = DownloadQueue.get(applicationContext)

    private var lastNotifyAt = 0L

    override fun onCreate() {
        super.onCreate()
        alive = true
        DownloadNotifier.ensureChannels(this)

        val started = startForegroundCompat(
            DownloadNotifier.buildProgressNotification(this, null, queue.queuedCount()),
        )
        if (!started) {
            // 没进前台就会被系统 5 秒后判死，主动收尾
            alive = false
            PlatformLog.error("下载服务未能进入前台，主动停止")
            stopSelf()
            return
        }
        PlatformLog.info("下载服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val running = queue.runningJob()
                queue.cancelRunning()
                PlatformLog.info("通知栏请求取消：${running?.id ?: "(无运行任务)"}")
            }

            ACTION_RETRY -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (!taskId.isNullOrBlank()) {
                    val ok = queue.retry(taskId)
                    PlatformLog.info("通知栏请求重试：$taskId ok=$ok")
                }
            }

            else -> Unit
        }

        ensureRunning()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false
        observeJob?.cancel()
        drainJob?.cancel()
        scope.cancel()
        PlatformLog.info("下载服务已销毁")
        super.onDestroy()
    }

    // ── 主循环 ────────────────────────────────────────────────────────────

    private fun ensureRunning() {
        if (!alive) return   // onCreate 里没能进前台：不硬跑（任务留在队列，下次前台再补）

        startForegroundCompat(DownloadNotifier.buildProgressNotification(this, queue.runningJob(), queue.queuedCount()))

        if (drainJob?.isActive == true) return

        observeJob?.cancel()
        observeJob = scope.launch {
            // StateFlow 本身已合并重复值，直接 collect 即可（conflate() 用在 StateFlow 上已废弃）
            queue.tasks.collect { updateProgressNotification(it.isNotEmpty()) }
        }

        drainJob = scope.launch {
            try {
                while (true) {
                    queue.drain()
                    if (queue.hasPending()) {
                        delay(80)                // 防止 drain 被别的协程占着时忙等
                        continue
                    }
                    delay(IDLE_WAIT_MS)          // 空闲观察窗：避免入队瞬间刚好收尾
                    if (queue.hasPending()) continue
                    break
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) PlatformLog.error("下载队列执行异常", t)
            }
            // 服务可能在执行期间被销毁，收尾失败无需惊动用户
            try {
                finishAndStop()
            } catch (_: Throwable) {
                // 已销毁/协程被取消：忽略
            }
        }
    }

    private fun updateProgressNotification(hasTasks: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < PROGRESS_THROTTLE_MS) return
        lastNotifyAt = now
        if (!hasTasks && !queue.hasPending()) return

        runCatching {
            startForegroundCompat(
                DownloadNotifier.buildProgressNotification(this, queue.runningJob(), queue.queuedCount()),
            )
        }
    }

    private suspend fun finishAndStop() {
        withContext(Dispatchers.Main) {
            observeJob?.cancel()
            observeJob = null
            alive = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { DownloadNotifier.cancelProgress(this@DownloadService) }
            stopSelf()
            PlatformLog.info("下载服务已停止（队列空闲）")
        }
    }

    private fun startForegroundCompat(notification: android.app.Notification): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DownloadNotifier.PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(DownloadNotifier.PROGRESS_NOTIFICATION_ID, notification)
        }
        true
    }.onFailure { PlatformLog.error("下载服务 startForeground 失败", it) }.getOrDefault(false)
}