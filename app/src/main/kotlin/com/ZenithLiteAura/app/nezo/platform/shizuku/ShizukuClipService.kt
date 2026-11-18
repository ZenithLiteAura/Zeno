package com.ZenithLiteAura.app.nezo.platform.shizuku

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.util.Log
import androidx.annotation.Keep
import com.ZenithLiteAura.app.nezo.BuildConfig

/**
 * 跑在 **Shizuku shell/root 进程** 里的剪贴板监听 UserService。
 *
 * ⚠️ Shizuku API v13 起 UserService 必须**直接继承 AIDL Stub**（类本身就是 IBinder），
 * 不能继承 `android.app.Service`（否则 starter 里 ClassCastException）。
 *
 * 本文件负责三件事：
 * 1. 在 shell 进程注册剪贴板监听 + 轮询兜底（部分 OEM 不派发 change 事件）；
 * 2. 启动时打一组**一次性诊断日志**（tag=NezoShizuku）：uid/包名/三种 Context 取 ClipboardManager 的结果、
 *    原始 binder 反射调用结果，用来判断「uid 不匹配」还是「OEM 剪贴板拒绝」；
 * 3. 如果当前是 **root 模式（uid=0）**，探测能否切到 uid=2000（`su 2000 -c id` 等），
 *    能切就用 `app_process` 跑 [ClipProbe] 作为兜底读剪贴板；切不了就老实记「不支持」。
 *
 * shell 进程写不进应用私有目录，所以只用 Log（TAG=NezoShizuku），需要时用 `adb logcat -s NezoShizuku` 看。
 */
class ShizukuClipService : IClipWatcher.Stub {

    private companion object {
        const val TAG = "NezoShizuku"
        /** 读不到内容时的诊断重试间隔 */
        const val DIAG_CLIP_RETRY_MS = 800L
    }

    private var callback: IClipWatcherCallback? = null
    private var clipboard: ClipboardManager? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastText: String = ""
    private var appContext: Context? = null
    private var pollThread: Thread? = null
    private var rootFallback: RootShellFallback? = null

    @Volatile
    private var polling: Boolean = false
    private var systemClipboard: ClipboardManager? = null
    private var diagnosticsDone = false

    /** 无参构造是必需的（老版本 Shizuku starter 用 Class.newInstance()）。 */
    constructor() : super() {
        Log.i(TAG, "ShizukuClipService() 构造 · uid=${Process.myUid()}")
    }

    /** Context 构造仅 Shizuku API v13+ 可用；@Keep 防止 R8 裁掉。 */
    @Keep
    constructor(context: Context) : super() {
        appContext = context
        Log.i(TAG, "ShizukuClipService(context) 构造 · uid=${Process.myUid()}")
    }

    // ── AIDL 入口 ─────────────────────────────────────────────────────────

    override fun start(cb: IClipWatcherCallback?) {
        // 任何 Throwable 都不能从 binder 方法逃逸：崩掉 shell 进程会让客户端一直「连接断开」
        try {
            startInternal(cb)
        } catch (t: Throwable) {
            Log.e(TAG, "start 异常（已兜住，不影响进程）", t)
        }
    }

    private fun startInternal(cb: IClipWatcherCallback?) {
        if (cb == null) {
            Log.w(TAG, "start() 收到空回调，忽略")
            return
        }
        // ★ uid=0（Shizuku root 模式）绝对不能读剪贴板：
        // 系统会以 "Calling uid 0 does not own package android" 为由「清空剪贴板」，
        // 用户刚复制的内容会直接消失。直接返回，保持降级。
        if (android.os.Process.myUid() == 0) {
            Log.w(TAG, "uid=0（root 模式）：不读剪贴板、不轮询（否则系统会清空剪贴板）")
            return
        }

        runCatching {
            callback = cb
            val manager = resolveClipboard()
            clipboard = manager
            if (manager == null) {
                Log.e(TAG, "拿不到 ClipboardManager")
            } else if (listener == null) {
                val newListener = ClipboardManager.OnPrimaryClipChangedListener { pushClipboard() }
                manager.addPrimaryClipChangedListener(newListener)
                listener = newListener
                Log.i(TAG, "已在 shell 进程注册剪贴板监听（uid=${Process.myUid()}）")
            }

            // 一次性诊断默认关闭（Android 17 上隐藏 API 反射可能直接抛/拖慢 binder 调用，
            // 慢 binder 会被 Shizuku 判为 UserService 无响应而回收）：
            //   adb shell touch /data/local/tmp/nezo_shizuku_diag   （debug 包才生效）
            if (!diagnosticsDone && diagEnabled()) {
                diagnosticsDone = true
                // 后台线程跑，绝不在 binder 线程做反射/诊断
                Thread({ runCatching { logDiagnostics() } }, "NezoShizukuDiag").apply {
                    isDaemon = true
                    start()
                }
            }

            // 首次主动读一次
            pushClipboard()

            // 轮询兜底：OPPO/HyperOS 等 OEM 可能不向 shell 进程派发 change 事件
            startPolling()

            // 【禁用 root 兜底】实测：uid=0 进程读剪贴板会让 ClipboardService 直接「清空剪贴板」
            // （日志 Could not grant permission to primary clip. Clearing clipboard. pkg=android），
            // 这会把用户刚复制的内容弄丢，副作用远大于收益 → 一律不启动。
            if (Process.myUid() == 0) {
                Log.w(TAG, "Shizuku 以 root 运行：本机不支持后台读剪贴板（请改用无线调试启动 Shizuku 或刷 Magisk 模块）")
            }
        }.onFailure { Log.e(TAG, "start 失败", it) }
    }

    override fun stop(): Boolean = try {
        stopInternal()
    } catch (t: Throwable) {
        Log.e(TAG, "stop 异常（已兜住）", t)
        false
    }

    private fun stopInternal(): Boolean = runCatching {
        stopPolling()
        rootFallback?.stop()
        rootFallback = null
        removeListener()
        callback = null
        lastText = ""
        Log.i(TAG, "已停止剪贴板监听")
        true
    }.getOrElse {
        Log.e(TAG, "stop 失败", it)
        false
    }

    // ── ClipboardManager ──────────────────────────────────────────────────

    /**
     * 取 ClipboardManager：优先系统 Context（package=android，与 shell/root uid 匹配），
     * 其次才用 Shizuku 传进来的包 Context。
     */
    private fun resolveClipboard(): ClipboardManager? = try {
        resolveClipboardUnsafe()
    } catch (t: Throwable) {
        Log.e(TAG, "取 ClipboardManager 异常（已兜住）", t)
        null
    }

    private fun resolveClipboardUnsafe(): ClipboardManager? {
        systemClipboard?.let { return it }

        // uid=2000（shell / 无线调试）时剪贴板按「uid + 包归属」校验：
        // 必须用 com.android.shell 的包 Context；pkg=android 的系统 Context 会「允许但返回空」。
        if (android.os.Process.myUid() == 2000) {
            shellPackageContext()?.let { ctx ->
                runCatching { ctx.getSystemService(ClipboardManager::class.java) }.getOrNull()?.let {
                    systemClipboard = it
                    Log.i(TAG, "uid=2000：使用 com.android.shell 包 Context（pkg=${ctx.packageName}）")
                    return it
                }
            }
            Log.w(TAG, "uid=2000：拿不到 com.android.shell 包 Context，回退系统 Context/binder")
        }

        systemContext()?.let { ctx ->
            runCatching { ctx.getSystemService(ClipboardManager::class.java) }.getOrNull()?.let {
                systemClipboard = it
                Log.i(TAG, "使用系统 Context 的 ClipboardManager（package=${ctx.packageName}）")
                return it
            }
        }
        return appContext?.let { c ->
            runCatching { c.getSystemService(ClipboardManager::class.java) }.getOrNull()?.also {
                Log.w(TAG, "回落到包 Context 的 ClipboardManager（可能收不到后台事件）")
            }
        }
    }

    private fun systemContext(): Context? = runCatching {
        val atClass = Class.forName("android.app.ActivityThread")
        val at = runCatching { atClass.getMethod("currentActivityThread").invoke(null) }.getOrNull()
            ?: runCatching { atClass.getMethod("systemMain").invoke(null) }.getOrNull()
            ?: return@runCatching null
        atClass.getMethod("getSystemContext").invoke(at) as? Context
    }.getOrNull()

    // ── 轮询 ──────────────────────────────────────────────────────────────

    private fun startPolling() {
        if (polling) return
        polling = true
        val t = Thread({
            Log.i(TAG, "启动剪贴板轮询兜底（800ms）")
            var tick = 0
            var nullNoticeShown = false
            while (polling) {
                runCatching { pushClipboard() }
                    .exceptionOrNull()
                    ?.let { Log.w(TAG, "轮询异常：${it.javaClass.simpleName}: ${it.message}") }

                if (!nullNoticeShown && clipboard?.primaryClip == null) {
                    nullNoticeShown = true
                    Log.w(
                        TAG,
                        "读不到剪贴板内容：uid=${Process.myUid()}；若 Shizuku 是 root 启动（uid=0），" +
                            "系统会允许调用（appop allowed）但按「uid+包归属」返回 null —— 见上方 [diag] 日志",
                    )
                }

                if (tick < 3) {
                    val m = clipboard
                    val clip = runCatching { m?.primaryClip }.getOrNull()
                    Log.i(TAG, "轮询第${tick}次 manager=${m != null} clip=${clip != null} cb=${callback != null} lastLen=${lastText.length}")
                }
                tick++
                runCatching { Thread.sleep(DIAG_CLIP_RETRY_MS) }
            }
            Log.i(TAG, "剪贴板轮询已退出")
        }, "NezoClipPoll")
        t.isDaemon = true
        pollThread = t
        t.start()
    }

    private fun stopPolling() {
        polling = false
        pollThread = null
    }

    /** 用隐藏 API 取 com.android.shell 的包 Context（uid=2000 下可用） */
    private fun shellPackageContext(): Context? = runCatching {
        val atClass = Class.forName("android.app.ActivityThread")
        val at = runCatching { atClass.getMethod("currentActivityThread").invoke(null) }.getOrNull()
            ?: atClass.getMethod("systemMain").invoke(null)
        val systemCtx = atClass.getMethod("getSystemContext").invoke(at) as Context
        val userHandleClass = Class.forName("android.os.UserHandle")
        val user0 = userHandleClass.getMethod("getUserHandleForUid", Int::class.javaPrimitiveType)
            .invoke(null, 0)
        val m = Context::class.java.getMethod(
            "createPackageContextAsUser", String::class.java, Int::class.javaPrimitiveType, userHandleClass,
        )
        m.invoke(systemCtx, "com.android.shell", 0, user0) as? Context
    }.getOrNull()

    /**
     * 直接走 IClipboard binder（不需要 Context）。
     * uid=2000 + callingPackage=com.android.shell 时归属校验能过；
     * Android 14+ 的签名是 (String pkg, String attributionTag, int userId, int deviceId)。
     */
    private fun readViaBinder(): android.content.ClipData? = runCatching {
        val smClass = Class.forName("android.os.ServiceManager")
        val binder = smClass.getMethod("getService", String::class.java).invoke(null, "clipboard")
            as? android.os.IBinder ?: return@runCatching null
        val stubClass = Class.forName("android.content.IClipboard\$Stub")
        val service = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder) ?: return@runCatching null

        for (m in service.javaClass.methods.filter { it.name == "getPrimaryClip" }) {
            val args: Array<Any?> = when (m.parameterTypes.size) {
                3 -> arrayOf("com.android.shell", null, 0)
                4 -> arrayOf("com.android.shell", null, 0, 0)
                else -> continue
            }
            val r = runCatching { m.invoke(service, *args) }
            val clip = r.getOrNull() as? android.content.ClipData ?: continue
            if (clip.itemCount > 0) return@runCatching clip
        }
        null
    }.getOrNull()

    private fun removeListener() {
        val manager = clipboard
        val current = listener
        if (manager != null && current != null) {
            runCatching { manager.removePrimaryClipChangedListener(current) }
        }
        listener = null
    }

    // ── 读 + 回传 ─────────────────────────────────────────────────────────

    private fun pushClipboard() {
        runCatching {
            val cb = callback ?: return
            val manager = clipboard ?: resolveClipboard()?.also { clipboard = it }
            // Context 路径可能抛 SecurityException（包名归属不匹配），不能把异常当"没有内容"直接返回，
            // 必须落到 binder 直读（callingPackage=com.android.shell + 进程 uid=2000 才能过校验）
            val fromContext = runCatching { manager?.primaryClip }.getOrNull()
            val binderClip = if (fromContext == null && android.os.Process.myUid() == 2000) readViaBinder() else null
            val clip = fromContext ?: binderClip ?: return
            if (clip.itemCount <= 0) return

            val item = clip.getItemAt(0)
            val text = (item.text?.toString() ?: item.coerceToText(appContext)?.toString())?.trim()
            if (text.isNullOrEmpty()) return
            if (text == lastText) return

            // root 兜底（ClipProbe）可能已经有更新内容，避免旧值覆盖
            lastText = text
            cb.onClipChanged(text)
            Log.i(TAG, "回传剪贴板文本 ${text.length} 字符")
        }.onFailure {
            Log.w(TAG, "回传剪贴板失败（应用侧可能已解绑）：${it.message}")
        }
    }

    /** root 兜底路径读到文本后调用（同样走回传去重） */
    private fun deliverFromRootFallback(text: String) {
        runCatching {
            val cb = callback ?: return
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == lastText) return
            lastText = trimmed
            cb.onClipChanged(trimmed)
            Log.i(TAG, "[root-fallback] 回传剪贴板文本 ${trimmed.length} 字符")
        }.onFailure { Log.w(TAG, "[root-fallback] 回传失败：${it.message}") }
    }

    // ── root 模式兜底 ─────────────────────────────────────────────────────

    @Suppress("unused")
    private fun startRootFallback() {
        // 【已禁用】见 start() 里的说明：uid=0 读剪贴板会触发系统清空剪贴板。
        if (true) return
        if (rootFallback != null) return
        val apkPath = runCatching { appContext?.applicationInfo?.sourceDir }.getOrNull()
        Log.i(TAG, "[root-fallback] 检测到 uid=0（root 模式），APK=$apkPath")
        val fallback = RootShellFallback(apkPath = apkPath, onText = { text -> deliverFromRootFallback(text) })
        rootFallback = fallback
        if (!fallback.start()) {
            rootFallback = null
            Log.w(TAG, "[root-fallback] 无法切到 uid=2000，root 模式下不支持后台读剪贴板（请改用无线调试启动 Shizuku）")
        }
    }

    // ── 一次性诊断 ────────────────────────────────────────────────────────

    /**
     * 打印诊断信息（只跑一次），用于区分：
     * - 「uid 不匹配」：系统 Context / 包 Context 都返回 null，但 appop 允许；
     * - 「OEM 剪贴板拒绝」：hasPrimaryClip 为 true 但 primaryClip 为 null、或直接抛 SecurityException。
     */
    /** 诊断开关：默认关闭；debug 包 + 创建下面这个文件（或 setprop）才开 */
    private fun diagEnabled(): Boolean {
        if (!BuildConfig.DEBUG) return false
        return runCatching { java.io.File("/data/local/tmp/nezo_shizuku_diag").exists() }.getOrDefault(false) ||
            runCatching {
                val clazz = Class.forName("android.os.SystemProperties")
                val get = clazz.getMethod("get", String::class.java, String::class.java)
                get.invoke(null, "debug.nezo.shizuku.diag", "0") == "1"
            }.getOrDefault(false)
    }

    private fun logDiagnostics() {
        try {
            logDiagnosticsUnsafe()
        } catch (t: Throwable) {
            Log.w(TAG, "[diag] 诊断异常（已兜住）：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logDiagnosticsUnsafe() {
        val uid = Process.myUid()
        val pid = Process.myPid()
        // UserHandle.getUserId 是隐藏 API，这里用公开的 Process.myUserHandle() 打日志即可
        val userHandle = runCatching { Process.myUserHandle() }.getOrNull()
        val appUid = runCatching { appContext?.applicationInfo?.uid }.getOrNull()

        Log.i(TAG, "[diag] ===== 开始诊断 uid=$uid pid=$pid userHandle=$userHandle sdk=${Build.VERSION.SDK_INT} =====")
        Log.i(TAG, "[diag] appContext: pkg=${appContext?.packageName} appUid=$appUid")

        // ① 系统 Context（package=android）
        val sysCtx = systemContext()
        Log.i(TAG, "[diag] A/system-ctx: ctx=${sysCtx != null} pkg=${sysCtx?.packageName}")
        probeClipboard("A/system", runCatching { sysCtx?.getSystemService(ClipboardManager::class.java) }.getOrNull())

        // ② shell 包 Context（createPackageContextAsUser 是隐藏 API，反射调用）
        val shellCtx = shellPackageContext(uid)
        Log.i(TAG, "[diag] B/shell-pkg-ctx: ctx=${shellCtx != null} pkg=${shellCtx?.packageName}")
        probeClipboard("B/shell-pkg", runCatching { shellCtx?.getSystemService(ClipboardManager::class.java) }.getOrNull())

        // ③ 原始 binder（ServiceManager + IClipboard 反射，逐个 getPrimaryClip 重载试）
        Log.i(TAG, "[diag] C/raw-binder: ${probeRawClipboardBinder()}")

        // ④ Shizuku 传进来的包 Context
        probeClipboard("D/app-ctx", runCatching { appContext?.getSystemService(ClipboardManager::class.java) }.getOrNull())

        Log.i(TAG, "[diag] ===== 诊断结束 =====")
    }

    /** `Context.createPackageContextAsUser` 是隐藏 API，反射调用；失败返回 null（只用于诊断） */
    private fun shellPackageContext(uid: Int): Context? = runCatching {
        val ctx = appContext ?: return@runCatching null
        val userHandle = UserHandle.getUserHandleForUid(uid)
        val method = Context::class.java.getMethod(
            "createPackageContextAsUser",
            String::class.java,
            Int::class.javaPrimitiveType,
            UserHandle::class.java,
        )
        method.invoke(ctx, "com.android.shell", 0, userHandle) as? Context
    }.onFailure {
        Log.w(TAG, "[diag] createPackageContextAsUser 反射失败：${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()
    /** 对单个 ClipboardManager 打 hasPrimaryClip / description / clip / 异常 */
    private fun probeClipboard(label: String, manager: ClipboardManager?) {
        if (manager == null) {
            Log.w(TAG, "[diag] $label: manager=null")
            return
        }
        val hasClip = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.hasPrimaryClip() else manager.primaryClip != null
        }.onFailure { Log.w(TAG, "[diag] $label: hasPrimaryClip 异常 ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()

        val description = runCatching {
            manager.primaryClipDescription?.let { d ->
                val types = (0 until d.mimeTypeCount).joinToString(",") { d.getMimeType(it) }
                "types=[$types] label=${d.label}"
            }
        }.onFailure { Log.w(TAG, "[diag] $label: description 异常 ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()

        val clipResult = runCatching { manager.primaryClip }
        clipResult.exceptionOrNull()?.let {
            Log.w(TAG, "[diag] $label: primaryClip 异常 ${it.javaClass.simpleName}: ${it.message}")
        }
        val clip = clipResult.getOrNull()
        val text = runCatching {
            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }.getOrNull()

        Log.i(
            TAG,
            "[diag] $label: manager=${manager.javaClass.name} hasClip=$hasClip desc=$description " +
                "clip=${clip != null} items=${clip?.itemCount ?: -1} textLen=${text?.length ?: -1} textPreview=${text?.take(12)}",
        )
    }

    /**
     * 直接走 `ServiceManager.getService("clipboard")` + `IClipboard$Stub.asInterface`，
     * 枚举所有 `getPrimaryClip(...)` 重载逐个调用（不同 Android 版本参数不同）。
     */
    private fun probeRawClipboardBinder(): String {
        val sb = StringBuilder()
        runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "clipboard") as? android.os.IBinder
            if (binder == null) {
                sb.append("service=null")
                return sb.toString()
            }
            sb.append("service=ok ")

            val iface = Class.forName("android.content.IClipboard\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)
            if (iface == null) {
                sb.append("iface=null")
                return sb.toString()
            }

            val methods = iface.javaClass.methods.filter { it.name == "getPrimaryClip" }
            sb.append("methods=${methods.size} ")
            for (method in methods) {
                val signature = method.parameterTypes.joinToString(",") { it.simpleName }
                val args = buildRawBinderArgs(method.parameterTypes)
                val result = runCatching { method.invoke(iface, *args) }
                val outcome = result.fold(
                    onSuccess = { value ->
                        if (value == null) {
                            "null"
                        } else {
                            val count = runCatching { value.javaClass.getMethod("getItemCount").invoke(value) }.getOrNull()
                            "clip(items=$count)"
                        }
                    },
                    onFailure = { e -> "ERR:${e.javaClass.simpleName}:${e.message}" },
                )
                sb.append("[$signature => $outcome] ")
            }
        }.onFailure {
            sb.append("ERR:${it.javaClass.simpleName}:${it.message}")
        }
        return sb.toString()
    }

    /** 按「第一个 String=pkg(com.android.shell)，第二个 String=attributionTag(null)，int 全 0」构造参数 */
    private fun buildRawBinderArgs(types: Array<Class<*>>): Array<Any?> {
        var stringSeen = 0
        return Array(types.size) { index ->
            when (types[index]) {
                String::class.java -> if (stringSeen++ == 0) "com.android.shell" else null
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
    }
}