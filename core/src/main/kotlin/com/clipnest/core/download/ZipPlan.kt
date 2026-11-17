package com.clipnest.core.download

/**
 * ZIP 打包规划（对应线上「下载所有图片 ZIP」的命名/目录规则）。
 *
 * ZIP 结构（与线上一致，条目全部平铺在一层目录下）：
 * ```
 * image_batch_旅行日记.zip
 * └── 所有图片/
 *     ├── 01_旅行日记.jpg
 *     └── 02_旅行日记.jpg
 * ```
 */
object ZipPlan {

    /** ZIP 文件名：`image_batch_<标题>.zip` / `video_batch_<标题>.zip`；标题为空时退回目录名。 */
    fun zipFileName(type: String, title: String): String {
        val kind = normalizeType(type)
        val safeTitle = DownloadNaming.sanitizeTitle(title).ifEmpty { folderName(kind) }
        return "${kind}_batch_$safeTitle.zip"
    }

    /** ZIP 内的一级目录名：图片 → `所有图片`，视频 → `所有视频`。 */
    fun folderName(type: String): String =
        if (normalizeType(type) == "video") "所有视频" else "所有图片"

    /**
     * 规划 ZIP 条目：`Pair.first = ZIP 内完整条目路径（含目录前缀）`，`Pair.second = 媒体源 URL`。
     *
     * - 扩展名是**占位**（图片 `.jpg`、视频 `.mp4`），真实扩展名由下载端 [MediaSniffer]
     *   嗅探后替换最后一个扩展名即可（`_1/_2` 去重后缀不会受影响）。
     * - 图片优先：`pics` 非空时忽略 `videoUrl`（与解析器的类型判定一致）。
     * - 命名规则 2/3 在同作品多图时必然重名，靠 [DownloadNaming.dedupe] 生成 `_1`/`_2`。
     */
    fun entries(
        pics: List<String>,
        videoUrl: String,
        title: String,
        rule: ZipNamingRule,
    ): List<Pair<String, String>> {
        val entries = ArrayList<Pair<String, String>>()
        val usedNames = LinkedHashSet<String>()

        if (pics.isNotEmpty()) {
            val folder = folderName("image")
            val validPics = pics.map { it.trim() }.filter { it.isNotEmpty() }
            validPics.forEachIndexed { index, url ->
                val name = DownloadNaming.fileName(rule, index + 1, title, "HD", "jpg")
                val unique = DownloadNaming.dedupe(name, usedNames)
                entries += "$folder/$unique" to url
            }
            return entries
        }

        val trimmedVideoUrl = videoUrl.trim()
        if (trimmedVideoUrl.isNotEmpty()) {
            val folder = folderName("video")
            val name = DownloadNaming.fileName(rule, 1, title, "HD", "mp4")
            val unique = DownloadNaming.dedupe(name, usedNames)
            entries += "$folder/$unique" to trimmedVideoUrl
        }
        return entries
    }

    private fun normalizeType(type: String): String =
        if (type.trim().equals("video", ignoreCase = true)) "video" else "image"
}