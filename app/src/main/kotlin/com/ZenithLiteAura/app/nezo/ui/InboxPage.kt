package com.ZenithLiteAura.app.nezo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipnest.core.ClipItem
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val ALL = "all"
private const val STARRED = "starred"

private val PLATFORM_ORDER = listOf("douyin", "xiaohongshu", "bilibili", "kuaishou", "weibo", "youtube", "generic")
private val PLATFORM_NAME = mapOf(
    "douyin" to "抖音",
    "xiaohongshu" to "小红书",
    "bilibili" to "哔哩哔哩",
    "kuaishou" to "快手",
    "weibo" to "微博",
    "youtube" to "YouTube",
    "generic" to "通用",
)

private sealed interface InboxEntry {
    data class Header(val label: String, val count: Int, val downloadable: Int) : InboxEntry
    data class Row(val item: ClipItem) : InboxEntry
}

/**
 * 收件箱：MiUiX SearchBar + 平台/收藏 chips + 今天/昨天/更早分组 + Card 卡片。
 * 卡片底部：「下载」主色胶囊（可下载平台）+ 加星 / 复制链接 / 浏览器打开。
 */
@Composable
fun InboxPage(
    items: List<ClipItem>,
    starredOnly: Boolean,
    search: String,
    onSearchChange: (String) -> Unit,
    platform: String,
    onPlatformChange: (String) -> Unit,
    newIds: Set<String>,
    onOpen: (ClipItem) -> Unit,
    onStar: (ClipItem) -> Unit,
    onCopy: (ClipItem) -> Unit,
    onOpenUrl: (ClipItem) -> Unit,
    onDownload: (ClipItem) -> Unit = {},
    onOpenDownload: () -> Unit = {},
) {
    val filter = if (starredOnly) STARRED else platform

    val source = remember(items) { items }
    val platforms = remember(source) {
        val seen = LinkedHashMap<String, String>()
        source.forEach {
            seen.putIfAbsent(
                it.platform,
                it.platformName.ifBlank { PLATFORM_NAME[it.platform] ?: it.platform },
            )
        }
        PLATFORM_ORDER.filter { seen.containsKey(it) }.map { it to seen.getValue(it) }
    }

    val filtered = remember(source, search, filter) {
        val q = search.trim().lowercase()
        source.filter { item ->
            val byFilter = when (filter) {
                ALL -> true
                STARRED -> item.starred
                else -> item.platform == filter
            }
            if (!byFilter) return@filter false
            if (q.isEmpty()) return@filter true
            listOf(item.author, item.title, item.url, item.platformName, item.workType)
                .joinToString(" ").lowercase().contains(q)
        }
    }

    val entries = remember(filtered) {
        val out = mutableListOf<InboxEntry>()
        val grouped = LinkedHashMap<String, MutableList<ClipItem>>()
        filtered.forEach { item ->
            grouped.getOrPut(dayGroupLabel(item.capturedAt)) { mutableListOf() }.add(item)
        }
        grouped.forEach { (label, list) ->
            out.add(InboxEntry.Header(label, list.size, list.count { canDownload(it) }))
            list.forEach { out.add(InboxEntry.Row(it)) }
        }
        out
    }

    Column(Modifier.fillMaxSize()) {
        if (!starredOnly) {
            SearchBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                inputField = {
                    TextField(
                        value = search,
                        onValueChange = onSearchChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = "搜索作者、标题或链接",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                    )
                },
                onExpandedChange = {},
                content = {},
            )
            FilterChips(platforms, filter, onPlatformChange)
        }

        if (entries.isEmpty()) {
            NestEmpty(
                icon = if (starredOnly) MiuixIcons.Favorites else MiuixIcons.ListView,
                title = when {
                    starredOnly || filter == STARRED -> "还没有加星的内容"
                    search.isNotBlank() || filter != ALL -> "没有匹配的内容"
                    else -> "还没有收录内容"
                },
                sub = when {
                    starredOnly || filter == STARRED -> "在卡片上点星标，重要的内容会留在这里"
                    search.isNotBlank() || filter != ALL -> "换个关键词，或把筛选切回「全部」"
                    else -> "复制一条抖音分享，或在外面点一下剪贴板收录"
                },
                modifier = Modifier.padding(bottom = 80.dp),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .scrollEndHaptic(),
            contentPadding = pageContentPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            entries.forEach { entry ->
                when (entry) {
                    is InboxEntry.Header -> item(key = "header-" + entry.label) {
                        InboxGroupHeader(
                            label = entry.label,
                            count = entry.count,
                            downloadable = entry.downloadable,
                            onOpenDownload = onOpenDownload,
                        )
                    }

                    is InboxEntry.Row -> item(key = entry.item.id) {
                        ClipCard(
                            item = entry.item,
                            isNew = newIds.contains(entry.item.id),
                            onOpen = { onOpen(entry.item) },
                            onStar = { onStar(entry.item) },
                            onCopy = { onCopy(entry.item) },
                            onOpenUrl = { onOpenUrl(entry.item) },
                            onDownload = { onDownload(entry.item) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxGroupHeader(
    label: String,
    count: Int,
    downloadable: Int,
    onOpenDownload: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label · $count 条",
            style = MiuixTheme.textStyles.subtitle.copy(fontWeight = FontWeight.Medium),
            color = cs.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        if (downloadable > 0) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onOpenDownload() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MiuixIcons.Download, null, Modifier.size(13.dp), tint = cs.primary)
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "$downloadable 条可下载",
                    style = MiuixTheme.textStyles.footnote2,
                    color = cs.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 卡片上的「下载」主入口：主色容器胶囊 + 图标，比小图标更显眼。 */
@Composable
private fun DownloadPill(onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    TextButton(
        text = "下载",
        onClick = onClick,
        minWidth = 58.dp,
        minHeight = 30.dp,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        textStyle = MiuixTheme.textStyles.footnote1,
        colors = ButtonDefaults.textButtonColors(
            color = cs.primaryContainer,
            textColor = cs.onPrimaryContainer,
        ),
    )
}

@Composable
private fun FilterChips(
    platforms: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val chips = remember(platforms) {
        listOf(ALL to "全部", STARRED to "收藏") + platforms
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips, key = { it.first }) { chip ->
            val active = selected == chip.first
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) cs.primary else cs.surfaceContainer)
                    .clickable { onSelect(chip.first) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = chip.second,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (active) cs.onPrimary else cs.onSurfaceVariantActions,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 一条收录卡片。 */
@Composable
fun ClipCard(
    item: ClipItem,
    isNew: Boolean,
    onOpen: () -> Unit,
    onStar: () -> Unit,
    onCopy: () -> Unit,
    onOpenUrl: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MiuixTheme.colorScheme
    val tag = platformTagColors(item.platform)
    val downloadable = canDownload(item)

    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        onClick = onOpen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.author.ifBlank { item.platformName.ifBlank { "未识别作者" } },
                style = MiuixTheme.textStyles.headline2,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(6.dp))
            if (item.workType.isNotBlank()) {
                TagText(item.workType, tag.container, tag.content)
                Box(Modifier.width(8.dp))
            }
            Text(
                text = item.platformName.ifBlank { PLATFORM_NAME[item.platform] ?: "" },
                style = MiuixTheme.textStyles.footnote2,
                color = cs.onSurfaceVariantSummary,
            )
            if (item.rate.isNotBlank()) {
                Box(Modifier.width(6.dp))
                Text(text = item.rate, style = MonoStyle, color = cs.onSurfaceVariantSummary)
            }
        }
        Box(Modifier.height(4.dp))
        Text(
            text = item.title.ifBlank { item.raw },
            style = MiuixTheme.textStyles.body2,
            color = cs.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = shortUrl(item.url),
                style = MonoStyle.copy(fontSize = MiuixTheme.textStyles.footnote2.fontSize),
                color = cs.onSurfaceVariantSummary.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(Modifier.width(8.dp))
            Text(
                text = relTime(item.capturedAt),
                style = Tnum.copy(fontSize = MiuixTheme.textStyles.footnote2.fontSize),
                color = cs.onSurfaceVariantSummary.copy(alpha = 0.72f),
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (downloadable) {
                DownloadPill(onClick = onDownload)
                Spacer(Modifier.width(2.dp))
            }
            CardAction(
                icon = if (item.starred) MiuixIcons.FavoritesFill else MiuixIcons.Favorites,
                desc = if (item.starred) "取消加星" else "加星",
                tint = if (item.starred) cs.primary else cs.onSurfaceVariantActions,
                onClick = onStar,
            )
            CardAction(MiuixIcons.Copy, "复制链接", cs.onSurfaceVariantActions, onCopy)
            CardAction(MiuixIcons.Forward, "浏览器打开", cs.onSurfaceVariantActions, onOpenUrl)
        }
    }
}

@Composable
private fun TagText(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = MiuixTheme.textStyles.footnote2, color = content, maxLines = 1)
    }
}

@Composable
private fun CardAction(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, desc, Modifier.size(17.dp), tint = tint)
    }
}