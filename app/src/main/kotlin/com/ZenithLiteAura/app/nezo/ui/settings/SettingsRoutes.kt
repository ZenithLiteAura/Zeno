package com.ZenithLiteAura.app.nezo.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 设置页路由：一级页（[Hub]）+ 各二级页。
 * 顶栏标题直接从 [title] 取，`ClipHomeScreen` 负责渲染返回箭头与 BackHandler。
 */
enum class SettingsRoute(val title: String) {
    Hub("设置"),
    Bubble("悬浮球"),
    Notification("通知栏"),
    Shizuku("后台自动监听"),
    Permissions("权限检查"),
    Dedupe("去重与兜底"),
    AutoDownload("自动下载"),
    NamingRule("命名规则"),
    Theme("外观"),
    Export("导出"),
    Clear("清空收录"),
    Trash("回收站"),
    AboutInfo("版本信息"),
}

/**
 * 一级页 → 二级页的导航入口。
 *
 * `SettingsPage` 的函数签名被冻结（不能加 `onNavigate` 参数），所以用 CompositionLocal 传：
 * `ClipHomeScreen` 提供实现并持有路由状态。
 */
val LocalSettingsNavigate = staticCompositionLocalOf<(SettingsRoute) -> Unit> { {} }