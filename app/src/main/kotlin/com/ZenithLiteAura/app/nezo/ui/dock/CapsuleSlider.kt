package com.ZenithLiteAura.app.nezo.ui.dock

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import com.ZenithLiteAura.app.nezo.ui.theme.LocalNestDark
import kotlin.math.abs

/**
 * 胶囊滑块的**唯一参数源** —— 搬自 NexioSchedule `ui/components/CapsuleSlider.kt`。
 *
 * 滑块的运动本身由 [CapsuleSelector] 负责（左右两条边界各一条弹簧、位移途中先撑开再收回），
 * 这里只管尺寸、颜色与按压表现。数值一个都没改。
 */
internal object CapsuleSlider {

    /** 滑块左右各内缩：宽度减 2×本值、位置补 1×本值，保持居中 */
    val Inset = 0.5.dp

    /** 滑块上下各内缩（底栏容器 56 → 滑块 48） */
    val VerticalInset = 8.dp

    /** 拖尾上限 */
    val TrailMax = 20.dp

    /** 按压时四周各缩多少（收缩量 = 2 × 本值） */
    val PressShrink = 2.dp

    /** 按压时容器的放大量（78 / 56） */
    const val PressScale: Float = 78f / 56f

    /** 拖尾增益：原版 miuix `OverlayView` 的 `min(4 × 单帧位移, 60px)` */
    const val TrailGain: Float = 4f

    /** 拖尾的一阶低通系数：直接吃每帧 dx 会一直哆嗦 */
    const val TrailSmoothing: Float = 0.55f

    /** 判定「按在了滑块上」的半格宽比例：小于它算抓滑块，大于它算点格子 */
    const val GrabHalfWidthRatio: Float = 0.55f

    /** 快甩阈值（dp/秒） */
    val FlingVelocityDpPerSecond = 420.dp

    /** 阻尼动画看成到位的阈值 */
    const val DampedVisibilityThreshold: Float = 0.001f

    /** 滑块底色：浅色压 6% 黑、深色提 10% 白 */
    fun color(isLightTheme: Boolean): Color =
        if (isLightTheme) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.1f)

    /** 当前主题下的滑块底色。 */
    @Composable
    @ReadOnlyComposable
    fun color(): Color = color(!LocalNestDark.current)

    /** 快甩补切：沿 [indexDirection] 至少前进一格，保留更远落点。 */
    fun flingTarget(
        startIndex: Int,
        positionalTarget: Int,
        indexDirection: Int,
        lastIndex: Int,
    ): Int {
        if (indexDirection == 0) return positionalTarget
        val flick = (startIndex + indexDirection).coerceIn(0, lastIndex)
        return if (indexDirection > 0) {
            maxOf(positionalTarget, flick)
        } else {
            minOf(positionalTarget, flick)
        }
    }
}

/** 胶囊手势快甩速度采样（搬自 NexioSchedule，未改动）。 */
internal class CapsuleFlingTracker(
    private val downUptimeMillis: Long,
    downX: Float,
) {
    private val velocityTracker = VelocityTracker()
    private var lastMoveUptime = downUptimeMillis
    private var lastTotalDx = 0f

    init {
        velocityTracker.addPosition(downUptimeMillis, Offset(downX, 0f))
    }

    /** 每帧 move 调用一次；[downX] 为按下横坐标。 */
    fun onMove(uptimeMillis: Long, position: Offset, downX: Float) {
        lastMoveUptime = uptimeMillis
        lastTotalDx = position.x - downX
        velocityTracker.addPosition(uptimeMillis, position)
    }

    /** 净位移（px）。 */
    val totalDx: Float get() = lastTotalDx

    /** 松手瞬时速度（px/s）。 */
    fun velocityX(): Float {
        val spanMs = (lastMoveUptime - downUptimeMillis).coerceAtLeast(16L)
        val avg = lastTotalDx * 1000f / spanMs
        val tracked = runCatching { velocityTracker.calculateVelocity().x }.getOrDefault(0f)
        return when {
            avg != 0f && tracked != 0f && (avg > 0f) != (tracked > 0f) -> avg
            abs(avg) > abs(tracked) -> avg
            else -> tracked
        }
    }

    /** 拖动且速度达标时返回屏幕方向（-1 左 / +1 右），否则 0。 */
    fun flingDirection(thresholdPxPerSec: Float, touchSlop: Float): Int {
        if (abs(lastTotalDx) <= touchSlop) return 0
        val vx = velocityX()
        if (abs(vx) < thresholdPxPerSec) return 0
        return if (vx > 0f) 1 else -1
    }
}

/**
 * 滑块本体：[CapsuleSlider] 的唯一绘制实现。
 *
 * 位置与宽度都在 **layout 期** 读（[capsuleBounds]），组合期不碰任何高频值；
 * 按压缩放走 `graphicsLayer`，绘制期读一次按压进度，不进组合、不触发 layout。
 * 只收缩、不压暗 —— 没有水波纹、没有遮罩层。
 *
 * @param pressProgress 按压进度 0..1
 * @param visibility 额外透明度（默认全不透明）
 */
@Composable
internal fun Modifier.capsuleSliderPill(
    selector: CapsuleSelector,
    heightPx: Int,
    pressProgress: () -> Float,
    visibility: () -> Float = { 1f },
): Modifier = this
    .capsuleBounds(selector, heightPx)
    .graphicsLayer {
        val progress = pressProgress()
        if (progress > 0f && size.width > 0f && size.height > 0f) {
            val shrink = CapsuleSlider.PressShrink.toPx() * 2f
            val targetX = ((size.width - shrink) / size.width).coerceAtLeast(0.5f)
            val targetY = ((size.height - shrink) / size.height).coerceAtLeast(0.5f)
            scaleX = 1f - (1f - targetX) * progress
            scaleY = 1f - (1f - targetY) * progress
        }
        val v = visibility()
        if (v < 1f) alpha = v
    }
    .clip(ContinuousCapsule)
    .background(CapsuleSlider.color())