package com.clipnest.core.platform

/**
 * 一个可选清晰度（解析结果里的「更多清晰度」列表元素）。
 *
 * @property label 展示名，如 `1080p` / `720p` / `4K` / `原画`
 * @property url 该清晰度可直接下载的地址
 * @property size 字节数；未知填 0
 * @property watermark 是否为带水印地址（`playwm` 等）
 */
data class MediaQuality(
    val label: String,
    val url: String,
    val size: Long = 0,
    val watermark: Boolean = false,
)