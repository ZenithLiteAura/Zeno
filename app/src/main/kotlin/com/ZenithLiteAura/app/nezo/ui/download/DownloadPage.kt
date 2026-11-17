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
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.ZenithLiteAura.app.nezo.data.DownloadTaskUi
import com.ZenithLiteAura.app.nezo.data.DownloadWorkUi
import com.ZenithLiteAura.app.nezo.ui.MonoStyle
import com.ZenithLiteAura.app.nezo.ui.NestEmpty
import com.ZenithLiteAura.app.nezo.ui.NestSize
import com.ZenithLiteAura.app.nezo.ui.Tnum
import com.ZenithLiteAura.app.nezo.ui.UiActions
import com.ZenithLiteAura.app.nezo.ui.common.RemoteImage
import com.ZenithLiteAura.app.nezo.ui.formatBytes
import com.ZenithLiteAura.app.nezo.ui.pageContentPadding
import com.ZenithLiteAura.app.nezo.ui.relTime
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.clipnest.core.AppSettings
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

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

/**
 * 下载页：粘贴作品链接（抖音/小红书/B站/快手）→ 解析 → 选择命名规则 → 打包/逐个下载 → 任务进度。
 * 所有下载动作只通过 [DownloadController]；UI 不做网络与文件操作。
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
    val tasks by controller.tasks.collectAsState()

    var input by remember { mutableStateOf("") }

    // 解析失败：把 DouyinWork.message 用 Snackbar 说出来
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
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "粘贴作品链接或分享文案",
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    minLines = 2,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        text = "解析",
                        onClick = {
                            val text = input.trim()
                            if (text.isEmpty()) onToast("先粘一个作品链接（抖音 / 小红书 / B站 / 快手）") else controller.resolve(text)
                        },
                    )
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
                    )
                    Spacer(Modifier.weight(1f))
                    if (work != null) {
                        TextButton(
                            text = "清除",
                            onClick = {
                                input = ""
                                controller.clearWork()
                            },
                        )
                    }
                }
                if (resolving) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                    )
                }
            }
        }

        item(key = "auto-title") { SmallTitle(text = "自动下载") }
        item(key = "auto-card") {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 4.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        text = "收录作品时自动解析并下载",
                        style = MiuixTheme.textStyles.headline1,
                        color = cs.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    SegmentedOptions(
                        options = AUTO_OPTIONS,
                        selected = settings.autoDownload,
                        onSelect = { value -> actions.updateSettings { it.copy(autoDownload = value) } },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when (settings.autoDownload) {
                            "off" -> "只在你点了下载按钮时才开始"
                            "images" -> "自动保存图文作品的图片，视频仍手动"
                            else -> "图文、视频都自动下载"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = cs.onSurfaceVariantSummary,
                    )
                }
                SwitchPreference(
                    title = "仅在 Wi-Fi 下自动下载",
                    summary = "移动数据下不自动开始，避免偷跑流量",
                    checked = settings.downloadWifiOnly,
                    onCheckedChange = { on -> actions.updateSettings { it.copy(downloadWifiOnly = on) } },
                )
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
                        onRuleChange = { value -> actions.updateSettings { it.copy(zipNamingRule = value) } },
                        onImagesZip = { controller.downloadImagesZip(settings.zipNamingRule.toIntOrNull() ?: 1) },
                        onVideoZip = { controller.downloadVideoZip(settings.zipNamingRule.toIntOrNull() ?: 1) },
                        onImagesOneByOne = { controller.downloadImagesIndividually() },
                        onVideo = { controller.downloadVideo() },
                    )
                } else {
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                MiuixIcons.Clear,
                                null,
                                Modifier.size(18.dp),
                                tint = cs.error,
                            )
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

        val activeTasks = tasks.count { it.state == "queued" || it.state == "running" }
        val doneTasks = tasks.count { it.state == "done" }
        val finishedTasks = tasks.count { it.state == "done" || it.state == "failed" || it.state == "canceled" }

        item(key = "task-title") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "下载任务 · 进行中 $activeTasks · 已完成 $doneTasks",
                    style = MiuixTheme.textStyles.subtitle.copy(fontWeight = FontWeight.Medium),
                    color = cs.onSurfaceVariantSummary,
                )
                Spacer(Modifier.weight(1f))
                if (finishedTasks > 0) {
                    TextButton(
                        text = "清空已完成",
                        onClick = { controller.clearFinished() },
                        minWidth = 0.dp,
                        minHeight = 32.dp,
                        textStyle = MiuixTheme.textStyles.footnote1,
                    )
                }
            }
        }
        if (tasks.isEmpty()) {
            item(key = "task-empty") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    NestEmpty(
                        icon = MiuixIcons.Folder,
                        title = "还没有下载任务",
                        sub = "解析成功后点「下载全部图片 ZIP」或「下载视频 ZIP」",
                    )
                }
            }
        } else {
            itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
                TaskCard(
                    task = task,
                    queuedAhead = tasks.take(index).count { it.state == "queued" },
                    onCancel = { controller.cancel(task.id) },
                    onOpen = { if (task.savedUri.isNotBlank()) controller.openSaved(task.savedUri) },
                    onRetry = {
                        if (!controller.retry(task.id)) {
                            onToast("重试没有成功，任务可能已经不在队列里")
                        }
                    },
                )
            }
        }

        item(key = "foot") {
            Text(
                text = "下载的图片与视频保存在系统相册 / 下载目录，ZIP 在 Download/ClipNest。",
                style = MiuixTheme.textStyles.footnote2,
                color = cs.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun WorkCard(
    work: DownloadWorkUi,
    namingRule: String,
    onRuleChange: (String) -> Unit,
    onImagesZip: () -> Unit,
    onVideoZip: () -> Unit,
    onImagesOneByOne: () -> Unit,
    onVideo: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val isImage = work.type == "image"
    val typeLabel = if (isImage) {
        "图文 · ${work.picCount} 张"
    } else {
        buildString {
            append("视频")
            if (work.videoQuality.isNotBlank()) append(" · ${work.videoQuality}")
            val size = formatBytes(work.videoSize)
            if (size.isNotBlank()) append(" · $size")
        }
    }

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

            Spacer(Modifier.height(12.dp))
            Text(
                text = "命名规则",
                style = MiuixTheme.textStyles.footnote1,
                color = cs.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NAMING_OPTIONS.forEach { (value, label) ->
                    val active = namingRule == value
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) cs.primary else cs.surfaceContainer)
                            .clickable { onRuleChange(value) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = label,
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (active) cs.onPrimary else cs.onSurfaceVariantActions,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
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

@Composable
private fun TaskCard(
    task: DownloadTaskUi,
    queuedAhead: Int,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val running = task.state == "running" || task.state == "queued"
    val progress: Float? = when {
        task.state == "done" -> 1f
        task.total > 0 -> (task.done.toFloat() / task.total).coerceIn(0f, 1f)
        task.state == "running" -> null
        else -> 0f
    }

    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindTag(task.kind)
            Spacer(Modifier.width(8.dp))
            Text(
                text = task.title.ifBlank { "未命名任务" },
                style = MiuixTheme.textStyles.headline2,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                IconButton(onClick = onCancel, modifier = Modifier.size(34.dp)) {
                    Icon(MiuixIcons.Clear, "取消下载", Modifier.size(18.dp), tint = cs.onSurfaceVariantActions)
                }
            } else if (task.state == "done" && task.savedUri.isNotBlank()) {
                IconButton(onClick = onOpen, modifier = Modifier.size(34.dp)) {
                    Icon(MiuixIcons.Folder, "打开", Modifier.size(18.dp), tint = cs.primary)
                }
            } else if (task.state == "failed" || task.state == "canceled") {
                TextButton(
                    text = "重试",
                    onClick = onRetry,
                    minWidth = 0.dp,
                    minHeight = 32.dp,
                    textStyle = MiuixTheme.textStyles.footnote1,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = taskStateText(task, queuedAhead),
                style = MiuixTheme.textStyles.footnote1,
                color = if (task.state == "failed") cs.error else cs.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = relTime(task.createdAt),
                style = Tnum.copy(fontSize = MiuixTheme.textStyles.footnote2.fontSize),
                color = cs.onSurfaceVariantSummary.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
        if (running) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
            )
            if (task.total > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${(task.done * 100 / task.total).coerceIn(0, 100)}%" +
                        if (task.bytesPerSec > 0) " · ${formatBytes(task.bytesPerSec)}/s" else "",
                    style = Tnum.copy(fontSize = MiuixTheme.textStyles.footnote2.fontSize),
                    color = cs.onSurfaceVariantSummary.copy(alpha = 0.8f),
                )
            }
        } else if (task.state == "done") {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "已保存 · 点右侧文件夹打开",
                style = MiuixTheme.textStyles.footnote2,
                color = cs.onSurfaceVariantSummary.copy(alpha = 0.8f),
            )
        } else if (task.state == "failed" && task.error.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = task.error,
                style = MiuixTheme.textStyles.footnote2,
                color = cs.onSurfaceVariantSummary,
            )
        }
    }
}

/** 任务状态文案：等待下载（排队第 N 位）/ 下载中 X/Y / 已完成 / 失败 / 已取消。 */
private fun taskStateText(task: DownloadTaskUi, queuedAhead: Int): String = when (task.state) {
    "queued" -> "等待下载（排队第 ${queuedAhead + 1} 位）"
    "running" -> buildString {
        append("下载中")
        if (task.total > 0) append(" ${task.done}/${task.total}")
        // bytesPerSec 目前后端还没填（一直 0），0 时不显示速度，只显示已下载体积
        if (task.bytesPerSec > 0) {
            append(" · ${formatBytes(task.bytesPerSec)}/s")
        } else {
            val size = formatBytes(task.bytes)
            if (size.isNotBlank()) append(" · 已下载 $size")
        }
    }

    "done" -> "已完成"
    "failed" -> "失败"
    "canceled" -> "已取消"
    else -> task.state
}

@Composable
private fun KindTag(kind: String) {
    val cs = MiuixTheme.colorScheme
    val (container, content) = when (kind) {
        "image_zip" -> cs.secondaryContainer to cs.onSecondaryContainer
        "video_zip" -> cs.tertiaryContainer to cs.onTertiaryContainer
        "video" -> cs.tertiaryContainer to cs.onTertiaryContainer
        else -> cs.primaryContainer to cs.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = kindLabel(kind),
            style = MiuixTheme.textStyles.footnote2,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
private fun SegmentedOptions(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(cs.surfaceContainer)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val active = selected == value
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) cs.surfaceContainerHigh else cs.surfaceContainer)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (active) cs.onSurface else cs.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    "image_zip" -> "图片 ZIP"
    "video_zip" -> "视频 ZIP"
    "image" -> "单张图片"
    "video" -> "视频"
    else -> kind
}

private fun readClipboard(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = cm?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    clip.getItemAt(0).coerceToText(context)?.toString()
}.getOrNull()