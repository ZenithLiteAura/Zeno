package com.clipnest.core.platform

/**
 * 通用作品解析结果（多平台统一结构，与 `douyin.DouyinWork` 字段语义一致）。
 * 抖音适配器 [DouyinPlatformResolver] 负责把旧结构映射过来。
 */
data class MediaWork(
    val ok: Boolean,
    val platform: String = "",
    /** 抖音 / 小红书 / 哔哩哔哩 / 快手 */
    val platformName: String = "",
    /** awemeId / noteId / bvid / photoId */
    val workId: String = "",
    /** image | video */
    val type: String = "",
    val title: String = "",
    val author: String = "",
    val pics: List<String> = emptyList(),
    val videoUrl: String = "",
    val videoQuality: String = "",
    val videoSize: Long = 0,
    val coverUrl: String = "",
    val source: String = "",
    /** not_found | parse_failed | timeout | network | no_media | unsupported */
    val code: String = "",
    val message: String = "",
)