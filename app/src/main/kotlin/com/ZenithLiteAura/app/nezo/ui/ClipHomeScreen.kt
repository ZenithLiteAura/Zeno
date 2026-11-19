package com.ZenithLiteAura.app.nezo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ZenithLiteAura.app.nezo.data.DownloadController
import com.ZenithLiteAura.app.nezo.ui.dock.ClipNestDock
import com.ZenithLiteAura.app.nezo.ui.download.DownloadListPage
import com.ZenithLiteAura.app.nezo.ui.download.DownloadPage
import com.ZenithLiteAura.app.nezo.ui.settings.LocalSettingsNavigate
import com.ZenithLiteAura.app.nezo.ui.settings.LogsPage
import com.ZenithLiteAura.app.nezo.ui.settings.SettingsDetailPage
import com.ZenithLiteAura.app.nezo.ui.settings.SettingsRoute
import com.ZenithLiteAura.app.nezo.ui.settings.TrashGateway
import com.ZenithLiteAura.app.nezo.ui.settings.TrashPage
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Paste
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 内部页面索引：0 主页、1 下载（dock 胶囊），2 设置（dock 圆钮）、3 识别规则（设置二级页）。 */
private const val TAB_HOME = 0
private const val TAB_DOWNLOAD = 1
private const val TAB_SETTINGS = 2
private const val TAB_RULES = 3

/** 参与切换动画的页面身份：一级页（主页/下载）与二级页（下载列表/设置/识别规则）区分动画参数。 */
private enum class NezoScreen {
    Home, Download, DownloadList, Settings, SettingsSub, Rules;

    val isPrimary: Boolean get() = this == Home || this == Download
}

/**
 * Nezo 主界面（MiUiX 外壳）。
 *
 * 一级页（主页 / 下载）显示悬浮 dock；下载列表、设置（含二级页）、识别规则都隐藏 dock。
 * 一级页切换：淡入 + 轻微水平位移（240ms）；二级页进出：slide + fade（300ms）。
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
    var tab by remember { mutableIntStateOf(TAB_HOME) }
    var pillTab by remember { mutableIntStateOf(TAB_HOME) }
    var search by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf("all") }
    var detailId by remember { mutableStateOf<String?>(null) }
    var showCapture by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmState?>(null) }
    var newIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lastSeenCaptured by remember { mutableStateOf<String?>(null) }
    // 设置页内部路由：Hub = 一级页，其它 = 二级页
    var settingsRoute by remember { mutableStateOf(SettingsRoute.Hub) }
    // 下载列表：独立二级页
    var showDownloadList by remember { mutableStateOf(false) }

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

    // dock 角标：进行中的下载数（queued + running）
    val downloadTasks by downloads.tasks.collectAsState()
    val activeDownloads = downloadTasks.count { it.state == "queued" || it.state == "running" }

    // 回收站条数（清空确认文案用）
    val trashItems by TrashGateway.trashFlow.collectAsState()

    val screen = when {
        tab == TAB_HOME -> NezoScreen.Home
        tab == TAB_DOWNLOAD -> if (showDownloadList) NezoScreen.DownloadList else NezoScreen.Download
        tab == TAB_RULES -> NezoScreen.Rules
        settingsRoute != SettingsRoute.Hub -> NezoScreen.SettingsSub
        else -> NezoScreen.Settings
    }

    // doll 显隐：只有主页 / 下载两个一级页显示
    val dockVisible = screen.isPrimary

    // 左上角返回箭头：只给「设置 / 设置二级 / 下载列表 / 识别规则」这些非一级 tab 的页面
    val showBack = screen == NezoScreen.DownloadList ||
        screen == NezoScreen.Settings ||
        screen == NezoScreen.Rules ||
        screen == NezoScreen.SettingsSub

    // 系统返回键：上面这些页面 + 下载页（下载页回主页，而不是直接退到桌面）
    val handleBack = showBack || screen == NezoScreen.Download

    fun goBack() {
        when {
            screen == NezoScreen.Settings -> {
                settingsRoute = SettingsRoute.Hub
                tab = pillTab
            }
            screen == NezoScreen.Download -> {
                tab = TAB_HOME
                pillTab = TAB_HOME
            }
            screen == NezoScreen.DownloadList -> showDownloadList = false
            screen == NezoScreen.Rules -> tab = TAB_SETTINGS
            screen == NezoScreen.SettingsSub -> settingsRoute = SettingsRoute.Hub
        }
    }

    BackHandler(enabled = handleBack) { goBack() }

    val screenTitle = when (screen) {
        NezoScreen.Home -> "主页"
        NezoScreen.Download -> "下载"
        NezoScreen.DownloadList -> "下载列表"
        NezoScreen.Settings, NezoScreen.SettingsSub -> settingsRoute.title
        NezoScreen.Rules -> "识别规则"
    }

    val requestClearAll: () -> Unit = {
        if (items.isEmpty()) {
            showToast("现在是空的，没什么可清")
        } else {
            confirm = ConfirmState(
                title = "清空全部收录？",
                desc = "本机保存的 ${items.size} 条记录会全部删除，不可撤销（回收站不受影响）。",
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
            showDownloadList = false
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
                        IconButton(onClick = { goBack() }) {
                            Icon(
                                imageVector = MiuixIcons.ChevronBackward,
                                contentDescription = "返回",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                },
                actions = {
                    if (screen == NezoScreen.Home) {
                        IconButton(onClick = { showCapture = true }) {
                            Icon(MiuixIcons.Paste, "手动收录", Modifier.size(21.dp))
                        }
                    }
                    if (screen == NezoScreen.Download) {
                        DownloadListEntry(
                            activeCount = activeDownloads,
                            onClick = { showDownloadList = true },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CompositionLocalProvider(LocalDockVisible provides dockVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        if (initialState.isPrimary && targetState.isPrimary) {
                            // 一级页：淡入 + 轻微水平位移 240ms
                            (fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                                slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it / 16 }) togetherWith
                                (fadeOut(tween(160)) +
                                    slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it / 16 })
                        } else {
                            // 二级页进出：slide + fade 300ms
                            (fadeIn(tween(300, easing = FastOutSlowInEasing)) +
                                slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 8 }) togetherWith
                                (fadeOut(tween(200)) +
                                    slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 12 })
                        }
                    },
                    label = "screen",
                ) { target ->
                    when (target) {
                        NezoScreen.Home -> InboxPage(
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
                                showDownloadList = false
                                pillTab = TAB_DOWNLOAD
                                tab = TAB_DOWNLOAD
                            },
                        )

                        NezoScreen.Download -> DownloadPage(
                            controller = downloads,
                            settings = settings,
                            actions = actions,
                            onToast = { message -> showToast(message) },
                        )

                        NezoScreen.DownloadList -> DownloadListPage(
                            controller = downloads,
                            onToast = { message -> showToast(message) },
                        )

                        NezoScreen.Rules -> RulesPage(
                            rules = rules,
                            actions = actions,
                            onToast = { message -> showToast(message) },
                        )

                        NezoScreen.Settings -> CompositionLocalProvider(
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
                                onOpenRules = { showDownloadList = false; tab = TAB_RULES },
                            )
                        }

                        NezoScreen.SettingsSub -> when (settingsRoute) {
                            SettingsRoute.Trash -> TrashPage(
                                actions = actions,
                                onToast = { message -> showToast(message) },
                                onRequestPurgeAll = {
                                    confirm = ConfirmState(
                                        title = "清空回收站？",
                                        desc = "回收站里的 ${trashItems.size} 条记录将被彻底删除，无法恢复。",
                                        confirmLabel = "清空",
                                        danger = true,
                                        onConfirm = {
                                            if (TrashGateway.purgeAll()) {
                                                showToast("回收站已清空")
                                            } else {
                                                showToast("清空失败，稍后再试")
                                            }
                                        },
                                    )
                                },
                            )

                            SettingsRoute.Logs -> LogsPage(
                                actions = actions,
                                onToast = { message -> showToast(message) },
                            )

                            else -> SettingsDetailPage(
                                route = settingsRoute,
                                settings = settings,
                                itemCount = items.size,
                                actions = actions,
                                onClearAll = requestClearAll,
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = dockVisible,
                    enter = fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(200, easing = FastOutSlowInEasing)) { it / 2 },
                    exit = fadeOut(tween(200)) +
                        slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 2 },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    ClipNestDock(
                        selectedTab = pillTab,
                        activeDownloads = activeDownloads,
                        onTabSelected = { index ->
                            settingsRoute = SettingsRoute.Hub
                            showDownloadList = false
                            pillTab = index
                            tab = index
                        },
                        onOpenSettings = {
                            settingsRoute = SettingsRoute.Hub
                            showDownloadList = false
                            tab = TAB_SETTINGS
                        },
                    )
                }
            }
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
                showDownloadList = false
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

/** 顶栏「下载列表」入口：图标 + 进行中任务角标。 */
@Composable
private fun DownloadListEntry(activeCount: Int, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Box {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = MiuixIcons.ListView,
                contentDescription = "下载列表",
                modifier = Modifier.size(21.dp),
            )
        }
        if (activeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(cs.error),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (activeCount > 9) "9+" else activeCount.toString(),
                    fontSize = 9.sp,
                    color = cs.onError,
                    maxLines = 1,
                )
            }
        }
    }
}