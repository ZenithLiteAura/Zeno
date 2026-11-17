package com.ZenithLiteAura.app.nezo.data

import android.content.Context
import com.clipnest.core.AppSettings
import com.clipnest.core.CaptureResult
import com.clipnest.core.CaptureRule
import com.clipnest.core.ClipItem
import com.clipnest.core.Exporter
import com.clipnest.core.ItemStore
import com.clipnest.core.ItemsChangeKind
import com.clipnest.core.RuleEngine
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
    val items: StateFlow<List<ClipItem>> = _items.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _rules = MutableStateFlow<List<CaptureRule>>(emptyList())
    val rules: StateFlow<List<CaptureRule>> = _rules.asStateFlow()

    /** 最近一次收录成功的事件（UI 用于弹 toast / 高亮） */
    private val _lastCaptured = MutableStateFlow<ClipItem?>(null)
    val lastCaptured: StateFlow<ClipItem?> = _lastCaptured.asStateFlow()

    val dataDir: File get() = File(appContext.filesDir, "clipnest")
    val logFile: File get() = File(dataDir, "clipnest.log")

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        dataDir.mkdirs()
        loadRulePrefs()
        engine = RuleEngine(RuleEngine_applyPrefs())
        settingStore = SettingsStore(File(dataDir, "settings.json"))
        store = ItemStore(File(dataDir, "items.json"), settingStore.current().maxItems, settingStore.current().dedupeByUrl)
        store.addListener { kind, item ->
            _items.value = store.items()
            if (kind == ItemsChangeKind.Added || kind == ItemsChangeKind.Updated) _lastCaptured.value = item
        }
        _items.value = store.items()
        _settings.value = settingStore.current()
        _rules.value = engine.rules()
        settingStore.addListener { s ->
            _settings.value = s
            store.maxItems = s.maxItems
            store.dedupeByUrl = s.dedupeByUrl
            setRuleEnabled("generic-link", s.capturePlainLinks)
        }
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

    fun updateSettings(mutate: (AppSettings) -> AppSettings): AppSettings {
        val next = settingStore.update(mutate)
        _settings.value = next
        return next
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        engine.setRuleEnabled(ruleId, enabled)
        ruleEnabled[ruleId] = enabled
        appContext.getSharedPreferences(PREFS_RULES, Context.MODE_PRIVATE)
            .edit().putBoolean(ruleId, enabled).apply()
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
}
