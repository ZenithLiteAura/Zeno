package com.ZenithLiteAura.app.nezo.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/**
 * 悬浮球（通路 C 的入口）：52dp 圆形玻璃球，可拖动。
 * 单击 → 拉起 QuickCaptureActivity 抢焦点读一次剪贴板；长按 → 打开主界面。
 * 没有悬浮窗权限时静默降级（返回 false），绝不让服务崩。
 */
class FloatingBubble(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {

    private val windowManager = runCatching {
        context.getSystemService(WindowManager::class.java)
    }.getOrNull()

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    var isShowing: Boolean = false
        private set

    private companion object {
        /** 主色渐变（与 CONTRACT 的 --accent #3B7BF6 同族） */
        const val COLOR_START = 0xFF4A86FF.toInt()
        const val COLOR_END = 0xFF2F66E0.toInt()
        const val COLOR_STROKE = 0x66FFFFFF.toInt()
        const val SIZE_DP = 52
    }

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
            val view = buildView(sizePx)
            val layoutParams = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screenWidth() - sizePx - dp(12)).coerceAtLeast(dp(12))
                y = (screenHeight() / 2).coerceAtLeast(dp(12))
            }

            windowManager?.addView(view, layoutParams)
            root = view
            params = layoutParams
            isShowing = true
            PlatformLog.info("悬浮球已显示")
            true
        }.onFailure {
            PlatformLog.warn("悬浮球添加失败：${it.message}")
        }.getOrDefault(false)
    }

    fun hide() {
        if (!isShowing) return
        runCatching {
            val view = root
            if (view != null) windowManager?.removeView(view)
        }.onFailure { PlatformLog.warn("悬浮球移除失败：${it.message}") }
        root = null
        params = null
        isShowing = false
    }

    // ── 视图 ──────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun buildView(sizePx: Int): FrameLayout {
        val container = FrameLayout(context)

        val circle = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(COLOR_START, COLOR_END)
                setStroke(dp(1), COLOR_STROKE)
            }
            elevation = dp(10).toFloat()
        }
        container.addView(circle, FrameLayout.LayoutParams(sizePx, sizePx))

        val glyph = TextView(context).apply {
            text = "📎"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        container.addView(glyph, FrameLayout.LayoutParams(sizePx, sizePx))

        attachTouch(container)
        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(view: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var downTime = 0L

        view.setOnTouchListener { _, event ->
            val layoutParams = params
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layoutParams?.x ?: 0
                    startY = layoutParams?.y ?: 0
                    dragging = false
                    downTime = System.currentTimeMillis()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (layoutParams == null) return@setOnTouchListener true
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) dragging = true
                    if (dragging) {
                        val size = dp(SIZE_DP)
                        layoutParams.x = (startX + dx).coerceIn(0, max(0, screenWidth() - size))
                        layoutParams.y = (startY + dy).coerceIn(0, max(0, screenHeight() - size))
                        runCatching { windowManager?.updateViewLayout(view, layoutParams) }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        val held = System.currentTimeMillis() - downTime
                        if (held >= 450L) runCatching { onLongPress() }
                        else runCatching { onTap() }
                    }
                }
            }
            true
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels
}