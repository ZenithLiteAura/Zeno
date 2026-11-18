package com.clipnest.core.platform

import com.clipnest.core.douyin.DouyinLinks
import com.clipnest.core.douyin.DouyinResolver
import com.clipnest.core.douyin.DouyinWork

/**
 * 抖音平台适配器：内部直接复用已验证的 [DouyinResolver]，把 [DouyinWork] 映射成通用 [MediaWork]。
 * `DouyinResolver` 的签名与行为完全不动（app 层还在直接用）。
 */
class DouyinPlatformResolver(
    private val resolver: DouyinResolver = DouyinResolver(),
) : PlatformResolver {

    override val id: String = "douyin"

    override fun supports(text: String): Boolean {
        if (text.isBlank()) return false
        val url = DouyinLinks.extractUrl(text) ?: return false
        if (DouyinLinks.isShortLink(url)) return true
        val host = PlatformLinks.host(url) ?: return false
        return host == "douyin.com" || host.endsWith(".douyin.com")
    }

    override fun resolve(text: String, budgetMs: Long): MediaWork =
        resolver.resolve(text, budgetMs).toMediaWork()
}

/** [DouyinWork] → 通用 [MediaWork]（字段语义一一对应，平台信息补齐）。 */
internal fun DouyinWork.toMediaWork(): MediaWork = MediaWork(
    ok = ok,
    platform = "douyin",
    platformName = "抖音",
    workId = awemeId,
    type = type,
    title = title,
    author = authorName,
    pics = pics,
    videoUrl = videoUrl,
    videoQuality = videoQuality,
    videoSize = videoSize,
    qualities = qualities,
    selectedQuality = selectedQuality,
    coverUrl = coverUrl,
    source = source,
    code = code,
    message = message,
)