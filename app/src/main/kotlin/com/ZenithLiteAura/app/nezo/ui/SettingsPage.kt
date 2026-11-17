package com.ZenithLiteAura.app.nezo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ZenithLiteAura.app.nezo.BuildConfig
import com.ZenithLiteAura.app.nezo.ui.settings.LocalSettingsNavigate
import com.ZenithLiteAura.app.nezo.ui.settings.SettingsActionRow
import com.ZenithLiteAura.app.nezo.ui.settings.SettingsGroup
import com.ZenithLiteAura.app.nezo.ui.settings.SettingsNavRow
import com.ZenithLiteAura.app.nezo.ui.settings.SettingsRoute
import com.ZenithLiteAura.app.nezo.ui.settings.SettingsSwitchRow
import com.ZenithLiteAura.app.nezo.ui.settings.SettingsValueRow
import com.ZenithLiteAura.app.nezo.ui.settings.UpdateGateway
import com.ZenithLiteAura.app.nezo.ui.settings.rememberUpdateInfo
import com.ZenithLiteAura.app.nezo.ui.settings.updateStatusText
import com.clipnest.core.AppSettings
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Alarm
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Paste
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.ScreenCapture
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置一级页（Nexio 结构）：
 * 分组卡片 + 每行「图标 + 主标题 + 右箭头」，开关类才用开关，**没有任何副标题/解释文案**；
 * 点箭头进二级页（`ui/settings/SettingsDetailPages.kt`），顶栏的标题与返回由 `ClipHomeScreen` 负责。
 */
@Composable
fun SettingsPage(
    settings: AppSettings,
    rules: Int,
    rulesEnabled: Int,
    itemCount: Int,
    starredCount: Int,
    actions: UiActions,
    onClearAll: () -> Unit,
    onOpenCapture: () -> Unit = {},
    onOpenRules: () -> Unit = {},
) {
    val navigate = LocalSettingsNavigate.current
    val context = LocalContext.current
    val updateInfo = rememberUpdateInfo()

    val autoLabel = when (settings.autoDownload) {
        "off" -> "关闭"
        "images" -> "仅图片"
        else -> "图片+视频"
    }
    val namingLabel = when (settings.zipNamingRule) {
        "2" -> "标题_清晰度"
        "3" -> "仅标题"
        "4" -> "仅序号"
        else -> "序号_标题"
    }
    val themeLabel = when (settings.theme) {
        "light" -> "浅色"
        "dark" -> "深色"
        else -> "跟随系统"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .scrollEndHaptic(),
        contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── 基础 ────────────────────────────────────────────────
        item(key = "base") {
            SettingsGroup("基础") {
                SettingsSwitchRow(
                    icon = MiuixIcons.Paste,
                    title = "监听剪贴板",
                    checked = settings.monitor,
                    onCheckedChange = { on -> actions.updateSettings { it.copy(monitor = on) } },
                )
                SettingsSwitchRow(
                    icon = MiuixIcons.Refresh,
                    title = "开机自启",
                    checked = settings.autostart,
                    onCheckedChange = { on -> actions.updateSettings { it.copy(autostart = on) } },
                )
                SettingsSwitchRow(
                    icon = MiuixIcons.Alarm,
                    title = "收录时提示",
                    checked = settings.notify,
                    onCheckedChange = { on -> actions.updateSettings { it.copy(notify = on) } },
                )
            }
        }

        // ── 收录方式 ────────────────────────────────────────────
        item(key = "capture") {
            SettingsGroup("收录方式") {
                SettingsNavRow(
                    icon = MiuixIcons.ScreenCapture,
                    title = "悬浮球",
                    value = if (settings.bubble) "已开启" else null,
                    onClick = { navigate(SettingsRoute.Bubble) },
                )
                SettingsNavRow(
                    icon = MiuixIcons.Messages,
                    title = "通知栏",
                    onClick = { navigate(SettingsRoute.Notification) },
                )
                SettingsNavRow(
                    icon = MiuixIcons.Lock,
                    title = "后台自动监听",
                    value = if (settings.shizukuMonitor) "已开启" else null,
                    onClick = { navigate(SettingsRoute.Shizuku) },
                )
                SettingsNavRow(
                    icon = MiuixIcons.Ok,
                    title = "权限检查",
                    onClick = { navigate(SettingsRoute.Permissions) },
                )
                SettingsNavRow(
                    icon = MiuixIcons.Tune,
                    title = "识别规则",
                    value = "$rulesEnabled/$rules",
                    onClick = onOpenRules,
                )
                SettingsNavRow(
                    icon = MiuixIcons.Paste,
                    title = "手动粘贴收录",
                    onClick = onOpenCapture,
                )
                SettingsNavRow(
                    icon = MiuixIcons.Filter,
                    title = "去重与兜底",
                    onClick = { navigate(SettingsRoute.Dedupe) },
                )
            }
        }

        // ── 下载 ────────────────────────────────────────────────
        item(key = "download") {
            SettingsGroup("下载") {
                SettingsNavRow(
                    icon = MiuixIcons.Download,
                    title = "自动下载",
                    value = autoLabel,
                    onClick = { navigate(SettingsRoute.AutoDownload) },
                )
                SettingsNavRow(
                    icon = MiuixIcons.Rename,
                    title = "命名规则",
                    value = namingLabel,
                    onClick = { navigate(SettingsRoute.NamingRule) },
                )
                SettingsSwitchRow(
                    icon = MiuixIcons.CloudFill,
                    title = "仅 Wi-Fi 下载",
                    checked = settings.downloadWifiOnly,
                    onCheckedChange = { on -> actions.updateSettings { it.copy(downloadWifiOnly = on) } },
                )
            }
        }

        // ── 外观 ────────────────────────────────────────────────
        item(key = "appearance") {
            SettingsGroup("外观") {
                SettingsNavRow(
                    icon = MiuixIcons.Theme,
                    title = "主题",
                    value = themeLabel,
                    onClick = { navigate(SettingsRoute.Theme) },
                )
            }
        }

        // ── 数据 ────────────────────────────────────────────────
        item(key = "data") {
            SettingsGroup("数据") {
                SettingsNavRow(
                    icon = MiuixIcons.Share,
                    title = "导出",
                    onClick = { navigate(SettingsRoute.Export) },
                )
                SettingsNavRow(
                    icon = MiuixIcons.Delete,
                    title = "清空收录",
                    value = "$itemCount 条",
                    onClick = {
                        if (itemCount == 0) {
                            actions.showToast("现在是空的")
                        } else {
                            navigate(SettingsRoute.Clear)
                        }
                    },
                )
                SettingsValueRow(
                    icon = MiuixIcons.Folder,
                    title = "数据目录",
                    value = "应用私有",
                )
            }
        }

        // ── 关于 ────────────────────────────────────────────────
        item(key = "about") {
            SettingsGroup("关于") {
                SettingsNavRow(
                    icon = MiuixIcons.Info,
                    title = "版本信息",
                    value = BuildConfig.VERSION_NAME,
                    onClick = { navigate(SettingsRoute.AboutInfo) },
                )
                SettingsActionRow(
                    icon = MiuixIcons.Refresh,
                    title = "检查更新",
                    value = updateStatusText(updateInfo),
                    onClick = { UpdateGateway.check(context, manual = true) },
                )
            }
        }

        item(key = "bottom-space") {
            Spacer(Modifier.height(8.dp))
        }
    }
}