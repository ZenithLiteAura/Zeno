package com.ZenithLiteAura.app.nezo.platform.shizuku

import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.util.Log

/**
 * `app_process` 入口：在 **uid=2000（shell）** 的独立进程里读一次剪贴板。
 *
 * 为什么需要它：Shizuku 若以 root 启动，UserService 进程是 uid=0，
 * 系统对「root 进程 + 包归属」的剪贴板读取会返回 null（appop 允许但内容为空）。
 * 如果设备上能通过 `su 2000 -c ...` 切到 uid=2000，就用这个类读，再由 shell 侧回传。
 *
 * 输出（stdout 单行，供 [RootShellFallback] 解析）：
 * - `OK:<base64(UTF-8 文本)>` 读到文本
 * - `NULL` 剪贴板为空 / 不是文本
 * - `ERR:<说明>` 失败
 *
 * 手动验证命令（设备上 root shell 里）：
 * ```
 * CLASSPATH=/data/app/.../base.apk app_process /system/bin \
 *   com.ZenithLiteAura.app.nezo.platform.shizuku.ClipProbe
 * ```
 */
object ClipProbe {

    private const val TAG = "NezoShizuku"

    @JvmStatic
    fun main(args: Array<String>) {
        val uid = android.os.Process.myUid()
        Log.i(TAG, "[ClipProbe] 启动 uid=$uid args=${args.joinToString(" ")}")

        // 首选：完全绕开 Context，直接走 IClipboard binder（callingPackage=com.android.shell）
        // uid=2000 进程用 shell 包名调用时归属校验能过，这是唯一可靠的路径：
        // app_process 里 ActivityThread.systemMain() 在 uid!=1000 时会抛 InvocationTargetException。
        val binderResult = readViaBinder(uid)
        if (binderResult != null) {
            println(binderResult)
            Log.i(TAG, "[ClipProbe] 结果(binder)：${binderResult.take(80)}")
            return
        }

        val result = try {
            val context = systemContext()
            if (context == null) {
                "ERR:no-system-context(uid=$uid)"
            } else {
                val manager = context.getSystemService(ClipboardManager::class.java)
                if (manager == null) {
                    "ERR:no-clipboard-manager(uid=$uid)"
                } else {
                    val hasClip = runCatching { manager.hasPrimaryClip() }.getOrNull()
                    val clip = runCatching { manager.primaryClip }
                    val clipError = clip.exceptionOrNull()
                    val data = clip.getOrNull()
                    Log.i(
                        TAG,
                        "[ClipProbe] uid=$uid pkg=${context.packageName} hasClip=$hasClip " +
                            "clip=${data != null} err=${clipError?.javaClass?.simpleName}:${clipError?.message}",
                    )

                    when {
                        clipError != null -> "ERR:${clipError.javaClass.simpleName}:${clipError.message}"
                        data == null || data.itemCount <= 0 -> "NULL"
                        else -> {
                            val item = data.getItemAt(0)
                            val text = item.text?.toString() ?: item.coerceToText(context)?.toString()
                            if (text.isNullOrEmpty()) {
                                "NULL"
                            } else {
                                "OK:" + Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            "ERR:${t.javaClass.simpleName}:${t.message}"
        }

        // 单行输出给 shell 侧解析
        println(result)
        Log.i(TAG, "[ClipProbe] 结果：${result.take(80)}")
    }

    /**
     * 直接用 ServiceManager + IClipboard.Stub 读剪贴板（不需要 Context）。
     *
     * 返回 null 表示这条路不可用（拿不到服务/反射失败），交给 Context 兜底。
     */
    private fun readViaBinder(uid: Int): String? = try {
        val smClass = Class.forName("android.os.ServiceManager")
        val binder = smClass.getMethod("getService", String::class.java).invoke(null, "clipboard")
            as? android.os.IBinder ?: return null

        val stubClass = Class.forName("android.content.IClipboard\$Stub")
        val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
        val service = asInterface.invoke(null, binder) ?: return null

        // Android 10~17 的签名：getPrimaryClip(String pkg, String attributionTag, int userId[, int deviceId])
        val methods = service.javaClass.methods.filter { it.name == "getPrimaryClip" }
        Log.i(TAG, "[ClipProbe] binder 可用方法：${methods.map { it.parameterTypes.size }}")

        var lastError: String? = null
        for (m in methods) {
            val types = m.parameterTypes
            val args = when (types.size) {
                3 -> arrayOf<Any?>("com.android.shell", null, 0)
                4 -> arrayOf<Any?>("com.android.shell", null, 0, 0)
                else -> continue
            }
            val r = runCatching { m.invoke(service, *args) }
            val err = r.exceptionOrNull()
            if (err != null) {
                lastError = "${err.javaClass.simpleName}:${err.message}"
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val clip = r.getOrNull() as? android.content.ClipData ?: return "NULL"
            if (clip.itemCount <= 0) return "NULL"
            val item = clip.getItemAt(0)
            val text = item.text?.toString() ?: item.coerceToText(null)?.toString()
            if (text.isNullOrEmpty()) return "NULL"
            return "OK:" + Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        Log.w(TAG, "[ClipProbe] binder 调用失败：$lastError")
        null
    } catch (t: Throwable) {
        Log.w(TAG, "[ClipProbe] binder 路径不可用：${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** app_process 里没有现成 Context：用 ActivityThread.systemMain() 拿系统 Context */
    private fun systemContext(): Context? = try {
        val atClass = Class.forName("android.app.ActivityThread")
        val activityThread = runCatching { atClass.getMethod("currentActivityThread").invoke(null) }.getOrNull()
            ?: atClass.getMethod("systemMain").invoke(null)
        atClass.getMethod("getSystemContext").invoke(activityThread) as? Context
    } catch (t: Throwable) {
        Log.w(TAG, "[ClipProbe] 取系统 Context 失败：${t.javaClass.simpleName}: ${t.message}")
        null
    }
}