package com.ZenithLiteAura.app.nezo.platform

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import com.ZenithLiteAura.app.nezo.platform.shizuku.ShizukuClipClient
import com.ZenithLiteAura.app.nezo.platform.update.UpdateChecker
import com.ZenithLiteAura.app.nezo.platform.update.UpdateState
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 平台层门面（MainActivity 只接这个对象）。
 * 所有方法都不抛异常：失败只记日志 / Toast，保证 UI 侧不用 try-catch。
 */
object PlatformBridge {

    private const val PERMISSION_POLL_MS = 500L
    private const val PERMISSION_TIMEOUT_MS = 10_000L

    // ── 服务 / 监听 ───────────────────────────────────────────────────────

    /** 按当前 settings 启停前台服务（服务内部再决定悬浮球） */
    fun syncServices(context: Context) {
        runCatching {
            ensureRepo(context)
            val settings = ClipRepo.settings.value
            if (!settings.autostartService) {
                MonitorService.stop(context)
            } else if (AppVisibility.isForeground) {
                // Android 12+ 后台不允许启动前台服务（系统抛 mAllowStartForeground=false）；
                // 只在前台主动拉起，开机与自恢复分别由 BootReceiver / START_STICKY 负责。
                MonitorService.start(context)
            } else {
                PlatformLog.info("应用在后台，跳过主动启动前台服务")
            }
        }.onFailure { PlatformLog.error("同步前台服务失败", it) }
    }

    /** Activity.onStart 调用：开始应用内监听（通路 A） */
    fun startMonitor(activity: Activity) {
        runCatching {
            ensureRepo(activity)
            ClipMonitor.start(activity)
        }.onFailure { PlatformLog.error("启动应用内监听失败", it) }
    }

    /**
     * Activity.onWindowFocusChanged(true) 调用：
     * 这是 Android 10+ 真正「允许读剪贴板」的时刻，补抓一次（带重试，防焦点晚到）。
     */
    fun onWindowFocused(activity: Activity) {
        runCatching {
            ensureRepo(activity)
            ClipMonitor.catchUp(activity.applicationContext, attempts = 4)
        }.onFailure { PlatformLog.error("焦点补抓失败", it) }
    }

    /** Activity.onStop 调用：停止应用内监听 */
    fun stopMonitor() {
        runCatching { ClipMonitor.stop() }.onFailure { PlatformLog.warn("停止应用内监听失败：${it.message}") }
    }

    fun isServiceRunning(context: Context): Boolean = runCatching {
        MonitorService.isRunning(context)
    }.getOrDefault(false)

    // ── 权限 ──────────────────────────────────────────────────────────────

    fun isOverlayGranted(context: Context): Boolean = PermissionHelper.hasOverlay(context)

    fun isNotificationGranted(context: Context): Boolean = PermissionHelper.hasNotification(context)

    /** 是否具备「真·后台监听」的系统权限（root 装成 priv-app 才有） */
    fun hasBackgroundClipboardPermission(context: Context): Boolean =
        BackgroundClipMonitor.hasPermission(context)

    /**
     * 申请悬浮窗权限，授权回来后按当前设置重启服务（服务会重新挂上悬浮球）。
     * 用户中途放弃也会回调 false，不会悬挂。
     */
    fun requestOverlayAndStart(activity: Activity, onResult: (Boolean) -> Unit) {
        if (isOverlayGranted(activity)) {
            runCatching { syncServices(activity) }
            onResult(true)
            return
        }

        if (!PermissionHelper.requestOverlay(activity)) {
            toast(activity, "没有找到悬浮窗设置页，请到系统设置里手动授权")
            onResult(false)
            return
        }

        awaitPermission(activity, { ctx -> PermissionHelper.hasOverlay(ctx) }) { granted ->
            if (granted) runCatching { syncServices(activity) }
            else toast(activity, "未授予悬浮窗权限，悬浮球无法显示")
            onResult(granted)
        }
    }

    /** 申请通知权限（13+ 弹系统框，更低版本跳设置页） */
    fun requestNotification(activity: Activity, onResult: (Boolean) -> Unit) {
        if (isNotificationGranted(activity)) {
            onResult(true)
            return
        }

        if (!PermissionHelper.requestNotification(activity)) {
            onResult(false)
            return
        }

        awaitPermission(activity, { ctx -> PermissionHelper.hasNotification(ctx) }) { granted ->
            if (!granted) toast(activity, "通知未开启，收录与服务状态将不可见")
            onResult(granted)
        }
    }

    /** 只打开悬浮窗授权页（UiActions.requestBubblePermission 用） */
    fun openOverlaySettings(activity: Activity) {
        runCatching { PermissionHelper.requestOverlay(activity) }
    }

    /** 跳系统通知设置页 */
    fun openNotificationSettings(activity: Activity) {
        runCatching { PermissionHelper.openNotificationSettings(activity) }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean =
        PermissionHelper.batteryOptimizationIgnored(context)

    /** 申请电池白名单（避免后台服务被回收） */
    fun requestIgnoreBatteryOptimization(activity: Activity) {
        val ok = runCatching { PermissionHelper.requestIgnoreBatteryOptimization(activity) }.getOrDefault(false)
        if (!ok) toast(activity, "没有找到电池优化设置页，请手动把Nezo加入白名单")
    }

    /** 厂商自启动白名单引导（国产 ROM 必需） */
    fun openAutoStartSettings(context: Context) {
        val ok = runCatching { PermissionHelper.openAutoStartSettings(context) }.getOrDefault(false)
        if (!ok) toast(context, "没有找到自启动管理页，请在系统设置-应用-自启动中手动允许")
    }

    // ── Shizuku 真·后台监听 ───────────────────────────────────────────────

    /**
     * 设置页显示的 Shizuku 状态文案（同时刷新内部状态）：
     * 「未安装 Shizuku」/「Shizuku 未运行…」/「未授权（点此授权）」/「正在连接…」/「已连接 · 离开Nezo也能收录」
     */
    fun shizukuStatus(context: Context): String = runCatching {
        ShizukuClipClient.statusText(context)
    }.getOrDefault("Shizuku 不可用")

    /**
     * 打开 Shizuku 后台监听：未授权会拉起 Shizuku 自己的授权框，已授权则直接绑定 UserService。
     * @return 是否已发起（不代表最终连通；最终状态用 [shizukuStatus] 看）
     */
    fun shizukuEnable(activity: Activity): Boolean = runCatching {
        ShizukuClipClient.enable(activity)
    }.onFailure { PlatformLog.warn("开启 Shizuku 后台监听失败：${it.message}") }.getOrDefault(false)

    /** 关闭 Shizuku 后台监听（解绑 shell 进程里的监听器，设置写回 false） */
    fun shizukuDisable() {
        runCatching { ShizukuClipClient.disable() }
            .onFailure { PlatformLog.warn("关闭 Shizuku 后台监听失败：${it.message}") }
    }

    fun shizukuConnected(): Boolean = runCatching { ShizukuClipClient.isConnected() }.getOrDefault(false)

    /** 响应式状态文案：设置页可直接 `collectAsState()`，连上/断开时自动刷新 */
    val shizukuStatusFlow: StateFlow<String>
        get() = ShizukuClipClient.statusTextFlow

    /** 打开 Shizuku 官网下载页（设置页「未安装」时用） */
    fun openShizukuWebsite(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { PlatformLog.warn("打开 Shizuku 官网失败：${it.message}") }
    }

    // ── 检查更新（GitHub Releases） ───────────────────────────────────────

    /** 最近一次检查结果：UI 直接 `collectAsState()` 拿到 checking/hasUpdate/latestVersion/error */
    val updateStateFlow: StateFlow<UpdateState>
        get() = UpdateChecker.state

    /** 当前 App 版本（= BuildConfig.VERSION_NAME） */
    fun currentVersion(): String = runCatching { UpdateChecker.currentVersion() }.getOrDefault("")

    /**
     * 检查更新。
     * @param manual true = 用户主动点（忽略 24h 缓存）；false = 启动时自动检查
     * 网络请求在 IO 线程，失败只写 `UpdateState.error`，绝不抛。
     */
    fun checkForUpdates(context: Context, manual: Boolean = true) {
        runCatching { UpdateChecker.check(context.applicationContext, manual) }
            .onFailure { PlatformLog.warn("触发检查更新失败：${it.message}") }
    }

    /** 打开 Release 页（没有具体 Release 时打开仓库 releases 列表） */
    fun openReleasePage(context: Context) {
        runCatching {
            val url = UpdateChecker.state.value.releaseUrl.ifBlank { UpdateChecker.REPO_RELEASES_URL }
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { PlatformLog.warn("打开 Release 页失败：${it.message}") }
    }

    // ── 导出 ──────────────────────────────────────────────────────────────

    /**
     * 导出一段文本：写到缓存目录，用 FileProvider 生成可读 Uri，再拉起系统分享面板
     * （用户可存到「文件」、发给自己、存网盘等，等价于 SAF 导出且不需要结果回调）。
     */
    fun exportText(context: Context, content: String, mimeName: String) {
        runCatching {
            if (content.isBlank()) {
                toast(context, "没有可导出的内容")
                return
            }

            val mime = mimeName.ifBlank { "text/plain" }
            val extension = when (mime.lowercase()) {
                "text/markdown", "text/x-markdown" -> "md"
                "text/csv", "text/comma-separated-values" -> "csv"
                "application/json" -> "json"
                else -> "txt"
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val fileName = "Nezo-$stamp.$extension"

            val dir = File(context.cacheDir, "export").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)

            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

            val share = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                putExtra(Intent.EXTRA_TEXT, content.take(4000))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(share, "导出Nezo收藏").apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            PlatformLog.info("导出：$fileName（${content.length} 字符）")
        }.onFailure {
            PlatformLog.error("导出失败", it)
            toast(context, "导出失败：${it.message}")
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────

    private fun ensureRepo(context: Context) {
        runCatching { ClipRepo.init(context.applicationContext) }
    }

    /**
     * 权限申请是系统弹窗（不保证触发 Activity 生命周期回调），
     * 所以用短轮询观察真实权限状态，最多等 10 秒。
     */
    private fun awaitPermission(
        activity: Activity,
        checker: (Context) -> Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val startAt = SystemClock.elapsedRealtime()

        val task = object : Runnable {
            override fun run() {
                val granted = runCatching { checker(activity) }.getOrDefault(false)
                val elapsed = SystemClock.elapsedRealtime() - startAt
                if (granted || elapsed >= PERMISSION_TIMEOUT_MS) {
                    runCatching { onResult(granted) }
                    return
                }
                handler.postDelayed(this, PERMISSION_POLL_MS)
            }
        }

        handler.postDelayed(task, PERMISSION_POLL_MS)
    }

    private fun toast(context: Context, message: String) {
        runCatching {
            val app = context.applicationContext as? Application
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(app ?: context, message, Toast.LENGTH_SHORT).show() }
            }
        }
    }
}