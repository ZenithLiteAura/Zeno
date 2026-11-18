package com.ZenithLiteAura.app.nezo.platform

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.edit
import com.ZenithLiteAura.app.nezo.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 悬浮球（通路 C 的入口）—— iOS/HyperOS 辅助触控风味的玻璃球。
 *
 * 视觉：
 * - 52dp 圆形玻璃球：半透明白（浅色 `#F2FFFFFF` / 深色 `#B3242424`）+ 顶部高光渐变 + 1dp 描边，底下垫一层径向渐变阴影；
 * - 球心是矢量 Z 字（[R.drawable.ic_bubble]），浅色主题下深色着色、深色主题下白色着色，不用 emoji/文字。
 *
 * 交互：
 * - 可拖动；拖动时放大 1.05、透明度 0.9；松手贴边吸附（左/右）、位置存 SharedPreferences（`nezo_bubble`）；
 * - 单击 = 一键收录，长按 500ms = 打开主界面；点击有轻微触感反馈；
 * - 静止 5 秒淡到 0.55，触摸立刻恢复 1.0；
 * - 屏幕旋转时把位置夹回可用区域；
 * - 没有悬浮窗权限时静默不显示（返回 false），绝不让服务崩。
 */
class FloatingBubble(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {

    private companion object {
        const val SIZE_DP = 52

        /** 窗口比球大 2*PAD（留给阴影），球居中 → 贴边时球距屏幕边正好 PAD */
        const val PAD_DP = 8

        const val LONG_PRESS_MS = 500L
        const val IDLE_FADE_DELAY_MS = 5_000L
        const val ANIM_MS = 160L
        const val SNAP_ANIM_MS = 200L

        const val IDLE_ALPHA = 0.55f
        const val DRAG_SCALE = 1.05f
        const val DRAG_ALPHA = 0.9f
        const val DRAG_SLOP_DP = 4

        const val PREFS_NAME = "nezo_bubble"
        const val KEY_X = "x"
        const val KEY_Y = "y"

        // 玻璃球配色
        const val FILL_LIGHT_TOP = 0xF2FFFFFF.toInt()
        const val FILL_LIGHT_BOTTOM = 0xCCFFFFFF.toInt()
        const val FILL_DARK_TOP = 0xB3242424.toInt()
        const val FILL_DARK_BOTTOM = 0x99242424.toInt()
        const val STROKE_LIGHT = 0x66FFFFFF.toInt()
        const val STROKE_DARK = 0x33FFFFFF.toInt()
        const val SHADOW_CENTER = 0x33000000
        const val SHADOW_EDGE = 0x00000000
        const val ICON_LIGHT = 0xFF17181A.toInt()
        const val ICON_DARK = 0xFFFFFFFF.toInt()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val windowManager = runCatching {
        context.getSystemService(WindowManager::class.java)
    }.getOrNull()

    private var root: FrameLayout? = null
    private var ball: FrameLayout? = null
    private var icon: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    private var windowPx = 0
    private var dragging = false
    private var longPressFired = false
    private var longPressPending = false

    var isShowing: Boolean = false
        private set

    // ── 闲置淡出 ──────────────────────────────────────────────────────────

    private val fadeOutRunnable = Runnable {
        if (!dragging) animateBall(alpha = IDLE_ALPHA, scale = 1f, duration = 320L)
    }

    private val longPressRunnable = Runnable {
        if (!longPressPending) return@Runnable
        longPressPending = false
        longPressFired = true
        runCatching { ball?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        runCatching { onLongPress() }
    }

    /** 旋转/深色切换时重新夹位置、刷新配色 */
    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            mainHandler.post {
                runCatching { applyColors() }
                runCatching { clampToScreen(persist = true) }
            }
        }

        override fun onLowMemory() = Unit
    }

    // ── 显示 / 隐藏 ───────────────────────────────────────────────────────

    fun show(): Boolean {
        if (isShowing) return true
        if (windowManager == null) {
            PlatformLog.warn("悬浮球无法显示：WindowManager 不可用")
            return false
        }
        if (!PermissionHelper.hasOverlay(context)) {
            PlatformLog.warn("悬浮球无法显示：未授予悬浮窗权限")
            return false
        }

        return runCatching {
            val sizePx = dp(SIZE_DP)
            windowPx = sizePx + dp(PAD_DP) * 2
            val view = buildView(sizePx)

            val layoutParams = WindowManager.LayoutParams(
                windowPx,
                windowPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = restoredX()
                y = restoredY()
            }

            windowManager?.addView(view, layoutParams)
            root = view
            params = layoutParams
            isShowing = true

            // 球的初始状态：正常不透明，5 秒后开始淡
            ball?.alpha = 1f
            scheduleIdleFade()
            runCatching { context.applicationContext.registerComponentCallbacks(componentCallbacks) }

            PlatformLog.info("悬浮球已显示（x=${layoutParams.x}, y=${layoutParams.y}, 球=${SIZE_DP}dp）")
            true
        }.onFailure {
            PlatformLog.warn("悬浮球添加失败：${it.message}")
        }.getOrDefault(false)
    }

    fun hide() {
        if (!isShowing) return
        mainHandler.removeCallbacks(fadeOutRunnable)
        mainHandler.removeCallbacks(longPressRunnable)
        runCatching { context.applicationContext.unregisterComponentCallbacks(componentCallbacks) }
        runCatching { persistPosition() }
        runCatching {
            val view = root
            if (view != null) windowManager?.removeView(view)
        }.onFailure { PlatformLog.warn("悬浮球移除失败：${it.message}") }

        root = null
        ball = null
        icon = null
        params = null
        dragging = false
        isShowing = false
    }

    // ── 视图 ──────────────────────────────────────────────────────────────

    private fun buildView(ballPx: Int): FrameLayout {
        val container = FrameLayout(context)

        // ① 径向渐变阴影（比球大一圈，模拟柔和投影；overlay 窗口里 elevation 不可靠）
        val shadow = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                setGradientRadius(windowPx / 2.0f)
                colors = intArrayOf(SHADOW_CENTER, SHADOW_EDGE)
            }
        }
        container.addView(shadow, FrameLayout.LayoutParams(windowPx, windowPx))

        // ② 玻璃球本体
        val ballView = FrameLayout(context).apply {
            background = buildGlassDrawable()
            elevation = dp(6).toFloat()
        }
        val ballParams = FrameLayout.LayoutParams(ballPx, ballPx).apply {
            gravity = Gravity.CENTER
        }
        container.addView(ballView, ballParams)

        // ③ 矢量图标（Z 字），按主题着色
        val iconView = ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val iconPx = dp(24)
        val iconParams = FrameLayout.LayoutParams(iconPx, iconPx).apply { gravity = Gravity.CENTER }
        ballView.addView(iconView, iconParams)

        ball = ballView
        icon = iconView
        applyColors()

        attachTouch(container)
        return container
    }

    /** 半透明白 + 顶部高光 + 1dp 描边的玻璃球 */
    private fun buildGlassDrawable(): GradientDrawable {
        val night = isNightMode()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = if (night) {
                intArrayOf(FILL_DARK_TOP, FILL_DARK_BOTTOM)
            } else {
                intArrayOf(FILL_LIGHT_TOP, FILL_LIGHT_BOTTOM)
            }
            setStroke(dp(1), if (night) STROKE_DARK else STROKE_LIGHT)
        }
    }

    /** 主题切换时刷新球底色与图标颜色 */
    private fun applyColors() {
        ball?.background = buildGlassDrawable()
        val tint = if (isNightMode()) ICON_DARK else ICON_LIGHT
        icon?.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
    }

    private fun isNightMode(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // ── 触摸 / 拖动 / 吸附 ────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(view: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var downTime = 0L

        view.setOnTouchListener { _, event ->
            val layoutParams = params
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (layoutParams == null) return@setOnTouchListener false

                    // 触摸立刻恢复可见，并取消闲置淡出
                    mainHandler.removeCallbacks(fadeOutRunnable)
                    animateBall(alpha = 1f, scale = 1f, duration = ANIM_MS)

                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    dragging = false
                    longPressFired = false
                    downTime = System.currentTimeMillis()

                    longPressPending = true
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (layoutParams == null) return@setOnTouchListener true
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()

                    if (!dragging && (abs(dx) > dp(DRAG_SLOP_DP) || abs(dy) > dp(DRAG_SLOP_DP))) {
                        dragging = true
                        longPressPending = false
                        mainHandler.removeCallbacks(longPressRunnable)
                        animateBall(alpha = DRAG_ALPHA, scale = DRAG_SCALE, duration = ANIM_MS)
                    }

                    if (dragging) {
                        layoutParams.x = (startX + dx).coerceIn(0, maxX())
                        layoutParams.y = (startY + dy).coerceIn(0, maxY())
                        runCatching { windowManager?.updateViewLayout(view, layoutParams) }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    longPressPending = false

                    if (dragging) {
                        dragging = false
                        animateBall(alpha = 1f, scale = 1f, duration = ANIM_MS)
                        snapToNearestEdge()
                    } else {
                        animateBall(alpha = 1f, scale = 1f, duration = ANIM_MS)
                        if (!longPressFired) {
                            val held = System.currentTimeMillis() - downTime
                            if (held >= LONG_PRESS_MS) {
                                runCatching { onLongPress() }
                            } else {
                                runCatching { ball?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                                runCatching { onTap() }
                            }
                        }
                    }

                    if (event.actionMasked == MotionEvent.ACTION_UP) scheduleIdleFade()
                }
            }
            true
        }
    }

    /** 松手贴到最近的左边或右边，并记住位置 */
    private fun snapToNearestEdge() {
        val layoutParams = params ?: return
        val targetX = if (layoutParams.x + windowPx / 2 < screenWidth() / 2) 0 else maxX()
        val targetY = layoutParams.y.coerceIn(0, maxY())
        val fromX = layoutParams.x

        if (fromX == targetX && layoutParams.y == targetY) {
            persistPosition()
            return
        }

        val animator = ValueAnimator.ofInt(fromX, targetX).apply {
            duration = SNAP_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                layoutParams.x = animation.animatedValue as Int
                layoutParams.y = targetY
                runCatching { windowManager?.updateViewLayout(root, layoutParams) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistPosition()
                }
            })
        }
        runCatching { animator.start() }
        persistPosition()
    }

    /** 屏幕旋转后把球夹回可用区域（x/y 都以窗口左上角为基准） */
    private fun clampToScreen(persist: Boolean) {
        val layoutParams = params ?: return
        val newX = layoutParams.x.coerceIn(0, maxX())
        val newY = layoutParams.y.coerceIn(0, maxY())
        if (newX != layoutParams.x || newY != layoutParams.y) {
            layoutParams.x = newX
            layoutParams.y = newY
            runCatching { windowManager?.updateViewLayout(root, layoutParams) }
            PlatformLog.info("悬浮球已按新屏幕尺寸复位：x=$newX, y=$newY")
        }
        if (persist) persistPosition()
    }

    // ── 动画 / 位置记忆 ───────────────────────────────────────────────────

    private fun animateBall(alpha: Float, scale: Float, duration: Long) {
        val view = ball ?: return
        view.animate().cancel()
        view.animate()
            .alpha(alpha)
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun scheduleIdleFade() {
        mainHandler.removeCallbacks(fadeOutRunnable)
        if (isShowing) mainHandler.postDelayed(fadeOutRunnable, IDLE_FADE_DELAY_MS)
    }

    private fun persistPosition() {
        val layoutParams = params ?: return
        runCatching {
            prefs.edit {
                putInt(KEY_X, layoutParams.x)
                putInt(KEY_Y, layoutParams.y)
            }
        }
    }

    private fun restoredX(): Int {
        val default = maxX()   // 默认贴右边
        val saved = prefs.getInt(KEY_X, -1)
        return if (saved >= 0) saved.coerceIn(0, maxX()) else default
    }

    private fun restoredY(): Int {
        val saved = prefs.getInt(KEY_Y, -1)
        return if (saved >= 0) saved.coerceIn(0, maxY()) else ((screenHeight() - windowPx) / 2)
    }

    // ── 尺寸工具 ──────────────────────────────────────────────────────────

    private fun maxX(): Int = max(0, screenWidth() - windowPx)

    private fun maxY(): Int = max(0, screenHeight() - windowPx)

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels
}