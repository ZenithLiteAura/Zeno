package com.ZenithLiteAura.app.nezo.ui.dock

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 连续曲率（G2）胶囊 —— 搬自 NexioSchedule 使用的 `com.haooz.softglass.shape.ContinuousCapsule`。
 *
 * softglass 是外部依赖，这里按它依赖的 kyant capsule 数学（`G2Continuity` 的
 * `createHorizontalCapsulePathSegments`）在本地重画了一份，**不引 softglass**：
 *
 * - 端头不是半圆：直边会**多延伸** 0.3965r（`0.5286651 × 0.75`，softglass 的 Capsule profile），
 *   曲率从直边处的 0 连续增长到 45° 处的 1/r，所以胶囊两端看起来比普通 `RoundedCornerShape(50%)`
 *   更「满」、与直边接得更顺 —— 这正是参考底栏的形状特征。
 * - 每个端头 = 1 段 90° 圆弧（三次贝塞尔拟合，避免 Android `arcTo` 折线近似出锯齿）
 *   + 上下各 1 段 G2 贝塞尔。
 *
 * 与参考源码的唯一差别：它的 `moveTo(0, radius)` 与自身圆弧起点（135°）不重合，
 * 会在最左端留下约 0.098r 的外凸；这里改从圆弧起点（下 45° 点）起步，路径连续、
 * 端头严格光滑（半径与全部控制点参数逐字照搬）。
 */
internal class ContinuousCapsuleShape : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Generic(Path())
        val path = Path()
        when {
            w > h -> horizontalCapsule(path, w.toDouble(), h.toDouble())

            // 竖向胶囊（底栏用不到）：退回标准 stadium，曲率差异肉眼不可见
            w < h -> return Outline.Rounded(
                RoundRect(
                    rect = Rect(0f, 0f, w, h),
                    radiusX = w * 0.5f,
                    radiusY = w * 0.5f,
                )
            )

            // 正方形 = 正圆（参考实现同样退化成 Outline.Rounded 圆）
            else -> path.addOval(Rect(0f, 0f, w, h))
        }
        return Outline.Generic(path)
    }

    override fun toString(): String = "ContinuousCapsule"
}

/** 单例：形状相等性走同一实例，`background/clip/border` 的 outline 缓存才命中。 */
internal val ContinuousCapsule: Shape = ContinuousCapsuleShape()

@Suppress("FunctionName")
internal fun ContinuousCapsule(): Shape = ContinuousCapsule

// ── G2 参数（softglass / kyant capsule 的 Capsule profile） ────────────────

/** 端头向直边延伸的比例：0.5286651 × 0.75 */
private const val CAPSULE_EXTENDED_FRACTION = 0.5286651 * 0.75

/** 端头里纯圆弧占 90° 角的比例；Capsule profile 为 0（整段 90° 都走圆弧） */
private const val CAPSULE_ARC_FRACTION = 0.0

/** Capsule profile 的贝塞尔曲率缩放 */
private const val CAPSULE_BEZIER_CURVATURE_SCALE = 1.0

/** 圆角矩形 profile 的贝塞尔曲率缩放：胶囊短到接近圆时按比例回落到它 */
private const val ROUND_RECT_BEZIER_CURVATURE_SCALE = 1.0732051

private val SQRT_HALF = 1.0 / sqrt(2.0)

/**
 * 横向胶囊（width > height）的完整轮廓。
 *
 * 顺序：左下 45° 点 →(圆弧，穿过最左点)→ 左上 45° 点 →(G2 贝塞尔)→ 顶边延伸点 →
 * 顶边直段 → 右上延伸点 →(G2 贝塞尔)→ 右上 45° 点 →(圆弧，穿过最右点)→ 右下 45° 点 →
 * 底边延伸点 → 底边直段 → 左下 45° 点，闭合。
 */
private fun horizontalCapsule(path: Path, width: Double, height: Double) {
    val radius = height * 0.5
    val cy = radius
    val cx = radius // 左端圆心 x
    val rx = width - radius // 右端圆心 x
    // 胶囊越短（越接近圆），G2 延伸量越小；短到 width == height 时完全退化成圆（ratio = 0）
    val ratio = ((width * 0.5 / radius - 1.0) / CAPSULE_EXTENDED_FRACTION).coerceIn(0.0, 1.0)
    val extend = radius * CAPSULE_EXTENDED_FRACTION * ratio
    val bezier = g2Bezier(
        extendedFraction = CAPSULE_EXTENDED_FRACTION * ratio,
        arcFraction = CAPSULE_ARC_FRACTION,
        bezierCurvatureScale = lerp(CAPSULE_BEZIER_CURVATURE_SCALE, ROUND_RECT_BEZIER_CURVATURE_SCALE, ratio),
        arcCurvatureScale = 1.0,
    )

    val arcRadians = PI * 0.5 * CAPSULE_ARC_FRACTION
    val bezierRadians = (PI * 0.5 - arcRadians) * 0.5
    val sweepRadians = (bezierRadians + arcRadians) * 2.0

    // 归一化贝塞尔 → px：p0 是延伸端点（贴在直边延长线上），p3 是 45° 点
    val p0 = Pt2(bezier.p0.x * radius, bezier.p0.y * radius)
    val p1 = Pt2(bezier.p1.x * radius, bezier.p1.y * radius)
    val p2 = Pt2(bezier.p2.x * radius, bezier.p2.y * radius)
    val p3 = Pt2(bezier.p3.x * radius, bezier.p3.y * radius)

    // 左端头：45° 点与直边相切点（把参考实现的 (radius - p.x, p.y) 映射写全）
    val upperLeft = Pt2(cx - p3.x, p3.y)
    val lowerLeft = Pt2(cx - p3.x, height - p3.y)
    val topTangent = Pt2(cx + extend, 0.0)
    val bottomTangent = Pt2(cx + extend, height)
    // 右端头 = 左端头关于 x = width/2 的镜像
    val upperRight = Pt2(rx + p3.x, p3.y)
    val topTangentRight = Pt2(rx - extend, 0.0)
    val bottomTangentRight = Pt2(rx - extend, height)

    path.moveTo(lowerLeft.x.toFloat(), lowerLeft.y.toFloat())
    // 左端圆弧：135° → 225°（y 轴向下，弧线穿过最左点）
    path.arcToCubic(cx, cy, radius, PI * 0.5 + bezierRadians, sweepRadians)
    // 左上 G2 段：45° 点 → 顶边延伸点
    path.cubicTo(
        (cx - p2.x).toFloat(), p2.y.toFloat(),
        (cx - p1.x).toFloat(), p1.y.toFloat(),
        topTangent.x.toFloat(), topTangent.y.toFloat(),
    )
    path.lineTo(topTangentRight.x.toFloat(), topTangentRight.y.toFloat())
    // 右上 G2 段：顶边延伸点 → 45° 点
    path.cubicTo(
        (rx + p1.x).toFloat(), p1.y.toFloat(),
        (rx + p2.x).toFloat(), p2.y.toFloat(),
        upperRight.x.toFloat(), upperRight.y.toFloat(),
    )
    // 右端圆弧：-45° → +45°
    path.arcToCubic(rx, cy, radius, -(PI * 0.5 - bezierRadians), sweepRadians)
    // 右下 G2 段：45° 点 → 底边延伸点
    path.cubicTo(
        (rx + p2.x).toFloat(), (height - p2.y).toFloat(),
        (rx + p1.x).toFloat(), (height - p1.y).toFloat(),
        bottomTangentRight.x.toFloat(), bottomTangentRight.y.toFloat(),
    )
    path.lineTo(bottomTangent.x.toFloat(), bottomTangent.y.toFloat())
    // 左下 G2 段：底边延伸点 → 45° 点
    path.cubicTo(
        (cx - p1.x).toFloat(), (height - p1.y).toFloat(),
        (cx - p2.x).toFloat(), (height - p2.y).toFloat(),
        lowerLeft.x.toFloat(), lowerLeft.y.toFloat(),
    )
    path.close()
}

/**
 * 圆弧的三次贝塞尔拟合（每段 ≤ 90°）。
 *
 * 不用 `Path.arcTo`：Android 上它按折线近似圆弧，56dp 的小圆角在低密度屏上会有可见棱。
 */
private fun Path.arcToCubic(
    centerX: Double,
    centerY: Double,
    radius: Double,
    startAngle: Double,
    sweepAngle: Double,
) {
    if (sweepAngle == 0.0) return
    val segmentCount = ceil(abs(sweepAngle) / (PI * 0.5)).toInt().coerceAtLeast(1)
    val segmentSweep = sweepAngle / segmentCount
    val k = 4.0 / 3.0 * tan(segmentSweep / 4.0)
    var angle = startAngle
    repeat(segmentCount) {
        val angle1 = angle + segmentSweep
        val p1x = centerX + radius * (cos(angle) - k * sin(angle))
        val p1y = centerY + radius * (sin(angle) + k * cos(angle))
        val p2x = centerX + radius * (cos(angle1) + k * sin(angle1))
        val p2y = centerY + radius * (sin(angle1) - k * cos(angle1))
        val p3x = centerX + radius * cos(angle1)
        val p3y = centerY + radius * sin(angle1)
        cubicTo(
            p1x.toFloat(), p1y.toFloat(),
            p2x.toFloat(), p2y.toFloat(),
            p3x.toFloat(), p3y.toFloat(),
        )
        angle = angle1
    }
}

// ── G2 基础贝塞尔（kyant capsule 数学，逐字搬运） ──────────────────────

private data class Pt2(val x: Double, val y: Double) {
    operator fun plus(other: Pt2) = Pt2(x + other.x, y + other.y)
    operator fun minus(other: Pt2) = Pt2(x - other.x, y - other.y)
    operator fun times(operand: Double) = Pt2(x * operand, y * operand)
}

private class CubicBez(val p0: Pt2, val p1: Pt2, val p2: Pt2, val p3: Pt2)

private fun lerp(start: Double, stop: Double, fraction: Double): Double =
    start + (stop - start) * fraction

/**
 * 半个端头的归一化贝塞尔：从「延伸端点」起、到「45° 点」止，起点曲率为 0（与直边相接）。
 */
private fun g2Bezier(
    extendedFraction: Double,
    arcFraction: Double,
    bezierCurvatureScale: Double,
    arcCurvatureScale: Double,
): CubicBez {
    val arcRadians = PI * 0.5 * arcFraction
    val bezierRadians = (PI * 0.5 - arcRadians) * 0.5
    val sinB = sin(bezierRadians)
    val cosB = cos(bezierRadians)

    return if (bezierCurvatureScale == 1.0 && arcCurvatureScale == 1.0) {
        val halfTan = sinB / (1.0 + cosB)
        CubicBez(
            p0 = Pt2(-extendedFraction, 0.0),
            p1 = Pt2((1.0 - 1.5 / (1.0 + cosB)) * halfTan, 0.0),
            p2 = Pt2(halfTan, 0.0),
            p3 = Pt2(sinB, 1.0 - cosB),
        )
    } else {
        val radiusScale = 1.0 / arcCurvatureScale
        val arcCenter = Pt2(0.0, 1.0) + Pt2(SQRT_HALF, -SQRT_HALF) * (1.0 - radiusScale)
        val arcStartPoint = arcCenter + Pt2(sinB, -cosB) * radiusScale
        g2BezierWithZeroStartCurvature(
            start = Pt2(-extendedFraction, 0.0),
            end = arcStartPoint,
            startTangent = Pt2(1.0, 0.0),
            endTangent = Pt2(cosB, sinB),
            endCurvature = bezierCurvatureScale,
        )
    }
}

/** 起点曲率为 0、终点曲率给定的一次求解（kyant capsule 的 `generateG2ContinuousBezier…`）。 */
private fun g2BezierWithZeroStartCurvature(
    start: Pt2,
    end: Pt2,
    startTangent: Pt2,
    endTangent: Pt2,
    endCurvature: Double,
): CubicBez {
    val a2 = 1.5 * endCurvature
    val b = startTangent.x * endTangent.y - startTangent.y * endTangent.x
    val dx = end.x - start.x
    val dy = end.y - start.y
    val c1 = -dy * startTangent.x + dx * startTangent.y
    val c2 = dy * endTangent.x - dx * endTangent.y

    val lambda0 = -c2 / b - a2 * c1 * c1 / b / b / b
    val lambda3 = -c1 / b

    val p0 = start
    val p1 = start + Pt2(
        (lambda0 * startTangent.x).coerceAtLeast(0.0),
        (lambda0 * startTangent.y).coerceAtLeast(0.0),
    )
    val p2 = end - Pt2(
        (lambda3 * endTangent.x).coerceAtLeast(0.0),
        (lambda3 * endTangent.y).coerceAtLeast(0.0),
    )
    return CubicBez(p0, p1, p2, end)
}