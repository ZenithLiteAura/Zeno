package com.ZenithLiteAura.app.nezo.ui.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ZenithLiteAura.app.nezo.platform.diag.LogCollector
import com.ZenithLiteAura.app.nezo.platform.diag.LogEntry
import kotlinx.coroutines.flow.StateFlow

/** UI 侧日志行 = host-shell 的 [LogEntry]（字段一一对应）。 */
typealias LogLine = LogEntry

/**
 * 日志与诊断桥：直连 `platform.diag.LogCollector`。
 * 采集、过滤、导出都在平台层，UI 只读 [entriesFlow] 并调用 start/stop/clear/export。
 */
object LogGateway {

    val available: Boolean get() = true

    val entriesFlow: StateFlow<List<LogEntry>> get() = LogCollector.entriesFlow

    fun start(context: Context, minLevel: String, channel: String) {
        LogCollector.start(context, minLevel, channel)
    }

    fun stop() {
        LogCollector.stop()
    }

    fun clear() {
        LogCollector.clear()
    }

    fun exportLines(): List<String> = LogCollector.exportLines()

    fun exportTo(context: Context, uri: Uri): Boolean = LogCollector.exportTo(context, uri)
}

/** 组合期读取实时日志。 */
@Composable
fun rememberLogLines(): List<LogLine> {
    val entries by LogGateway.entriesFlow.collectAsState()
    return entries
}