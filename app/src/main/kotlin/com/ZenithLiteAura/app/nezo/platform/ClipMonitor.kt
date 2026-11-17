package com.ZenithLiteAura.app.nezo.platform

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 应用内剪贴板监听（通路 A）。
 *
 * 只在 Activity 前台期间工作：优先用 ClipboardManager 的变更监听器，
 * 拿不到监听器时退化为按 settings.pollIntervalMs 轮询。
 * 离开前台必须调用 stop()——Android 10+ 后台读剪贴板会被系统直接拦掉。
 */
object ClipMonitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var manager: ClipboardManager? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var pollJob: Job? = null
    private var lastSignature: Int = 0
    private var running: Boolean = false

    val isRunning: Boolean get() = running

    /** 幂等：重复调用直接返回 */
    fun start(activity: Activity) {
        if (running) return

        val appContext = activity.applicationContext
        runCatching { ClipRepo.init(appContext) }
        running = true
        lastSignature = 0

        val clipManager = runCatching { appContext.getSystemService(ClipboardManager::class.java) }.getOrNull()
        manager = clipManager

        if (clipManager != null) {
            runCatching {
                val l = ClipboardManager.OnPrimaryClipChangedListener { handleChange(appContext) }
                clipManager.addPrimaryClipChangedListener(l)
                listener = l
            }.onFailure { PlatformLog.warn("剪贴板监听器注册失败，改用轮询：${it.message}") }
        }

        // 变化监听器在部分 ROM/版本上不会触发（或只有拿焦点后才触发），
        // 所以**始终**再跑一路轮询兜底，两路都走 signature 去重。
        startPolling(appContext)

        // 回到前台先补抓一次：典型场景是「在抖音复制 → 切回Nezo」，
        // 这段内容在应用处于后台时系统不允许读，只有回到前台这一刻才拿得到。
        // 注意：Activity.onStart 时窗口往往还没拿到焦点，这里读会失败，所以真正可靠的是
        // onWindowFocusChanged 再调一次 [catchUp]（见 MainActivity）。
        catchUp(appContext)

        PlatformLog.info("应用内监听已启动（监听器=${listener != null} 轮询=${currentInterval(appContext)}ms）")
    }

    /** 幂等 */
    fun stop() {
        if (!running) return
        running = false

        runCatching {
            val l = listener
            val m = manager
            if (l != null && m != null) m.removePrimaryClipChangedListener(l)
        }
        listener = null
        manager = null

        pollJob?.cancel()
        pollJob = null
        PlatformLog.info("应用内监听已停止")
    }

    private fun startPolling(context: Context) {
        pollJob = scope.launch {
            while (isActive) {
                if (runCatching { ClipRepo.settings.value.monitor }.getOrDefault(true)) {
                    handleChange(context)
                }
                delay(currentInterval(context))
            }
        }
    }

    private fun currentInterval(context: Context): Long {
        val ms = runCatching { ClipRepo.settings.value.pollIntervalMs }.getOrDefault(300)
        return ms.coerceIn(150, 5000).toLong()
    }

    /**
     * 补抓当前剪贴板：命中规则且**没收录过**才落库。
     * 用于「应用回到前台 / 窗口刚拿到焦点」的时刻 —— Android 10+ 这时才允许读剪贴板。
     * `attempts` 用于刚拿焦点时可能还读不到（系统异步授予焦点）的重试。
     */
    fun catchUp(context: Context, attempts: Int = 1) {
        if (!running) return
        if (!runCatching { ClipRepo.settings.value.monitor }.getOrDefault(true)) return

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            repeat(attempts.coerceAtLeast(1)) { index ->
                val text = ClipboardIo.readText(context)
                if (!text.isNullOrBlank()) {
                    val signature = ClipboardIo.signature(text)
                    val changed = signature != lastSignature
                    lastSignature = signature
                    if (changed) {
                        val already = runCatching { ClipRepo.firstMatchUrl(text) }.getOrNull()
                            ?.let { url -> runCatching { ClipRepo.containsUrl(url) }.getOrDefault(false) }
                            ?: false
                        if (!already) {
                            CaptureEngine.capture(context, text, source = "clipboard")
                        } else {
                            PlatformLog.info("补抓跳过：该链接已收录")
                        }
                    }
                    return@launch
                }
                if (index + 1 < attempts) delay(220)
            }
        }
    }

    private fun handleChange(context: Context) {
        if (!running) return
        if (!runCatching { ClipRepo.settings.value.monitor }.getOrDefault(true)) return

        val text = ClipboardIo.readText(context) ?: return
        val signature = ClipboardIo.signature(text)
        if (signature == lastSignature) return
        lastSignature = signature

        // 已经收录过的链接不再重复采集（避免每次回到前台都闪一次提示）
        if (alreadyStored(text)) return

        CaptureEngine.capture(context, text, source = "clipboard")
    }

    /** 只识别不落库，判断这条链接是否已经在收件箱里 */
    private fun alreadyStored(text: String): Boolean = runCatching {
        val url = ClipRepo.preview(text).firstOrNull()?.item?.url ?: return@runCatching false
        ClipRepo.items.value.any { it.url == url }
    }.getOrDefault(false)
}