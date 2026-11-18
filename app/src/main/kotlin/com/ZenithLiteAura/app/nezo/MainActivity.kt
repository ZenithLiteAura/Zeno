package com.ZenithLiteAura.app.nezo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import com.ZenithLiteAura.app.nezo.platform.download.DownloadControllerImpl
import com.ZenithLiteAura.app.nezo.platform.download.DownloadQueue
import com.ZenithLiteAura.app.nezo.platform.PlatformBridge
import com.ZenithLiteAura.app.nezo.ui.ClipHomeScreen
import com.ZenithLiteAura.app.nezo.ui.UiActions
import com.ZenithLiteAura.app.nezo.ui.theme.ClipNestTheme
import com.clipnest.core.AppSettings

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClipRepo.init(applicationContext)
        // 下载控制器：解析抖音作品 + 排队下载（图片 ZIP / 视频 ZIP）
        val downloads = DownloadControllerImpl(applicationContext)
        enableEdgeToEdge()

        val actions = UiActions(
            setStar = { id, starred -> ClipRepo.setStar(id, starred) },
            delete = { id -> ClipRepo.delete(id) },
            clearAll = { ClipRepo.clearAll() },
            captureText = { text -> ClipRepo.captureText(text, source = "manual") },
            captureClipboard = {
                val result = com.ZenithLiteAura.app.nezo.platform.CaptureEngine.captureClipboard(this, source = "manual")
                toast(if (result == null) "剪贴板里没有可收录的内容" else "已收录 · ${result.item?.author ?: ""}")
            },
            preview = { text -> ClipRepo.preview(text) },
            updateSettings = { mutate: (AppSettings) -> AppSettings ->
                ClipRepo.updateSettings(mutate)
                PlatformBridge.syncServices(this)
            },
            setRuleEnabled = { id, enabled -> ClipRepo.setRuleEnabled(id, enabled) },
            requestBubblePermission = { PlatformBridge.openOverlaySettings(this) },
            requestNotificationPermission = {
                PlatformBridge.requestNotification(this) { granted ->
                    ClipRepo.updateSettings { it.copy(notify = granted) }
                }
            },
            openAutoStartSettings = { PlatformBridge.openAutoStartSettings(this) },
            requestBatteryWhitelist = { PlatformBridge.requestIgnoreBatteryOptimization(this) },
            requestOverlayAndStart = {
                PlatformBridge.requestOverlayAndStart(this) { granted ->
                    ClipRepo.updateSettings { it.copy(bubble = granted) }
                    PlatformBridge.syncServices(this)
                }
            },
            exportText = { format ->
                val content = ClipRepo.export(format)
                val mime = when (format.lowercase()) {
                    "md" -> "text/markdown"
                    "csv" -> "text/csv"
                    "json" -> "application/json"
                    else -> "text/plain"
                }
                PlatformBridge.exportText(this, content, mime)
            },
            openUrl = { url -> openInBrowser(url) },
            copyText = { text ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("Nezo", text))
                toast("已复制")
            },
            showToast = { text -> toast(text) },
        )

        setContent {
            val settings by ClipRepo.settings.collectAsState()
            val items by ClipRepo.items.collectAsState()
            val rules by ClipRepo.rules.collectAsState()
            val lastCaptured by ClipRepo.lastCaptured.collectAsState()

            ClipNestTheme(
                darkTheme = when (settings.theme) {
                    "dark" -> true
                    "light" -> false
                    else -> isSystemInDarkTheme()
                },
            ) {
                ClipHomeScreen(
                    items = items,
                    settings = settings,
                    rules = rules,
                    lastCaptured = remember(lastCaptured) { lastCaptured },
                    actions = actions,
                    downloads = downloads,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ClipRepo.init(applicationContext)
        PlatformBridge.syncServices(this)
        PlatformBridge.startMonitor(this)
        // 后台排队等待的下载任务（自动下载在后台只入队），回到前台时补跑
        runCatching { DownloadQueue.kickPending(applicationContext) }
    }

    override fun onStop() {
        PlatformBridge.stopMonitor()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 拿到焦点的这一刻才允许读剪贴板：把「在抖音复制、再切回Nezo」的内容补抓进来
        if (hasFocus) PlatformBridge.onWindowFocused(this)
    }

    private fun openInBrowser(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("打不开这个链接") }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
