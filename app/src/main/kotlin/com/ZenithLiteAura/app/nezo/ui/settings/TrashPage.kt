package com.ZenithLiteAura.app.nezo.ui.settings

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ZenithLiteAura.app.nezo.ui.MonoStyle
import com.ZenithLiteAura.app.nezo.ui.NestEmpty
import com.ZenithLiteAura.app.nezo.ui.pageContentPadding
import com.ZenithLiteAura.app.nezo.ui.platformTagColors
import com.ZenithLiteAura.app.nezo.ui.relTime
import com.ZenithLiteAura.app.nezo.ui.shortUrl
import com.ZenithLiteAura.app.nezo.ui.Tnum
import com.ZenithLiteAura.app.nezo.ui.UiActions
import com.clipnest.core.ClipItem
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 回收站二级页：列表 + 单条「恢复 / 彻底删除」+ 顶部「清空回收站」。
 * 路由标题与返回箭头由 `ClipHomeScreen` 渲染；清空的二次确认也走根部的 `ConfirmState`。
 */
@Composable
fun TrashPage(
    actions: UiActions,
    onToast: (String) -> Unit,
    onRequestPurgeAll: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val trash by TrashGateway.trashFlow.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .scrollEndHaptic(),
        contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "trash-header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "回收站 · ${trash.size} 条",
                    style = MiuixTheme.textStyles.subtitle.copy(fontWeight = FontWeight.Medium),
                    color = cs.onSurfaceVariantSummary,
                )
                Spacer(Modifier.weight(1f))
                if (trash.isNotEmpty()) {
                    TextButton(
                        text = "清空回收站",
                        onClick = onRequestPurgeAll,
                        minWidth = 0.dp,
                        minHeight = 32.dp,
                        textStyle = MiuixTheme.textStyles.footnote1,
                    )
                }
            }
        }

        if (trash.isEmpty()) {
            item(key = "trash-empty") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    NestEmpty(
                        icon = MiuixIcons.Folder,
                        title = "回收站是空的",
                        sub = "下载完成或校验失败的内容会自动移到这里",
                    )
                }
            }
        } else {
            items(trash, key = { it.id }) { item ->
                TrashItemCard(
                    item = item,
                    onRestore = {
                        if (TrashGateway.restore(item.id)) {
                            onToast("已恢复到主页")
                        } else {
                            onToast("恢复失败，稍后再试")
                        }
                    },
                    onPurge = {
                        if (TrashGateway.purgeOne(item.id) { id -> actions.delete(id) }) {
                            onToast("已彻底删除")
                        } else {
                            onToast("删除失败，稍后再试")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TrashItemCard(
    item: ClipItem,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val tag = platformTagColors(item.platform)
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(
                text = item.platformName.ifBlank { item.platform },
                container = tag.container,
                content = tag.content,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.author.ifBlank { "未识别作者" },
                style = MiuixTheme.textStyles.headline2,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRestore, modifier = Modifier.size(34.dp)) {
                Icon(MiuixIcons.Refresh, "恢复", Modifier.size(18.dp), tint = cs.primary)
            }
            IconButton(onClick = onPurge, modifier = Modifier.size(34.dp)) {
                Icon(MiuixIcons.Delete, "彻底删除", Modifier.size(18.dp), tint = cs.error)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.title.ifBlank { item.raw },
            style = MiuixTheme.textStyles.body2,
            color = cs.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = shortUrl(item.url),
                style = MonoStyle.copy(fontSize = MiuixTheme.textStyles.footnote2.fontSize),
                color = cs.onSurfaceVariantSummary.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "移入 " + relTime(if (item.trashAt > 0) item.trashAt else item.capturedAt),
                style = Tnum.copy(fontSize = MiuixTheme.textStyles.footnote2.fontSize),
                color = cs.onSurfaceVariantSummary.copy(alpha = 0.72f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Badge(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = MiuixTheme.textStyles.footnote2, color = content, maxLines = 1)
    }
}