package com.ZenithLiteAura.app.nezo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 当前是否深色。
 *
 * 仅供 dock（`ui/dock` 目录，Lead 维护的液态玻璃实现）判断高光方向使用；
 * 页面本体的颜色一律读 [MiuixTheme.colorScheme]。
 */
val LocalNestDark = staticCompositionLocalOf { false }

val nestIsDark: Boolean
    @Composable @ReadOnlyComposable
    get() = LocalNestDark.current

/**
 * 页面底色 / 卡片色。
 *
 * Miuix 默认 light 的 background 是纯白、卡片也是白 —— 卡片会和页面糊在一起。
 * 这里按 Nexio 的层次把页面压到 #F2F3F5、卡片留白，深色对应 #14161A / #1F2127。
 */
private val ClipLightColors = lightColorScheme().copy(
    background = Color(0xFFF2F3F5),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF7F7F8),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF2F3F5),
)

private val ClipDarkColors = darkColorScheme().copy(
    background = Color(0xFF14161A),
    surface = Color(0xFF1B1D22),
    surfaceContainer = Color(0xFF1F2127),
    surfaceContainerHigh = Color(0xFF26282F),
    surfaceContainerHighest = Color(0xFF2B2E36),
    surfaceVariant = Color(0xFF24262C),
)

/**
 * ClipNest Nezo主题。
 *
 * 与 NexioSchedule 同一套 MiUiX 主题：颜色 / 文字样式 / 字号层次全部来自 [MiuixTheme]，
 * 页面里不再有自定义调色板（浅色背景即 Miuix 的 background，深色自动跟随）。
 *
 * 签名与 MainActivity 的调用保持一致：`ClipNestTheme(darkTheme = ...) { ... }`。
 */
@Composable
fun ClipNestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val controller = remember(darkTheme) {
        ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            lightColors = ClipLightColors,
            darkColors = ClipDarkColors,
        )
    }
    CompositionLocalProvider(LocalNestDark provides darkTheme) {
        MiuixTheme(
            controller = controller,
            textStyles = defaultTextStyles(),
            content = content,
        )
    }
}