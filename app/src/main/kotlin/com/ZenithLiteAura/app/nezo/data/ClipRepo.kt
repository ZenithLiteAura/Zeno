package com.ZenithLiteAura.app.nezo.data

import android.content.Context
import androidx.core.content.edit
import com.clipnest.core.AppSettings
import com.clipnest.core.CaptureResult
import com.clipnest.core.CaptureRule
import com.clipnest.core.ClipItem
import com.clipnest.core.Exporter
import com.clipnest.core.ItemStore
import com.clipnest.core.ItemsChangeKind
import com.clipnest.core.RuleEngine
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import com.clipnest.core.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 全局数据入口（Lead 冻结契约）。
 * UI 只读 StateFlow + 调用方法；平台层负责采集、服务、权限；两者都不直接碰 Store。
 */
object ClipRepo {

    private const val PREFS_RULES = "rules"
    private lateinit var appContext: Context

    private lateinit var store: ItemStore
    private lateinit var settingStore: SettingsStore
    private lateinit var engine: RuleEngine
    private val ruleEnabled = LinkedHashMap<String, Boolean>()

    private val _items = MutableStateFlow<List<ClipItem>>(emptyList())

    /** 收件箱（已过滤回收站，`trashAt == 0`） */
    val items: StateFlow<List<ClipItem>> = _items.asStateFlow()

    private val _trash = MutableStateFlow<List<ClipItem>>(emptyList())

    /** 回收站（`trashAt > 0`，按移入时间倒序） */
    val trash: StateFlow<List<ClipItem>> = _trash.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _rules = MutableStateFlow<List<CaptureRule>>(emptyList())
    val rules: StateFlow<List<CaptureRule>> = _rules.asStateFlow()

    /** 最近一次收录成功的事件（UI 用于弹 toast / 高亮） */
    private val _lastCaptured = MutableStateFlow<ClipItem?>(null)
    val lastCaptured: StateFlow<ClipItem?> = _lastCaptured.asStateFlow()

    /** 数据目录：改名后统一用 `files/nezo`（旧版 `files/clipnest` 会在 init 时迁移过来） */
    val dataDir: File get() = File(appContext.filesDir, "nezo")
    val logFile: File get() = File(dataDir, "nezo.log")

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        // 先迁移旧数据目录，再建新目录/加载数据（迁移失败只记日志，不阻塞启动）
        migrateLegacyDataDir()
        dataDir.mkdirs()
        loadRulePrefs()
        engine = RuleEngine(RuleEngine_applyPrefs())
        settingStore = SettingsStore(File(dataDir, "settings.json"))
        store = ItemStore(File(dataDir, "items.json"), settingStore.current().maxItems, settingStore.current().dedupeByUrl)
        store.addListener { kind, item ->
            refreshItemsLocked()
            if (kind == ItemsChangeKind.Added || kind == ItemsChangeKind.Updated) _lastCaptured.value = item
        }
        refreshItemsLocked()
        _settings.value = settingStore.current()
        _rules.value = engine.rules()
        settingStore.addListener { s ->
            _settings.value = s
            store.maxItems = s.maxItems
            store.dedupeByUrl = s.dedupeByUrl
            setRuleEnabled("generic-link", s.capturePlainLinks)
        }
    }

    /** 收件箱/回收站两个流一起刷新（同一份 store 快照，只过滤一次） */
    private fun refreshItemsLocked() {
        val all = store.items()
        _items.value = all.filter { it.trashAt == 0L }
        _trash.value = all.filter { it.trashAt > 0L }.sortedByDescending { it.trashAt }
    }

    private fun RuleEngine_applyPrefs(): List<CaptureRule> =
        RuleEngine().rules().map { rule ->
            val saved = ruleEnabled[rule.id]
            val enabled = when (rule.id) {
                "generic-link" -> false
                else -> saved ?: rule.enabled
            }
            rule.copy(enabled = enabled)
        }

    private fun loadRulePrefs() {
        val prefs = appContext.getSharedPreferences(PREFS_RULES, Context.MODE_PRIVATE)
        prefs.all.forEach { (k, v) -> if (v is Boolean) ruleEnabled[k] = v }
    }

    /** 识别并收录一段文本；未命中返回 null，命中返回结果（调用方负责提示） */
    fun captureText(text: String, source: String = "clipboard"): CaptureResult? {
        engine.rules().forEach { rule ->
            // 引擎内部的规则状态以 engine 为准，改启用状态时同步
        }
        val result = engine.tryCapture(text, source)
        val item = result.item
        if (!result.matched || item == null) return null
        val stored = store.add(item)
        _lastCaptured.value = stored
        return result.copy(item = stored)
    }

    /** 只识别不落库（规则页预览用） */
    fun preview(text: String): List<CaptureResult> = engine.previewAll(text)

    /** 这段文本命中的第一条链接（不落库）；未命中返回 null */
    fun firstMatchUrl(text: String): String? = engine.previewAll(text).firstOrNull()?.item?.url

    /** 该链接是否已经收录过（用于「回到前台补抓」时避免重复置顶） */
    fun containsUrl(url: String): Boolean = _items.value.any { it.url == url }

    fun setStar(id: String, starred: Boolean) = store.setStar(id, starred)

    fun delete(id: String) = store.remove(id)

    fun clearAll() = store.clear()

    fun updateItem(item: ClipItem) = store.update(item)

    // ── 下载标记 / 回收站 ────────────────────────────────────────────────

    /**
     * 给「这次下载相关」的条目打下载标记（url 相同 或 url/raw 含 awemeId），返回被更新的 id 列表。
     * @param check `ok` / `size_mismatch` / `missing`
     */
    fun markDownloaded(
        url: String,
        awemeId: String,
        check: String,
        timeMs: Long = System.currentTimeMillis(),
    ): List<String> = store.markDownloaded(url, awemeId, check, timeMs)

    /** 移入回收站（主页 items 流会自动过滤掉） */
    fun moveToTrash(id: String, timeMs: Long = System.currentTimeMillis()): Boolean =
        store.moveToTrash(id, timeMs)

    /** 从回收站恢复回收件箱 */
    fun restoreFromTrash(id: String): Boolean = store.restoreFromTrash(id)

    /** 彻底清空回收站，返回删除条数 */
    fun purgeTrash(): Int = store.purgeTrash()

    /** 回收站里单条彻底删除 */
    fun purgeTrash(id: String): Boolean {
        val ok = store.purgeTrash(id)
        if (ok) runCatching { refreshItemsLocked() }
        return ok
    }

    fun updateSettings(mutate: (AppSettings) -> AppSettings): AppSettings {
        val next = settingStore.update(mutate)
        _settings.value = next
        return next
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        engine.setRuleEnabled(ruleId, enabled)
        ruleEnabled[ruleId] = enabled
        appContext.getSharedPreferences(PREFS_RULES, Context.MODE_PRIVATE)
            .edit { putBoolean(ruleId, enabled) }
        _rules.value = engine.rules()
    }

    fun export(format: String, starredOnly: Boolean = false): String {
        val list = store.items().let { if (starredOnly) it.filter { i -> i.starred } else it }
        return when (format.lowercase()) {
            "md", "markdown" -> Exporter.toMarkdown(list)
            "csv" -> Exporter.toCsv(list)
            "json" -> Exporter.toJson(list)
            else -> Exporter.toText(list)
        }
    }

    // ── 旧数据目录迁移（ClipNest → Nezo） ────────────────────────────────

    /**
     * 1.2.0 改名后数据目录从 `files/clipnest` 换成 `files/nezo`。
     *
     * 首次启动时把旧目录整体搬过来：
     * 1. `files/nezo` 不存在 → 直接 `renameTo`（同一分区，瞬时完成）；
     * 2. rename 失败（被占用等）→ 逐个复制 `items.json / settings.json / rules.json / downloads.json /
     *    auto_downloaded.json / logs` 等全部内容，再尽力删除旧目录；
     * 3. `files/nezo` 已存在（之前迁了一半）→ 只补旧目录里缺的文件，幂等。
     *
     * 任何异常都只记日志，绝不影响启动。
     */
    private fun migrateLegacyDataDir() {
        runCatching {
            val filesDir = appContext.filesDir
            val legacy = File(filesDir, "clipnest")
            val target = File(filesDir, "nezo")

            if (!legacy.isDirectory) return@runCatching

            if (target.isDirectory) {
                // 已经迁过（或曾经中途失败）：补齐缺失文件后清掉旧目录
                copyMissing(legacy, target)
                runCatching { legacy.deleteRecursively() }
                renameLegacyLog(target)
                PlatformLog.info("旧数据目录已存在新目录：补齐缺失文件并清理 files/clipnest")
                return@runCatching
            }

            val renamed = legacy.renameTo(target)
            if (!renamed) {
                target.mkdirs()
                copyMissing(legacy, target)
                runCatching { legacy.deleteRecursively() }
            }

            renameLegacyLog(target)
            PlatformLog.info("已迁移旧数据目录 files/clipnest → files/nezo（rename=$renamed）")
        }.onFailure {
            PlatformLog.warn("旧数据目录迁移失败（不影响启动）：${it.message}")
        }
    }

    /** 递归复制 [from] 里有、[to] 里没有的文件（不覆盖已有文件） */
    private fun copyMissing(from: File, to: File) {
        val children = from.listFiles() ?: return
        for (child in children) {
            runCatching {
                val destination = File(to, child.name)
                if (child.isDirectory) {
                    if (!destination.exists()) destination.mkdirs()
                    copyMissing(child, destination)
                } else if (!destination.exists()) {
                    child.copyTo(destination, overwrite = false)
                }
            }.onFailure {
                PlatformLog.warn("迁移 ${child.name} 失败：${it.message}")
            }
        }
    }

    /** 旧日志文件名 clipnest.log → nezo.log */
    private fun renameLegacyLog(dir: File) {
        runCatching {
            val old = File(dir, "clipnest.log")
            val new = File(dir, "nezo.log")
            if (old.isFile && !new.exists()) old.renameTo(new)
        }
    }
}
