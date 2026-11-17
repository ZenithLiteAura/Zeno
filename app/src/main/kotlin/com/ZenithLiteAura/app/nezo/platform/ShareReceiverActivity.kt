package com.ZenithLiteAura.app.nezo.platform

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.Toast
import com.ZenithLiteAura.app.nezo.data.ClipRepo

/**
 * 「分享到Nezo」接收器（通路 B，最可靠的后台收录方式，零权限）。
 *
 * 抖音/小红书 → 分享 → Nezo：系统以 ACTION_SEND + text/plain 把分享文案丢进来，
 * 这里收录后立刻关闭，用户只看到一条 Toast。
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { ClipRepo.init(applicationContext) }
        runCatching {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0f)
            overridePendingTransition(0, 0)
        }

        handle(extractSharedText())
    }

    private fun extractSharedText(): String? {
        val fromIntent = intent ?: return null

        val direct = runCatching { fromIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }
            .getOrNull()
            ?: runCatching { fromIntent.getStringExtra(Intent.EXTRA_TEXT) }.getOrNull()

        if (!direct.isNullOrBlank()) return direct

        // 少数 App 用 ClipData 传内容
        return runCatching {
            val clip = fromIntent.clipData
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                item.text?.toString() ?: item.coerceToText(this)?.toString()
            } else {
                null
            }
        }.getOrNull()
    }

    private fun handle(raw: String?) {
        try {
            val text = raw?.trim()
            if (text.isNullOrBlank()) {
                toast("没有收到可收录的分享内容")
                return
            }

            val result = CaptureEngine.capture(this, text, source = "share")
            val item = result?.item
            if (item != null) {
                val who = if (item.author.isNotBlank()) " · ${item.author}" else ""
                toast("已收录到Nezo：${item.platformName}$who")
            } else {
                toast("这段内容里没有识别到可收录的分享链接")
            }
        } catch (t: Throwable) {
            PlatformLog.error("分享收录异常", t)
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