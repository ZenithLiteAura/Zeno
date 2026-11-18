package com.clipnest.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 设置存储：JSON 落盘 + 原子写。
 * 缺字段用默认值（老文件升级不炸）；数值非法时按 Windows 版规则夹取；坏文件备份后回落默认值。
 */
class SettingsStore(private val file: File) {

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(AppSettings) -> Unit>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var current: AppSettings = load()

    fun current(): AppSettings = synchronized(lock) { current }

    fun addListener(listener: (AppSettings) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (AppSettings) -> Unit) {
        listeners -= listener
    }

    /** 修改 + 落盘 + 通知；mutate 抛异常时回滚并返回旧值（不抛）。 */
    fun update(mutate: (AppSettings) -> AppSettings): AppSettings {
        val next: AppSettings
        synchronized(lock) {
            next = try {
                sanitize(mutate(current))
            } catch (_: Exception) {
                return current
            }
            current = next
            persistLocked(next)
        }
        listeners.forEach { listener ->
            try {
                listener(next)
            } catch (_: Exception) {
                // 监听器异常不能影响保存
            }
        }
        return next
    }

    fun set(settings: AppSettings): AppSettings = update { settings }

    /** 夹取非法数值（与 C# SettingsStore.Normalize 一致）。 */
    private fun sanitize(s: AppSettings): AppSettings = s.copy(
        pollIntervalMs = when {
            s.pollIntervalMs < 50 -> 400
            s.pollIntervalMs > 10_000 -> 10_000
            else -> s.pollIntervalMs
        },
        maxItems = s.maxItems.coerceIn(50, 100_000),
        theme = if (s.theme in VALID_THEMES) s.theme else "auto",
    )

    private fun load(): AppSettings {
        val text = FileIo.readTextOrNull(file)
        if (text.isNullOrBlank()) return AppSettings()

        val root = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            FileIo.backupAndRemoveCorrupt(file)
            return AppSettings()
        }
        if (!root.isJsonObject) {
            FileIo.backupAndRemoveCorrupt(file)
            return AppSettings()
        }

        val o = root.asJsonObject
        return sanitize(
            AppSettings(
                monitor = o.bool("monitor", true),
                autostart = o.bool("autostart", true),
                notify = o.bool("notify", true),
                dedupeByUrl = o.bool("dedupeByUrl", true),
                autostartService = o.bool("autostartService", true),
                bubble = o.bool("bubble", false),
                theme = o.str("theme"),
                pollIntervalMs = o.int("pollIntervalMs", 400),
                maxItems = o.int("maxItems", 5000),
                capturePlainLinks = o.bool("capturePlainLinks", false),
                autoDownload = o.str("autoDownload").ifEmpty { "off" },
                zipNamingRule = o.str("zipNamingRule").ifEmpty { "1" },
                downloadWifiOnly = o.bool("downloadWifiOnly", true),
                autoTrashAfterDownload = o.bool("autoTrashAfterDownload", true),
                autoTrashIgnoreCheck = o.bool("autoTrashIgnoreCheck", false),
                shizukuMonitor = o.bool("shizukuMonitor", false),
            )
        )
    }

    private fun persistLocked(settings: AppSettings) {
        try {
            FileIo.writeAtomic(file, gson.toJson(settings))
        } catch (_: Exception) {
            // 落盘失败不能影响主流程
        }
    }

    private companion object {
        val VALID_THEMES = setOf("auto", "light", "dark")
    }
}