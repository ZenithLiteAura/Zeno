package com.ZenithLiteAura.app.nezo.platform.shizuku

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import com.ZenithLiteAura.app.nezo.platform.BackgroundClipMonitor
import com.ZenithLiteAura.app.nezo.platform.CaptureEngine
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * 应用进程侧的 Shizuku 客户端：负责「探测 → 授权 → 绑定 UserService → 接收剪贴板回调」。
 *
 * 设计原则：**任何一步失败都不允许影响 App 主流程**。
 * - 未安装 / 未运行 / 未授权 / Android 17 上的官方 Shizuku bug 都只落一条中文状态 + 日志；
 * - 连接断开有上限重试（2 次），彻底失败就保持现状，应用内监听（ClipMonitor）照旧工作；
 * - 回调来自 Binder 线程，一律切主线程再采集。
 */
object ShizukuClipClient {

    // ── 常量 ──────────────────────────────────────────────────────────────

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 0x5A17
    private const val USER_SERVICE_VERSION = 2
    private const val USER_SERVICE_SUFFIX = "clipwatcher"
    private const val RETRY_LIMIT = 2
    private const val RETRY_DELAY_MS = 3000L

    /** Android 17（API 37）上官方 Shizuku 13.6.0 有「授权列表为空」的已知 bug */
    private const val ANDROID_17_API = 37

    enum class State {
        NOT_INSTALLED,
        NOT_RUNNING,
        NO_PERMISSION,
        CONNECTING,
        CONNECTED,

        /** Shizuku 以 root（uid=0）启动：能连上、但系统不会把剪贴板内容给 root 进程 */
        ROOT_MODE_UNSUPPORTED,

        /** 可用但连不上（Android 17 官方 Shizuku bug 等） */
        UNAVAILABLE,
        ERROR,
    }

    data class Status(val state: State, val message: String) {
        val connected: Boolean get() = state == State.CONNECTED
    }

    private val STATUS_NOT_INSTALLED = Status(State.NOT_INSTALLED, "未安装 Shizuku（需先安装 Shizuku 13+）")
    private val STATUS_NOT_RUNNING = Status(State.NOT_RUNNING, "Shizuku 未运行，请先打开 Shizuku 并启动服务")
    private val STATUS_NO_PERMISSION = Status(State.NO_PERMISSION, "未授权（点此授权）")
    private val STATUS_CONNECTING = Status(State.CONNECTING, "正在连接 Shizuku…")
    private val STATUS_CONNECTED = Status(State.CONNECTED, "已连接 · 离开Nezo也能收录")

    /** root 启动的 Shizuku：连得上但读不到剪贴板（实测 uid=0 + 包归属校验不匹配） */
    private val STATUS_ROOT_UNSUPPORTED = Status(
        State.ROOT_MODE_UNSUPPORTED,
        "Shizuku 以 root 运行，读不到剪贴板 · 请改用「无线调试」启动 Shizuku，或刷 Magisk 模块",
    )

    // ── 运行状态 ──────────────────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var watcher: IClipWatcher? = null

    @Volatile
    private var binding = false
    private var bindSeq = 0

    @Volatile
    private var connected = false

    private var boundArgs: Shizuku.UserServiceArgs? = null
    private var retryCount = 0
    private var lastError = ""
    private var lastText = ""
    private var lastCaptureAt = 0L

    /** Shizuku 远程 uid：2000 = adb/无线调试模式；0 = root 模式 */
    @Volatile
    private var remoteUid = -1

    /** root 模式下真的收到过剪贴板内容（说明这台设备 root 也能读，就不报警告） */
    @Volatile
    private var rootModeClipSeen = false

    private val isMainThread: Boolean
        get() = Looper.myLooper() == Looper.getMainLooper()

    /** 绑定/解绑统一在主线程做（Shizuku 的 ServiceConnection 回调也在主线程） */
    private fun runOnMain(block: () -> Unit) {
        if (isMainThread) {
            block()
        } else {
            mainHandler.post { runCatching { block() }.onFailure { PlatformLog.warn("Shizuku 主线程任务失败：${it.message}") } }
        }
    }

    private val _status = MutableStateFlow(STATUS_NOT_INSTALLED)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 给 UI 直接 collectAsState 用的状态文案 */
    private val _statusText = MutableStateFlow(STATUS_NOT_INSTALLED.message)
    val statusTextFlow: StateFlow<String> = _statusText.asStateFlow()

    // ── 绑定回调 ──────────────────────────────────────────────────────────

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            runCatching {
                if (binder == null || !binder.pingBinder()) throw IllegalStateException("binder 不可用")

                val api = IClipWatcher.Stub.asInterface(binder)
                // 注意：监听器在 shell 进程里注册（应用进程注册的后台收不到回调）
                api.start(callback)

                watcher = api
                connected = true
                retryCount = 0
                lastError = ""

                // 关键诊断：Shizuku 远程 uid。2000=adb 模式（能读），0=root 模式（实测读不到剪贴板）
                remoteUid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
                rootModeClipSeen = false
                PlatformLog.info("Shizuku 远程 uid=$remoteUid（2000=无线调试/adb 模式，0=root 模式）")

                if (remoteUid == 0) {
                    updateStatus(STATUS_ROOT_UNSUPPORTED)
                    PlatformLog.warn(
                        "Shizuku 以 root 运行（uid=0）：系统允许调用但不会返回剪贴板内容，" +
                            "详见 logcat -s NezoShizuku 的 [diag] 日志",
                    )
                } else {
                    updateStatus(STATUS_CONNECTED)
                }
                PlatformLog.info("Shizuku 剪贴板监听已连接（监听器在 shell 进程）")
            }.onFailure {
                fail("连接后初始化失败：${it.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connected = false
            binding = false
            watcher = null
            remoteUid = -1
            rootModeClipSeen = false

            // 断开原因尽量说清楚：Shizuku 服务停了 / UserService 被回收 / 未知
            val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            val reason = when {
                !binderAlive -> "Shizuku 服务已停止"
                name != null -> "UserService 被回收（${name.className.substringAfterLast('.')}）"
                else -> "UserService 意外退出（可能被系统限制后台）"
            }
            lastError = reason
            PlatformLog.warn("Shizuku 连接断开：$reason · name=$name · pingBinder=$binderAlive")
            updateStatus(Status(State.ERROR, "连接断开：$reason，正在重试…"))
            scheduleRetry()
        }

        override fun onNullBinding(name: ComponentName?) {
            binding = false
            fail("Shizuku 返回空 binder（UserService 没能启动，name=$name）")
        }
    }

    /** 被 shell 进程回调（Binder 线程） */
    private val callback = object : IClipWatcherCallback.Stub() {
        override fun onClipChanged(text: String?) {
            if (text.isNullOrBlank()) return
            mainHandler.post {
                runCatching { handleClip(text) }
                    .onFailure { PlatformLog.error("Shizuku 回调处理失败", it) }
            }
        }
    }

    private fun handleClip(text: String) {
        val context = appContext ?: return
        val settings = runCatching { ClipRepo.settings.value }.getOrNull() ?: return
        if (!settings.monitor || !settings.shizukuMonitor) return

        // root 模式先前判了「读不到」，现在真收到内容 → 这台设备 root 也能读，恢复为已连接
        if (remoteUid == 0 && !rootModeClipSeen) {
            rootModeClipSeen = true
            updateStatus(STATUS_CONNECTED)
            PlatformLog.info("root 模式下仍收到剪贴板内容，状态恢复为已连接")
        }

        val now = SystemClock.elapsedRealtime()
        if (text == lastText && now - lastCaptureAt < 1200L) return

        // 已收录过的链接不重复打扰（与后台轮询/应用内监听一致）
        val url = runCatching { ClipRepo.firstMatchUrl(text) }.getOrNull()
        if (url != null && runCatching { ClipRepo.containsUrl(url) }.getOrDefault(false)) {
            lastText = text
            return
        }

        lastText = text
        lastCaptureAt = now

        val result = CaptureEngine.capture(context, text, source = "shizuku")
        if (result?.item != null) {
            PlatformLog.info("Shizuku 后台收录成功：${result.item?.platformName} ${result.item?.url}")
        }
    }

    // ── 对外 API ──────────────────────────────────────────────────────────

    /** 冷启动时调用一次：注册 Shizuku binder 就绪/断开监听（不会主动连接） */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        runCatching {
            // sticky：如果 Shizuku 已经在运行，注册时会立刻回调一次
            Shizuku.addBinderReceivedListenerSticky {
                mainHandler.post {
                    PlatformLog.info("Shizuku binder 已就绪")
                    retryCount = 0
                    runCatching { sync(app) }
                }
            }
            Shizuku.addBinderDeadListener {
                mainHandler.post {
                    connected = false
                    binding = false
                    watcher = null
                    remoteUid = -1
                    rootModeClipSeen = false
                    lastError = "Shizuku 服务已停止"
                    updateStatus(Status(State.ERROR, "Shizuku 服务已停止/断开 · 请重新启动 Shizuku"))
                    PlatformLog.warn("Shizuku binder 断开（服务停止或被系统杀）")
                }
            }
        }.onFailure {
            // 没装 Shizuku 时这里可能抛异常，属于正常情况
            PlatformLog.info("Shizuku 监听器注册失败（未安装时正常）：${it.message}")
        }
    }

    /** 设置页显示用的状态文案（同时刷新内部状态与 [statusTextFlow]） */
    fun statusText(context: Context): String {
        val status = detect(context)
        updateStatus(status)
        return status.message
    }

    fun currentStatus(): Status = _status.value

    fun isConnected(): Boolean = connected

    /** Shizuku 远程 uid：-1 未知 / 2000 无线调试(adb) / 0 root */
    fun remoteUid(): Int = remoteUid

    /** 是否 root 模式（uid=0，实测读不到剪贴板） */
    fun isRootMode(): Boolean = remoteUid == 0

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrElse {
        // Android 11+ 没在 <queries> 里声明时会抛 NameNotFound；此时退化成「binder 是否可达」
        isShizukuRunning()
    }

    fun isShizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 申请 Shizuku 授权（弹出 Shizuku 自己的授权框） */
    fun requestPermission(activity: Activity?, onResult: (Boolean) -> Unit = {}) {
        if (!isShizukuRunning()) {
            PlatformLog.warn("请求 Shizuku 授权失败：Shizuku 未运行")
            onResult(false)
            return
        }
        if (hasPermission()) {
            onResult(true)
            return
        }

        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                runCatching { Shizuku.removeRequestPermissionResultListener(this) }
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                PlatformLog.info("Shizuku 授权结果：granted=$granted")
                mainHandler.post { runCatching { onResult(granted) } }
            }
        }

        runCatching {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
        }.onFailure {
            runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
            PlatformLog.warn("拉起 Shizuku 授权失败：${it.message}")
            mainHandler.post { runCatching { onResult(false) } }
        }
    }

    /**
     * 用户点开开关：写入 `settings.shizukuMonitor = true` 并尽力连接。
     * @return 是否已发起连接/授权（不代表最终连通）
     */
    fun enable(activity: Activity): Boolean {
        val context = activity.applicationContext
        init(context)
        runCatching { ClipRepo.updateSettings { it.copy(shizukuMonitor = true) } }

        val current = detect(context)
        PlatformLog.info("开启 Shizuku 后台监听，当前状态=${current.state}")

        return when (current.state) {
            State.NOT_INSTALLED -> {
                updateStatus(STATUS_NOT_INSTALLED)
                false
            }

            State.NOT_RUNNING -> {
                updateStatus(STATUS_NOT_RUNNING)
                false
            }

            State.NO_PERMISSION -> {
                requestPermission(activity) { granted ->
                    if (granted) {
                        runCatching { bind(context) }
                    } else {
                        updateStatus(STATUS_NO_PERMISSION)
                    }
                }
                true
            }

            State.CONNECTED -> {
                updateStatus(STATUS_CONNECTED)
                true
            }

            State.ROOT_MODE_UNSUPPORTED -> {
                updateStatus(STATUS_ROOT_UNSUPPORTED)
                true
            }

            else -> {
                runCatching { bind(context) }
                true
            }
        }
    }

    /** 用户关掉开关：写入设置、解绑 UserService */
    fun disable() {
        runCatching { ClipRepo.updateSettings { it.copy(shizukuMonitor = false) } }
        stop()
        appContext?.let { ctx -> runOnMain { updateStatus(detect(ctx)) } }
        PlatformLog.info("已关闭 Shizuku 后台监听")
    }

    /** 按当前设置同步（幂等）：Application / 前台服务 / 设置变化时调用 */
    fun sync(context: Context) {
        if (!isMainThread) {
            mainHandler.post { runCatching { sync(context) } }
            return
        }

        init(context)

        val settings = runCatching { ClipRepo.settings.value }.getOrNull() ?: return
        if (!settings.shizukuMonitor) {
            stop()
            return
        }

        // 已经有系统级权限（priv-app 安装）时不需要 Shizuku，避免两套监听重复收录
        if (BackgroundClipMonitor.hasPermission(context)) {
            PlatformLog.info("已具备系统级后台剪贴板权限，跳过 Shizuku")
            stop()
            return
        }

        when (val current = detect(context)) {
            STATUS_CONNECTED -> updateStatus(STATUS_CONNECTED)

            STATUS_ROOT_UNSUPPORTED -> updateStatus(STATUS_ROOT_UNSUPPORTED)

            // detect() 在「已就绪但还没发起绑定」时也返回 CONNECTING；
            // 之前这里只回显状态、从不 bind()，表现就是永远停在「正在连接」。
            STATUS_CONNECTING -> {
                if (binding) {
                    updateStatus(STATUS_CONNECTING)
                } else {
                    updateStatus(STATUS_CONNECTING)
                    runCatching { bind(context) }
                }
            }
            STATUS_NOT_INSTALLED -> {
                PlatformLog.info("Shizuku 未安装，后台监听保持关闭")
                updateStatus(STATUS_NOT_INSTALLED)
                quitQuietly()
            }

            STATUS_NOT_RUNNING -> {
                PlatformLog.info("Shizuku 未运行，后台监听保持关闭（启动 Shizuku 后会自动重连）")
                updateStatus(STATUS_NOT_RUNNING)
                quitQuietly()
            }

            STATUS_NO_PERMISSION -> {
                PlatformLog.info("Shizuku 未授权，等待用户在设置页授权")
                updateStatus(STATUS_NO_PERMISSION)
            }

            else -> {
                if (current.state == State.ERROR || current.state == State.UNAVAILABLE) {
                    updateStatus(current)
                }
                runCatching { bind(context) }
            }
        }
    }

    /** 解绑 + 停止 shell 侧监听（幂等） */
    fun stop() {
        runOnMain { stopInternal() }
    }

    private fun stopInternal() {
        val api = watcher
        if (api != null) runCatching { api.stop() }
        watcher = null

        val args = boundArgs
        if (args != null) {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
        boundArgs = null

        val wasConnected = connected
        connected = false
        binding = false
        retryCount = 0
        remoteUid = -1
        rootModeClipSeen = false
        if (wasConnected) PlatformLog.info("Shizuku 剪贴板监听已停止")
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun detect(context: Context): Status {
        if (connected) {
            return if (remoteUid == 0 && !rootModeClipSeen) STATUS_ROOT_UNSUPPORTED else STATUS_CONNECTED
        }
        if (binding) return STATUS_CONNECTING
        if (!isInstalled(context)) return STATUS_NOT_INSTALLED
        if (!isShizukuRunning()) return STATUS_NOT_RUNNING
        if (!hasPermission()) return STATUS_NO_PERMISSION

        return when {
            lastError.isNotBlank() && Build.VERSION.SDK_INT >= ANDROID_17_API -> Status(
                State.UNAVAILABLE,
                "Shizuku 连接失败（Android 17 请换 Shevery / Nightzuku 等 fork）",
            )

            lastError.isNotBlank() -> Status(State.ERROR, "连接失败：$lastError")

            else -> STATUS_CONNECTING
        }
    }

    private fun bind(context: Context) {
        if (!isMainThread) {
            mainHandler.post { runCatching { bind(context) } }
            return
        }
        if (connected || binding) return
        if (!hasPermission()) {
            updateStatus(STATUS_NO_PERMISSION)
            return
        }

        runCatching {
            // 先解掉可能残留的旧绑定：force-stop 过应用时 Shizuku 侧的 shell 进程
            // 可能还没被回收，再用同名 suffix 绑定会复用到那个"死"记录，表现为一直 CONNECTING。
            boundArgs?.let { old ->
                runCatching { Shizuku.unbindUserService(old, connection, true) }
                PlatformLog.info("解绑上一次残留的 Shizuku UserService")
            }
            boundArgs = null
            binding = true
            updateStatus(STATUS_CONNECTING)

            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShizukuClipService::class.java.name),
            )
                .daemon(false)
                .version(USER_SERVICE_VERSION)
                .processNameSuffix(USER_SERVICE_SUFFIX)
                // 与 NexioSchedule 一致：显式写死 false。
                // 之前用 BuildConfig.DEBUG 会在 debug 包传 true，部分 ROM 对 debuggable 的 UserService 有额外限制。
                .debuggable(false)

            boundArgs = args
            Shizuku.bindUserService(args, connection)
            PlatformLog.info("正在绑定 Shizuku UserService（suffix=$USER_SERVICE_SUFFIX, version=$USER_SERVICE_VERSION）")

            // 兜底超时：Shizuku starter 起不来（类不对/被系统杀/版本不兼容）时不能永远停在「正在连接」
            val seq = ++bindSeq
            mainHandler.postDelayed({
                if (seq == bindSeq && !connected && binding) {
                    binding = false
                    lastError = "连接超时（8 秒）：UserService 没起来"
                    PlatformLog.warn("Shizuku 绑定超时（UserService 未回调 onServiceConnected）")
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                    updateStatus(
                        if (Build.VERSION.SDK_INT >= ANDROID_17_API) {
                            Status(State.UNAVAILABLE, "连接超时：Android 17 请换 Shevery / Nightzuku 等 fork")
                        } else {
                            Status(State.ERROR, "连接超时（8 秒）· 点开关重试")
                        }
                    )
                }
            }, 8_000L)
        }.onFailure {
            binding = false
            boundArgs = null
            lastError = it.message.orEmpty()
            PlatformLog.warn("bindUserService 抛异常：${it.message}")
            updateStatus(
                if (Build.VERSION.SDK_INT >= ANDROID_17_API) {
                    Status(State.UNAVAILABLE, "Shizuku 连接失败（Android 17 请换 Shevery / Nightzuku 等 fork）")
                } else {
                    Status(State.ERROR, "绑定失败：${it.message}")
                }
            )
        }
    }

    private fun fail(reason: String) {
        binding = false
        connected = false
        watcher = null
        remoteUid = -1
        rootModeClipSeen = false
        lastError = reason
        PlatformLog.warn("Shizuku 连接失败：$reason")

        updateStatus(
            if (Build.VERSION.SDK_INT >= ANDROID_17_API) {
                Status(State.UNAVAILABLE, "Shizuku 连接失败（Android 17 请换 Shevery / Nightzuku 等 fork）")
            } else {
                Status(State.ERROR, "连接失败：$reason")
            }
        )
        scheduleRetry()
    }

    private fun scheduleRetry() {
        val context = appContext ?: return
        if (!wantShizuku()) return
        if (retryCount >= RETRY_LIMIT) {
            PlatformLog.warn("Shizuku 重连已达上限（$RETRY_LIMIT 次），保持降级")
            updateStatus(
                Status(
                    State.ERROR,
                    "连接不稳定（已重试 $RETRY_LIMIT 次）· 请确认 Shizuku 是「无线调试」启动，且未被系统限制后台",
                )
            )
            return
        }

        retryCount++
        val delay = RETRY_DELAY_MS * retryCount
        PlatformLog.info("Shizuku ${delay}ms 后重连（第 $retryCount 次）")
        mainHandler.postDelayed({
            runCatching {
                if (wantShizuku()) bind(context)
            }
        }, delay)
    }

    private fun wantShizuku(): Boolean =
        runCatching { ClipRepo.settings.value.shizukuMonitor }.getOrDefault(false)

    private fun quitQuietly() {
        // 不动用户设置，只释放 binder（比如 Shizuku 停着，等它起来再自动连）
        val api = watcher
        if (api != null) runCatching { api.stop() }
        watcher = null
        val args = boundArgs
        if (args != null) runCatching { Shizuku.unbindUserService(args, connection, true) }
        boundArgs = null
        connected = false
        binding = false
        remoteUid = -1
        rootModeClipSeen = false
    }

    private fun updateStatus(status: Status) {
        val changed = _status.value != status
        _status.value = status
        _statusText.value = status.message
        if (changed) PlatformLog.info("Shizuku 状态：${status.state} · ${status.message}")
    }
}