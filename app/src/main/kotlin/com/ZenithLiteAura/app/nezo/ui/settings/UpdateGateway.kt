package com.ZenithLiteAura.app.nezo.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ZenithLiteAura.app.nezo.platform.PlatformBridge
import com.ZenithLiteAura.app.nezo.platform.update.UpdateState

/**
 * 检查更新（直连 host-shell 的 `PlatformBridge`）。
 *
 * 只做三件事：把 `updateStateFlow` 包成组合期状态、触发手动检查、打开 Release 页；
 * 网络请求/版本比较都在平台层，UI 不解析 GitHub 响应。
 */
object UpdateGateway {

    fun check(context: Context, manual: Boolean = true) {
        PlatformBridge.checkForUpdates(context, manual)
    }

    fun openRelease(context: Context) {
        PlatformBridge.openReleasePage(context)
    }
}

/** 组合期读取更新状态（宿主未开始检查时为全默认值）。 */
@Composable
fun rememberUpdateInfo(): UpdateState {
    val state by PlatformBridge.updateStateFlow.collectAsState()
    return state
}

/** 状态文案：正在检查… / 发现 vX.Y.Z / 已是最新 / 检查失败。 */
fun updateStatusText(state: UpdateState): String = when {
    state.checking -> "正在检查…"
    state.hasUpdate && state.latestVersion.isNotBlank() -> "发现 v${state.latestVersion}"
    state.error.isNotBlank() -> "检查失败"
    state.latestVersion.isNotBlank() -> "已是最新"
    else -> "未检查"
}