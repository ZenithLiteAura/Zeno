package com.ZenithLiteAura.app.nezo.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ZenithLiteAura.app.nezo.BuildConfig
import com.ZenithLiteAura.app.nezo.platform.PlatformBridge
import com.ZenithLiteAura.app.nezo.ui.UiActions
import com.ZenithLiteAura.app.nezo.ui.pageContentPadding
import com.clipnest.core.AppSettings
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置二级页：开关 / 选项 / 状态列表，只有标题，不放解释文案。
 * 顶栏由 `ClipHomeScreen` 渲染（标题 + 返回箭头），这里只画内容。
 */
@Composable
fun SettingsDetailPage(
    route: SettingsRoute,
    settings: AppSettings,
    itemCount: Int,
    actions: UiActions,
    onClearAll: () -> Unit,
) {
    when (route) {
        SettingsRoute.Bubble -> BubblePage(settings, actions)
        SettingsRoute.Notification -> NotificationPage(settings, actions)
        SettingsRoute.Shizuku -> ShizukuPage(settings, actions)
        SettingsRoute.Permissions -> PermissionsPage(actions)
        SettingsRoute.Dedupe -> DedupePage(settings, actions)
        SettingsRoute.AutoDownload -> AutoDownloadPage(settings, actions)
        SettingsRoute.NamingRule -> NamingRulePage(settings, actions)
        SettingsRoute.Theme -> ThemePage(settings, actions)
        SettingsRoute.Export -> ExportPage(actions)
        SettingsRoute.Clear -> ClearPage(itemCount, onClearAll)
        // 回收站页由 ClipHomeScreen 单独渲染（需要 ConfirmState 与 toast）
        SettingsRoute.Trash -> Unit
        SettingsRoute.AboutInfo -> AboutInfoPage(actions)
        SettingsRoute.Hub -> Unit
    }
}

@Composable
private fun DetailList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .scrollEndHaptic(),
        contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/* ── 收录方式 ─────────────────────────────────────────────────────── */

@Composable
private fun BubblePage(settings: AppSettings, actions: UiActions) {
    val context = LocalContext.current
    val overlay = remember { PlatformBridge.isOverlayGranted(context) }
    DetailList {
        item(key = "bubble") {
            SettingsGroup("悬浮球") {
                SettingsPlainSwitch(
                    title = "悬浮球一键收录",
                    checked = settings.bubble,
                    onCheckedChange = { on ->
                        actions.updateSettings { it.copy(bubble = on) }
                        if (on) actions.requestOverlayAndStart()
                    },
                )
                SettingsLabelRow(
                    title = "悬浮窗权限",
                    value = if (overlay) "已授权" else "去授权",
                    onClick = { actions.requestBubblePermission() },
                )
            }
        }
    }
}

@Composable
private fun NotificationPage(settings: AppSettings, actions: UiActions) {
    val context = LocalContext.current
    val granted = remember { PlatformBridge.isNotificationGranted(context) }
    val running = remember { PlatformBridge.isServiceRunning(context) }
    DetailList {
        item(key = "notification") {
            SettingsGroup("通知栏") {
                SettingsPlainSwitch(
                    title = "常驻前台服务",
                    checked = settings.autostartService,
                    onCheckedChange = { on -> actions.updateSettings { it.copy(autostartService = on) } },
                )
                SettingsLabelRow(
                    title = "服务状态",
                    value = if (running) "运行中" else "已停止",
                )
                SettingsLabelRow(
                    title = "通知权限",
                    value = if (granted) "已授权" else "去授权",
                    onClick = { actions.requestNotificationPermission() },
                )
            }
        }
    }
}

@Composable
private fun ShizukuPage(settings: AppSettings, actions: UiActions) {
    val context = LocalContext.current
    val activity = context as? Activity
    val status by PlatformBridge.shizukuStatusFlow.collectAsState()
    // 状态只显示短值（完整文案太长会挤标题行）
    val shortStatus = when {
        status.contains("root 运行") -> "root 模式 · 读不到剪贴板"
        status.contains("已连接") -> "已连接"
        status.contains("未授权") -> "未授权"
        status.contains("未安装") -> "未安装"
        status.contains("未运行") -> "未运行"
        status.contains("正在连接") -> "连接中"
        else -> status
    }
    val rootMode = PlatformBridge.shizukuIsRootMode()
    DetailList {
        item(key = "shizuku") {
            SettingsGroup("后台自动监听") {
                SettingsPlainSwitch(
                    title = "Shizuku 后台监听",
                    checked = settings.shizukuMonitor,
                    onCheckedChange = { on ->
                        actions.updateSettings { it.copy(shizukuMonitor = on) }
                        if (on) {
                            activity?.let { PlatformBridge.shizukuEnable(it) }
                        } else {
                            PlatformBridge.shizukuDisable()
                        }
                    },
                )
                SettingsLabelRow(
                    title = "Shizuku 状态",
                    value = shortStatus,
                    onClick = { activity?.let { PlatformBridge.shizukuEnable(it) } },
                )
                SettingsLabelRow(
                    title = "打开 Shizuku",
                    // 修复误判：HyperOS/Android 11+ 包可见性会让 getPackageInfo 认为「未安装」
                    value = PlatformBridge.shizukuOpenLabel(context),
                    onClick = {
                        when {
                            // root 启动的 Shizuku 读不到剪贴板，引导去 Shizuku 里改用无线调试
                            PlatformBridge.shizukuIsRootMode() -> PlatformBridge.openShizukuApp(context)
                            !PlatformBridge.shizukuInstalled(context) -> PlatformBridge.openShizukuWebsite(context)
                            else -> PlatformBridge.openShizukuApp(context)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionsPage(actions: UiActions) {
    val context = LocalContext.current
    val activity = context as? Activity
    val overlay = remember { PlatformBridge.isOverlayGranted(context) }
    val notification = remember { PlatformBridge.isNotificationGranted(context) }
    val backgroundClip = remember { PlatformBridge.hasBackgroundClipboardPermission(context) }
    val batteryIgnored = remember { PlatformBridge.isBatteryOptimizationIgnored(context) }

    DetailList {
        item(key = "permissions") {
            SettingsGroup("权限检查") {
                SettingsLabelRow(
                    title = "悬浮窗权限",
                    value = if (overlay) "已授权" else "去授权",
                    onClick = { actions.requestBubblePermission() },
                )
                SettingsLabelRow(
                    title = "通知权限",
                    value = if (notification) "已授权" else "去授权",
                    onClick = { actions.requestNotificationPermission() },
                )
                SettingsLabelRow(
                    title = "后台读剪贴板",
                    value = if (backgroundClip) "已授权" else "受系统限制",
                )
                SettingsLabelRow(
                    title = "电池优化",
                    value = if (batteryIgnored) "已忽略" else "去设置",
                    onClick = { activity?.let { PlatformBridge.requestIgnoreBatteryOptimization(it) } },
                )
                SettingsLabelRow(
                    title = "自启动权限",
                    value = "去设置",
                    onClick = { PlatformBridge.openAutoStartSettings(context) },
                )
            }
        }
    }
}

@Composable
private fun DedupePage(settings: AppSettings, actions: UiActions) {
    DetailList {
        item(key = "dedupe") {
            SettingsGroup("去重与兜底") {
                SettingsPlainSwitch(
                    title = "按链接去重",
                    checked = settings.dedupeByUrl,
                    onCheckedChange = { on -> actions.updateSettings { it.copy(dedupeByUrl = on) } },
                )
                SettingsPlainSwitch(
                    title = "收录普通链接",
                    checked = settings.capturePlainLinks,
                    onCheckedChange = { on -> actions.updateSettings { it.copy(capturePlainLinks = on) } },
                )
            }
        }
    }
}

/* ── 下载 ─────────────────────────────────────────────────────────── */

private val AUTO_OPTIONS = listOf(
    "off" to "关闭",
    "images" to "仅图片",
    "all" to "图片+视频",
)

private val NAMING_OPTIONS = listOf(
    "1" to "序号_标题",
    "2" to "标题_清晰度",
    "3" to "仅标题",
    "4" to "仅序号",
)

@Composable
private fun AutoDownloadPage(settings: AppSettings, actions: UiActions) {
    DetailList {
        item(key = "auto") {
            SettingsGroup("自动下载") {
                AUTO_OPTIONS.forEach { option ->
                    SettingsOptionRow(
                        title = option.second,
                        selected = settings.autoDownload == option.first,
                        onClick = { actions.updateSettings { it.copy(autoDownload = option.first) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun NamingRulePage(settings: AppSettings, actions: UiActions) {
    DetailList {
        item(key = "naming") {
            SettingsGroup("命名规则") {
                NAMING_OPTIONS.forEach { option ->
                    SettingsOptionRow(
                        title = option.second,
                        selected = settings.zipNamingRule == option.first,
                        onClick = { actions.updateSettings { it.copy(zipNamingRule = option.first) } },
                    )
                }
            }
        }
    }
}

/* ── 外观 ─────────────────────────────────────────────────────────── */

private val THEME_OPTIONS = listOf(
    "auto" to "跟随系统",
    "light" to "浅色",
    "dark" to "深色",
)

@Composable
private fun ThemePage(settings: AppSettings, actions: UiActions) {
    DetailList {
        item(key = "theme") {
            SettingsGroup("主题") {
                THEME_OPTIONS.forEach { option ->
                    SettingsOptionRow(
                        title = option.second,
                        selected = settings.theme == option.first,
                        onClick = { actions.updateSettings { it.copy(theme = option.first) } },
                    )
                }
            }
        }
    }
}

/* ── 数据 ─────────────────────────────────────────────────────────── */

private val EXPORT_FORMATS = listOf(
    "Markdown" to "md",
    "TXT" to "txt",
    "CSV" to "csv",
    "JSON" to "json",
)

@Composable
private fun ExportPage(actions: UiActions) {
    DetailList {
        item(key = "export") {
            SettingsGroup("导出") {
                EXPORT_FORMATS.forEach { format ->
                    SettingsLabelRow(
                        title = format.first,
                        value = "导出",
                        onClick = { actions.exportText(format.second) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClearPage(itemCount: Int, onClearAll: () -> Unit) {
    DetailList {
        item(key = "clear") {
            SettingsGroup("清空") {
                SettingsLabelRow(
                    title = "清空全部收录",
                    value = "$itemCount 条",
                    onClick = onClearAll,
                )
            }
        }
    }
}

/* ── 版本信息 / 致谢 ─────────────────────────────────────────────── */

private const val AUTHOR_URL = "https://github.com/ZenithLiteAura"
private const val PROJECT_URL = "https://github.com/ZenithLiteAura/Zeno"

private data class ThanksEntry(val name: String, val value: String, val url: String)

private val THANKS = listOf(
    ThanksEntry("NexioSchedule", "GitHub", "https://github.com/HaoZai000/NexioSchedule"),
    ThanksEntry("MiUiX", "GitHub", "https://github.com/compose-miuix-ui/miuix"),
    ThanksEntry("Shizuku", "GitHub", "https://github.com/RikkaApps/Shizuku"),
)

@Composable
private fun AboutInfoPage(actions: UiActions) {
    val context = LocalContext.current
    val info = rememberUpdateInfo()
    DetailList {
        if (info.hasUpdate) {
            item(key = "update-button") {
                Button(
                    onClick = { UpdateGateway.openRelease(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = "下载新版本" +
                            if (info.latestVersion.isNotBlank()) " v${info.latestVersion}" else "",
                    )
                }
            }
        }
        item(key = "app-info") {
            SettingsGroup("版本信息") {
                SettingsLabelRow(title = "应用", value = "Nezo")
                SettingsLabelRow(title = "版本", value = BuildConfig.VERSION_NAME)
                SettingsLabelRow(
                    title = "作者",
                    value = "ZenithLiteAura",
                    onClick = { actions.openUrl(AUTHOR_URL) },
                )
                SettingsLabelRow(
                    title = "项目主页",
                    value = "Zeno",
                    onClick = { actions.openUrl(PROJECT_URL) },
                )
                SettingsLabelRow(
                    title = "检查更新",
                    value = updateStatusText(info),
                    onClick = { UpdateGateway.check(context, manual = true) },
                )
            }
        }
        item(key = "thanks") {
            SettingsGroup("致谢") {
                THANKS.forEach { entry ->
                    SettingsLabelRow(
                        title = entry.name,
                        value = entry.value,
                        onClick = if (entry.url.isNotBlank()) {
                            { actions.openUrl(entry.url) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}