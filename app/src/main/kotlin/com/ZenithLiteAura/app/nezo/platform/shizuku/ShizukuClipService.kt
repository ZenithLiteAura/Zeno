package com.ZenithLiteAura.app.nezo.platform.shizuku

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.annotation.Keep

/**
 * 跑在 **Shizuku 的 shell 进程（UID 2000）** 里的剪贴板监听 UserService。
 *
 * ⚠️ 关键：Shizuku API v13 起，UserService 必须**直接继承 AIDL 的 Stub**
 * （类本身就是 IBinder），而**不是**继承 `android.app.Service`。
 * 写成 Service 会在 starter 里抛 `ClassCastException: ... cannot be cast to android.os.IBinder`。
 * 参考官方示例：Shizuku-API/demo/.../service/UserService.java。
 *
 * 为什么必须放在 shell 进程：Android 10 起后台读剪贴板只对持有 signature 级
 * `READ_CLIPBOARD_IN_BACKGROUND` 的包放行，而 `com.android.shell` 就持有；
 * 且 `sendClipChangedBroadcast()` 会对每个监听者再校验一次，所以监听器也必须在这里注册。
 *
 * 这个类**不要**注册进 AndroidManifest：Shizuku 通过 bindUserService 以 shell 身份直接实例化它。
 * shell 进程写不进应用私有目录，所以只用 Log（TAG=ClipNestShizuku）。
 */
class ShizukuClipService : IClipWatcher.Stub {

    private companion object {
        const val TAG = "ClipNestShizuku"
    }

    private var callback: IClipWatcherCallback? = null
    private var clipboard: ClipboardManager? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastText: String = ""
    private var appContext: Context? = null
    private var pollThread: Thread? = null

    @Volatile
    private var polling: Boolean = false
    private var systemClipboard: ClipboardManager? = null

    /** 无参构造是必需的（Shizuku starter 用 Class.newInstance() 实例化）。 */
    constructor() : super() {
        Log.i(TAG, "ShizukuClipService() 构造 · uid=${android.os.Process.myUid()}")
    }

    /** Context 构造仅 Shizuku API v13+ 可用；@Keep 防止 R8 裁掉。 */
    @Keep
    constructor(context: Context) : super() {
        appContext = context
        Log.i(TAG, "ShizukuClipService(context) 构造 · uid=${android.os.Process.myUid()}")
    }

    override fun start(cb: IClipWatcherCallback?) {
        if (cb == null) {
            Log.w(TAG, "start() 收到空回调，忽略")
            return
        }
        runCatching {
            callback = cb
            val manager = resolveClipboard()
            clipboard = manager
            if (manager == null) {
                Log.e(TAG, "拿不到 ClipboardManager")
                return
            }
            if (listener == null) {
                val newListener = ClipboardManager.OnPrimaryClipChangedListener { pushClipboard() }
                manager.addPrimaryClipChangedListener(newListener)
                listener = newListener
                Log.i(TAG, "已在 shell 进程注册剪贴板监听（uid=${android.os.Process.myUid()}）")
            }
            // 首次也主动读一次：用户可能在开启前就已经复制好了
            pushClipboard()

            // 兜底轮询：部分 OEM（OPPO/HyperOS）的剪贴板服务不向 shell 进程派发 change 事件，
            // 注册成功但永远收不到回调。shell/root 进程本身能读剪贴板，所以直接轮询最可靠。
            startPolling()
        }.onFailure { Log.e(TAG, "start 失败", it) }
    }

    override fun stop(): Boolean = runCatching {
        stopPolling()
        removeListener()
        callback = null
        lastText = ""
        Log.i(TAG, "已停止剪贴板监听")
        true
    }.getOrElse {
        Log.e(TAG, "stop 失败", it)
        false
    }

    /**
     * 取 ClipboardManager。
     *
     * ⚠️ 必须优先用**系统 Context**，不能用 Shizuku 传进来的包 Context：
     * `sendClipChangedBroadcast()` 会按「注册监听时的包名」再查一次 appop，
     * 用 `createPackageContextAsUser` 得到的包 Context 注册时，包名是 com.ZenithLiteAura.app.nezo
     * 而进程 uid 是 0/shell，`checkPackage(uid, pkg)` 对不上 → 监听永远收不到回调。
     * 系统 Context（package=android）与 shell/root uid 匹配，才能拿到事件。
     */
    private fun resolveClipboard(): ClipboardManager? {
        if (systemClipboard != null) return systemClipboard

        // 1) 已有的 ActivityThread → getSystemContext
        val ctx: Context? = runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = runCatching { atClass.getMethod("currentActivityThread").invoke(null) }.getOrNull()
                ?: atClass.getMethod("systemMain").invoke(null)
            atClass.getMethod("getSystemContext").invoke(at) as Context
        }.getOrNull()

        if (ctx != null) {
            runCatching { ctx.getSystemService(ClipboardManager::class.java) }.getOrNull()?.let {
                systemClipboard = it
                Log.i(TAG, "使用系统 Context 的 ClipboardManager（package=${ctx.packageName}）")
                return it
            }
        }
        // 2) 兜底才用 Shizuku 给的包 Context
        return appContext?.let { c ->
            runCatching { c.getSystemService(ClipboardManager::class.java) }.getOrNull()?.also {
                Log.w(TAG, "回落到包 Context 的 ClipboardManager（可能收不到后台事件）")
            }
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        val t = Thread({
            Log.i(TAG, "启动剪贴板轮询兜底（800ms）")
            var tick = 0
            var nullNoticeShown = false
            while (polling) {
                val r = runCatching { pushClipboard() }
                r.exceptionOrNull()?.let { Log.w(TAG, "轮询异常：${it.javaClass.simpleName}: ${it.message}") }
                if (!nullNoticeShown && clipboard?.primaryClip == null) {
                    nullNoticeShown = true
                    Log.w(
                        TAG,
                        "读不到剪贴板内容：Shizuku 若以 root 启动，uid=0 与包归属校验不匹配" +
                            "（需要 uid=2000/shell）——请用「无线调试」方式启动 Shizuku",
                    )
                }
                if (tick < 3) {
                    val m = clipboard
                    val clip = runCatching { m?.primaryClip }.getOrNull()
                    Log.i(TAG, "轮询第${tick}次 manager=${m != null} clip=${clip != null} cb=${callback != null} lastLen=${lastText.length}")
                }
                tick++
                runCatching { Thread.sleep(800) }
            }
            Log.i(TAG, "剪贴板轮询已退出")
        }, "ClipNestClipPoll")
        t.isDaemon = true
        pollThread = t
        t.start()
    }

    private fun stopPolling() {
        polling = false
        pollThread = null
    }

    private fun removeListener() {
        val manager = clipboard
        val current = listener
        if (manager != null && current != null) {
            runCatching { manager.removePrimaryClipChangedListener(current) }
        }
        listener = null
    }

    /** 读一次剪贴板并回传应用进程；任何异常都吞掉（应用侧解绑后 binder 会 dead） */
    private fun pushClipboard() {
        runCatching {
            val cb = callback ?: return
            val manager = clipboard ?: resolveClipboard()?.also { clipboard = it } ?: return
            val clip = manager.primaryClip ?: return
            if (clip.itemCount <= 0) return

            val item = clip.getItemAt(0)
            val text = (item.text?.toString() ?: item.coerceToText(appContext)?.toString())?.trim()
            if (text.isNullOrEmpty()) return
            if (text == lastText) return

            lastText = text
            cb.onClipChanged(text)
            Log.i(TAG, "回传剪贴板文本 ${text.length} 字符")
        }.onFailure {
            Log.w(TAG, "回传剪贴板失败（应用侧可能已解绑）：${it.message}")
        }
    }
}
