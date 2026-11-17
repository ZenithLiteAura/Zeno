package com.ZenithLiteAura.app.nezo.ui.dock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import com.ZenithLiteAura.app.nezo.ui.theme.LocalNestDark
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 底部导航底栏 —— 搬自 NexioSchedule `ui/components/LiquidBottomTabs.kt`。
 *
 * **已删掉的依赖**（原版要柔光玻璃 shader，本工程没接）：
 * - `drawBackdrop(...)` + `softglass` 的 blur / runtimeShader / `applySoftGlass` / `GlassBackdrop`
 *   → 换成 [dockGlassSurface]：半透明实色 + 1dp 顶部内高光 + 外投影，形状仍是 [ContinuousCapsule]；
 * - `InteractiveHighlight`（自带 runtimeShader 光晕）→ [DockPressState] + 径向渐变按压高光；
 * - `DampedDragAnimation`（Animatable 弹簧组）→ 只保留按压进度 [DockPressState]（它原本只用于
 *   按压高光与按压缩放；滑块的位移一直由 [CapsuleSelector] 的弹簧负责，与本类无关）；
 * - `computeDragTransform`（按压缩放 + 沿拖动方向拉伸 + tanh 阻尼位移）→ 简单 `graphicsLayer`
 *   按压缩放（1 + 2dp / 高度 × 进度，与原版 pressScaleDp = 2f 的公式一致）。
 *
 * 其余几何、手势、动效参数**逐字保留**：胶囊/滑块/圆钮尺寸、拖动抓取判定 0.55 格宽、
 * 拖尾低通 0.55 与增益 4、快甩阈值 420dp/s、松手吸附与快甩补切、左右两条弹簧的吸附手感。
 */
private const val DOCK_PRESS_SCALE_DP = 2f

/** 底栏胶囊底色。tab 胶囊与圆按钮共用同一块材质，颜色必须同源，所以只在这里定义一次。 */
internal fun dockSurfaceColor(isDark: Boolean): Color =
    if (isDark) Color(0xFF242424).copy(alpha = 0.94f) else Color(0xFFFAFAFA).copy(alpha = 0.94f)

/**
 * 我们自己的「玻璃」表面（替代 softglass）：半透明实色 + 顶部 1dp 内高光 + 外投影。
 *
 * 形状必须是 [ContinuousCapsule]：端头是 G2 连续曲率，普通 `RoundedCornerShape(50%)` 的
 * 半圆端头会明显「收」进去一截。
 */
internal fun Modifier.dockGlassSurface(shape: Shape, isDark: Boolean): Modifier = this
    .shadow(elevation = 16.dp, shape = shape, clip = false)
    .clip(shape)
    .background(dockSurfaceColor(isDark))
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.9f), Color.Transparent),
        ),
        shape = shape,
    )

/**
 * 按压/拖动进度 + 跟手位置（替代 `InteractiveHighlight` 的弹簧部分）。
 *
 * 高光松手时**原地**淡出：位置停在松手处，不回弹，否则会看到「拖过去再滑回来」的位移感。
 */
@Stable
internal class DockPressState(private val scope: CoroutineScope) {

    private val pressAnimation = Animatable(0f, PRESS_VISIBILITY_THRESHOLD)
    private val positionAnimation = Animatable(
        Offset.Zero,
        Offset.VectorConverter,
        Offset(PRESS_VISIBILITY_THRESHOLD, PRESS_VISIBILITY_THRESHOLD),
    )

    /** 按压进度 0..1 */
    val progress: Float get() = pressAnimation.value

    /** 高光中心（按下时的手指位置，拖动时跟手） */
    val offset: Offset get() = positionAnimation.value

    fun press(position: Offset) {
        scope.launch {
            launch { pressAnimation.animateTo(1f, PressSpec) }
            launch { positionAnimation.snapTo(position) }
        }
    }

    fun track(position: Offset) {
        scope.launch {
            launch { positionAnimation.snapTo(position) }
        }
    }

    fun release() {
        scope.launch {
            launch { pressAnimation.animateTo(0f, PressSpec) }
        }
    }

    private companion object {
        /** 与参考实现同一组曲线：dampingRatio 0.5 / stiffness 300。 */
        const val PRESS_VISIBILITY_THRESHOLD = 0.001f
        val PressSpec = spring<Float>(
            dampingRatio = 0.5f,
            stiffness = 300f,
            visibilityThreshold = PRESS_VISIBILITY_THRESHOLD,
        )
    }
}

/**
 * 纯观察式按压跟踪：不消费任何指针事件，`clickable` / 拖动手势照常工作。
 *
 * 走 [PointerEventPass.Initial]：在兄弟节点 consume 之前拿到事件，拖动手势吃掉位移后照样跟手。
 */
private fun Modifier.dockPressTracker(state: DockPressState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        state.press(down.position)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            state.track(change.position)
        }
        state.release()
    }
}

/**
 * 按压高光：手指处的圆形径向渐变（白色叠加），裁在父级形状里。
 *
 * @param radiusBaseDp 光晕半径基准；null = 用节点 minDimension（胶囊），给了就 min(节点, 基准)
 * @param fixedRadius 是否固定用 [radiusBaseDp]（圆钮：正圆，光晕半径按直径算）
 * @param drawFlatOverlay 是否额外铺一层 8% 整面提亮（胶囊要、圆钮不要）
 */
private fun Modifier.dockPressHighlight(
    state: DockPressState,
    isDark: Boolean,
    radiusBaseDp: Dp?,
    fixedRadius: Boolean,
    lightPeakAlpha: Float,
    drawFlatOverlay: Boolean,
): Modifier = drawWithContent {
    val progress = state.progress
    if (progress > 0f && size.width > 0f && size.height > 0f) {
        val base = when {
            radiusBaseDp == null -> size.minDimension
            fixedRadius -> radiusBaseDp.toPx()
            else -> minOf(size.minDimension, radiusBaseDp.toPx())
        }
        val radius = base * 1.5f
        val raw = state.offset
        val center = Offset(
            raw.x.coerceIn(0f, size.width),
            raw.y.coerceIn(0f, size.height),
        )
        val peak = (if (isDark) 0.15f else lightPeakAlpha) * progress
        if (drawFlatOverlay) {
            drawRect(Color.White.copy(alpha = 0.08f * progress), blendMode = BlendMode.Plus)
        }
        // 无 GlassRuntimeShader（API<33）：用径向渐变而不是整块平铺 —— 平铺没有圆形边界
        drawRect(
            brush = Brush.radialGradient(
                center = center,
                radius = radius,
                colors = listOf(
                    Color.White.copy(alpha = peak),
                    Color.White.copy(alpha = peak),
                    Color.Transparent,
                ),
            ),
            blendMode = BlendMode.Plus,
        )
    }
    drawContent()
}

/**
 * 底栏右端的圆形按钮：一块**独立**表面，与 tab 胶囊之间只有间距、没有融合。
 */
internal data class LiquidBottomTrailing(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    /** 与胶囊的静止间距 */
    val gap: Dp = 10.dp,
)

/** 底部导航 tab：纯视觉，手势由 [LiquidBottomTabs] 统一处理 */
@Composable
internal fun RowScope.LiquidBottomTab(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(ContinuousCapsule)
            .semantics { role = Role.Tab }
            .fillMaxHeight()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/**
 * 底部导航：一块 tab 胶囊 + 一颗独立圆按钮，两块表面互不融合。
 *
 * 手势统一由最上层的手势节点处理：按下 tab 选中、拖胶囊 1:1 跟手、松手吸附最近 tab 并提交。
 * 滑块只是胶囊内的纯色指示（miuix `OverlayView` 同款：左右两条边各一条弹簧）。
 *
 * 形变：胶囊与圆钮**各一份** [DockPressState]，按压缩放走 `graphicsLayer`（尺寸无关的
 * 1 + 2dp/h × 进度），高光单独一层并跟着父级形状裁切；滑块只跟手平移、不额外缩放。
 */
@Composable
internal fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    containerHeight: Dp = 56.dp,
    selectorHeight: Dp = 48.dp,
    trailing: LiquidBottomTrailing? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val isLightTheme = !LocalNestDark.current
    val isDark = !isLightTheme
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()
    val maxIndex = (tabsCount - 1).toFloat()

    // 胶囊与圆按钮各有自己的按压进度：两块表面分开之后不能再共用一份高光状态，
    // 否则按其中一块、另一块也会跟着亮。
    val pillPress = remember(animationScope) { DockPressState(animationScope) }
    val trailingPress = remember(animationScope, containerHeight) { DockPressState(animationScope) }

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val viewConfiguration = LocalViewConfiguration.current
        val gapDp = trailing?.gap ?: 0.dp
        val trailingSize = if (trailing != null) containerHeight else 0.dp
        val pillPx =
            constraints.maxWidth.toFloat() - with(density) { (gapDp + trailingSize).toPx() }
        val pillWidthDp = with(density) { pillPx.toDp() }
        val padPx = with(density) { 4f.dp.toPx() }
        val tabWidthPx = (pillPx - padPx * 2f) / tabsCount
        val selectorInsetPx = with(density) { CapsuleSlider.Inset.toPx() }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val ltrSign = if (isLtr) 1f else -1f
        // 不能用 selectedTabIndex 做 remember key：每次重组都是新实例，currentIndex 会被冲掉
        var currentIndex by remember { mutableIntStateOf(selectedTabIndex()) }
        val latestSelectedTabIndex by rememberUpdatedState(selectedTabIndex)

        val selectorWidthPx = tabWidthPx - selectorInsetPx * 2f
        // 槽位左边界：RTL 下 index 0 在最右，几何位置与逻辑 index 反向
        val slotLeft: (Float) -> Float = { index ->
            if (isLtr) {
                padPx + index * tabWidthPx + selectorInsetPx
            } else {
                pillPx - padPx - (index + 1f) * tabWidthPx + selectorInsetPx
            }
        }
        val capsule = remember(
            animationScope, tabsCount, tabWidthPx, pillPx, padPx, selectorInsetPx, isLtr,
        ) {
            CapsuleSelector(
                scope = animationScope,
                widthPx = selectorWidthPx,
                startLeftPx = slotLeft(currentIndex.toFloat()),
                // trail 到边就不再延伸（原版同款夹限），也不许滑出胶囊的 4dp 内边
                leftLimit = { padPx..(pillPx - padPx - selectorWidthPx) },
            )
        }
        val selectorHeightPx = with(density) { selectorHeight.roundToPx() }
        val trailMaxPx = with(density) { CapsuleSlider.TrailMax.toPx() }
        // 快甩阈值 dp/秒 → px/秒
        val flingVelocityThresholdPxPerSec =
            with(density) { CapsuleSlider.FlingVelocityDpPerSecond.toPx() }
        // 松手 / 点别的 tab 时，目标槽位落在胶囊当前所在位置的哪一侧（几何方向，RTL 自动成立）
        val settleToSlot: (Int) -> Unit = { index ->
            val target = slotLeft(index.toFloat())
            capsule.settleTo(target, forward = target > capsule.leftPx)
        }
        // 胶囊视觉中心落在哪个槽位。比逻辑 index 准，且天然支持 RTL 与半格拖拽。
        val indexAtCapsule: () -> Int = {
            val raw = if (isLtr) {
                (capsule.centerPx - padPx) / tabWidthPx - 0.5f
            } else {
                (pillPx - padPx - capsule.centerPx) / tabWidthPx - 0.5f
            }
            raw.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
        }
        LaunchedEffect(tabsCount) {
            snapshotFlow { latestSelectedTabIndex() }
                .collectLatest { index ->
                    if (index != currentIndex) currentIndex = index
                }
        }
        LaunchedEffect(capsule) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    settleToSlot(index)
                    onTabSelected(index)
                }
        }

        // 两块表面都用绝对定位：不能用 Row —— 它在 RTL 下会自动反转子节点顺序，
        // 而下面的手势层是绝对定位的，反转后手势区与胶囊会错开。
        // ── tab 胶囊 ──
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(pillWidthDp)
                .height(containerHeight)
                // 整块胶囊（表面 + 滑块 + 图标）一起按压放大。
                // 用 draw 期读同一份按压状态，不进组合期 animation state。
                .graphicsLayer {
                    val progress = pillPress.progress
                    if (progress > 0f && size.height > 0f) {
                        val scale = 1f + DOCK_PRESS_SCALE_DP.dp.toPx() / size.height * progress
                        scaleX = scale
                        scaleY = scale
                    }
                }
                .dockGlassSurface(shape = ContinuousCapsule, isDark = isDark),
        ) {
            // 按压高光：整面 8% 提亮 + 手指处径向光晕，随父级形状一起裁切
            Box(
                Modifier
                    .matchParentSize()
                    .dockPressHighlight(
                        state = pillPress,
                        isDark = isDark,
                        radiusBaseDp = null,
                        fixedRadius = false,
                        // 底栏是半透明表面，浅色默认 0.05 太弱
                        lightPeakAlpha = 0.1f,
                        drawFlatOverlay = true,
                    )
            )
            // 选中滑块：抄 miuix OverlayView —— 左右两条边各自一条弹簧，
            // 位移途中宽度先撑开再收回，所以平移带橡皮筋感。
            // 位置与宽度都在 layout 期读（[capsuleBounds]），组合期不读任何高频值。
            Box(
                Modifier.capsuleSliderPill(
                    selector = capsule,
                    heightPx = selectorHeightPx,
                    pressProgress = { pillPress.progress },
                )
            )
            Row(
                Modifier.fillMaxSize().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }

        // ── 右侧圆按钮：另一块独立表面 ──
        if (trailing != null) {
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = pillWidthDp + gapDp)
                    .size(containerHeight)
                    // 与胶囊同一套按压缩放：尺寸无关的 1 + 2dp/h × 进度
                    .graphicsLayer {
                        val progress = trailingPress.progress
                        if (progress > 0f && size.height > 0f) {
                            val scale = 1f + DOCK_PRESS_SCALE_DP.dp.toPx() / size.height * progress
                            scaleX = scale
                            scaleY = scale
                        }
                    }
                    .dockGlassSurface(shape = ContinuousCapsule, isDark = isDark)
                    // 纯观察、不消费，clickable 照常收到点击
                    .dockPressTracker(trailingPress)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            trailing.onClick()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 高光单独一层：与胶囊那份完全独立（圆钮只要手指处光晕，不整面铺白）
                Box(
                    Modifier
                        .matchParentSize()
                        .dockPressHighlight(
                            state = trailingPress,
                            isDark = isDark,
                            radiusBaseDp = containerHeight,
                            fixedRadius = true,
                            lightPeakAlpha = 0.1f,
                            drawFlatOverlay = false,
                        )
                )
                androidx.compose.material3.Icon(
                    imageVector = trailing.icon,
                    contentDescription = trailing.contentDescription,
                    tint = if (isLightTheme) Color.Black.copy(alpha = 0.85f)
                    else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        // 统一手势层：按下 tab / 拖胶囊 / 松手选中。
        // 只覆盖胶囊宽度 —— 它是最上层的兄弟节点，盖到圆按钮会抢走命中测试。
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(pillWidthDp)
                .fillMaxHeight()
                // 胶囊的高光/拉伸进度由这一层手势观察：下面的 pointerInput 会 consume，
                // 跟踪层走 Initial pass，照样能跟手
                .dockPressTracker(pillPress)
                .pointerInput(
                    capsule, tabsCount, tabWidthPx, isLtr, padPx, pillPx, trailMaxPx,
                    flingVelocityThresholdPxPerSec,
                ) {
                    val touchSlop = viewConfiguration.touchSlop
                    val flingVelocityThreshold = flingVelocityThresholdPxPerSec
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downX = down.position.x
                        // 圆按钮那一侧交给它自己的点击
                        if (downX > pillPx) return@awaitEachGesture
                        val startIndex = currentIndex.coerceIn(0, tabsCount - 1)
                        val fling = CapsuleFlingTracker(down.uptimeMillis, downX)

                        // 按在滑块上才是拖；按在别的 tab 上直接选中（同原版：胶囊就是拖动把手）
                        val capsuleHalf = tabWidthPx * CapsuleSlider.GrabHalfWidthRatio
                        var dragging = abs(downX - capsule.centerPx) <= capsuleHalf
                        var pressedTab = -1
                        // 以「按下那一刻胶囊在哪」为锚，不用逻辑 index：
                        // 从飞行中途接手也不会先跳一格
                        var dragAnchorLeft = capsule.leftPx
                        var dragAnchorX = downX
                        var trailVelocity = 0f

                        if (!dragging) {
                            pressedTab = if (isLtr) {
                                floor((downX - padPx) / tabWidthPx).toInt()
                            } else {
                                floor((size.width - padPx - downX) / tabWidthPx).toInt()
                            }.fastCoerceIn(0, tabsCount - 1)
                            settleToSlot(pressedTab)
                        }

                        var lastX = downX
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                // 松手吸附按胶囊的实际位置判，不用逻辑 value
                                val positionalTarget = if (dragging) {
                                    indexAtCapsule()
                                } else {
                                    pressedTab.fastCoerceIn(0, tabsCount - 1)
                                }
                                // RTL 下把屏幕方向换成逻辑 index 方向
                                val flingDir = if (dragging) {
                                    fling.flingDirection(flingVelocityThreshold, touchSlop) *
                                        ltrSign.toInt()
                                } else 0
                                val target = CapsuleSlider.flingTarget(
                                    startIndex = startIndex,
                                    positionalTarget = positionalTarget,
                                    indexDirection = flingDir,
                                    lastIndex = tabsCount - 1,
                                )
                                currentIndex = target
                                settleToSlot(target)
                                // 立即通知宿主，不等 snapshotFlow 下一拍，减少与 pager 错帧
                                onTabSelected(target)
                                break
                            }

                            val dx = change.position.x - lastX
                            val totalDx = change.position.x - downX
                            fling.onMove(change.uptimeMillis, change.position, downX)

                            // 点 tab 后继续滑，接管为拖动；锚点直接取当前手指位置，
                            // 把滑出 touchSlop 的那一小段吃掉，胶囊不会突然跳一下
                            if (!dragging && abs(totalDx) > touchSlop) {
                                dragging = true
                                dragAnchorLeft = capsule.leftPx
                                dragAnchorX = change.position.x
                                trailVelocity = 0f
                                lastX = change.position.x
                            }

                            if (dragging) {
                                // 拖尾：原版是 min(4 × 单帧位移, 60px)，这里先做一阶低通，
                                // 否则每帧 dx 抖动会让尾巴一直哆嗦
                                trailVelocity = trailVelocity * CapsuleSlider.TrailSmoothing +
                                    dx * (1f - CapsuleSlider.TrailSmoothing)
                                val trail = (abs(trailVelocity) * CapsuleSlider.TrailGain)
                                    .coerceAtMost(trailMaxPx)
                                val dir = when {
                                    trailVelocity > 0.5f -> 1
                                    trailVelocity < -0.5f -> -1
                                    else -> 0
                                }
                                capsule.onDrag(
                                    leftPx = dragAnchorLeft + (change.position.x - dragAnchorX),
                                    trailPx = trail,
                                    dir = dir,
                                )
                            }

                            lastX = change.position.x
                            change.consume()
                        }
                    }
                }
        )
    }
}