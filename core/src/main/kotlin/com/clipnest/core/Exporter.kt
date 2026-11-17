package com.clipnest.core

import com.google.gson.GsonBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 导出器：md / txt / csv / json（格式与 Windows 原型 C# Exporter 对齐）。
 */
object Exporter {

    private const val RULE = "----------------------------------------"
    private const val HEAVY_RULE = "========================================"
    private const val CSV_HEADER = "作者,平台,类型,标题,链接,评分,收录时间,星标"

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun toMarkdown(items: List<ClipItem>): String = buildString {
        appendLine("# ClipNest 剪巢 · 收藏导出")
        appendLine()
        appendLine("> 导出时间：" + formatTime(System.currentTimeMillis()) + " · 共 " + items.size + " 条")
        appendLine()
        items.forEach { item ->
            val author = item.author.ifEmpty { item.platformName }.ifEmpty { "未署名" }
            val platform = item.platformName.ifEmpty { item.platform }.ifEmpty { "链接" }
            appendLine("## " + author + " · " + platform)
            appendLine()
            appendLine(item.title.ifEmpty { item.url })
            appendLine()
            if (item.workType.isNotEmpty()) appendLine("- 类型：" + item.workType)
            if (item.rate.isNotEmpty()) appendLine("- 评分：" + item.rate)
            if (item.url.isNotEmpty()) appendLine("- 链接：" + item.url)
            appendLine("- 收录：" + formatTime(item.capturedAt))
            if (item.starred) appendLine("- 星标：是")
            appendLine()
        }
    }

    fun toText(items: List<ClipItem>): String = buildString {
        appendLine("ClipNest 剪巢 · 收藏导出")
        appendLine("导出时间：" + formatTime(System.currentTimeMillis()) + " · 共 " + items.size + " 条")
        appendLine(HEAVY_RULE)
        appendLine()
        items.forEachIndexed { index, item ->
            val author = item.author.ifEmpty { item.platformName }.ifEmpty { "未署名" }
            val platform = item.platformName.ifEmpty { item.platform }.ifEmpty { "链接" }
            appendLine("[" + (index + 1) + "] " + author + " · " + platform)
            if (item.workType.isNotEmpty()) appendLine("类型：" + item.workType)
            val titleLine = item.title.ifEmpty { item.url }
            appendLine("标题：" + titleLine)
            appendLine("链接：" + item.url)
            append("收录：" + formatTime(item.capturedAt))
            if (item.starred) append("  ★")
            appendLine()
            appendLine(RULE)
        }
    }

    fun toCsv(items: List<ClipItem>): String = buildString {
        append(CSV_HEADER).append("\r\n")
        items.forEach { item ->
            val cells = listOf(
                item.author,
                item.platformName.ifEmpty { item.platform },
                item.workType,
                item.title,
                item.url,
                item.rate,
                formatTime(item.capturedAt),
                if (item.starred) "1" else "",
            )
            append(cells.joinToString(",") { escapeCsv(it) }).append("\r\n")
        }
    }

    fun toJson(items: List<ClipItem>): String =
        GsonBuilder().setPrettyPrinting().create().toJson(items)

    private fun escapeCsv(value: String): String {
        val needsQuote = value.contains(',') || value.contains('"') ||
            value.contains('\n') || value.contains('\r')
        if (!needsQuote) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun formatTime(epochMillis: Long): String {
        val local = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        return TIME_FORMAT.format(local)
    }
}