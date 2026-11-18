package com.ZenithLiteAura.app.nezo.platform.shizuku

import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.util.Log

/**
 * `app_process` 入口：以 Shizuku 的 shell 身份读剪贴板。
 *
 * 两种模式：
 * 1. 单次（默认）：读一次，打印一行结果，退出。供 [RootShellFallback] 使用。
 * 2. `--watch <ms>`：长驻循环，给 [ShizukuExecMonitor] 用：
 *    - 启动先打印 `READY`；
 *    - 内容变化时打印 `OK:<base64(UTF-8)>`；
 *    - 剪贴板变空时打印一次 `NULL`；
 *    - 每条都 flush，主进程按行读。
 *
 * 关键实现：**优先走 `ServiceManager` + `IClipboard` binder（callingPackage=com.android.shell）**，
 * 完全不需要 `ActivityThread`/`Context` —— app_process 里 `ActivityThread.systemMain()` 在
 * 非 system 进程会抛 `InvocationTargetException`，Android 17 上更不可靠。Context 只作兜底。
 *
 * 手动验证（设备 shell 里），把 `<apk>` 换成 `pm path com.ZenithLiteAura.app.nezo` 的路径：
 * ```
 * CLASSPATH=<apk> app_process /system/bin com.ZenithLiteAura.app.nezo.platform.shizuku.ClipProbe --watch 1500
 * ```
 */
object ClipProbe {

    private const val TAG = "NezoShizuku"
    private const val SHELL_PACKAGE = "com.android.shell"

    @Volatile
    private var running: Boolean = true

    @JvmStatic
    fun main(args: Array<String>) {
        val uid = android.os.Process.myUid()
        if (args.firstOrNull() == "--watch") {
            val interval = args.getOrNull(1)?.toLongOrNull()?.coerceIn(300L, 10_000L) ?: 1500L
            Log.i(TAG, "[ClipProbe] watch 模式 uid=$uid interval=${interval}ms")
            watch(interval)
            return
        }

        Log.i(TAG, "[ClipProbe] 单次模式 uid=$uid")
        val output = try {
            when (val result = probeOnce(uid)) {
                is ProbeResult.Text -> "OK:" + encode(result.value)
                ProbeResult.Empty -> "NULL"
                ProbeResult.Unavailable -> "ERR:clipboard-unavailable(uid=$uid)"
            }
        } catch (t: Throwable) {
            "ERR:${t.javaClass.simpleName}:${t.message}"
        }
        emit(output)
        Log.i(TAG, "[ClipProbe] 结果：${output.take(80)}")
    }

    // ── watch 模式 ────────────────────────────────────────────────────────

    private fun watch(intervalMs: Long) {
        running = true
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread { running = false })
        }

        emit("READY")
        var lastText: String? = null
        var hadText = false
        var lastUnavailable = false

        while (running) {
            val result = try {
                probeOnce(android.os.Process.myUid())
            } catch (t: Throwable) {
                Log.w(TAG, "[ClipProbe] watch 读取异常：${t.javaClass.simpleName}: ${t.message}")
                ProbeResult.Unavailable
            }

            when (result) {
                is ProbeResult.Text -> {
                    if (result.value != lastText) {
                        lastText = result.value
                        hadText = true
                        lastUnavailable = false
                        emit("OK:" + encode(result.value))
                    }
                }

                ProbeResult.Empty -> {
                    if (hadText || !lastUnavailable) {
                        hadText = false
                        lastText = null
                        lastUnavailable = false
                        emit("NULL")
                    }
                }

                ProbeResult.Unavailable -> {
                    if (!lastUnavailable) {
                        lastUnavailable = true
                        emit("ERR:clipboard-unavailable")
                    }
                }
            }

            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                running = false
            }
        }
        Log.i(TAG, "[ClipProbe] watch 退出")
    }

    private fun emit(line: String) {
        runCatching {
            println(line)
            System.out.flush()
        }
    }

    private fun encode(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    // ── 读取 ──────────────────────────────────────────────────────────────

    private sealed class ProbeResult {
        data class Text(val value: String) : ProbeResult()
        object Empty : ProbeResult()
        object Unavailable : ProbeResult()
    }

    private var binderFailLogs = 0

    private fun probeOnce(uid: Int): ProbeResult {
        // ① 纯 binder：不需要 Context，是 uid=2000 下最可靠的一条
        when (val binder = readViaBinder()) {
            is ProbeResult.Text -> return binder
            ProbeResult.Empty -> return ProbeResult.Empty
            ProbeResult.Unavailable -> Unit
        }

        // ② 兜底：系统 Context（部分 ROM 上可用）
        val context = systemContext() ?: return ProbeResult.Unavailable
        val manager = runCatching { context.getSystemService(ClipboardManager::class.java) }.getOrNull()
            ?: return ProbeResult.Unavailable

        val clip = runCatching { manager.primaryClip }
        clip.exceptionOrNull()?.let {
            Log.w(TAG, "[ClipProbe] Context 读剪贴板异常（uid=$uid）：${it.javaClass.simpleName}: ${it.message}")
        }
        val data = clip.getOrNull() ?: return ProbeResult.Empty
        if (data.itemCount <= 0) return ProbeResult.Empty
        val item = data.getItemAt(0)
        val text = item.text?.toString() ?: item.coerceToText(context)?.toString()
        return if (text.isNullOrEmpty()) ProbeResult.Empty else ProbeResult.Text(text)
    }

    /**
     * 直接走 `ServiceManager` + `IClipboard$Stub`，callingPackage 传 `com.android.shell`。
     * 兼容 Android 10~17 的 `getPrimaryClip(String pkg, String attributionTag, int userId[, int deviceId])`。
     */
    private fun readViaBinder(): ProbeResult = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "clipboard") as? android.os.IBinder
            ?: return ProbeResult.Unavailable

        val stubClass = Class.forName("android.content.IClipboard\$Stub")
        val service = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder)
            ?: return ProbeResult.Unavailable

        val methods = service.javaClass.methods.filter { it.name == "getPrimaryClip" }
        if (methods.isEmpty()) return ProbeResult.Unavailable

        var sawEmpty = false
        var lastError: String? = null
        for (method in methods) {
            val args = when (method.parameterTypes.size) {
                3 -> arrayOf<Any?>(SHELL_PACKAGE, null, 0)
                4 -> arrayOf<Any?>(SHELL_PACKAGE, null, 0, 0)
                else -> continue
            }
            val result = runCatching { method.invoke(service, *args) }
            val error = result.exceptionOrNull()
            if (error != null) {
                lastError = "${error.javaClass.simpleName}:${error.message}"
                continue
            }
            val clip = result.getOrNull() as? android.content.ClipData
            if (clip == null || clip.itemCount <= 0) {
                sawEmpty = true
                continue
            }
            val item = clip.getItemAt(0)
            val text = item.text?.toString() ?: item.coerceToText(null)?.toString()
            if (text.isNullOrEmpty()) {
                sawEmpty = true
                continue
            }
            return ProbeResult.Text(text)
        }

        if (lastError != null && binderFailLogs < 3) {
            binderFailLogs++
            Log.w(TAG, "[ClipProbe] binder getPrimaryClip 失败：$lastError")
        }
        if (sawEmpty) ProbeResult.Empty else ProbeResult.Unavailable
    } catch (t: Throwable) {
        if (binderFailLogs < 3) {
            binderFailLogs++
            Log.w(TAG, "[ClipProbe] binder 路径不可用：${t.javaClass.simpleName}: ${t.message}")
        }
        ProbeResult.Unavailable
    }

    /** app_process 里拿系统 Context（只作兜底；非 system 进程可能失败） */
    private fun systemContext(): Context? = try {
        val atClass = Class.forName("android.app.ActivityThread")
        val activityThread = runCatching { atClass.getMethod("currentActivityThread").invoke(null) }.getOrNull()
            ?: atClass.getMethod("systemMain").invoke(null)
        atClass.getMethod("getSystemContext").invoke(activityThread) as? Context
    } catch (t: Throwable) {
        if (binderFailLogs < 3) {
            binderFailLogs++
            Log.w(TAG, "[ClipProbe] 取系统 Context 失败：${t.javaClass.simpleName}: ${t.message}")
        }
        null
    }
}