package com.ZenithLiteAura.app.nezo.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 「真·后台监听」：仅在应用被授予 [Manifest.permission.READ_CLIPBOARD_IN_BACKGROUND] 时生效。
 *
 * 这个权限是 signature|privileged 级，普通安装拿不到；把 APK 装进 /system/priv-app 并加入
 * privapp 白名单后才有（见 README「root 增强」与 tools/make-magisk-module.ps1）。
 * 没权限时本对象啥也不做，不会刷日志、不会耗电。
 */
object BackgroundClipMonitor {

    private const val PERMISSION = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastSignature = 0

    fun hasPermission(context: Context): Boolean = runCatching {
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 幂等；没权限直接返回 false */
    fun start(context: Context): Boolean {
        if (!hasPermission(context)) return false
        if (job != null) return true
        val appContext = context.applicationContext
        runCatching { ClipRepo.init(appContext) }
        job = scope.launch {
            PlatformLog.info("真·后台监听已启动（READ_CLIPBOARD_IN_BACKGROUND 已授予）")
            while (isActive) {
                runCatching {
                    if (ClipRepo.settings.value.monitor) {
                        val text = ClipboardIo.readText(appContext)
                        if (!text.isNullOrBlank()) {
                            val signature = ClipboardIo.signature(text)
                            if (signature != lastSignature) {
                                lastSignature = signature
                                val already = ClipRepo.firstMatchUrl(text)
                                    ?.let { url -> ClipRepo.containsUrl(url) } ?: false
                                if (!already) {
                                    CaptureEngine.capture(appContext, text, source = "background")
                                }
                            }
                        }
                    }
                }
                delay(700)
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
