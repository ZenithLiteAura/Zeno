package com.ZenithLiteAura.app.nezo.platform.shizuku

import android.util.Base64
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * root 模式（UserService 的 uid=0）下的「读剪贴板」兜底尝试。
 *
 * 思路：root 进程本身读不到剪贴板（OEM/系统按 uid+包归属返回 null）。
 * 如果设备上的 su 允许切到 **uid=2000（shell）**，就用 `app_process` 启动一个 uid=2000 的进程
 * 跑 [ClipProbe] 读剪贴板，再把结果回传给 [ShizukuClipService]。
 *
 * 一切以实测为准：
 * - 启动时先探测 `id` 与几种 `su ... 2000`（含 `setpriv`）方式，**只有真的拿到 uid=2000 才启用**；
 * - 拿不到就记一条明确的日志并返回 false（不假装能用）。
 */
internal class RootShellFallback(
    private val apkPath: String?,
    private val onText: (String) -> Unit,
) {

    private companion object {
        const val TAG = "NezoShizuku"
        const val PROBE_POLL_MS = 1200L
        const val PROBE_CLASS = "com.ZenithLiteAura.app.nezo.platform.shizuku.ClipProbe"
        const val EXEC_TIMEOUT_MS = 6000L
    }

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var builder: ((String) -> List<String>)? = null

    val isRunning: Boolean get() = running

    fun start(): Boolean {
        val apk = apkPath?.takeIf { it.isNotBlank() }
        if (apk == null) {
            Log.w(TAG, "[root-fallback] 拿不到 APK 路径，放弃")
            return false
        }

        val resolved = probeShellBuilder()
        if (resolved == null) {
            Log.w(TAG, "[root-fallback] 无法切到 uid=2000，root 模式下不支持后台读剪贴板")
            return false
        }

        builder = resolved
        running = true
        thread = Thread({ pollLoop(resolved, apk) }, "NezoClipProbePoll").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "[root-fallback] 已启用 uid=2000 兜底（app_process + ClipProbe）")
        return true
    }

    fun stop() {
        running = false
        thread = null
    }

    // ── 能力探测 ──────────────────────────────────────────────────────────

    /** 依次尝试几种能切到 uid=2000 的启动方式；返回「命令字符串 → argv」的构造器 */
    private fun probeShellBuilder(): ((String) -> List<String>)? {
        val myUid = android.os.Process.myUid()
        Log.i(TAG, "[root-fallback] 当前 uid=$myUid，开始探测 uid=2000 可行性")

        // 控制组：直连 id（应为 uid=0）
        Log.i(TAG, "[root-fallback] 直连 id => ${exec(listOf("/system/bin/sh", "-c", "id"), 2500)?.trim()?.take(200)}")

        // 附带证据：系统剪贴板服务状态（dumpsys clipboard 在 root 下可读，能看出是「没有内容」还是「有内容但拒绝给 root」）
        runCatching {
            val dump = exec(listOf("/system/bin/sh", "-c", "dumpsys clipboard 2>&1 | head -40"), 4000)
            if (!dump.isNullOrBlank()) {
                Log.i(TAG, "[root-fallback] dumpsys clipboard:\n${dump.take(1600)}")
            } else {
                Log.w(TAG, "[root-fallback] dumpsys clipboard 无输出")
            }
        }.onFailure { Log.w(TAG, "[root-fallback] dumpsys 失败：${it.message}") }

        val candidates: List<Pair<String, (String) -> List<String>>> = listOf(
            "su 2000 -c" to { cmd: String -> listOf("su", "2000", "-c", cmd) },
            "su -u 2000 -c" to { cmd: String -> listOf("su", "-u", "2000", "-c", cmd) },
            "su --user 2000 -c" to { cmd: String -> listOf("su", "--user", "2000", "-c", cmd) },
            "setpriv --reuid=2000" to { cmd: String ->
                listOf(
                    "setpriv", "--reuid=2000", "--regid=2000", "--clear-groups",
                    "/system/bin/sh", "-c", cmd,
                )
            },
        )

        for ((label, build) in candidates) {
            val output = exec(build("id"), 2500)
            val trimmed = output?.trim()?.take(200)
            Log.i(TAG, "[root-fallback] 探测 [$label] id => $trimmed")
            if (output != null && output.contains("uid=2000")) {
                Log.i(TAG, "[root-fallback] [$label] 可用 ✓")
                return build
            }
        }

        Log.w(TAG, "[root-fallback] 所有方式都没拿到 uid=2000（这台设备 root 不能降权到 shell）")
        return null
    }

    // ── 轮询 ──────────────────────────────────────────────────────────────

    private fun pollLoop(build: (String) -> List<String>, apk: String) {
        val commands = listOf(
            "CLASSPATH=$apk /system/bin/app_process /system/bin $PROBE_CLASS",
            "CLASSPATH=$apk /system/bin/app_process64 /system/bin $PROBE_CLASS",
        )
        var commandIndex = 0
        var lastText = ""
        var errorCount = 0
        var tick = 0

        while (running) {
            val command = commands[commandIndex]
            val output = exec(build(command), EXEC_TIMEOUT_MS)

            if (output == null) {
                // 当前 app_process 变体失败 → 换另一个再试，失败次数太多就只记录
                if (errorCount++ < 3) {
                    Log.w(TAG, "[root-fallback] 执行无输出（第 $errorCount 次）：$command")
                }
                commandIndex = if (commandIndex == 0) 1 else 0
            } else {
                // app_process 会往 stderr 打自己的日志（已 merge 到 stdout），
                // 所以扫描所有行找结果行，而不是直接取第一行
                val outputLines = output.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
                val line = outputLines.firstOrNull {
                    it.startsWith("OK:") || it == "NULL" || it.startsWith("ERR:")
                } ?: outputLines.firstOrNull().orEmpty()
                if (tick < 3) {
                    Log.i(TAG, "[root-fallback] 轮询第${tick}次 => ${line.take(120)}")
                }

                when {
                    line.startsWith("OK:") -> {
                        val text = runCatching {
                            String(Base64.decode(line.removePrefix("OK:"), Base64.NO_WRAP), Charsets.UTF_8)
                        }.getOrNull()
                        if (!text.isNullOrBlank() && text != lastText) {
                            lastText = text
                            onText(text)
                            Log.i(TAG, "[root-fallback] 读到剪贴板 ${text.length} 字符")
                        }
                        commandIndex = 0
                        errorCount = 0
                    }

                    line == "NULL" -> {
                        errorCount = 0
                    }

                    line.startsWith("ERR:") -> {
                        if (errorCount++ < 5) Log.w(TAG, "[root-fallback] ClipProbe 失败：$line")
                    }

                    line.isNotEmpty() -> {
                        if (errorCount++ < 5) Log.i(TAG, "[root-fallback] 输出：${line.take(160)}")
                    }
                }
                tick++
            }

            runCatching { Thread.sleep(PROBE_POLL_MS) }
        }
        Log.i(TAG, "[root-fallback] 轮询已退出")
    }

    // ── 进程执行 ──────────────────────────────────────────────────────────

    private fun exec(args: List<String>, timeoutMs: Long): String? {
        var process: java.lang.Process? = null
        return try {
            process = ProcessBuilder(args).redirectErrorStream(true).start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                Log.w(TAG, "[root-fallback] 命令超时：${args.take(2)}")
                runCatching { process.destroyForcibly() }
                null
            } else {
                process.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[root-fallback] 命令失败：${args.take(2)} ${t.javaClass.simpleName}: ${t.message}")
            runCatching { process?.destroyForcibly() }
            null
        }
    }
}