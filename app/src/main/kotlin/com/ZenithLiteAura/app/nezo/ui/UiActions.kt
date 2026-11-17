package com.ZenithLiteAura.app.nezo.ui

import com.clipnest.core.AppSettings
import com.clipnest.core.CaptureResult
import com.clipnest.core.ClipItem

/**
 * UI → 平台层的动作集合（Lead 冻结契约）。
 * UI 只调这些回调，不直接碰 Service / Clipboard / Intent。
 */
data class UiActions(
    val setStar: (String, Boolean) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
    val clearAll: () -> Unit = {},
    val captureText: (String) -> Unit = {},
    /** 一键收录当前剪贴板（dock 右侧圆钮 / 通知按钮用） */
    val captureClipboard: () -> Unit = {},
    val preview: (String) -> List<CaptureResult> = { emptyList() },
    val updateSettings: ((AppSettings) -> AppSettings) -> Unit = {},
    val setRuleEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val requestBubblePermission: () -> Unit = {},
    val requestNotificationPermission: () -> Unit = {},
    /** 打开厂商「自启动」管理页（HyperOS: 应用设置 → 自启动） */
    val openAutoStartSettings: () -> Unit = {},
    /** 申请电池优化白名单（让后台服务不被回收） */
    val requestBatteryWhitelist: () -> Unit = {},
    val requestOverlayAndStart: () -> Unit = {},
    val exportText: (String) -> Unit = {},
    val openUrl: (String) -> Unit = {},
    val copyText: (String) -> Unit = {},
    val showToast: (String) -> Unit = {},
)
