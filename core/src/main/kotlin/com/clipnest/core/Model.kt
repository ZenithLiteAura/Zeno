package com.clipnest.core

/**
 * ClipNest 剪巢 · 数据模型（冻结契约）
 * 与 Windows 原型 src/ClipNest.Core/Contract.cs 字段一一对应，方便迁移与对照。
 */
data class ClipItem(
    val id: String = "",
    val platform: String = "",
    val platformName: String = "",
    val ruleId: String = "",
    val author: String = "",
    val workType: String = "",
    val title: String = "",
    val url: String = "",
    val rate: String = "",
    val extra: String = "",
    val raw: String = "",
    val capturedAt: Long = System.currentTimeMillis(),
    val source: String = "clipboard",
    val starred: Boolean = false,
    val note: String = "",
    val coverUrl: String? = null,
    val meta: String? = null,
)

data class AppSettings(
    val monitor: Boolean = true,
    val autostart: Boolean = true,
    val notify: Boolean = true,
    val dedupeByUrl: Boolean = true,
    val autostartService: Boolean = true,
    val bubble: Boolean = false,
    val theme: String = "auto",
    val pollIntervalMs: Int = 400,
    val maxItems: Int = 5000,
    val capturePlainLinks: Boolean = false,
    /** 自动下载：off | images | all */
    val autoDownload: String = "off",
    /** ZIP 命名规则：1=序号_标题 2=标题_HD 3=仅标题 4=仅序号 */
    val zipNamingRule: String = "1",
    /** 自动下载仅在 Wi-Fi 下进行 */
    val downloadWifiOnly: Boolean = true,
    /** Shizuku 真·后台剪贴板监听（需用户安装并授权 Shizuku） */
    val shizukuMonitor: Boolean = false,
)

data class CaptureRule(
    val id: String,
    val name: String,
    val platform: String,
    val platformName: String,
    val pattern: String,
    val description: String = "",
    val sample: String = "",
    val builtin: Boolean = true,
    val enabled: Boolean = true,
)

data class CaptureResult(
    val matched: Boolean,
    val normalText: String = "",
    val item: ClipItem? = null,
    val rule: CaptureRule? = null,
)

enum class ItemsChangeKind { Added, Updated, Removed, Cleared, Reloaded }
