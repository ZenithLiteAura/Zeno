package com.ZenithLiteAura.app.nezo.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 仅 debug 包存在的测试用接收器：`adb shell am broadcast -a com.clipnest.DEBUG_SET_CLIP --es text "..."`。
 * 用来在真机上模拟「在抖音复制了一段文案」，验证前台补抓是否生效。
 */
class DebugClipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val raw = intent?.getStringExtra("text")
        val text = raw ?: intent?.getStringExtra("b64")?.let {
            runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        } ?: return
        ClipboardIo.copy(context, text)
        PlatformLog.info("DEBUG 已写入剪贴板：${text.take(40)}")
    }
}
