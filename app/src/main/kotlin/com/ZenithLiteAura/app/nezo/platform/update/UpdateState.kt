package com.ZenithLiteAura.app.nezo.platform.update

/**
 * 检查更新结果（与 UI 契约一致，字段名/默认值不要改）。
 *
 * - [checking] 正在请求 GitHub；
 * - [latestVersion] 最新 Release 版本（已去掉 `v` 前缀，如 `1.2.1`）；
 * - [hasUpdate] 只有比当前 [BuildConfig.VERSION_NAME] 更高才为 true；
 * - [releaseUrl] Release 页面（GitHub `html_url`）；
 * - [apkUrl] Release 里第一个 `.apk` 资产的下载地址（留给 UI 用，App 不静默安装）；
 * - [error] 失败原因（中文，可直接展示）。
 */
data class UpdateState(
    val checking: Boolean = false,
    val latestVersion: String = "",
    val hasUpdate: Boolean = false,
    val releaseUrl: String = "",
    val apkUrl: String = "",
    val error: String = "",
)