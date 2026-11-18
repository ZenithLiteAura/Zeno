package com.ZenithLiteAura.app.nezo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ZenithLiteAura.app.nezo.ui.dock.bottomFloatingPadding
import com.clipnest.core.ClipItem
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ── 布局 token（只保留间距常量，颜色/字号一律走 MiuixTheme） ─────── */

object NestSize {
    val PagePadding = 16.dp
    val CardRadius = 20.dp
    val ContentBottom = 116.dp
    val SectionGap = 10.dp
}

/**
 * dock 上方内容需要留出的底部空间：
 * 56dp 胶囊 + [bottomFloatingPadding] + 12dp 呼吸。所有滚动列表统一用它，避免被 dock 压住。
 */
@Composable
fun dockContentBottomPadding(): Dp = 56.dp + bottomFloatingPadding() + 12.dp

/**
 * 当前是否显示浮动 dock：主页 / 下载两个一级页为 true，设置（含二级页）/ 识别规则 / 下载列表为 false。
 * 页面统一读它来调节底部留白，避免 dock 隐藏后还空一条。
 */
val LocalDockVisible = staticCompositionLocalOf { true }

/**
 * 页面列表的统一 contentPadding：
 * 顶部再留 10dp（Scaffold 已经让出顶栏高度，这里只补视觉呼吸），底部按 dock 显隐避开悬浮 dock。
 */
@Composable
fun pageContentPadding(top: Dp = 10.dp, extraBottom: Dp = 0.dp): PaddingValues = PaddingValues(
    start = NestSize.PagePadding,
    end = NestSize.PagePadding,
    top = top,
    bottom = (if (LocalDockVisible.current) dockContentBottomPadding() else 28.dp) + extraBottom,
)

/** 等宽（链接、文件名、进度数字）。 */
val MonoStyle: TextStyle = TextStyle(fontFamily = FontFamily.Monospace)

/** 表格数字（时间、计数）。 */
val Tnum: TextStyle = TextStyle(fontFeatureSettings = "tnum")

/** 平台标签色：primary / secondary / tertiary 容器色轮转（与 Nexio 的标签体系一致）。 */
data class TagColors(val container: Color, val content: Color)

private val PLATFORM_INDEX = listOf("douyin", "xiaohongshu", "kuaishou", "bilibili", "weibo", "youtube", "generic")

@Composable
fun platformTagColors(platform: String): TagColors {
    val cs = MiuixTheme.colorScheme
    val index = PLATFORM_INDEX.indexOf(platform).let { if (it < 0) 0 else it }
    return when (index % 3) {
        0 -> TagColors(cs.primaryContainer, cs.onPrimaryContainer)
        1 -> TagColors(cs.secondaryContainer, cs.onSecondaryContainer)
        else -> TagColors(cs.tertiaryContainer, cs.onTertiaryContainer)
    }
}

/** 空状态：图标 + 标题 + 说明，全用 Miuix 文字层级。 */
@Composable
fun NestEmpty(
    icon: ImageVector,
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 56.dp, bottom = 40.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(44.dp), tint = cs.onSurfaceVariantSummary.copy(alpha = 0.45f))
        }
        Text(
            text = title,
            style = MiuixTheme.textStyles.headline2,
            color = cs.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = sub,
            style = MiuixTheme.textStyles.body2,
            color = cs.onSurfaceVariantSummary.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 可以送去下载页解析的平台（解析器目前覆盖抖音，其余平台会由下载页给出失败提示）。 */
val DOWNLOADABLE_PLATFORMS = setOf("douyin", "xiaohongshu", "bilibili", "kuaishou")

fun canDownload(item: ClipItem): Boolean {
    if (item.url.isBlank()) return false
    if (item.platform == "douyin" || item.url.contains("douyin.com")) return true
    return item.platform in DOWNLOADABLE_PLATFORMS
}

/* ── 时间 / 链接格式化 ────────────────────────────────────────────── */

private val zone: ZoneId get() = ZoneId.systemDefault()

private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

fun relTime(epochMs: Long): String {
    val now = ZonedDateTime.now(zone)
    val t = Instant.ofEpochMilli(epochMs).atZone(zone)
    val minutes = (now.toInstant().toEpochMilli() - epochMs) / 60_000L
    val today = now.toLocalDate()
    val date = t.toLocalDate()
    return when {
        minutes < 0L -> "刚刚"
        minutes < 1L -> "刚刚"
        minutes < 60L -> "${minutes} 分钟前"
        date == today -> "${(minutes / 60L).coerceAtLeast(1L)} 小时前"
        date == today.minusDays(1) -> "昨天 ${timeFmt.format(t)}"
        date.year == today.year -> dateFmt.format(t)
        else -> "${date.year}-${"%02d".format(date.monthValue)}-${"%02d".format(date.dayOfMonth)}"
    }
}

fun dayGroupLabel(epochMs: Long): String {
    val now = ZonedDateTime.now(zone)
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    return when (date) {
        now.toLocalDate() -> "今天"
        now.toLocalDate().minusDays(1) -> "昨天"
        else -> "更早"
    }
}

fun fullTime(epochMs: Long): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(zone)
    return "${t.year}-${"%02d".format(t.monthValue)}-${"%02d".format(t.dayOfMonth)} ${timeFmt.format(t)}"
}

fun shortUrl(url: String): String {
    val s = url.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    if (s.isEmpty()) return "（无链接）"
    return if (s.length > 30) s.take(29) + "…" else s
}

/** 人类可读体积：4.8 MB。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1) "%.1f MB".format(mb) else "${bytes / 1024} KB"
}