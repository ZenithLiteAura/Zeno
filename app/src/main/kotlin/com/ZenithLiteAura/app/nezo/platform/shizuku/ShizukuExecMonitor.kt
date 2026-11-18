package com.ZenithLiteAura.app.nezo.platform.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import com.ZenithLiteAura.app.nezo.platform.CaptureEngine
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import rikka.shizuku.Shizuku

/**
 * 用 **Shizuku.newProcess 起一个 shell 进程**跑 [ClipProbe] 来做后台剪贴板监听。
 *
 * 背景：Android 17 / HyperOS 4 上 Shizuku 13.x 的 UserService 会被系统回收
 * （客户端表现为「连接断开：UserService 被回收」）。NexioSchedule 的 `execAsShell` 证明
 * 反射调 `Shizuku.newProcess(...)` 以 shell 身份起进程在 13.x 可用，于是改成：
 *
 * ```
 * sh -c "CLASSPATH=<base.apk> app_process /system/bin <ClipProbe> --watch 1500"
 * ```
 * 主进程读它的 stdout（`OK:<base64>` / `NULL` / `READY` / `ERR:...`），拿到文本就走
 * `CaptureEngine.capture(source="shizuku")`，与 UserService 方案完全同一条收录管线。
 *
 * 相比 UserService 的好处：进程是普通子进程，不受 Shizuku UserService starter/回收机制影响。
 * 进程意外退出会自动重启（2s 起步退避，最多 3 次）。
 */
internal object ShizukuExecMonitor {

    private const val TAG = "NezoShizuku"
    private const val PROBE_CLASS = "com.ZenithLiteAura.app.nezo.platform.shizuku.ClipProbe"
    private const val WATCH_INTERVAL_MS = 1500L
    private const val RESTART_DELAY_MS = 2000L
    private const val MAX_RESTART = 3
    private const val DEDUPE_WINDOW_MS = 1200L

    @Volatile
    private var running = false

    @Volatile
    private var process: java.lang.Process? = null

    @Volatile
    private var lastText = ""

    @Volatile
    private var lastDeliveredAt = 0L

    @SuppressLint("StaticFieldLeak")   // 只存 applicationContext
    private var contextRef: Context? = null
    private var supervisor: Thread? = null
    private var restarts = 0
    private var errorLogs = 0

    /** ClipProbe 起来并打印 READY（客户端据此把状态置为「已连接」） */
    @Volatile
    var onReady: (() -> Unit)? = null

    /** 监督线程退出（非用户主动 stop 时给出原因） */
    @Volatile
    var onStopped: ((String) -> Unit)? = null

    // ── 对外 API ──────────────────────────────────────────────────────────

    val isRunning: Boolean
        get() = running && process != null

    /** 幂等启动；返回 false 表示当前环境用不了（未运行/未授权/newProcess 不可用） */
    @Synchronized
    fun start(context: Context): Boolean {
        if (running) return true
        if (!canRun()) {
            PlatformLog.info("Exec 监听未启动：Shizuku 未运行或未授权")
            return false
        }
        val apk = runCatching { context.applicationContext?.applicationInfo?.sourceDir }.getOrNull()
        if (apk.isNullOrBlank()) {
            PlatformLog.warn("Exec 监听未启动：拿不到 APK 路径")
            return false
        }
        contextRef = context.applicationContext
        lastText = ""
        restarts = 0
        errorLogs = 0
        running = true
        supervisor = Thread({ supervise() }, "NezoExecMonitor").apply {
            isDaemon = true
            start()
        }
        PlatformLog.info("Exec 监听启动（Shizuku.newProcess + ClipProbe --watch $WATCH_INTERVAL_MS ms）")
        return true
    }

    @Synchronized
    fun stop() {
        if (!running && process == null) return
        running = false
        val proc = process
        process = null
        runCatching { proc?.destroy() }
        runCatching { supervisor?.interrupt() }
        supervisor = null
        lastText = ""
        PlatformLog.info("Exec 监听已停止")
    }

    fun canRun(): Boolean = runCatching {
        if (!Shizuku.pingBinder()) return@runCatching false
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return@runCatching false
        }
        // root 模式（uid=0）读剪贴板会让 Android 直接「清空剪贴板」（实测日志 Could not grant permission…Clearing clipboard），
        // 副作用远大于收益 → 一律不启动。
        val remoteUid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        if (remoteUid == 0) {
            PlatformLog.warn("Exec 监听不可用：Shizuku 以 root 运行（uid=0），读剪贴板会让系统清空剪贴板")
            return@runCatching false
        }
        true
    }.getOrDefault(false)

    // ── 监督 / 重启 ───────────────────────────────────────────────────────

    private fun supervise() {
        val appContext = contextRef
        while (running) {
            if (!shouldRun()) break

            val proc = newProcessReflect(buildCommand(appContext))
            if (proc == null) {
                PlatformLog.warn("Exec 监听：Shizuku.newProcess 不可用（反射失败），放弃该路径")
                break
            }

            process = proc
            PlatformLog.info("Exec 监听：ClipProbe 进程已启动")

            val exitCode = pump(proc)
            process = null
            runCatching { proc.destroy() }

            if (!running) break
            if (!shouldRun()) break

            if (restarts >= MAX_RESTART) {
                PlatformLog.warn("Exec 监听：进程连续退出 ${MAX_RESTART} 次，不再重启")
                break
            }
            restarts++
            val delay = RESTART_DELAY_MS * restarts
            PlatformLog.warn("Exec 监听：ClipProbe 退出(code=$exitCode)，${delay}ms 后重启（第 $restarts 次）")
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                break
            }
        }

        val wasRunning = running
        running = false
        PlatformLog.info("Exec 监听监督线程结束")
        if (wasRunning) {
            runCatching { onStopped?.invoke("Exec 监听已停止") }
        }
    }

    /** 读 stdout 直到进程结束，返回 exitCode（失败 -1） */
    private fun pump(proc: java.lang.Process): Int {
        val stderrDrain = Thread({ drainError(proc) }, "NezoExecStderr").apply {
            isDaemon = true
            start()
        }
        try {
            proc.inputStream.bufferedReader().use { reader ->
                while (running) {
                    val line = runCatching { reader.readLine() }.getOrNull() ?: break
                    handleLine(line.trim())
                }
            }
        } catch (t: Throwable) {
            PlatformLog.warn("Exec 监听读 stdout 异常：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { stderrDrain.interrupt() }
        }
        return runCatching { proc.waitFor() }.getOrDefault(-1)
    }

    private fun drainError(proc: java.lang.Process) {
        runCatching {
            proc.errorStream.bufferedReader().use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break
                    // ClipProbe 的日志会走 stderr，只留少量，避免刷屏
                    if (errorLogs < 8) {
                        errorLogs++
                        Log.i(TAG, "[exec-probe] ${line.take(160)}")
                    }
                }
            }
        }
    }

    private fun handleLine(line: String) {
        when {
            line == "READY" -> {
                restarts = 0
                PlatformLog.info("Exec 监听：ClipProbe 已就绪（READY）")
                runCatching { onReady?.invoke() }
            }

            line.startsWith("OK:") -> {
                val text = runCatching {
                    String(Base64.decode(line.removePrefix("OK:"), Base64.NO_WRAP), Charsets.UTF_8)
                }.getOrNull()
                if (!text.isNullOrBlank()) deliver(text)
            }

            line == "NULL" -> Unit

            line.startsWith("ERR:") -> {
                if (errorLogs < 8) {
                    errorLogs++
                    PlatformLog.warn("Exec 监听：ClipProbe 报错 ${line.take(120)}")
                }
            }

            else -> Unit   // app_process 自身的噪声行，忽略
        }
    }

    // ── 文本 → 收录 ───────────────────────────────────────────────────────

    private fun deliver(text: String) {
        runCatching {
            val context = contextRef ?: return
            val settings = runCatching { ClipRepo.settings.value }.getOrNull() ?: return
            if (!settings.monitor || !settings.shizukuMonitor) return

            val now = SystemClock.elapsedRealtime()
            if (text == lastText && now - lastDeliveredAt < DEDUPE_WINDOW_MS) return

            // 已收录过的链接不重复打扰
            val url = runCatching { ClipRepo.firstMatchUrl(text) }.getOrNull()
            if (url != null && runCatching { ClipRepo.containsUrl(url) }.getOrDefault(false)) {
                lastText = text
                return
            }

            lastText = text
            lastDeliveredAt = now

            val result = CaptureEngine.capture(context, text, source = "shizuku")
            if (result?.item != null) {
                PlatformLog.info("Exec 后台收录成功：${result.item?.platformName} ${result.item?.url}")
            }
        }.onFailure {
            PlatformLog.warn("Exec 收录处理失败：${it.message}")
        }
    }

    // ── 起进程 ────────────────────────────────────────────────────────────

    private fun shouldRun(): Boolean =
        runCatching { ClipRepo.settings.value.shizukuMonitor }.getOrDefault(false)

    private fun buildCommand(context: Context?): String {
        val apk = runCatching { context?.applicationInfo?.sourceDir }.getOrNull().orEmpty()
        val quotedApk = if (apk.contains(' ')) "\"$apk\"" else apk
        return "CLASSPATH=$quotedApk /system/bin/app_process /system/bin $PROBE_CLASS --watch $WATCH_INTERVAL_MS"
    }

    /**
     * 反射调用 `Shizuku.newProcess(String[] cmd, String[] env, String dir)`。
     * Shizuku 13.x 里它是 `private static`，返回 `ShizukuRemoteProcess extends Process`。
     * 先试 public 重载（将来版本若公开），再试 `getDeclaredMethod` + `isAccessible = true`（Nexio 同款）。
     */
    private fun newProcessReflect(command: String): java.lang.Process? {
        val args = arrayOf("sh", "-c", command)

        runCatching {
            val method = Shizuku::class.java.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            val started = method.invoke(null, args, null, null) as? java.lang.Process
            if (started != null) {
                PlatformLog.info("Shizuku.newProcess（public）调用成功")
                return started
            }
        }.onFailure {
            Log.i(TAG, "newProcess(public) 不可用：${it.javaClass.simpleName}: ${it.message}")
        }

        runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val started = method.invoke(null, args, null, null) as? java.lang.Process
            if (started != null) {
                PlatformLog.info("Shizuku.newProcess（private 反射）调用成功")
                return started
            }
        }.onFailure {
            PlatformLog.warn("Shizuku.newProcess 反射失败：${it.javaClass.simpleName}: ${it.message}")
            Log.w(TAG, "newProcess(private) 反射失败", it)
        }

        return null
    }
}