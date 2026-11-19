package com.ZenithLiteAura.app.nezo.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ZenithLiteAura.app.nezo.R
import com.clipnest.core.ClipItem
import kotlin.math.roundToInt

/**
 * 后台收录成功的**悬浮提示**（不是系统通知）。
 *
 * 场景：用户不在 Nezo 前台（分享收录 / 悬浮球 / 通知按钮 / Shizuku 后台监听）时，
 * 收录成功后弹一个 2 秒自动消失的玻璃小卡片，给一个明确的视觉反馈。
 *
 * - 只有拿到悬浮窗权限才显示，否则静默（系统通知仍由 Notifier 负责）；
 * - `FLAG_NOT_TOUCHABLE`：完全不拦截触摸，也不抢焦点；
 * - 位置：屏幕底部、距底 120dp，水平居中（dock 上方）；
 * - 连续收录时**复用同一张卡片**（更新文案 + 重置计时），不会叠一堆。
 */
object CaptureToast {

    private const val DURATION_MS = 2000L
    private const val ANIM_MS = 180L
    private const val BOTTOM_MARGIN_DP = 120
    private const val MAX_WIDTH_DP = 320
    private const val MIN_WIDTH_DP = 200
    private const val SIDE_MARGIN_DP = 16

    // 玻璃卡片配色（与 dock / 悬浮球一致）
    private const val FILL_LIGHT_TOP = 0xF2FFFFFF.toInt()
    private const val FILL_LIGHT_BOTTOM = 0xE6FFFFFF.toInt()
    private const val FILL_DARK_TOP = 0xB3242424.toInt()
    private const val FILL_DARK_BOTTOM = 0xA81E1E1E.toInt()
    private const val STROKE_LIGHT = 0x66FFFFFF.toInt()
    private const val STROKE_DARK = 0x33FFFFFF.toInt()
    private const val TEXT_PRIMARY_LIGHT = 0xFF17181A.toInt()
    private const val TEXT_SECONDARY_LIGHT = 0xFF5F636C.toInt()
    private const val TEXT_PRIMARY_DARK = 0xFFF2F3F5.toInt()
    private const val TEXT_SECONDARY_DARK = 0xFFB4BAC5.toInt()
    private const val ICON_LIGHT = 0xFF3B7BF6.toInt()
    private const val ICON_DARK = 0xFF8AB4FF.toInt()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var iconView: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var dismissRunnable: Runnable? = null

    /** 展示一条「已收录」提示；内部已保证主线程、权限、异常兜底。@return 是否真的展示了（无权限/失败=false） */
    fun show(context: Context, item: ClipItem): Boolean {
        return runCatching {
            val appContext = context.applicationContext
            density = appContext.resources.displayMetrics.density
            if (!PermissionHelper.hasOverlay(appContext)) return false

            if (!isMainThread()) {
                mainHandler.post { show(appContext, item) }
                return true
            }

            if (root == null) {
                createView(appContext) ?: return false
            }

            bindContent(item)
            showWindow()
            scheduleDismiss()
            root != null
        }.onFailure { PlatformLog.warn("悬浮提示显示失败：${it.message}") }.getOrDefault(false)
    }

    /** 立刻收起（设置关闭/页面销毁时可调用） */
    fun dismiss() {
        runCatching {
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            dismissRunnable = null
            val view = root ?: return
            root = null   // 先释放引用：紧接着的新收录会重建一张，避免与淡出动画抢同一个 view
            view.animate().cancel()
            view.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(ANIM_MS)
                .withEndAction {
                    runCatching { windowManager?.removeView(view) }
                }
                .start()
        }
    }

    // ── 视图 ──────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createView(context: Context): FrameLayout? {
        windowManager = runCatching { context.getSystemService(WindowManager::class.java) }.getOrNull()
            ?: return null

        val night = isNightMode(context)
        val widthPx = computeWidth(context)

        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = if (night) {
                    intArrayOf(FILL_DARK_TOP, FILL_DARK_BOTTOM)
                } else {
                    intArrayOf(FILL_LIGHT_TOP, FILL_LIGHT_BOTTOM)
                }
                setStroke(dp(1), if (night) STROKE_DARK else STROKE_LIGHT)
            }
            elevation = dp(10).toFloat()
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(16), dp(11))
        }

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_stat_clipnest)
            colorFilter = PorterDuffColorFilter(if (night) ICON_DARK else ICON_LIGHT, PorterDuff.Mode.SRC_IN)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) })

        val textColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(context).apply {
            setTextColor(if (night) TEXT_PRIMARY_DARK else TEXT_PRIMARY_LIGHT)
            textSize = 13.5f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val body = TextView(context).apply {
            setTextColor(if (night) TEXT_SECONDARY_DARK else TEXT_SECONDARY_LIGHT)
            textSize = 12f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        textColumn.addView(title)
        textColumn.addView(body)
        row.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        container.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val layoutParams = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(BOTTOM_MARGIN_DP)
        }

        root = container
        params = layoutParams
        titleView = title
        bodyView = body
        iconView = icon
        return container
    }

    private fun bindContent(item: ClipItem) {
        val platform = item.platformName.ifBlank { "链接" }
        titleView?.text = "已收录 · $platform"
        val title = item.title.ifBlank { item.url }
        bodyView?.text = title.ifBlank { "已保存到收件箱" }
        // 内容变化时给个极轻的“刷新”动画，让用户感知到又收录了一条
        root?.animate()?.cancel()
        root?.scaleX = 0.97f
        root?.scaleY = 0.97f
        root?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(ANIM_MS)?.start()
    }

    private fun showWindow() {
        val view = root ?: return
        val layoutParams = params ?: return
        val manager = windowManager ?: return

        // 已经在显示：只刷新内容并复原（连续收录时不叠卡片、不闪）
        if (view.parent != null) {
            runCatching { manager.updateViewLayout(view, layoutParams) }
            view.alpha = 1f
            view.translationY = 0f
            return
        }

        view.alpha = 0f
        view.translationY = dp(16).toFloat()
        runCatching {
            manager.addView(view, layoutParams)
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(ANIM_MS)
                .start()
        }.onFailure {
            PlatformLog.warn("悬浮提示 addView 失败：${it.message}")
            root = null
        }
    }

    private fun scheduleDismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { dismiss() }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, DURATION_MS)
    }

    // ── 尺寸 / 主题 ───────────────────────────────────────────────────────

    private fun computeWidth(context: Context): Int {
        val screen = context.resources.displayMetrics.widthPixels
        val usable = screen - dp(SIDE_MARGIN_DP * 2)
        return usable.coerceAtMost(dp(MAX_WIDTH_DP)).coerceAtLeast(dp(MIN_WIDTH_DP).coerceAtMost(usable))
    }

    private fun isNightMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private var density = 2f

    private fun dp(value: Int): Int = (value * density).roundToInt().coerceAtLeast(1)
}