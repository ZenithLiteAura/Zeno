package com.ZenithLiteAura.app.nezo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipnest.core.ClipItem
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 详情抽屉（MiUiX OverlayBottomSheet）：标题、作者/平台/时间、链接、原文全文、底部操作。
 */
@Composable
fun DetailSheet(
    item: ClipItem?,
    onDismiss: () -> Unit,
    onToggleStar: (ClipItem) -> Unit,
    onDelete: (ClipItem) -> Unit,
    onCopyLink: (ClipItem) -> Unit,
    onCopyRaw: (ClipItem) -> Unit,
    onOpenUrl: (ClipItem) -> Unit,
    onDownload: ((ClipItem) -> Unit)? = null,
) {
    val cs = MiuixTheme.colorScheme
    var shown by remember { mutableStateOf<ClipItem?>(null) }
    LaunchedEffect(item) { if (item != null) shown = item }
    val data = item ?: shown

    OverlayBottomSheet(
        show = item != null,
        title = data?.let { it.author.ifBlank { it.platformName.ifBlank { "详情" } } } ?: "详情",
        onDismissRequest = onDismiss,
    ) {
        val content = data ?: return@OverlayBottomSheet
        Column(
            modifier = Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = content.title.ifBlank { content.raw },
                style = MiuixTheme.textStyles.title2,
                color = cs.onSurface,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = content.platformName.ifBlank { content.platform },
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.onSurfaceVariantSummary,
                )
                Text("  ·  ", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
                Text(
                    text = fullTime(content.capturedAt),
                    style = Tnum.copy(fontSize = MiuixTheme.textStyles.footnote1.fontSize),
                    color = cs.onSurfaceVariantSummary,
                )
            }
            if (content.url.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.surfaceContainer)
                        .clickable { onOpenUrl(content) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MiuixIcons.Link, null, Modifier.size(14.dp), tint = cs.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = content.url,
                        style = MonoStyle.copy(fontSize = MiuixTheme.textStyles.footnote2.fontSize),
                        color = cs.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("原文", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
                Spacer(Modifier.width(6.dp))
                TextButton(text = "复制全文", onClick = { onCopyRaw(content) })
            }
            Text(
                text = content.raw.ifBlank { content.title },
                style = MonoStyle.copy(fontSize = MiuixTheme.textStyles.footnote2.fontSize),
                color = cs.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            if (content.rate.isNotBlank() || content.extra.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = listOf(
                        content.rate.takeIf { it.isNotBlank() }?.let { "rate $it" } ?: "",
                        content.extra.takeIf { it.isNotBlank() } ?: "",
                    ).filter { it.isNotEmpty() }.joinToString("   "),
                    style = MonoStyle.copy(fontSize = MiuixTheme.textStyles.footnote2.fontSize),
                    color = cs.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(14.dp))
            // 一键发送到下载页：只对支持的平台显示
            if (onDownload != null && canDownload(content)) {
                Button(
                    onClick = { onDownload(content) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(MiuixIcons.Download, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("解析并下载")
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("复制链接", MiuixIcons.Copy, cs.onSurfaceVariantActions) { onCopyLink(content) }
                ActionButton(
                    if (content.starred) "已加星" else "加星",
                    MiuixIcons.Favorites,
                    if (content.starred) cs.primary else cs.onSurfaceVariantActions,
                ) { onToggleStar(content) }
                ActionButton("删除", MiuixIcons.Delete, cs.error) { onDelete(content) }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = "关闭",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceContainer)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(5.dp))
        Text(label, style = MiuixTheme.textStyles.footnote2, color = tint, maxLines = 1)
    }
}

/** 手动收录抽屉（MiUiX OverlayBottomSheet）。 */
@Composable
fun CaptureSheet(
    visible: Boolean,
    actions: UiActions,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    var text by remember(visible) { mutableStateOf("") }
    var error by remember(visible) { mutableStateOf<String?>(null) }

    OverlayBottomSheet(
        show = visible,
        title = "手动收录",
        onDismissRequest = onDismiss,
        endAction = {
            TextButton(
                text = "收录",
                onClick = {
                    val value = text.trim()
                    if (value.isEmpty()) {
                        error = "先粘一段分享文本"
                        return@TextButton
                    }
                    val hit = actions.preview(value).firstOrNull { it.matched && it.item != null }
                    if (hit == null) {
                        error = "没识别到平台分享文案，换一条试试"
                        return@TextButton
                    }
                    actions.captureText(value)
                    onToast("已提交收录")
                    onDismiss()
                },
            )
        },
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "把分享文案粘进来，和自动收录用的是同一套规则。",
                style = MiuixTheme.textStyles.footnote2,
                color = cs.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = "分享文案",
                singleLine = false,
                minLines = 3,
            )
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = error ?: "",
                    style = MiuixTheme.textStyles.footnote2,
                    color = cs.error,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 二次确认（MiUiX OverlayDialog）。 */
data class ConfirmState(
    val title: String,
    val desc: String,
    val confirmLabel: String,
    val danger: Boolean,
    val onConfirm: () -> Unit,
)

@Composable
fun ConfirmDialog(state: ConfirmState?, onDismiss: () -> Unit) {
    if (state == null) return
    OverlayDialog(
        show = true,
        title = state.title,
        summary = state.desc,
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = state.confirmLabel,
                onClick = {
                    state.onConfirm()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}