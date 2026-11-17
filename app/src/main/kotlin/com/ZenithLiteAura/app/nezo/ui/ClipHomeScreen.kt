package com.ZenithLiteAura.app.nezo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ZenithLiteAura.app.nezo.data.DownloadController
import com.ZenithLiteAura.app.nezo.ui.dock.ClipNestDock
import com.ZenithLiteAura.app.nezo.ui.download.DownloadPage
import com.ZenithLiteAura.app.nezo.ui.settings.LocalSettingsNavigate
import com.ZenithLiteAura.app.nezo.ui.settings.SettingsDetailPage
import com.ZenithLiteAura.app.nezo.ui.settings.SettingsRoute
import com.clipnest.core.AppSettings
import com.clipnest.core.CaptureRule
import com.clipnest.core.ClipItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Paste
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 内部页面索引：0 收件箱、1 下载（dock 胶囊），2 设置（dock 圆钮）、3 识别规则（设置二级页）。 */
private const val TAB_INBOX = 0
private const val TAB_DOWNLOAD = 1
private const val TAB_SETTINGS = 2
private const val TAB_RULES = 3

/**
 * Nezo主界面（MiUiX 版外壳）。
 *
 * 结构对齐 NexioSchedule：`Scaffold` + `SmallTopAppBar`（居中标题 + 右侧 IconButton）+
 * 页面 LazyColumn + 底部悬浮 dock + `SnackbarHost`；不再有自绘壁纸 / 玻璃面板。
 *
 * 契约：函数签名保持不变，MainActivity 只认它。
 */
@Composable
fun ClipHomeScreen(
    items: List<ClipItem>,
    settings: AppSettings,
    rules: List<CaptureRule>,
    lastCaptured: ClipItem?,
    actions: UiActions,
    downloads: DownloadController,
) {
    var tab by remember { mutableIntStateOf(TAB_INBOX) }
    var pillTab by remember { mutableIntStateOf(TAB_INBOX) }
    var search by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf("all") }
    var detailId by remember { mutableStateOf<String?>(null) }
    var showCapture by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmState?>(null) }
    var newIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lastSeenCaptured by remember { mutableStateOf<String?>(null) }
    // 设置页内部路由：Hub = 一级页，其它 = 二级页
    var settingsRoute by remember { mutableStateOf(SettingsRoute.Hub) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun showToast(message: String) {
        if (message.isBlank()) return
        scope.launch { snackbar.showSnackbar(message) }
    }

    LaunchedEffect(lastCaptured?.id) {
        val captured = lastCaptured ?: return@LaunchedEffect
        if (captured.id == lastSeenCaptured) return@LaunchedEffect
        lastSeenCaptured = captured.id
        newIds = newIds + captured.id
        showToast("已收录 · " + captured.author.ifBlank { captured.platformName.ifBlank { "新内容" } })
        delay(2400)
        newIds = newIds - captured.id
    }

    val detail = remember(detailId, items) { items.firstOrNull { it.id == detailId } }
    val starredCount = items.count { it.starred }
    val enabledRules = rules.count { it.enabled }

    // 顶栏返回：识别规则 → 回设置一级页；设置二级页 → 回设置一级页
    val showBack = tab == TAB_RULES || (tab == TAB_SETTINGS && settingsRoute != SettingsRoute.Hub)

    // dock 角标：进行中的下载数（queued + running）
    val downloadTasks by downloads.tasks.collectAsState()
    val activeDownloads = downloadTasks.count { it.state == "queued" || it.state == "running" }

    BackHandler(enabled = showBack) {
        when {
            tab == TAB_RULES -> tab = TAB_SETTINGS
            settingsRoute != SettingsRoute.Hub -> settingsRoute = SettingsRoute.Hub
        }
    }

    val titles = listOf("收件箱", "下载", "设置", "识别规则")
    val screenTitle = if (tab == TAB_SETTINGS) settingsRoute.title else titles[tab]

    val requestClearAll: () -> Unit = {
        if (items.isEmpty()) {
            showToast("现在是空的，没什么可清")
        } else {
            confirm = ConfirmState(
                title = "清空全部收录？",
                desc = "本机保存的 ${items.size} 条记录会全部删除，不可撤销。",
                confirmLabel = "清空",
                danger = true,
                onConfirm = {
                    actions.clearAll()
                    showToast("已清空 ${items.size} 条记录")
                },
            )
        }
    }

    val onStar: (ClipItem) -> Unit = { item -> actions.setStar(item.id, !item.starred) }
    val onCopy: (ClipItem) -> Unit = { item ->
        if (item.url.isNotBlank()) {
            actions.copyText(item.url)
            showToast("已复制链接")
        }
    }
    val onOpenUrl: (ClipItem) -> Unit = { item ->
        if (item.url.isNotBlank()) actions.openUrl(item.url) else showToast("这条没有链接")
    }
    val onDownload: (ClipItem) -> Unit = { item ->
        if (item.url.isBlank()) {
            showToast("这条没有可下载的链接")
        } else {
            tab = TAB_DOWNLOAD
            pillTab = TAB_DOWNLOAD
            downloads.resolve(item.url)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = screenTitle,
                color = MiuixTheme.colorScheme.background,
                navigationIcon = {
                    if (showBack) {
                        IconButton(
                            onClick = {
                                if (tab == TAB_RULES) tab = TAB_SETTINGS else settingsRoute = SettingsRoute.Hub
                            },
                        ) {
                            Icon(
                                imageVector = MiuixIcons.ChevronBackward,
                                contentDescription = "返回",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                },
                actions = {
                    if (tab == TAB_DOWNLOAD) {
                        IconButton(onClick = { downloads.clearFinished() }) {
                            Icon(MiuixIcons.Clear, "清空已完成", Modifier.size(21.dp))
                        }
                    }
                    if (tab == TAB_INBOX || tab == TAB_DOWNLOAD) {
                        IconButton(onClick = { showCapture = true }) {
                            Icon(MiuixIcons.Paste, "手动收录", Modifier.size(21.dp))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Crossfade(targetState = tab, animationSpec = tween(240), label = "tab") { index ->
                when (index) {
                    TAB_INBOX -> InboxPage(
                        items = items,
                        starredOnly = false,
                        search = search,
                        onSearchChange = { search = it },
                        platform = platform,
                        onPlatformChange = { platform = it },
                        newIds = newIds,
                        onOpen = { detailId = it.id },
                        onStar = onStar,
                        onCopy = onCopy,
                        onOpenUrl = onOpenUrl,
                        onDownload = onDownload,
                        onOpenDownload = {
                            pillTab = TAB_DOWNLOAD
                            tab = TAB_DOWNLOAD
                        },
                    )

                    TAB_DOWNLOAD -> DownloadPage(
                        controller = downloads,
                        settings = settings,
                        actions = actions,
                        onToast = { message -> showToast(message) },
                    )

                    TAB_RULES -> RulesPage(
                        rules = rules,
                        actions = actions,
                        onToast = { message -> showToast(message) },
                    )

                    else -> if (settingsRoute == SettingsRoute.Hub) {
                        CompositionLocalProvider(
                            LocalSettingsNavigate provides { route -> settingsRoute = route },
                        ) {
                            SettingsPage(
                                settings = settings,
                                rules = rules.size,
                                rulesEnabled = enabledRules,
                                itemCount = items.size,
                                starredCount = starredCount,
                                actions = actions,
                                onClearAll = requestClearAll,
                                onOpenCapture = { showCapture = true },
                                onOpenRules = { tab = TAB_RULES },
                            )
                        }
                    } else {
                        SettingsDetailPage(
                            route = settingsRoute,
                            settings = settings,
                            itemCount = items.size,
                            actions = actions,
                            onClearAll = requestClearAll,
                        )
                    }
                }
            }

            ClipNestDock(
                selectedTab = pillTab,
                // dock 角标：进行中下载数（queued + running），dock 侧负责绘制
                activeDownloads = activeDownloads,
                onTabSelected = { index ->
                    settingsRoute = SettingsRoute.Hub
                    pillTab = index
                    tab = index
                },
                onOpenSettings = {
                    settingsRoute = SettingsRoute.Hub
                    tab = TAB_SETTINGS
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        DetailSheet(
            item = detail,
            onDismiss = { detailId = null },
            onToggleStar = onStar,
            onDelete = { item ->
                detailId = null
                confirm = ConfirmState(
                    title = "删除这一条？",
                    desc = "「" + item.title.ifBlank { item.raw }.take(34) + "」会从本机记录里移除，不可撤销。",
                    confirmLabel = "删除",
                    danger = true,
                    onConfirm = {
                        actions.delete(item.id)
                        showToast("已删除这一条")
                    },
                )
            },
            onCopyLink = onCopy,
            onCopyRaw = { item ->
                actions.copyText(item.raw.ifBlank { item.title })
                showToast("已复制全文")
            },
            onOpenUrl = onOpenUrl,
            onDownload = { item ->
                detailId = null
                pillTab = TAB_DOWNLOAD
                tab = TAB_DOWNLOAD
                downloads.resolve(item.url)
            },
        )

        CaptureSheet(
            visible = showCapture,
            actions = actions,
            onDismiss = { showCapture = false },
            onToast = { message -> showToast(message) },
        )

        ConfirmDialog(state = confirm, onDismiss = { confirm = null })
    }
}