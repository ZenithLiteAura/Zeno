package com.ZenithLiteAura.app.nezo.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ZenithLiteAura.app.nezo.data.ClipRepo

/**
 * 开机自启（+ 应用更新后）恢复监听服务。
 * 国产 ROM 可能拦截，设置页提供了白名单引导入口（PermissionHelper.openAutoStartSettings）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val accepted = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!accepted) return

        runCatching {
            ClipRepo.init(context.applicationContext)
            val settings = ClipRepo.settings.value
            PlatformLog.info(
                "开机自启检查（$action）：autostart=${settings.autostart} autostartService=${settings.autostartService}"
            )

            // 「开机自启」与「常驻前台服务」是两道独立的闸：都打开才会在开机后拉起服务
            if (settings.autostart && settings.autostartService) {
                MonitorService.start(context)
            } else {
                PlatformLog.info("开机自启已关闭，跳过启动服务")
            }
        }.onFailure { PlatformLog.error("开机自启处理失败", it) }
    }
}