package com.clipnest.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 收件箱存储：内存列表 + JSON 原子落盘。
 *
 * - 线程安全：所有读写都在同一把锁内
 * - 新的在前；按规范化 url 去重（命中则更新时间、刷新非空字段、移到最前，事件 Updated）
 * - 超出 maxItems 从尾部截断
 * - 坏文件复制成 `.corrupt-yyyyMMdd-HHmmss` 后从空列表开始，绝不抛异常
 * - 监听器抛异常一律吞掉
 */
class ItemStore(
    private val file: File,
    maxItems: Int = 5000,
    dedupeByUrl: Boolean = true,
) {

    @Volatile
    var maxItems: Int = maxItems.coerceAtLeast(1)

    @Volatile
    var dedupeByUrl: Boolean = dedupeByUrl

    private val lock = Any()
    private val items = mutableListOf<ClipItem>()
    private val listeners = CopyOnWriteArrayList<(ItemsChangeKind, ClipItem?) -> Unit>()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    init {
        synchronized(lock) { loadLocked() }
    }

    fun addListener(listener: (ItemsChangeKind, ClipItem?) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (ItemsChangeKind, ClipItem?) -> Unit) {
        listeners -= listener
    }

    /** 快照，新的在前。 */
    fun items(): List<ClipItem> = synchronized(lock) { items.toList() }

    fun count(): Int = synchronized(lock) { items.size }

    fun findById(id: String): ClipItem? = synchronized(lock) { items.firstOrNull { it.id == id } }

    /**
     * 新增。按规范化 url 去重命中时不追加：更新 capturedAt、刷新非空字段、保留 Id/星标/备注，
     * 移到最前，返回已存在项；否则插到最前。
     */
    fun add(item: ClipItem): ClipItem {
        val normalizedUrl = TextUtil.normalizeUrl(item.url)
        val prepared = prepare(item, normalizedUrl)

        val stored: ClipItem
        val kind: ItemsChangeKind
        synchronized(lock) {
            val existingIndex =
                if (dedupeByUrl && normalizedUrl.isNotEmpty()) {
                    items.indexOfFirst { it.url == normalizedUrl }
                } else {
                    -1
                }

            if (existingIndex >= 0) {
                val old = items[existingIndex]
                stored = old.copy(
                    platform = prepared.platform.ifEmpty { old.platform },
                    platformName = prepared.platformName.ifEmpty { old.platformName },
                    ruleId = prepared.ruleId.ifEmpty { old.ruleId },
                    author = prepared.author.ifEmpty { old.author },
                    workType = prepared.workType.ifEmpty { old.workType },
                    title = prepared.title.ifEmpty { old.title },
                    url = normalizedUrl,
                    rate = prepared.rate.ifEmpty { old.rate },
                    extra = prepared.extra.ifEmpty { old.extra },
                    raw = prepared.raw.ifEmpty { old.raw },
                    capturedAt = prepared.capturedAt,
                    source = prepared.source.ifEmpty { old.source },
                    // id / starred / note / coverUrl / meta 保留旧值
                )
                items.removeAt(existingIndex)
                items.add(0, stored)
                kind = ItemsChangeKind.Updated
            } else {
                stored = prepared
                items.add(0, stored)
                kind = ItemsChangeKind.Added
            }

            trimLocked()
            persistLocked()
        }
        notifyListeners(kind, stored)
        return stored
    }

    /** 按 Id 覆盖。 */
    fun update(item: ClipItem): Boolean {
        var changed = false
        synchronized(lock) {
            val index = items.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                items[index] = prepare(item, TextUtil.normalizeUrl(item.url))
                trimLocked()
                persistLocked()
                changed = true
            }
        }
        if (changed) notifyListeners(ItemsChangeKind.Updated, item)
        return changed
    }

    /** 星标；未知 id 返回 false。状态没变化时返回 true 但不重复发事件。 */
    fun setStar(id: String, starred: Boolean): Boolean {
        var updated: ClipItem? = null
        synchronized(lock) {
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return false
            val old = items[index]
            if (old.starred == starred) return true
            val changed = old.copy(starred = starred)
            items[index] = changed
            updated = changed
            persistLocked()
        }
        updated?.let { notifyListeners(ItemsChangeKind.Updated, it) }
        return true
    }

    fun remove(id: String): Boolean {
        var removed: ClipItem? = null
        synchronized(lock) {
            val index = items.indexOfFirst { it.id == id }
            if (index >= 0) {
                removed = items.removeAt(index)
                persistLocked()
            }
        }
        removed?.let { notifyListeners(ItemsChangeKind.Removed, it) }
        return removed != null
    }

    fun clear() {
        synchronized(lock) {
            items.clear()
            persistLocked()
        }
        notifyListeners(ItemsChangeKind.Cleared, null)
    }

    /** 重新从磁盘加载（坏文件备份后回落空列表）。 */
    fun reload() {
        synchronized(lock) { loadLocked() }
        notifyListeners(ItemsChangeKind.Reloaded, null)
    }

    // ── 内部 ─────────────────────────────────────────────────────

    private fun prepare(item: ClipItem, normalizedUrl: String): ClipItem {
        var copy = item
        if (copy.url != normalizedUrl) copy = copy.copy(url = normalizedUrl)
        if (copy.id.isEmpty()) copy = copy.copy(id = UUID.randomUUID().toString().replace("-", ""))
        if (copy.capturedAt <= 0L) copy = copy.copy(capturedAt = System.currentTimeMillis())
        if (copy.source.isEmpty()) copy = copy.copy(source = "clipboard")
        return copy
    }

    private fun trimLocked() {
        while (items.size > maxItems) items.removeAt(items.size - 1)
    }

    private fun notifyListeners(kind: ItemsChangeKind, item: ClipItem?) {
        listeners.forEach { listener ->
            try {
                listener(kind, item)
            } catch (_: Exception) {
                // 监听器异常不能影响存储
            }
        }
    }

    private fun loadLocked() {
        items.clear()
        val text = FileIo.readTextOrNull(file)
        if (text.isNullOrBlank()) return

        val root = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            FileIo.backupAndRemoveCorrupt(file)
            return
        }

        val array: JsonArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.has("items") && root.asJsonObject.get("items").isJsonArray ->
                root.asJsonObject.getAsJsonArray("items")
            else -> {
                // 既不是数组也不是包装格式：按坏文件处理
                if (!root.isJsonObject) FileIo.backupAndRemoveCorrupt(file)
                return
            }
        }

        val parsed = ArrayList<ClipItem>(array.size())
        array.forEach { element ->
            if (!element.isJsonObject) return@forEach
            runCatching { parseItem(element.asJsonObject) }.getOrNull()?.let { parsed.add(it) }
        }

        // 新的在前（文件本身也是按时间倒序写的，这里再排一次保证一致）
        parsed.sortByDescending { it.capturedAt }
        items.addAll(parsed)
        trimLocked()
    }

    private fun parseItem(o: JsonObject): ClipItem = ClipItem(
        id = o.str("id").ifEmpty { UUID.randomUUID().toString().replace("-", "") },
        platform = o.str("platform"),
        platformName = o.str("platformName"),
        ruleId = o.str("ruleId"),
        author = o.str("author"),
        workType = o.str("workType"),
        title = o.str("title"),
        url = TextUtil.normalizeUrl(o.str("url")),
        rate = o.str("rate"),
        extra = o.str("extra"),
        raw = o.str("raw"),
        capturedAt = o.longOrNull("capturedAt") ?: System.currentTimeMillis(),
        source = o.str("source").ifEmpty { "clipboard" },
        starred = o.bool("starred", false),
        note = o.str("note"),
        coverUrl = o.strOrNull("coverUrl"),
        meta = o.strOrNull("meta"),
    )

    private fun persistLocked() {
        try {
            FileIo.writeAtomic(file, gson.toJson(items))
        } catch (_: Exception) {
            // 落盘失败不能影响主流程
        }
    }
}