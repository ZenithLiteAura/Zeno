package com.clipnest.core.platform

/**
 * 单平台解析器。实现方必须保证 [resolve] 不抛裸异常：
 * 失败也要返回 `ok=false` + code/message（`not_found/parse_failed/timeout/network/no_media/unsupported`）。
 */
interface PlatformResolver {
    /** douyin / xiaohongshu / bilibili / kuaishou */
    val id: String

    /** 只做识别（host / 短链 / 文案特征），不发网络请求。 */
    fun supports(text: String): Boolean

    /** 解析；实现内部自己控制总预算 [budgetMs]。 */
    fun resolve(text: String, budgetMs: Long = 60_000): MediaWork
}

/**
 * 平台分发入口：按 URL host 选解析器；识别不出来直接返回 `unsupported`。
 * 顺序固定为 抖音 → B 站 → 小红书 → 快手（host 互不重叠）。
 */
object PlatformResolvers {

    private val resolvers: List<PlatformResolver> = listOf(
        DouyinPlatformResolver(),
        BilibiliResolver(),
        XiaohongshuResolver(),
        KuaishouResolver(),
    )

    fun pick(text: String): PlatformResolver? = resolvers.firstOrNull { it.supports(text) }

    fun resolve(text: String, budgetMs: Long = 60_000): MediaWork {
        val resolver = pick(text)
            ?: return MediaWork(
                ok = false,
                code = "unsupported",
                message = "暂不支持该平台：目前支持 抖音 / 小红书 / 哔哩哔哩 / 快手 的分享链接或文案",
            )
        return resolver.resolve(text, budgetMs)
    }
}