package com.clipnest.core.douyin

/**
 * 抖音作品解析结果（契约 §2 冻结结构，Field 名/默认值不许改）。
 *
 * 与 Python 参考实现 `resolve.py` 的 dict 对应：
 * - ok / aweme_id / type / title / author.nickname / pics / videos[0].url / source
 * Kotlin 侧拍平成 [videoUrl] + [videoQuality] + [videoSize]（App 只需要单个最佳视频地址）。
 */
data class DouyinWork(
    val ok: Boolean,
    val awemeId: String = "",
    /** image | video */
    val type: String = "",
    val title: String = "",
    val authorName: String = "",
    val pics: List<String> = emptyList(),
    val videoUrl: String = "",
    /** 例如 1080p；拿不到留空 */
    val videoQuality: String = "",
    val videoSize: Long = 0,
    val coverUrl: String = "",
    /** iesdouyin | seo */
    val source: String = "",
    /** not_found | parse_failed | timeout | network | no_media */
    val code: String = "",
    val message: String = "",
)