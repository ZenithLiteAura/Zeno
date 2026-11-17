package com.ZenithLiteAura.app.nezo.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 更新状态（UI 侧镜像，字段与 host-shell 的 `UpdateState` 一一对应）。 */
data class UpdateInfo(
    val checking: Boolean = false,
    val latestVersion: String = "",
    val hasUpdate: Boolean = false,
    val releaseUrl: String = "",
    val apkUrl: String = "",
    val error: String = "",
)

/**
 * 检查更新能力的前置桥。
 *
 * host-shell 的 `PlatformBridge.updateStateFlow / checkForUpdates / openReleasePage` 还没合并，
 * 这里先用反射调用（接口名与 Lead 给的一致）；合并后把本文件的反射换成直接调用即可，
 * 调用方（设置 hub / 版本信息页）不需要改。
 *
 * TODO(接口): host-shell 提交后删掉反射，改为
 *   `PlatformBridge.updateStateFlow.collectAsState()` / `PlatformBridge.checkForUpdates(ctx, true)`。
 */
object UpdateGateway {

    private const val BRIDGE_CLASS = "com.ZenithLiteAura.app.nezo.platform.PlatformBridge"
    private val fallbackFlow = MutableStateFlow<Any?>(null)

    private val bridge: Any? by lazy {
        runCatching { Class.forName(BRIDGE_CLASS).getField("INSTANCE").get(null) }.getOrNull()
    }

    /** 宿主是否已经提供更新能力（反射探测）。 */
    val available: Boolean get() = bridge?.javaClass?.methods?.any { it.name == "checkForUpdates" } == true

    @Suppress("UNCHECKED_CAST")
    val updateFlow: StateFlow<Any?> by lazy {
        runCatching {
            bridge?.javaClass?.getMethod("getUpdateStateFlow")?.invoke(bridge) as? StateFlow<Any?>
        }.getOrNull() ?: fallbackFlow
    }

    /** 触发检查更新；返回是否成功把调用交给宿主。 */
    fun check(context: Context, manual: Boolean = true): Boolean = runCatching {
        val method = bridge?.javaClass?.getMethod(
            "checkForUpdates",
            Context::class.java,
            java.lang.Boolean.TYPE,
        ) ?: return@runCatching false
        method.invoke(bridge, context, manual)
        true
    }.getOrDefault(false)

    /** 打开 Release 页面；宿主没有该能力时用 [fallbackOpen] 打开 releaseUrl。 */
    fun openRelease(context: Context, releaseUrl: String, fallbackOpen: (String) -> Unit): Boolean {
        val ok = runCatching {
            val method = bridge?.javaClass?.getMethod("openReleasePage", Context::class.java)
                ?: return@runCatching false
            method.invoke(bridge, context)
            true
        }.getOrDefault(false)
        if (!ok && releaseUrl.isNotBlank()) fallbackOpen(releaseUrl)
        return ok
    }

    fun map(raw: Any?): UpdateInfo {
        if (raw == null) return UpdateInfo()
        fun boolOf(name: String): Boolean =
            runCatching { raw.javaClass.getMethod("get$name").invoke(raw) as? Boolean }.getOrNull() ?: false

        fun stringOf(name: String): String =
            runCatching { raw.javaClass.getMethod("get$name").invoke(raw) as? String }.getOrNull() ?: ""

        return UpdateInfo(
            checking = boolOf("Checking"),
            latestVersion = stringOf("LatestVersion"),
            hasUpdate = boolOf("HasUpdate"),
            releaseUrl = stringOf("ReleaseUrl"),
            apkUrl = stringOf("ApkUrl"),
            error = stringOf("Error"),
        )
    }
}

/** 组合期读取更新状态（宿主未提供时返回全默认值）。 */
@Composable
fun rememberUpdateInfo(): UpdateInfo {
    val raw by UpdateGateway.updateFlow.collectAsState()
    return remember(raw) { UpdateGateway.map(raw) }
}

/** 一行状态文案：正在检查… / 发现 vX.Y.Z / 已是最新 / 检查失败。 */
fun updateStatusText(info: UpdateInfo): String = when {
    info.checking -> "正在检查…"
    info.hasUpdate && info.latestVersion.isNotBlank() -> "发现 v${info.latestVersion}"
    info.error.isNotBlank() -> "检查失败"
    info.latestVersion.isNotBlank() -> "已是最新"
    else -> "未检查"
}