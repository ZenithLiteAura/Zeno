package com.ZenithLiteAura.app.nezo.platform

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.Toast
import com.ZenithLiteAura.app.nezo.data.ClipRepo

/**
 * 透明的一键收录页（通路 C）：从悬浮球或通知按钮拉起。
 *
 * Android 10+ 只有前台应用能读剪贴板，所以这里「抢一次前台焦点 → 读剪贴板 → 收录 → 立刻关闭」，
 * 全程约 200ms 且不可见（透明 + 无动画 + noHistory）。
 */
class QuickCaptureActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { ClipRepo.init(applicationContext) }

        // 完全透明、不加暗、无切换动画
        runCatching {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0f)
            overridePendingTransition(0, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true

        // 等这一帧真正显示出来再读，避免个别 ROM 认为应用还在后台
        val decor = window?.decorView
        if (decor != null) decor.post { captureOnce() } else captureOnce()
    }

    private fun captureOnce() {
        try {
            val text = ClipboardIo.readText(this)
            if (text.isNullOrBlank()) {
                toast("剪贴板里没有可收录的文字")
                return
            }

            val result = CaptureEngine.capture(this, text, source = "bubble")
            val item = result?.item
            if (item != null) {
                val who = if (item.author.isNotBlank()) " · ${item.author}" else ""
                toast("已收录到Nezo：${item.platformName}$who")
            } else {
                toast("剪贴板内容里没有识别到可收录的分享链接")
            }
        } catch (t: Throwable) {
            PlatformLog.error("一键收录异常", t)
            toast("收录失败，请重试")
        } finally {
            finishQuietly()
        }
    }

    private fun finishQuietly() {
        runCatching { overridePendingTransition(0, 0) }
        runCatching { finish() }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
}