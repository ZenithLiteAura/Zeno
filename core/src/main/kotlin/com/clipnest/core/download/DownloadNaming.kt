package com.clipnest.core.download

/**
 * 命名规则（与线上 `namingPattern` 1..4 一一对应）：
 * 1 = 序号_标题，2 = 标题_分辨率，3 = 仅标题，4 = 仅序号。
 */
enum class ZipNamingRule(val number: Int) {
    SEQ_TITLE(1),
    TITLE_RES(2),
    TITLE_ONLY(3),
    SEQ_ONLY(4),
    ;

    companion object {
        /** UI/设置里的 `rule=1..4` → 枚举；非法值回落 [SEQ_TITLE]（与线上 default 行为一致）。 */
        fun of(rule: Int): ZipNamingRule = entries.firstOrNull { it.number == rule } ?: SEQ_TITLE
    }
}

/**
 * ZIP / 文件命名（对应线上模块 43045 的 `truncateName + switch(namingPattern)` 与 39957 的 `dedupeFileName`）。
 */
object DownloadNaming {

    /** 文件名主体上限（线上 LIMIT.FILE_TITLE）。 */
    const val MAX_TITLE_LENGTH = 150

    /** 标题为空时的兜底名。 */
    const val DEFAULT_TITLE = "file"

    /** Windows/Android 都不友好的文件名字符（全角标点不替换，与线上正则一致）。 */
    private const val INVALID_CHARS = "\\/:*?\"<>|"

    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * 清洗标题：非法字符 `\ / : * ? " < > |` 与控制符换成 `_`、压空白、
     * 截断 150 字符；另外去掉首尾的点/空格（防止 ZIP 条目出现 `..` 之类的路径穿越名）。
     */
    fun sanitizeTitle(raw: String): String {
        if (raw.isEmpty()) return ""

        val builder = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch in INVALID_CHARS -> builder.append('_')
                // 换行/制表/全角空格等统一成空格，后面再压缩
                ch.isWhitespace() -> builder.append(' ')
                Character.isISOControl(ch) -> builder.append('_')
                else -> builder.append(ch)
            }
        }

        var title = WHITESPACE_RUN.replace(builder.toString(), " ").trim()
        if (title.codePointCount(0, title.length) > MAX_TITLE_LENGTH) {
            // 按 code point 截断：emoji 是代理对，按 UTF-16 char 截会切出半个字符（显示成 ）
            val endIndex = title.offsetByCodePoints(0, MAX_TITLE_LENGTH)
            title = title.substring(0, endIndex).trimEnd()
        }
        title = title.trim('.', ' ')
        return title
    }

    /**
     * 按规则生成文件名（带扩展名）。
     *
     * @param rule 命名规则
     * @param seq 序号（从 1 开始；超过 99 不截断，直接 `100`）
     * @param title 作品标题（内部会 [sanitizeTitle]）
     * @param res 分辨率（图片固定传 `"HD"`；视频传 `videoQuality`；空则回落 `"HD"`）
     * @param ext 扩展名，可带或不带点（`"jpg"` / `".jpg"`）
     */
    fun fileName(rule: ZipNamingRule, seq: Int, title: String, res: String, ext: String): String {
        val sequence = (if (seq <= 0) 1 else seq).toString().padStart(2, '0')
        val base = sanitizeTitle(title).ifEmpty { DEFAULT_TITLE }
        val resolution = sanitizeTitle(res).ifEmpty { "HD" }
        val suffix = normalizeExtension(ext)

        return when (rule) {
            ZipNamingRule.SEQ_TITLE -> "${sequence}_$base$suffix"
            ZipNamingRule.TITLE_RES -> "${base}_$resolution$suffix"
            ZipNamingRule.TITLE_ONLY -> "$base$suffix"
            ZipNamingRule.SEQ_ONLY -> "$sequence$suffix"
        }
    }

    /**
     * 同名去重：`标题.jpg` → `标题_1.jpg` → `标题_2.jpg`（序号从 1 开始）。
     * 去重后缀插在扩展名之前；无扩展名时退化成 `name_1`。
     * 命中名会被写进 [used]（调用方整个批次共用一个 Set）。
     */
    fun dedupe(name: String, used: MutableSet<String>): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""

        var candidate = name
        var index = 1
        while (!used.add(candidate)) {
            candidate = "${stem}_${index++}$extension"
        }
        return candidate
    }

    private fun normalizeExtension(ext: String): String {
        val cleaned = ext.trim().trimStart('.').lowercase()
        return if (cleaned.isEmpty()) "" else ".$cleaned"
    }
}