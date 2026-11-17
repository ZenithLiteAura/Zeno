package com.ZenithLiteAura.app.nezo.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 剪贴板安全读写。
 *
 * Android 10+ 只有「前台应用」或「默认输入法」能读剪贴板，所以：
 * - 应用内监听（ClipMonitor）只在 Activity 前台时读；
 * - 悬浮球/通知按钮用透明 Activity 抢一次前台再读（QuickCaptureActivity）。
 * 任何异常（空剪贴板、非文本、系统拒绝）都返回 null / false，绝不抛。
 */
object ClipboardIo {

    /** 读剪贴板纯文本；空/非文本/被拒 → null */
    fun readText(context: Context): String? = runCatching {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val item = clip.getItemAt(0)
        val text = item.text?.toString() ?: item.coerceToText(context)?.toString()
        text?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 写入剪贴板 */
    fun copy(context: Context, text: String): Boolean = runCatching {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        manager.setPrimaryClip(ClipData.newPlainText("ClipNest", text))
        true
    }.getOrDefault(false)

    /** 文本指纹：用于轮询去重，避免同一条内容被重复采集 */
    fun signature(text: String?): Int = text?.hashCode() ?: 0
}