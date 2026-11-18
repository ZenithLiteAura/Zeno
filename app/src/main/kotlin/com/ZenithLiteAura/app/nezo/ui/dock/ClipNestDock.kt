package com.ZenithLiteAura.app.nezo.ui.dock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 圆钮直径，与胶囊同高 */
private val BOTTOM_BAR_CIRCLE = 56.dp

/** 胶囊与圆钮的静止间距 */
private val BOTTOM_BAR_GAP = 10.dp

/** 每格占屏比，沿用参考项目「3 格占 0.63」的密度 */
private const val TAB_WIDTH_FRACTION = 0.21f

/** 胶囊里的 tab 数：主页 / 下载。设置是右侧那颗圆钮，不占格。 */
private const val TAB_COUNT = 2

/**
 * ClipNest Nezo的底部 dock —— 布局与常量搬自 NexioSchedule `ui/components/ScheduleBottomBar.kt`。
 *
 * 形态与参考截图 1:1：一块 G2 连续曲率胶囊 + 右侧一颗独立圆钮，两块之间 10dp 静止间距，
 * 整条 dock 浮在内容上方（`bottomFloatingPadding()`），按「胶囊 + 间距 + 圆钮」水平居中。
 *
 * 与参考的差异只在**导航语义**：
 * - 胶囊里 2 个 tab：主页（`MiuixIcons.ListView`）、规则（`MiuixIcons.Download`）；
 * - 右侧圆钮：设置（`MiuixIcons.Settings`），点击进「设置」页；
 * - 收藏不再是一级 tab，收进「主页」的筛选 chip。
 *
 * @param selectedTab 胶囊选中的下标（0 = 主页，1 = 下载）。进设置页时保持不变。
 * @param onTabSelected 胶囊 tab 被选中
 * @param onOpenSettings 右侧圆钮被点击
 */
@Composable
fun ClipNestDock(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** 进行中的下载任务数（排队 + 下载中），>0 时在「下载」tab 上显示角标 */
    activeDownloads: Int = 0,
) {
    val iconTint = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.8f)

    val openSettings by rememberUpdatedState(onOpenSettings)
    val trailing = remember {
        LiquidBottomTrailing(
            icon = MiuixIcons.Settings,
            contentDescription = "设置",
            onClick = { openSettings() },
            gap = BOTTOM_BAR_GAP,
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomFloatingPadding())
            .height(BOTTOM_BAR_CIRCLE),
    ) {
        // 每格占屏 0.21（参考「3 格占 0.63」的密度）
        val pillWidth = maxWidth * (TAB_WIDTH_FRACTION * TAB_COUNT)
        // 居中按「胶囊 + 间距 + 圆钮」算；两块独立表面，不需要额外拖动余量
        val visibleWidth = pillWidth + BOTTOM_BAR_GAP + BOTTOM_BAR_CIRCLE
        val nodeLeft = (maxWidth - visibleWidth) / 2

        LiquidBottomTabs(
            selectedTabIndex = { selectedTab.coerceIn(0, TAB_COUNT - 1) },
            onTabSelected = onTabSelected,
            tabsCount = TAB_COUNT,
            containerHeight = BOTTOM_BAR_CIRCLE,
            trailing = trailing,
            modifier = Modifier
                .offset(x = nodeLeft)
                .width(visibleWidth)
                .height(BOTTOM_BAR_CIRCLE),
        ) {
            LiquidBottomTab {
                Icon(
                    imageVector = MiuixIcons.ListView,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = iconTint,
                )
                Text(
                    text = "主页",
                    fontSize = 11.sp,
                    color = iconTint,
                    maxLines = 1,
                )
            }
            LiquidBottomTab {
                Box {
                    Icon(
                        imageVector = MiuixIcons.Download,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconTint,
                    )
                    if (activeDownloads > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 8.dp, y = (-5).dp)
                                .size(15.dp)
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (activeDownloads > 9) "9+" else activeDownloads.toString(),
                                fontSize = 9.sp,
                                color = MiuixTheme.colorScheme.onPrimary,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Text(
                    text = "下载",
                    fontSize = 11.sp,
                    color = iconTint,
                    maxLines = 1,
                )
            }
        }
    }
}