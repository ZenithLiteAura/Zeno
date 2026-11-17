package com.ZenithLiteAura.app.nezo.platform.download

import com.clipnest.core.douyin.DouyinWork
import com.clipnest.core.platform.MediaWork

/**
 * 多平台解析结果 → 下载引擎内部沿用的 DouyinWork 形状。
 *
 * 下载层（队列 / ZIP / MediaSaver）只关心「标题、图片列表、视频地址、质量、大小」，
 * 与平台无关；这里做一层薄映射，避免下载层为每个平台写一套分支。
 */
internal fun MediaWork.toDouyinWork(): DouyinWork = DouyinWork(
    ok = ok,
    awemeId = workId,
    type = type,
    title = title,
    authorName = author,
    pics = pics,
    videoUrl = videoUrl,
    videoQuality = videoQuality,
    videoSize = videoSize,
    coverUrl = coverUrl,
    source = source,
    code = code,
    message = message,
)
