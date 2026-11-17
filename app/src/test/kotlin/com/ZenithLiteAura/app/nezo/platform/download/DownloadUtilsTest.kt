package com.ZenithLiteAura.app.nezo.platform.download

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 下载层纯逻辑单测（不触网、不依赖 Android 运行时）：
 * - ZIP 条目扩展名替换（core 用 .jpg/.mp4 占位，嗅探后要替换而不是追加）
 * - URL 扩展名 / MIME 推断
 * - 任务 JSON 往返（持久化恢复；上次 running 的任务应标记为中断失败）
 * - 自动下载记录 FIFO 淘汰（最多 200 条）
 */
class DownloadUtilsTest {

    // ── ZIP 条目扩展名 ────────────────────────────────────────────────────

    @Test
    fun `替换占位扩展名时保留目录与去重后缀`() {
        assertEquals(
            "所有图片/01_旅行日记.webp",
            ZipWriter.replaceExtension("所有图片/01_旅行日记.jpg", "webp"),
        )
        assertEquals(
            "所有图片/旅行日记_1.png",
            ZipWriter.replaceExtension("所有图片/旅行日记_1.jpg", "png"),
        )
        assertEquals(
            "所有视频/视频标题.mp4",
            ZipWriter.replaceExtension("所有视频/视频标题.mp4", "mp4"),
        )
    }

    @Test
    fun `条目没有扩展名时补上`() {
        assertEquals(
            "所有图片/01_标题.jpg",
            ZipWriter.replaceExtension("所有图片/01_标题", "jpg"),
        )
    }

    @Test
    fun `扩展名为空时原样返回`() {
        assertEquals("所有图片/01_标题.jpg", ZipWriter.replaceExtension("所有图片/01_标题.jpg", ""))
    }

    // ── URL / MIME ────────────────────────────────────────────────────────

    @Test
    fun `从带签名参数的 URL 取扩展名`() {
        assertEquals("webp", DownloadSupport.extensionOfUrl("https://p3-ex.douyinpic.com/a/b/c.webp?x=1&y=2"))
        assertEquals("jpg", DownloadSupport.extensionOfUrl("https://p3.douyinpic.com/a.jpg"))
        assertEquals("bin", DownloadSupport.extensionOfUrl("https://aweme.snssdk.com/aweme/v1/play/?video_id=abc"))
        assertEquals("bin", DownloadSupport.extensionOfUrl("not-a-url"))
    }

    @Test
    fun `MIME 推断`() {
        assertEquals("image/webp", DownloadSupport.mimeOf("webp"))
        assertEquals("video/mp4", DownloadSupport.mimeOf("mp4"))
        assertEquals("application/zip", DownloadSupport.mimeOf("zip"))
        assertEquals("image/webp", DownloadSupport.mimeOfName("content://media/external/images/media/42.webp"))
        assertEquals("video/mp4", DownloadSupport.mimeOfName("file:///sdcard/Movies/ClipNest/a.mp4"))
        assertEquals("*/*", DownloadSupport.mimeOfName("content://media/external/images/media/42"))
    }

    @Test
    fun `人类可读大小`() {
        assertEquals("0 B", DownloadSupport.formatSize(0))
        assertEquals("2 KB", DownloadSupport.formatSize(2048))
        assertEquals("2.0 MB", DownloadSupport.formatSize(2L * 1024 * 1024))
    }

    // ── 任务持久化 ────────────────────────────────────────────────────────

    @Test
    fun `任务 JSON 往返保留源数据`() {
        val job = DownloadJob(
            id = "job-1",
            awemeId = "7400000000000000001",
            title = "旅行日记",
            kind = DownloadKind.IMAGE_ZIP,
            rule = 2,
            pics = listOf("https://p3/1.webp", "https://p3/2.webp"),
            coverUrl = "https://p3/1.webp",
        )
        job.state = DownloadState.DONE
        job.done = 2
        job.total = 2
        job.bytes = 12345
        job.savedUri = "content://media/external/downloads/1"

        val roundTrip = DownloadJob.fromJson(JsonParser.parseString(job.toJson().toString()).asJsonObject)
        assertNotNull(roundTrip)
        requireNotNull(roundTrip)

        assertEquals("job-1", roundTrip.id)
        assertEquals("7400000000000000001", roundTrip.awemeId)
        assertEquals(DownloadKind.IMAGE_ZIP, roundTrip.kind)
        assertEquals(2, roundTrip.rule)
        assertEquals(listOf("https://p3/1.webp", "https://p3/2.webp"), roundTrip.pics)
        assertEquals(DownloadState.DONE, roundTrip.state)
        assertEquals("content://media/external/downloads/1", roundTrip.savedUri)
    }

    @Test
    fun `上次中断的 running 任务恢复为失败`() {
        val job = DownloadJob(
            id = "job-2",
            awemeId = "7400",
            title = "视频",
            kind = DownloadKind.VIDEO,
            videoUrl = "https://aweme.snssdk.com/aweme/v1/play/?video_id=abc",
        )
        job.state = DownloadState.RUNNING

        val restored = DownloadJob.fromJson(JsonParser.parseString(job.toJson().toString()).asJsonObject)
        requireNotNull(restored)

        assertEquals(DownloadState.FAILED, restored.state)
        assertTrue(restored.error.contains("中断"))
    }

    // ── 自动下载去重记录 ──────────────────────────────────────────────────

    @Test
    fun `自动下载记录最多 200 条且先进先出`() {
        val file = File.createTempFile("clipnest-auto-downloaded", ".json")
        file.delete()
        try {
            val store = AutoDownloadedStore(file)
            repeat(205) { index -> store.add("id$index") }

            assertTrue(store.contains("id204"))
            assertFalse(store.contains("id0"))
            assertFalse(store.contains("id4"))
            assertTrue(store.contains("id5"))
            assertTrue(file.isFile)

            // 重新加载后仍然记得
            val reloaded = AutoDownloadedStore(file)
            assertTrue(reloaded.contains("id204"))
            assertFalse(reloaded.contains("id0"))
        } finally {
            file.delete()
        }
    }
}