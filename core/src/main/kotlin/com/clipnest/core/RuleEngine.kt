package com.clipnest.core

import java.util.UUID

/**
 * 平台分享文本识别引擎（Kotlin 版，语义与 Windows 原型 C# RuleEngine 对齐）。
 *
 * - 命名组：rate / author / worktype / title / url / extra
 * - 按规则列表顺序匹配，命中即返回完整 ClipItem
 * - 每条正则单独跑在守护线程上，超过 250ms 直接放弃（防止恶意/超长文本卡死）
 * - previewAll 只返回命中项，并按规范化 url 去重（保留优先级最高的一条）
 */
class RuleEngine(catalog: List<CaptureRule> = RuleCatalog.defaults()) {

    @Volatile
    private var currentRules: List<CaptureRule> = catalog.toList()

    fun rules(): List<CaptureRule> = currentRules

    /** 启停某条规则（Android 版由 ClipRepo 负责把状态写进 SharedPreferences）。 */
    fun setRuleEnabled(id: String, enabled: Boolean) {
        currentRules = currentRules.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    /** 识别并构建一条 ClipItem；未命中返回 matched=false。 */
    fun tryCapture(text: String, source: String = "clipboard"): CaptureResult {
        val normalized = TextUtil.normalize(text)
        if (normalized.isEmpty()) return CaptureResult(false, normalized)
        val matchText = normalized.take(MAX_MATCH_CHARS)

        for (rule in currentRules) {
            val item = captureWith(rule, matchText, normalized, text, source) ?: continue
            return CaptureResult(true, normalized, item, rule)
        }
        return CaptureResult(false, normalized)
    }

    /** 只识别不落库；返回所有命中规则的结果，按规范化 url 去重、保持规则优先级顺序。 */
    fun previewAll(text: String): List<CaptureResult> {
        val normalized = TextUtil.normalize(text)
        if (normalized.isEmpty()) return emptyList()
        val matchText = normalized.take(MAX_MATCH_CHARS)

        val byUrl = LinkedHashMap<String, CaptureResult>()
        for (rule in currentRules) {
            val item = captureWith(rule, matchText, normalized, text, "manual") ?: continue
            byUrl.putIfAbsent(item.url, CaptureResult(true, normalized, item, rule))
        }
        return byUrl.values.toList()
    }

    // ── 内部实现 ─────────────────────────────────────────────────

    private fun captureWith(
        rule: CaptureRule,
        matchText: String,
        normalized: String,
        rawText: String,
        source: String,
    ): ClipItem? {
        if (!rule.enabled) return null
        if (!passesGuard(rule, matchText)) return null
        if (!hintMatches(rule, matchText)) return null
        val regex = RuleCatalog.compiled(rule) ?: return null

        val match = try {
            findWithTimeout(regex, matchText)
        } catch (_: Exception) {
            null
        } ?: return null

        val rawUrl = match.groupOrNull("url") ?: return null
        val url = TextUtil.normalizeUrl(rawUrl)
        if (url.isEmpty()) return null

        val author = TextUtil.compact(match.groupOrNull("author"), 80)
        val workType = TextUtil.compact(match.groupOrNull("worktype"), 40)
        val rate = TextUtil.compact(match.groupOrNull("rate"), 40)
        val extra = TextUtil.compact(match.groupOrNull("extra"), 240)
        var title = TextUtil.compact(match.groupOrNull("title"), 240)

        // 标题兜底：规则没给标题 → 原文去掉链接；再兜不住就用「作者的作品」/「平台名分享」
        if (title.isEmpty()) {
            title = TextUtil.compact(normalized.replace(rawUrl, " "), 240)
        }
        if (title.isEmpty()) {
            title = when {
                author.isNotEmpty() && workType.isNotEmpty() -> "${author}的${workType}"
                author.isNotEmpty() -> "${author}的作品"
                else -> rule.platformName + "分享"
            }
        }

        return ClipItem(
            id = UUID.randomUUID().toString().replace("-", ""),
            platform = rule.platform,
            platformName = rule.platformName,
            ruleId = rule.id,
            author = author,
            workType = workType,
            title = title,
            url = url,
            rate = rate,
            extra = extra,
            raw = rawText,
            capturedAt = System.currentTimeMillis(),
            source = source.ifBlank { "clipboard" },
        )
    }

    /** generic-link 兜底额外约束：Normalize 后 <8 字符或不含 http 一律不命中。 */
    private fun passesGuard(rule: CaptureRule, text: String): Boolean {
        if (rule.id != "generic-link") return true
        if (text.length < 8) return false
        if (!text.contains("http", ignoreCase = true)) return false
        return true
    }

    companion object {
        /** 单次正则匹配最长耗时；超时视为未命中。 */
        const val REGEX_TIMEOUT_MS = 250L

        /** 超长剪贴板文本只取前 N 个字符做匹配，避免 O(n²) 扫描。 */
        private const val MAX_MATCH_CHARS = 20_000

        /**
         * 规则快速预筛：文本里连平台特征词都没有就不必跑正则。
         * 未在表里的规则（自定义）不做预筛。
         */
        private val RULE_HINTS: Map<String, List<String>> = mapOf(
            "douyin-share" to listOf("复制打开抖音"),
            "douyin-legacy" to listOf("复制此链接"),
            "douyin-plain" to listOf("douyin.com"),
            "xiaohongshu" to listOf("xiaohongshu.com", "xhslink.com"),
            "kuaishou" to listOf("kuaishou.com"),
            "bilibili" to listOf("b23.tv", "bilibili.com"),
            "weibo" to listOf("weibo.com", "weibo.cn", "t.cn"),
            "youtube" to listOf("youtu.be", "youtube.com"),
            "generic-link" to listOf("http"),
        )

        private fun hintMatches(rule: CaptureRule, text: String): Boolean {
            val hints = RULE_HINTS[rule.id] ?: return true
            return hints.any { text.contains(it, ignoreCase = true) }
        }

        /**
         * 带 [REGEX_TIMEOUT_MS] 截止时间跑正则。
         *
         * 实现方式：传入自定义 [DeadlineCharSequence]，正则每读一个字符都会检查是否超时，
         * 超时立刻抛 [RegexTimeoutException] 从内部中止匹配 —— 不需要额外线程，
         * 也就不会留下“跑到一半被放弃”的匹配线程。
         */
        private fun findWithTimeout(regex: Regex, text: String): MatchResult? {
            val deadline = System.nanoTime() + REGEX_TIMEOUT_MS * 1_000_000L
            return try {
                regex.find(DeadlineCharSequence(text, deadline))
            } catch (_: RegexTimeoutException) {
                null
            } catch (_: StackOverflowError) {
                // 病态正则的另一种死法：回溯过深。同样按未命中处理。
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** 正则匹配超时（由 [DeadlineCharSequence] 在取字符时抛出）。 */
internal class RegexTimeoutException : RuntimeException("regex match timeout")

/**
 * 带截止时间的 CharSequence：让 java.util.regex 在每次读取字符时感知超时。
 * 正则引擎内部所有字符读取都走 [get]，因此在灾难性回溯时也能及时中止。
 */
internal class DeadlineCharSequence(
    private val text: String,
    private val deadlineNanos: Long,
) : CharSequence {

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (System.nanoTime() - deadlineNanos >= 0) throw RegexTimeoutException()
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineCharSequence(text.substring(startIndex, endIndex), deadlineNanos)

    override fun toString(): String = text
}

private fun MatchResult.groupOrNull(name: String): String? =
    try {
        groups[name]?.value
    } catch (_: Exception) {
        null
    }

/**
 * 内置规则目录：与 C# 版 9 条规则一致，列表顺序 = 匹配优先级。
 * 标题组限长 {0,600}，防止无链接长文本触发 O(n²) 扫描。
 */
object RuleCatalog {

    private const val DOUYIN_URL =
        """https?://(?:v\.douyin\.com|(?:www\.|m\.)?douyin\.com|(?:www\.)?iesdouyin\.com)/[A-Za-z0-9_\-./?=&%#:+~]*"""

    private val cache = HashMap<String, Regex>()

    /** 编译并缓存规则正则；非法正则返回 null（不抛）。 */
    fun compiled(rule: CaptureRule): Regex? {
        val key = rule.id + '\u0000' + rule.pattern
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val compiled = try {
            Regex(rule.pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        } catch (_: Exception) {
            return null
        }
        synchronized(cache) { cache[key] = compiled }
        return compiled
    }

    fun defaults(): List<CaptureRule> = listOf(
        CaptureRule(
            id = "douyin-share",
            name = "抖音新版分享",
            platform = "douyin",
            platformName = "抖音",
            description = "「x.xx 复制打开抖音，看看【作者的作品】…」新版分享文案",
            sample = "4.89 复制打开抖音，看看【想成为摄影糕手的图文作品】下次还要来陪我呀。 https://v.douyin.com/depmmgQEbkU/ h@O.kP 03/16 :4pm",
            pattern = """(?<rate>\d+(?:[.,]\d+)?)?\s*复制打开抖音[，,：:\s]*看看\s*(?:【(?<author>[^】]{1,60}?)的(?<worktype>[^】的]{0,12}?作品)】\s*)?(?<title>[\s\S]{0,600}?)\s*(?<url>$DOUYIN_URL)(?<extra>[\s\S]*)""",
        ),
        CaptureRule(
            id = "douyin-legacy",
            name = "抖音旧版复制口令",
            platform = "douyin",
            platformName = "抖音",
            description = "「复制此链接，打开Dou音搜索，直接观看视频！」旧版分享文案",
            sample = "8.43 复制此链接，打开Dou音搜索，直接观看视频！ https://v.douyin.com/abcdEF/",
            pattern = """(?<rate>\d+(?:[.,]\d+)?)?\s*复制此链接[，,]?\s*打开(?:Dou音|抖音)\s*搜索[，,]?\s*直接观看视频[！!]?\s*(?<url>$DOUYIN_URL)(?<extra>[\s\S]*)""",
        ),
        CaptureRule(
            id = "douyin-plain",
            name = "抖音链接",
            platform = "douyin",
            platformName = "抖音",
            description = "只出现抖音短链时的兜底识别",
            sample = "https://v.douyin.com/depmmgQEbkU/",
            pattern = """(?<title>[\s\S]{0,600}?)(?<url>$DOUYIN_URL)(?<extra>[\s\S]*)""",
        ),
        CaptureRule(
            id = "xiaohongshu",
            name = "小红书分享",
            platform = "xiaohongshu",
            platformName = "小红书",
            description = "小红书笔记分享文案或 xhslink 短链",
            sample = "99 发现一篇好笔记 https://xhslink.com/abcdEF",
            pattern = """(?<title>[\s\S]{0,600}?)(?<url>https?://(?:www\.)?(?:xiaohongshu\.com|xhslink\.com)/[A-Za-z0-9_\-./?=&%#:+~]+)(?<extra>[\s\S]*)""",
        ),
        CaptureRule(
            id = "kuaishou",
            name = "快手分享",
            platform = "kuaishou",
            platformName = "快手",
            description = "快手作品分享短链",
            sample = "https://v.kuaishou.com/abcdEF 复制此消息",
            pattern = """(?<title>[\s\S]{0,600}?)(?<url>https?://(?:v\.|www\.|m\.)?kuaishou\.com/[A-Za-z0-9_\-./?=&%#:+~]+)(?<extra>[\s\S]*)""",
        ),
        CaptureRule(
            id = "bilibili",
            name = "哔哩哔哩分享",
            platform = "bilibili",
            platformName = "哔哩哔哩",
            description = "b23.tv 短链或 bilibili.com 视频链接",
            sample = "【标题】 https://b23.tv/abcdEF",
            pattern = """(?<title>[\s\S]{0,600}?)(?<url>https?://(?:b23\.tv|(?:www\.|m\.)?bilibili\.com)/[A-Za-z0-9_\-./?=&%#:+~]+)(?<extra>[\s\S]*)""",
        ),
        CaptureRule(
            id = "weibo",
            name = "微博分享",
            platform = "weibo",
            platformName = "微博",
            description = "t.cn 短链或微博正文链接",
            sample = "https://weibo.com/1234/abcdEF",
            pattern = """(?<title>[\s\S]{0,600}?)(?<url>https?://(?:t\.cn|(?:www\.|m\.|video\.)?weibo\.(?:com|cn))/[A-Za-z0-9_\-./?=&%#:+~]+)(?<extra>[\s\S]*)""",
        ),
        CaptureRule(
            id = "youtube",
            name = "YouTube 分享",
            platform = "youtube",
            platformName = "YouTube",
            description = "youtu.be 短链或 youtube 视频链接",
            sample = "https://youtu.be/abcdEF",
            pattern = """(?<title>[\s\S]{0,600}?)(?<url>https?://(?:youtu\.be|(?:www\.|m\.)?youtube\.com)/[A-Za-z0-9_\-./?=&%#:+~]+)(?<extra>[\s\S]*)""",
        ),
        CaptureRule(
            id = "generic-link",
            name = "任意链接",
            platform = "generic",
            platformName = "链接",
            description = "兜底：任意 http(s) 链接（默认关闭）",
            sample = "https://example.com/a/b",
            pattern = """(?<title>[\s\S]{0,600}?)(?<url>https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+)(?<extra>[\s\S]*)""",
            enabled = false,
        ),
    )
}