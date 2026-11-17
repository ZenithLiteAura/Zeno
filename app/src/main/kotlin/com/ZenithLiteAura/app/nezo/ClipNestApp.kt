package com.ZenithLiteAura.app.nezo

import android.app.Application
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import com.ZenithLiteAura.app.nezo.platform.AppVisibility
import com.ZenithLiteAura.app.nezo.platform.MonitorService
import com.ZenithLiteAura.app.nezo.platform.Notifier
import com.ZenithLiteAura.app.nezo.platform.PlatformBridge
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import com.ZenithLiteAura.app.nezo.platform.shizuku.ShizukuClipClient
import com.ZenithLiteAura.app.nezo.platform.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Nezo Application 入口：
 * 1) 初始化数据层（ClipRepo）2) 建通知渠道 3) 前后台状态追踪
 * 4) 按 settings.autostartService 同步前台服务（设置变更时自动跟随）。
 */
class ClipNestApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // 第一件事：装崩溃自诊断（要早于任何可能抛异常的三方初始化）
        runCatching { CrashGuard.install(this) }

        // 崩溃展示页跑在独立进程 :crash，只需装好守卫，不做重初始化
        if (processName().endsWith(":crash")) return

        runCatching { ClipRepo.init(this) }
            .onFailure { PlatformLog.error("数据层初始化失败", it) }

        AppVisibility.attach(this)
        Notifier.ensureChannels(this)

        PlatformLog.info("Nezo启动 · 服务运行=${runCatching { MonitorService.isRunning(this) }.getOrDefault(false)}")

        // Shizuku 后台剪贴板：先注册 binder 就绪/断开监听，再按设置尝试连接。
        // 没装 / 没运行 / 没授权 / Android 17 官方 bug 都只落一条状态，不影响应用内监听。
        runCatching { ShizukuClipClient.init(this) }
        // 冷启动不阻塞：稍微延后再尝试连接（未安装时 pingBinder 会快速返回，但避免 binder 往返影响首帧）
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { ShizukuClipClient.sync(this) }
        }, 800L)

        // 自动检查更新：先把上次结果读出来给 UI，再延迟 4s 请求 GitHub（manual=false，24h 内不重复请求）。
        // 全过程在 IO 协程里，失败只落 UpdateState.error，不影响启动。
        runCatching { UpdateChecker.loadCached(this) }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { PlatformBridge.checkForUpdates(this, manual = false) }
        }, 4_000L)

        // autostartService / bubble / shizukuMonitor 变化 → 同步服务与 Shizuku 监听
        scope.launch {
            ClipRepo.settings
                .map { Triple(it.autostartService, it.bubble, it.shizukuMonitor) }
                .distinctUntilChanged()
                .collect {
                    runCatching { PlatformBridge.syncServices(this@ClipNestApp) }
                        .onFailure { e -> PlatformLog.warn("同步前台服务失败：${e.message}") }
                    runCatching { ShizukuClipClient.sync(this@ClipNestApp) }
                        .onFailure { e -> PlatformLog.warn("同步 Shizuku 监听失败：${e.message}") }
                }
        }

        // 冷启动的服务对齐放在 MainActivity.onStart（App 启动瞬间起前台服务在部分 ROM 会被拒）
    }

    private fun processName(): String = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else applicationInfo.processName.orEmpty()
    }.getOrDefault("")
}