package com.ZenithLiteAura.app.nezo.platform

import android.content.Context
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import com.ZenithLiteAura.app.nezo.platform.download.AutoDownloadHook
import com.clipnest.core.CaptureResult

/**
 * 采集引擎：所有收录通路（应用内监听 / 分享 / 悬浮球 / 通知按钮）最终都走这里。
 * 命中即落库（ClipRepo.captureText），并根据「收录提示」设置决定是否发系统通知。
 */
object CaptureEngine {

    /**
     * 收录一段文本。
     * @return null = 没命中任何规则（不打扰用户）；非 null = 命中，item 为落库后的条目
     */
    fun capture(context: Context, text: String?, source: String = "clipboard"): CaptureResult? {
        if (text.isNullOrBlank()) return null

        val result = runCatching { ClipRepo.captureText(text, source) }
            .onFailure { PlatformLog.error("采集失败（$source）", it) }
            .getOrNull() ?: return null

        val item = result.item ?: return result
        PlatformLog.info("收录成功[$source] ${item.platformName} ${item.author} ${item.url}")

        // 前台时 UI 自己会提示（Toast/卡片高亮），只在后台才给反馈：
        // ① 悬浮小卡片（有悬浮窗权限时，最直观）；② 没有悬浮窗权限时退回系统通知（settings.notify 控制）
        val settings = runCatching { ClipRepo.settings.value }.getOrNull()
        if (!AppVisibility.isForeground) {
            val toastShown = runCatching { CaptureToast.show(context.applicationContext, item) }
                .onFailure { PlatformLog.warn("收录悬浮提示失败：${it.message}") }
                .getOrDefault(false)

            if (!toastShown && settings?.notify == true) {
                runCatching { Notifier.notifyCaptured(context, item) }
                    .onFailure { PlatformLog.warn("收录通知失败：${it.message}") }
            }
        }

        // 自动下载钩子：抖音作品按 settings.autoDownload 异步解析并入队。
        // 钩子内部自己收敛所有异常，且永远先入队（后台不起前台服务），绝不影响采集主流程。
        runCatching { AutoDownloadHook.onCaptured(context.applicationContext, item) }
            .onFailure { PlatformLog.warn("自动下载钩子调用失败：${it.message}") }

        return result
    }

    /** 直接读剪贴板再收录（悬浮球 / 通知按钮用） */
    fun captureClipboard(context: Context, source: String = "clipboard"): CaptureResult? =
        capture(context, ClipboardIo.readText(context), source)
}