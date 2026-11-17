package com.clipnest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 设置存储：缺字段默认值、数值夹取、主题校验、更新落盘/通知、mutate 异常回滚、坏文件备份。
 */
class SettingsStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newFile(name: String = "settings.json"): File = File(folder.root, name)

    @Test
    fun `文件不存在时使用默认设置`() {
        val store = SettingsStore(newFile())
        assertEquals(AppSettings(), store.current())
        assertFalse("构造时不应凭空写文件", newFile().exists())
    }

    @Test
    fun `老文件缺字段用默认值`() {
        val file = newFile()
        file.writeText("""{"monitor":false,"theme":"light"}""", Charsets.UTF_8)

        val current = SettingsStore(file).current()
        assertFalse(current.monitor)
        assertEquals("light", current.theme)
        assertTrue("缺字段 autostart 默认 true", current.autostart)
        assertTrue("缺字段 notify 默认 true", current.notify)
        assertTrue("缺字段 dedupeByUrl 默认 true", current.dedupeByUrl)
        assertTrue("缺字段 autostartService 默认 true", current.autostartService)
        assertFalse("缺字段 bubble 默认 false", current.bubble)
        assertEquals("缺字段 pollIntervalMs 默认 400", 400, current.pollIntervalMs)
        assertEquals("缺字段 maxItems 默认 5000", 5000, current.maxItems)
        assertFalse("缺字段 capturePlainLinks 默认 false", current.capturePlainLinks)
    }

    @Test
    fun `update 落盘、通知并可在新实例读回`() {
        val file = newFile()
        val store = SettingsStore(file)
        var events = 0
        store.addListener { events++ }

        val next = store.update { it.copy(monitor = false, pollIntervalMs = 250, maxItems = 800) }

        assertEquals(1, events)
        assertFalse(next.monitor)
        assertEquals(250, next.pollIntervalMs)
        assertEquals(800, next.maxItems)
        assertTrue("update 后应落盘", file.isFile)

        assertEquals(next, SettingsStore(file).current())
        assertEquals(next, store.current())
    }

    @Test
    fun `数值夹取与主题校验`() {
        val store = SettingsStore(newFile())

        assertEquals("pollIntervalMs < 50 回落 400", 400, store.update { it.copy(pollIntervalMs = 1) }.pollIntervalMs)
        assertEquals("pollIntervalMs > 10000 夹到 10000", 10_000, store.update { it.copy(pollIntervalMs = 99_999) }.pollIntervalMs)
        assertEquals("maxItems < 50 夹到 50", 50, store.update { it.copy(maxItems = 1) }.maxItems)
        assertEquals("maxItems > 100000 夹到 100000", 100_000, store.update { it.copy(maxItems = 9_999_999) }.maxItems)
        assertEquals("非法主题回落 auto", "auto", store.update { it.copy(theme = "purple") }.theme)
        assertEquals("合法主题保留", "dark", store.update { it.copy(theme = "dark") }.theme)
        assertEquals("合法主题保留", "light", store.update { it.copy(theme = "light") }.theme)
    }

    @Test
    fun `mutate 抛异常时回滚且不通知`() {
        val store = SettingsStore(newFile())
        val before = store.current()
        var events = 0
        store.addListener { events++ }

        val after = store.update { throw IllegalStateException("boom") }

        assertEquals(before, after)
        assertEquals(before, store.current())
        assertEquals(0, events)
    }

    @Test
    fun `set 直接覆盖`() {
        val store = SettingsStore(newFile())
        val target = AppSettings(monitor = false, bubble = true, maxItems = 123, theme = "dark")
        val saved = store.set(target)
        assertEquals(target.copy(maxItems = 123, theme = "dark"), saved)
        assertEquals(saved, store.current())
    }

    @Test
    fun `坏文件备份为 corrupt-时间戳 并回落默认值`() {
        val file = newFile("bad-settings.json")
        file.writeText("这不是 JSON", Charsets.UTF_8)

        val store = SettingsStore(file)
        assertEquals(AppSettings(), store.current())

        val backups = folder.root.listFiles()
            ?.filter { it.name.startsWith("bad-settings.json.corrupt-") }
            ?: emptyList()
        assertEquals("坏文件应备份一次", 1, backups.size)
        assertTrue(backups[0].readText().contains("这不是 JSON"))
    }
}