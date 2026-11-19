package com.ZenithLiteAura.app.nezo.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ZenithLiteAura.app.nezo.ui.MonoStyle
import com.ZenithLiteAura.app.nezo.ui.UiActions
import com.ZenithLiteAura.app.nezo.ui.warningTagColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LEVELS = listOf("DEBUG", "INFO", "WARN", "ERROR")
private val CHANNELS = listOf("app" to "本应用", "system" to "系统")

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

/**
 * 日志与诊断页：筛选（严重程度 / 渠道）+ 开始/停止/清空 + 实时日志代码框（换行/复制/导出）。
 * 采集、导出、状态都在 `LogGateway`（platform.diag.LogCollector）一侧，UI 只负责展示。
 */
@Composable
fun LogsPage(
    actions: UiActions,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MiuixTheme.colorScheme
    val context = LocalContext.current
    val entries = rememberLogLines()
    val listState = rememberLazyListState()

    var level by remember { mutableStateOf("INFO") }
    var channel by remember { mutableStateOf("app") }
    var collecting by remember { mutableStateOf(false) }
    var autoWrap by remember { mutableStateOf(true) }
    var showLevelPicker by remember { mutableStateOf(false) }
    var showChannelPicker by remember { mutableStateOf(false) }

    // 新日志到达时自动滚到底部
    LaunchedEffect(entries.size, collecting) {
        if (entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri != null) {
            val ok = LogGateway.exportTo(context, uri)
            onToast(if (ok) "已导出" else "导出失败，日志模块可能尚未就绪")
        }
    }

    fun toggleCollect(next: Boolean) {
        if (next) {
            if (!LogGateway.available) {
                onToast("日志采集模块尚未就绪")
                return
            }
            LogGateway.start(context, level, channel)
            collecting = true
            onToast("开始采集")
        } else {
            LogGateway.stop()
            collecting = false
            onToast("已停止采集")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
    ) {
        // 1) 筛选行：左右两个等宽下拉框
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterBox(
                    label = "严重程度",
                    value = level,
                    onClick = { showLevelPicker = true },
                )
                FilterBox(
                    label = "渠道",
                    value = CHANNELS.firstOrNull { it.first == channel }?.second ?: "本应用",
                    onClick = { showChannelPicker = true },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 2) 操作行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (collecting) {
                Button(
                    onClick = { toggleCollect(false) },
                    modifier = Modifier
                        .weight(1.6f)
                        .height(44.dp),
                ) {
                    Text("停止")
                }
            } else {
                Button(
                    onClick = { toggleCollect(true) },
                    modifier = Modifier
                        .weight(1.6f)
                        .height(44.dp),
                ) {
                    Text("开始采集")
                }
            }
            TextButton(
                text = "清空",
                onClick = {
                    LogGateway.clear()
                    onToast("已清空")
                },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        // 3) 日志代码框
        Card(modifier = Modifier.weight(1f).fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "实时日志",
                        style = MiuixTheme.textStyles.headline2,
                        color = cs.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        text = "自动换行",
                        onClick = { autoWrap = !autoWrap },
                        minWidth = 0.dp,
                        minHeight = 30.dp,
                        textStyle = MiuixTheme.textStyles.footnote2,
                        colors = ButtonDefaults.textButtonColors(
                            textColor = if (autoWrap) cs.primary else cs.onSurfaceVariantSummary,
                        ),
                    )
                    IconButton(
                        onClick = {
                            val text = entries.joinToString("\n") { formatLine(it) }
                            if (text.isBlank()) {
                                onToast("还没有日志")
                            } else {
                                actions.copyText(text)
                                onToast("已复制 ${entries.size} 条日志")
                            }
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(MiuixIcons.Copy, "复制日志", Modifier.size(18.dp), tint = cs.onSurfaceVariantActions)
                    }
                    IconButton(
                        onClick = { exportLauncher.launch("nezo-log-${System.currentTimeMillis()}.txt") },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(MiuixIcons.Download, "导出日志", Modifier.size(18.dp), tint = cs.onSurfaceVariantActions)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(cs.surfaceContainer),
                ) {
                    if (entries.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "点「开始采集」查看日志",
                                style = MiuixTheme.textStyles.footnote1,
                                color = cs.onSurfaceVariantSummary,
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            items(entries.size) { index ->
                                val line = entries[index]
                                Text(
                                    text = formatLine(line),
                                    style = MonoStyle.copy(fontSize = 10.5.sp, lineHeight = 16.sp),
                                    color = logColor(line.level, line.channel),
                                    maxLines = if (autoWrap) Int.MAX_VALUE else 1,
                                    overflow = if (autoWrap) TextOverflow.Clip else TextOverflow.Ellipsis,
                                    softWrap = autoWrap,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 严重程度选择
    if (showLevelPicker) {
        OverlayDialog(
            show = true,
            title = "严重程度",
            onDismissRequest = { showLevelPicker = false },
        ) {
            Column {
                LEVELS.forEach { value ->
                    SettingsOptionRow(
                        title = value,
                        selected = level == value,
                        onClick = {
                            level = value
                            showLevelPicker = false
                            if (collecting) LogGateway.start(context, value, channel)
                        },
                    )
                }
            }
        }
    }

    // 渠道选择
    if (showChannelPicker) {
        OverlayDialog(
            show = true,
            title = "渠道",
            onDismissRequest = { showChannelPicker = false },
        ) {
            Column {
                CHANNELS.forEach { option ->
                    SettingsOptionRow(
                        title = option.second,
                        selected = channel == option.first,
                        onClick = {
                            channel = option.first
                            showChannelPicker = false
                            if (collecting) LogGateway.start(context, level, option.first)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.FilterBox(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = cs.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MiuixTheme.textStyles.headline2,
                color = cs.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = cs.onSurfaceVariantSummary,
            )
        }
    }
}

private fun formatLine(line: LogLine): String =
    "${timeFormatter.format(Instant.ofEpochMilli(line.timeMs))}  ${line.level.padEnd(5)}  ${line.message}"

/** 着色：本应用渠道按级别上色；系统渠道用普通正文色。 */
@Composable
private fun logColor(level: String, channel: String): Color {
    val cs = MiuixTheme.colorScheme
    if (channel != "app") return cs.onSurface
    return when (level) {
        "DEBUG" -> cs.onSurfaceVariantSummary
        "INFO" -> cs.primary
        "WARN" -> warningTagColors().content
        "ERROR" -> cs.error
        else -> cs.onSurface
    }
}