package com.clipnest.core.douyin

import com.clipnest.core.platform.MediaQuality

/**
 * 抖音作品解析结果（契约 §2 冻结结构，Field 名/默认值不许改）。
 *
 * 与 Python 参考实现 `resolve.py` 的 dict 对应：
 * - ok / aweme_id / type / title / author.nickname / pics / videos[0].url / source
 * Kotlin 侧拍平成 [videoUrl] + [videoQuality] + [videoSize]（`videoUrl`/`videoQuality` = 默认选中清晰度）。
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
    /** 可选清晰度列表（从高到低）；拿不到多档时只有 1 条，图文作品为空 */
    val qualities: List<MediaQuality> = emptyList(),
    /** 默认选中清晰度 label（与 [videoQuality] 同值） */
    val selectedQuality: String = "",
    val coverUrl: String = "",
    /** iesdouyin | seo */
    val source: String = "",
    /** not_found | parse_failed | timeout | network | no_media */
    val code: String = "",
    val message: String = "",
)