package com.ZenithLiteAura.app.nezo.ui.download

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ZenithLiteAura.app.nezo.data.DownloadController
import com.ZenithLiteAura.app.nezo.data.DownloadWorkUi
import com.ZenithLiteAura.app.nezo.ui.NestSize
import com.ZenithLiteAura.app.nezo.ui.UiActions
import com.ZenithLiteAura.app.nezo.ui.common.RemoteImage
import com.ZenithLiteAura.app.nezo.ui.formatBytes
import com.ZenithLiteAura.app.nezo.ui.pageContentPadding
import com.ZenithLiteAura.app.nezo.ui.settings.SettingsOptionRow
import com.clipnest.core.AppSettings
import com.clipnest.core.platform.MediaQuality
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val NAMING_OPTIONS = listOf(
    "1" to "序号_标题",
    "2" to "标题_清晰度",
    "3" to "仅标题",
    "4" to "仅序号",
)

/**
 * 下载页：粘贴作品链接 → 解析 → 选命名规则/清晰度 → 打包或逐个下载。
 *
 * 下载任务列表不在本页（独立「下载列表」二级页，见 `DownloadListPage.kt`）；
 * 自动下载设置也不在本页（设置 → 下载）。
 * 所有网络/文件动作只通过 [DownloadController]，UI 不解析响应。
 */
@Composable
fun DownloadPage(
    controller: DownloadController,
    settings: AppSettings,
    actions: UiActions,
    modifier: Modifier = Modifier,
    onToast: (String) -> Unit = {},
) {
    val cs = MiuixTheme.colorScheme
    val context = LocalContext.current
    val work by controller.work.collectAsState()
    val resolving by controller.resolving.collectAsState()

    var input by remember { mutableStateOf("") }
    var showNamingPicker by remember { mutableStateOf(false) }
    var showQualityPicker by remember { mutableStateOf(false) }

    // 解析失败：把平台返回的中文 message 用 Snackbar 抛出来
    LaunchedEffect(work) {
        val w = work ?: return@LaunchedEffect
        if (!w.ok && w.message.isNotBlank()) onToast(w.message)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical()
            .scrollEndHaptic(),
        contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "input-title") { SmallTitle(text = "作品链接") }
        item(key = "input-card") {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                // 第一行：输入框 + 行内「清除」
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        label = "粘贴作品链接或分享文案",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                    )
                    if (input.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                input = ""
                                controller.clearWork()
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Clear,
                                contentDescription = "清除",
                                modifier = Modifier.size(18.dp),
                                tint = cs.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 第二行：主操作 + 次级操作，等高、等间距
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 次级操作在左，主操作（解析链接·蓝底）在右
                    TextButton(
                        text = "读剪贴板",
                        onClick = {
                            val text = readClipboard(context)
                            if (text.isNullOrBlank()) {
                                onToast("剪贴板里没有文本")
                            } else {
                                input = text
                                controller.resolve(text)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                    )
                    Button(
                        onClick = {
                            val text = input.trim()
                            if (text.isEmpty()) {
                                onToast("先粘一个作品链接（抖音 / 小红书 / B站 / 快手）")
                            } else {
                                controller.resolve(text)
                            }
                        },
                        modifier = Modifier
                            .weight(1.7f)
                            .height(44.dp),
                        // 主操作：主色蓝底强调
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("解析链接")
                    }
                }
                if (resolving) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        val result = work
        if (result != null) {
            item(key = "result-title") { SmallTitle(text = "解析结果") }
            item(key = "result-card") {
                if (result.ok) {
                    WorkCard(
                        work = result,
                        namingRule = settings.zipNamingRule,
                        onNamingClick = { showNamingPicker = true },
                        onQualityClick = { showQualityPicker = true },
                        onImagesZip = { controller.downloadImagesZip(settings.zipNamingRule.toIntOrNull() ?: 1) },
                        onVideoZip = { controller.downloadVideoZip(settings.zipNamingRule.toIntOrNull() ?: 1) },
                        onImagesOneByOne = { controller.downloadImagesIndividually() },
                        onVideo = { controller.downloadVideo() },
                    )
                } else {
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(MiuixIcons.Clear, null, Modifier.size(18.dp), tint = cs.error)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "解析失败",
                                    style = MiuixTheme.textStyles.headline2,
                                    color = cs.onSurface,
                                )
                                Text(
                                    text = result.message.ifBlank { "这个作品暂时拿不到，换个链接试试" },
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = cs.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 命名规则选择（Nexio 风格：行 → 弹层单选）
    if (showNamingPicker) {
        OverlayDialog(
            show = true,
            title = "命名规则",
            onDismissRequest = { showNamingPicker = false },
        ) {
            Column {
                NAMING_OPTIONS.forEach { option ->
                    SettingsOptionRow(
                        title = option.second,
                        selected = settings.zipNamingRule == option.first,
                        onClick = {
                            actions.updateSettings { it.copy(zipNamingRule = option.first) }
                            showNamingPicker = false
                        },
                    )
                }
            }
        }
    }

    // 清晰度选择：选项来自 work.qualities；选中后 controller 内部改用该项 url
    if (showQualityPicker) {
        val qualities = work?.qualities.orEmpty()
        OverlayDialog(
            show = true,
            title = "清晰度",
            onDismissRequest = { showQualityPicker = false },
        ) {
            Column {
                if (qualities.isEmpty()) {
                    SettingsOptionRow(
                        title = (work?.selectedQuality?.ifBlank { work?.videoQuality } ?: "").ifBlank { "默认" },
                        selected = true,
                        onClick = { showQualityPicker = false },
                    )
                } else {
                    qualities.forEach { quality ->
                        SettingsOptionRow(
                            title = qualityLabel(quality),
                            selected = quality.label == work?.selectedQuality,
                            onClick = {
                                controller.selectQuality(quality.label)
                                showQualityPicker = false
                            },
                        )
                    }
                }
                if (qualities.size <= 1) {
                    Text(
                        text = "平台仅返回该清晰度",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** 清晰度选项文案：label + 体积 + 水印标记。 */
private fun qualityLabel(quality: MediaQuality): String = buildString {
    append(quality.label)
    val size = formatBytes(quality.size)
    if (size.isNotBlank()) append(" · ").append(size)
    if (quality.watermark) append(" · 带水印")
}

@Composable
private fun WorkCard(
    work: DownloadWorkUi,
    namingRule: String,
    onNamingClick: () -> Unit,
    onQualityClick: () -> Unit,
    onImagesZip: () -> Unit,
    onVideoZip: () -> Unit,
    onImagesOneByOne: () -> Unit,
    onVideo: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val isImage = work.type == "image"
    val effectiveQuality = work.selectedQuality.ifBlank { work.videoQuality }
    val typeLabel = if (isImage) {
        "图文 · ${work.picCount} 张"
    } else {
        buildString {
            append("视频")
            if (effectiveQuality.isNotBlank()) append(" · $effectiveQuality")
            val size = formatBytes(work.videoSize)
            if (size.isNotBlank()) append(" · $size")
        }
    }
    val namingLabel = NAMING_OPTIONS.firstOrNull { it.first == namingRule }?.second ?: "序号_标题"

    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        RemoteImage(
            url = work.coverUrl,
            contentDescription = work.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(20.dp)),
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = work.title.ifBlank { "（无标题作品）" },
                style = MiuixTheme.textStyles.headline1,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = work.author.ifBlank { "未知作者" },
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isImage) cs.secondaryContainer else cs.tertiaryContainer)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = typeLabel,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (isImage) cs.onSecondaryContainer else cs.onTertiaryContainer,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            PickRow(title = "命名规则", value = namingLabel, onClick = onNamingClick)
            // 清晰度：视频作品始终显示一行（qualities 可能为空，用 videoQuality 兜底）；
            // 图片作品没有清晰度概念，不显示
            val hasQualities = !isImage
            if (hasQualities) {
                PickRow(
                    title = "清晰度",
                    value = effectiveQuality.ifBlank { "默认" },
                    onClick = onQualityClick,
                )
                if (work.qualities.size <= 1) {
                    Text(
                        text = "平台仅返回该清晰度",
                        style = MiuixTheme.textStyles.footnote2,
                        color = cs.onSurfaceVariantSummary.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { if (isImage) onImagesZip() else onVideoZip() },
                modifier = Modifier.fillMaxWidth(),
                enabled = if (isImage) work.picCount > 0 else work.videoUrl.isNotBlank(),
            ) {
                Icon(MiuixIcons.Download, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (isImage) "下载全部图片 ZIP" else "下载视频 ZIP")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = if (isImage) "逐个下载图片" else "直接保存视频文件",
                onClick = { if (isImage) onImagesOneByOne() else onVideo() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 选择行：左标题 + 右侧当前值 + 箭头（Nexio 设置项风格）。 */
@Composable
private fun PickRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NestSize.CardRadius))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.headline1,
            color = cs.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            color = cs.onSurfaceVariantSummary,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = cs.onSurfaceVariantSummary,
        )
    }
}

private fun readClipboard(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = cm?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    clip.getItemAt(0).coerceToText(context)?.toString()
}.getOrNull()