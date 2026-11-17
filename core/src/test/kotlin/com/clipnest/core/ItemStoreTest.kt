package com.clipnest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 收件箱存储：增删改查、去重、上限截断、原子持久化、坏文件备份、监听器异常吞掉、并发安全。
 * 全部使用 TemporaryFolder，绝不写用户真实目录。
 */
class ItemStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var seq = 0
    private val createdFiles = mutableListOf<File>()

    private fun newStore(maxItems: Int = 5000, dedupeByUrl: Boolean = true): ItemStore {
        val file = File(folder.root, "items-${seq++}.json")
        createdFiles += file
        return ItemStore(file, maxItems, dedupeByUrl)
    }

    private fun lastFile(): File = createdFiles.last()

    @Test
    fun `文件不存在时从空列表开始`() {
        val store = newStore()
        assertEquals(0, store.count())
        assertTrue(store.items().isEmpty())
        assertFalse("构造时不应凭空写文件", lastFile().exists())
    }

    @Test
    fun `新增插到最前并落盘`() {
        val store = newStore()
        val a = store.add(TestData.item("https://v.douyin.com/aaa/", "A", capturedAt = 1000))
        val b = store.add(TestData.item("https://v.douyin.com/bbb/", "B", capturedAt = 2000))

        assertTrue("空 id 应自动补 Guid", a.id.isNotEmpty())
        assertEquals(2, store.count())
        assertEquals("新的在前", b.id, store.items()[0].id)
        assertEquals(a.id, store.items()[1].id)
        assertTrue("add 后应落盘", lastFile().isFile)
        assertTrue("items.json 是 JSON 数组", lastFile().readText().trimStart().startsWith("["))

        val reloaded = ItemStore(lastFile(), 5000, true)
        assertEquals(2, reloaded.count())
        assertEquals(b.id, reloaded.items()[0].id)
    }

    @Test
    fun `按规范化 url 去重并更新时间移到最前`() {
        val store = newStore()
        val first = store.add(TestData.item("https://v.douyin.com/aaa/?utm_source=share&spm=xxx", "旧标题", capturedAt = 1000))
        val events = mutableListOf<Pair<ItemsChangeKind, ClipItem?>>()
        store.addListener { kind, item -> events += kind to item }

        val deduped = store.add(TestData.item("https://v.douyin.com/aaa/", "新标题", capturedAt = 2000))

        assertEquals("去重后不应新增", 1, store.count())
        assertEquals("返回已存在项", first.id, deduped.id)
        assertEquals("capturedAt 应更新", 2000L, deduped.capturedAt)
        assertEquals("标题应刷新", "新标题", deduped.title)
        assertEquals("url 应规范化", "https://v.douyin.com/aaa/", store.items()[0].url)
        assertEquals(ItemsChangeKind.Updated, events.single().first)
    }

    @Test
    fun `dedupeByUrl 关闭时允许重复链接`() {
        val store = newStore(dedupeByUrl = false)
        store.add(TestData.item("https://v.douyin.com/aaa/", "第一次", capturedAt = 1000))
        store.add(TestData.item("https://v.douyin.com/aaa/", "第二次", capturedAt = 2000))
        assertEquals(2, store.count())
    }

    @Test
    fun `超过 maxItems 从尾部截断`() {
        val store = newStore(maxItems = 3)
        repeat(5) { i ->
            store.add(TestData.item("https://example.com/$i", "T$i", capturedAt = 1000L + i))
        }
        assertEquals(3, store.count())
        assertEquals(
            listOf("https://example.com/4", "https://example.com/3", "https://example.com/2"),
            store.items().map { it.url },
        )

        val reloaded = ItemStore(lastFile(), 3, true)
        assertEquals(3, reloaded.count())
        assertEquals("https://example.com/4", reloaded.items()[0].url)
    }

    @Test
    fun `运行中调小 maxItems 在下一次写入时截断`() {
        val store = newStore(maxItems = 10)
        repeat(3) { i -> store.add(TestData.item("https://example.com/$i", "T$i", capturedAt = 1000L + i)) }
        store.maxItems = 1
        store.add(TestData.item("https://example.com/x", "X", capturedAt = 5000))
        assertEquals(1, store.count())
    }

    @Test
    fun `update 按 Id 覆盖并落盘`() {
        val store = newStore()
        val added = store.add(TestData.item("https://v.douyin.com/aaa/", "标题"))
        val edited = added.copy(title = "改过的标题", starred = true)

        assertTrue(store.update(edited))
        assertEquals(1, store.count())
        assertEquals("改过的标题", store.findById(added.id)?.title)
        assertTrue(store.findById(added.id)!!.starred)
        assertTrue(lastFile().readText().contains("改过的标题"))

        assertFalse("未知 id 返回 false", store.update(TestData.item("https://example.com/none")))
    }

    @Test
    fun `setStar 正常与幂等`() {
        val store = newStore()
        val added = store.add(TestData.item("https://v.douyin.com/aaa/", "标题"))
        var events = 0
        store.addListener { _, _ -> events++ }

        assertTrue(store.setStar(added.id, true))
        assertTrue(store.findById(added.id)!!.starred)
        assertEquals(1, events)

        assertTrue("同状态再设一次仍返回 true", store.setStar(added.id, true))
        assertEquals("同状态不应重复发事件", 1, events)
        assertFalse("未知 id 返回 false", store.setStar("no-such-id", true))
    }

    @Test
    fun `remove 与 clear`() {
        val store = newStore()
        val a = store.add(TestData.item("https://v.douyin.com/aaa/", "A"))
        val b = store.add(TestData.item("https://v.douyin.com/bbb/", "B"))
        val events = mutableListOf<ItemsChangeKind>()
        store.addListener { kind, _ -> events += kind }

        assertTrue(store.remove(b.id))
        assertEquals(1, store.count())
        assertEquals(ItemsChangeKind.Removed, events.last())
        assertFalse("重复删除返回 false", store.remove(b.id))

        store.clear()
        assertEquals(0, store.count())
        assertEquals(ItemsChangeKind.Cleared, events.last())
        assertEquals("[]", lastFile().readText().trim())
        assertEquals(0, ItemStore(lastFile(), 5000, true).count())

        assertNotNull(a.id)
    }

    @Test
    fun `监听器抛异常被吞掉`() {
        val store = newStore()
        store.addListener { _, _ -> throw IllegalStateException("listener boom") }
        val stored = store.add(TestData.item("https://example.com/x", "X"))
        assertEquals(1, store.count())
        assertTrue(store.setStar(stored.id, true))
        assertTrue(store.remove(stored.id))
    }

    @Test
    fun `坏文件备份为 corrupt-时间戳 并从空列表开始`() {
        val file = File(folder.root, "corrupt-items.json")
        file.writeText("{这不是合法的 JSON", Charsets.UTF_8)

        val store = ItemStore(file, 10, true)
        assertEquals(0, store.count())

        val backups = folder.root.listFiles()
            ?.filter { it.name.startsWith("corrupt-items.json.corrupt-") }
            ?: emptyList()
        assertEquals("坏文件应备份一次", 1, backups.size)
        assertTrue("备份内容应与原文件一致", backups[0].readText().contains("这不是合法的 JSON"))
        assertFalse("原文件应被移走，后续写入才能重建干净文件", file.exists())
    }

    @Test
    fun `reload 读取外部变更并触发 Reloaded`() {
        val store = newStore()
        store.add(TestData.item("https://v.douyin.com/old/", "旧"))
        assertEquals(1, store.count())

        lastFile().writeText(
            """{"items":[{"id":"abc","url":"https://example.com/ext","title":"外部写入","capturedAt":123}]}""",
            Charsets.UTF_8,
        )
        val events = mutableListOf<ItemsChangeKind>()
        store.addListener { kind, _ -> events += kind }

        store.reload()
        assertEquals(1, store.count())
        assertEquals("https://example.com/ext", store.items()[0].url)
        assertEquals("外部写入", store.items()[0].title)
        assertEquals(ItemsChangeKind.Reloaded, events.last())
    }

    @Test
    fun `兼容裸数组与包装格式`() {
        val bare = File(folder.root, "bare.json")
        bare.writeText(
            """[{"id":"1","url":"https://example.com/a","title":"裸数组","capturedAt":100}]""",
            Charsets.UTF_8,
        )
        assertEquals(1, ItemStore(bare, 10, true).count())

        val wrapped = File(folder.root, "wrapped.json")
        wrapped.writeText(
            """{"items":[{"id":"2","url":"https://example.com/b","title":"包装","capturedAt":200}]}""",
            Charsets.UTF_8,
        )
        assertEquals(1, ItemStore(wrapped, 10, true).count())
    }

    @Test
    fun `add 补全 id、规范化 url、clamp 时间与 source`() {
        val store = newStore()
        val raw = ClipItem(
            url = " https://Example.com/A/?utm_source=x ",
            title = "t",
            capturedAt = 0,
            source = "",
        )
        val stored = store.add(raw)
        assertEquals("https://example.com/A/", stored.url)
        assertTrue(stored.id.isNotEmpty())
        assertEquals("clipboard", stored.source)
        assertTrue(stored.capturedAt > 0)
    }

    @Test
    fun `items 返回快照`() {
        val store = newStore()
        store.add(TestData.item("https://example.com/1", "1"))
        val snapshot = store.items()
        store.add(TestData.item("https://example.com/2", "2"))
        assertEquals(1, snapshot.size)
        assertEquals(2, store.items().size)
    }

    @Test
    fun `并发 add 不丢数据`() {
        val store = newStore()
        val threads = 8
        val perThread = 25
        val startLatch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { t ->
                pool.submit {
                    startLatch.await()
                    repeat(perThread) { i ->
                        store.add(
                            TestData.item(
                                "https://example.com/c/$t-$i",
                                "T$t-$i",
                                capturedAt = 1000L + t * 100 + i,
                            ),
                        )
                    }
                }
            }
            startLatch.countDown()
            pool.shutdown()
            assertTrue("并发写入应在 30s 内完成", pool.awaitTermination(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * perThread, store.count())
        assertEquals("所有 url 均应保留", threads * perThread, store.items().map { it.url }.distinct().size)

        val reloaded = ItemStore(lastFile(), 5000, true)
        assertEquals("落盘结果应完整", threads * perThread, reloaded.count())
    }
}