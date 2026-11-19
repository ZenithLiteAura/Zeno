package com.ZenithLiteAura.app.nezo.platform.diag

import android.content.Context
import android.net.Uri
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.Calendar
import java.util.Locale

/** 一条日志（app = 我们自己的 nezo.log；system = logcat） */
data class LogEntry(
    val timeMs: Long,
    val level: String,     // DEBUG / INFO / WARN / ERROR
    val channel: String,   // app | system
    val message: String,
)

/**
 * 日志收集器（给设置页「日志与诊断」用）。
 *
 * - **app 渠道**：增量 tail `files/nezo/nezo.log`（1s 轮询；文件被截断时自动从头读）；
 * - **system 渠道**：`logcat -v time -d` 增量拉取（记录已读行数，只保留与本 App 相关的行），不用 root，
 *   `logcat` 不可用时给一条「系统日志不可用」的 WARN；
 * - 统一级别映射：`V/D→DEBUG`、`I→INFO`、`W→WARN`、`E/F→ERROR`，按 `minLevel` 过滤；
 * - 内存最多保留 2000 条（新的在尾部），通过 [entriesFlow] 实时推给 UI；
 * - [stop] 会停线程并销毁 logcat 子进程。
 */
object LogCollector {

    private const val MAX_ENTRIES = 2000
    private const val POLL_INTERVAL_MS = 1000L
    private const val LOG_READ_LIMIT_BYTES = 512 * 1024
    private const val LOGCAT_TIMEOUT_MS = 3000L
    private const val LOGCAT_MAX_LINES = 6000

    const val CHANNEL_APP = "app"
    const val CHANNEL_SYSTEM = "system"

    private val LEVEL_RANK = mapOf(
        "DEBUG" to 0,
        "INFO" to 1,
        "WARN" to 2,
        "ERROR" to 3,
    )

    private val lock = Any()
    private val buffer = ArrayList<LogEntry>()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entriesFlow: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var minRank = 0
    private var activeChannel = CHANNEL_APP

    private var appFile: File? = null
    private var appOffset = 0L
    private var appLeftover = ""

    private var systemSeenLines = 0
    private var systemProcess: Process? = null
    private var systemUnavailableLogged = false

    private val appTimeRegex = Regex("""^\[(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})]\s+\[([A-Z])]\s?(.*)$""")
    private val logcatRegex = Regex("""^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+([^:]+?)\s*:\s?(.*)$""")

    // ── 对外 API ──────────────────────────────────────────────────────────

    /**
     * 开始收集。
     * @param minLevel DEBUG / INFO / WARN / ERROR（也接受 V/D/I/W/E）
     * @param channel app | system
     */
    @Synchronized
    fun start(context: Context, minLevel: String, channel: String) {
        stop()

        minRank = parseLevel(minLevel)?.let { LEVEL_RANK.getValue(it) } ?: 0
        activeChannel = if (channel.equals(CHANNEL_SYSTEM, ignoreCase = true)) CHANNEL_SYSTEM else CHANNEL_APP

        val appContext = context.applicationContext
        appFile = runCatching { ClipRepo.logFile }.getOrElse {
            runCatching { File(appContext.filesDir, "nezo/nezo.log") }.getOrNull()
        }
        appOffset = 0L
        appLeftover = ""
        systemSeenLines = 0
        systemUnavailableLogged = false

        running = true
        worker = Thread({ loop() }, "NezoLogCollector").apply {
            isDaemon = true
            start()
        }
        PlatformLog.info("日志收集开始：channel=$activeChannel minLevel=${levelOfRank(minRank)}")
    }

    @Synchronized
    fun stop() {
        val proc = systemProcess
        systemProcess = null
        running = false
        runCatching { worker?.interrupt() }
        worker = null
        runCatching { proc?.destroy() }
        if (proc != null) PlatformLog.info("日志收集停止（已销毁 logcat 进程）")
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    /** 按当前过滤导出成文本行（给 UI 预览/分享用） */
    fun exportLines(): List<String> = _entries.value.map { entry ->
        "[${formatTime(entry.timeMs)}] [${entry.level}] [${entry.channel}] ${entry.message}"
    }

    /** 写到 SAF 选中的 uri（纯文本 UTF-8）；成功返回 true */
    fun exportTo(context: Context, uri: Uri): Boolean {
        val lines = exportLines()
        return runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                stream.write((lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: return false
            true
        }.onFailure {
            PlatformLog.warn("导出日志失败：${it.message}")
        }.getOrDefault(false)
    }

    // ── 采集线程 ──────────────────────────────────────────────────────────

    private fun loop() {
        while (running) {
            runCatching {
                if (activeChannel == CHANNEL_SYSTEM) pollSystem() else pollApp()
            }.onFailure {
                PlatformLog.warn("日志采集异常：${it.javaClass.simpleName}: ${it.message}")
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    // ── app 渠道：增量 tail nezo.log ──────────────────────────────────────

    private fun pollApp() {
        val file = appFile ?: return
        if (!file.isFile) return

        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length <= 0L) return
        if (length < appOffset) {   // 日志被截断（>512KB 会清空）→ 从头再来
            appOffset = 0L
            appLeftover = ""
        }
        if (length == appOffset) return

        val toRead = (length - appOffset).coerceAtMost(LOG_READ_LIMIT_BYTES.toLong()).toInt()
        if (toRead <= 0) return

        val chunk = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(appOffset)
                val bytes = ByteArray(toRead)
                val read = raf.read(bytes)
                if (read <= 0) return@use null
                if (read == toRead) String(bytes, 0, read, Charsets.UTF_8)
                else String(bytes, 0, read, Charsets.UTF_8)
            }
        }.getOrNull() ?: return

        appOffset += toRead

        // 处理完整行，最后一段（可能半行）留到下次
        val combined = appLeftover + chunk
        val parts = combined.split('\n')
        appLeftover = if (combined.endsWith('\n')) "" else parts.last()
        val completeLines = if (combined.endsWith('\n')) parts.dropLast(1) else parts.dropLast(1)

        for (raw in completeLines) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val match = appTimeRegex.find(line) ?: continue
            val (month, day, hour, minute, second, millis, levelChar, message) =
                match.destructured
            val level = mapLevel(levelChar.firstOrNull() ?: 'I')
            append(
                LogEntry(
                    timeMs = composeTime(month, day, hour, minute, second, millis),
                    level = level,
                    channel = CHANNEL_APP,
                    message = message,
                ),
            )
        }
    }

    // ── system 渠道：logcat -v time -d 增量 ───────────────────────────────

    private fun pollSystem() {
        val process = runCatching {
            ProcessBuilder("logcat", "-v", "time", "-d")
                .redirectErrorStream(true)
                .start()
        }.getOrNull()

        if (process == null) {
            if (!systemUnavailableLogged) {
                systemUnavailableLogged = true
                append(
                    LogEntry(
                        timeMs = System.currentTimeMillis(),
                        level = "WARN",
                        channel = CHANNEL_SYSTEM,
                        message = "系统日志不可用：无法执行 logcat（可能被系统限制）",
                    ),
                )
            }
            return
        }

        systemProcess = process
        val output = runCatching {
            val finished = process.waitFor(LOGCAT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { "" }
        runCatching { process.destroy() }
        systemProcess = null

        if (output.isBlank()) {
            if (!systemUnavailableLogged) {
                systemUnavailableLogged = true
                append(
                    LogEntry(
                        timeMs = System.currentTimeMillis(),
                        level = "WARN",
                        channel = CHANNEL_SYSTEM,
                        message = "系统日志不可用：logcat 没有返回内容",
                    ),
                )
            }
            return
        }

        val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
        // logcat 缓冲被清空/轮转时行数会变少 → 从头重新同步
        if (lines.size < systemSeenLines) systemSeenLines = 0
        var start = systemSeenLines.coerceAtMost(lines.size)
        systemSeenLines = lines.size
        // 缓冲太大时只处理最近一段，避免首次一次性刷几千行
        if (lines.size - start > LOGCAT_MAX_LINES) {
            start = lines.size - LOGCAT_MAX_LINES
        }

        for (index in start until lines.size) {
            val line = lines[index]
            if (!isRelevantSystemLine(line)) continue
            val match = logcatRegex.find(line)
            if (match == null) {
                append(
                    LogEntry(
                        timeMs = System.currentTimeMillis(),
                        level = "INFO",
                        channel = CHANNEL_SYSTEM,
                        message = line.take(400),
                    ),
                )
                continue
            }
            val (month, day, hour, minute, second, millis, levelChar, tag, message) = match.destructured
            append(
                LogEntry(
                    timeMs = composeTime(month, day, hour, minute, second, millis),
                    level = mapLevel(levelChar.firstOrNull() ?: 'I'),
                    channel = CHANNEL_SYSTEM,
                    message = "${tag.trim()}: $message",
                ),
            )
        }
    }

    /** 系统日志只保留与本 App / Shizuku / 下载 / 收录相关的行 */
    private fun isRelevantSystemLine(line: String): Boolean {
        if (line.isBlank()) return false
        return SYSTEM_KEYWORDS.any { line.contains(it, ignoreCase = false) }
    }

    private val SYSTEM_KEYWORDS = listOf(
        "com.ZenithLiteAura.app.nezo",
        "Nezo",
        "ClipProbe",
        "Shizuku",
        "NezoShizuku",
        "NezoDownload",
        "NezoExec",
    )

    // ── 工具 ──────────────────────────────────────────────────────────────

    private fun append(entry: LogEntry) {
        if ((LEVEL_RANK[entry.level] ?: 0) < minRank) return
        synchronized(lock) {
            buffer.add(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeAt(0)
            _entries.value = ArrayList(buffer)
        }
    }

    private fun parseLevel(raw: String): String? = when (raw.trim().uppercase(Locale.US)) {
        "V", "VERBOSE", "D", "DEBUG" -> "DEBUG"
        "I", "INFO" -> "INFO"
        "W", "WARN", "WARNING" -> "WARN"
        "E", "ERROR", "F", "FATAL" -> "ERROR"
        else -> null
    }

    private fun mapLevel(levelChar: Char): String = when (levelChar) {
        'V', 'D' -> "DEBUG"
        'I' -> "INFO"
        'W' -> "WARN"
        'E', 'F' -> "ERROR"
        else -> "INFO"
    }

    private fun levelOfRank(rank: Int): String =
        LEVEL_RANK.entries.firstOrNull { it.value == rank }?.key ?: "DEBUG"

    /** 用「今年」补全 `MM-dd HH:mm:ss.SSS`，避免 SimpleDateFormat 默认 1970 年 */
    private fun composeTime(
        month: String,
        day: String,
        hour: String,
        minute: String,
        second: String,
        millis: String,
    ): Long = runCatching {
        val calendar = Calendar.getInstance()
        val now = calendar.timeInMillis
        val year = Calendar.getInstance().get(Calendar.YEAR)
        calendar.clear()
        calendar.set(
            year,
            (month.toIntOrNull() ?: 1) - 1,
            day.toIntOrNull() ?: 1,
            hour.toIntOrNull() ?: 0,
            minute.toIntOrNull() ?: 0,
            second.toIntOrNull() ?: 0,
        )
        calendar.set(Calendar.MILLISECOND, millis.toIntOrNull() ?: 0)
        val parsed = calendar.timeInMillis
        // 偏差过大（比如跨年）就用当前时间兜底
        if (kotlin.math.abs(parsed - now) > 366L * 24 * 3600 * 1000) now else parsed
    }.getOrElse { System.currentTimeMillis() }

    private val TIME_FORMAT = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun formatTime(timeMs: Long): String = runCatching {
        TIME_FORMAT.format(java.util.Date(timeMs))
    }.getOrDefault("")
}