package com.ZenithLiteAura.app.nezo.ui.download

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ZenithLiteAura.app.nezo.data.DownloadController
import com.ZenithLiteAura.app.nezo.data.DownloadTaskUi
import com.ZenithLiteAura.app.nezo.ui.NestEmpty
import com.ZenithLiteAura.app.nezo.ui.Tnum
import com.ZenithLiteAura.app.nezo.ui.formatBytes
import com.ZenithLiteAura.app.nezo.ui.pageContentPadding
import com.ZenithLiteAura.app.nezo.ui.relTime
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 下载列表（独立二级页）：汇总行 + 任务行（等待/下载中/已完成/失败可重试/已取消）。
 * 从下载页右上角入口进入，顶栏标题与返回由 `ClipHomeScreen` 负责。
 */
@Composable
fun DownloadListPage(
    controller: DownloadController,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tasks by controller.tasks.collectAsState()
    val activeTasks = tasks.count { it.state == "queued" || it.state == "running" }
    val doneTasks = tasks.count { it.state == "done" }
    val finishedTasks = tasks.count { it.state == "done" || it.state == "failed" || it.state == "canceled" }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical()
            .scrollEndHaptic(),
        contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "summary") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "进行中 $activeTasks · 已完成 $doneTasks",
                    style = MiuixTheme.textStyles.subtitle.copy(fontWeight = FontWeight.Medium),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
            item(key = "empty") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    NestEmpty(
                        icon = MiuixIcons.Folder,
                        title = "还没有下载任务",
                        sub = "回下载页解析作品，然后点「下载全部图片 ZIP」或「下载视频 ZIP」",
                    )
                }
            }
        } else {
            itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
                DownloadTaskCard(
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
    }
}

@Composable
private fun DownloadTaskCard(
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
            TaskKindTag(task.kind)
            Spacer(Modifier.width(6.dp))
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
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
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
private fun TaskKindTag(kind: String) {
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

private fun kindLabel(kind: String): String = when (kind) {
    "image_zip" -> "图片 ZIP"
    "video_zip" -> "视频 ZIP"
    "image" -> "单张图片"
    "video" -> "视频"
    else -> kind
}