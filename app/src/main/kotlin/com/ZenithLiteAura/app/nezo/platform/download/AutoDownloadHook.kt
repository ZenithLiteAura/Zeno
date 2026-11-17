package com.ZenithLiteAura.app.nezo.platform.download

import android.content.Context
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import com.clipnest.core.ClipItem
import com.clipnest.core.douyin.DouyinLinks
import com.clipnest.core.platform.PlatformResolvers
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动下载钩子：[com.ZenithLiteAura.app.nezo.platform.CaptureEngine] 收录成功后调用。
 *
 * 触发条件（全部满足）：
 * 1. `settings.autoDownload != "off"`；
 * 2. 作品平台是抖音；
 * 3. `downloadWifiOnly` 打开时必须连的是 Wi-Fi；
 * 4. 该 `awemeId` 不在 `files/clipnest/auto_downloaded.json`（最多 200 条）里。
 *
 * 命中后异步解析并入队：`images` 档只下图片 ZIP；`all` 档图片作品下图片 ZIP、视频作品下视频 ZIP。
 * 解析/网络失败不标记已下载（10 分钟内不重复重试），避免反复骚扰。
 */
object AutoDownloadHook {

    private const val FAILURE_COOLDOWN_MS = 10 * 60 * 1000L
    private const val MAX_REMEMBER_FAILURES = 100

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentFailures = ConcurrentHashMap<String, Long>()

    @Volatile
    private var storeInstance: AutoDownloadedStore? = null

    /** 采集成功后调用；任何异常都在内部收敛，绝不影响采集主流程。 */
    fun onCaptured(context: Context, item: ClipItem) {
        runCatching { handle(context.applicationContext, item) }
            .onFailure { PlatformLog.error("自动下载钩子异常", it) }
    }

    private fun handle(context: Context, item: ClipItem) {
        runCatching { ClipRepo.init(context) }
        val settings = runCatching { ClipRepo.settings.value }.getOrNull() ?: return

        val mode = settings.autoDownload.lowercase().ifBlank { "off" }
        if (mode == "off") return
        if (!item.platform.equals("douyin", ignoreCase = true)) return

        if (!DownloadSupport.hasInternet(context)) {
            PlatformLog.info("自动下载跳过：当前无网络")
            return
        }
        if (settings.downloadWifiOnly && !DownloadSupport.isWifiConnected(context)) {
            PlatformLog.info("自动下载跳过：设置为仅 Wi-Fi 自动下载")
            return
        }

        val raw = item.raw.ifBlank { item.url }
        if (raw.isBlank()) return

        val knownId = runCatching { DouyinLinks.extractAwemeId(raw) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { DouyinLinks.extractAwemeId(item.url) }.getOrNull()?.takeIf { it.isNotBlank() }

        val downloaded = storeFor(context)
        if (knownId != null) {
            if (downloaded.contains(knownId)) {
                PlatformLog.info("自动下载跳过：作品 $knownId 已自动下载过")
                return
            }
            val failedAt = recentFailures[knownId]
            if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS) {
                PlatformLog.info("自动下载跳过：作品 $knownId 刚失败过，冷却中")
                return
            }
        }

        PlatformLog.info("自动下载触发：mode=$mode awemeId=${knownId ?: "待解析"}")
        scope.launch { resolveAndEnqueue(context, raw, knownId, mode, settings.zipNamingRule) }
    }

    private suspend fun resolveAndEnqueue(
        context: Context,
        raw: String,
        knownId: String?,
        mode: String,
        ruleText: String,
    ) {
        val work = runCatching { PlatformResolvers.resolve(raw).toDouyinWork() }
            .onFailure { PlatformLog.warn("自动下载解析异常：${it.message}") }
            .getOrNull()

        if (work == null || !work.ok) {
            knownId?.let(::rememberFailure)
            PlatformLog.warn("自动下载解析失败：code=${work?.code ?: "exception"} message=${work?.message ?: ""}")
            return
        }

        val awemeId = work.awemeId.ifBlank { knownId.orEmpty() }
        if (awemeId.isBlank()) {
            PlatformLog.warn("自动下载跳过：解析结果里没有 awemeId")
            return
        }

        val downloaded = storeFor(context)
        if (downloaded.contains(awemeId)) {
            PlatformLog.info("自动下载跳过：作品 $awemeId 已自动下载过")
            return
        }

        val queue = DownloadQueue.get(context)
        if (queue.hasActiveFor(awemeId)) {
            PlatformLog.info("自动下载跳过：作品 $awemeId 已在下载队列中")
            return
        }

        val rule = ruleText.toIntOrNull()?.coerceIn(1, 4) ?: 1
        val title = work.title.ifBlank { work.authorName }

        var enqueued = false
        when {
            work.type == "image" && work.pics.isNotEmpty() -> {
                queue.enqueueImageZip(awemeId, title, work.pics, rule)
                enqueued = true
            }

            work.type == "video" && mode == "all" && work.videoUrl.isNotBlank() -> {
                queue.enqueueVideoZip(awemeId, title, work.videoUrl, rule, work.videoQuality)
                enqueued = true
            }

            work.type == "video" -> {
                PlatformLog.info("自动下载跳过：仅图片模式下不自动下载视频作品")
            }

            else -> {
                PlatformLog.info("自动下载跳过：没有可下载媒体（type=${work.type} pics=${work.pics.size}）")
            }
        }

        if (enqueued) {
            downloaded.add(awemeId)
            PlatformLog.info("自动下载已入队：awemeId=$awemeId type=${work.type} mode=$mode")
        }
    }

    private fun rememberFailure(awemeId: String) {
        if (awemeId.isBlank()) return
        recentFailures[awemeId] = System.currentTimeMillis()
        if (recentFailures.size > MAX_REMEMBER_FAILURES) {
            val deadline = System.currentTimeMillis() - FAILURE_COOLDOWN_MS
            recentFailures.entries.removeAll { it.value < deadline }
        }
    }

    private fun storeFor(context: Context): AutoDownloadedStore {
        storeInstance?.let { return it }
        return synchronized(this) {
            storeInstance ?: AutoDownloadedStore(DownloadSupport.autoDownloadedFile(context)).also {
                storeInstance = it
            }
        }
    }
}

/** 自动下载过的作品 id（最多 200 条，先进先出淘汰），落在 `files/clipnest/auto_downloaded.json`。 */
internal class AutoDownloadedStore(private val file: File) {

    companion object {
        private const val MAX_IDS = 200
    }

    private val lock = Any()
    private val ids = LinkedHashSet<String>()

    init {
        load()
    }

    fun contains(awemeId: String): Boolean = synchronized(lock) { ids.contains(awemeId) }

    fun add(awemeId: String) {
        if (awemeId.isBlank()) return
        synchronized(lock) {
            ids.remove(awemeId)
            ids.add(awemeId)
            while (ids.size > MAX_IDS) {
                val oldest = ids.firstOrNull() ?: break
                ids.remove(oldest)
            }
        }
        persist()
    }

    private fun load() {
        runCatching {
            val text = DownloadSupport.readText(file) ?: return@runCatching
            val root = JsonParser.parseString(text)
            when {
                root.isJsonArray -> root.asJsonArray.forEach { addLoaded(it) }
                root.isJsonObject -> root.asJsonObject.getAsJsonArray("ids")?.forEach { addLoaded(it) }
                else -> Unit
            }
        }.onFailure { PlatformLog.warn("自动下载记录读取失败：${it.message}") }
    }

    private fun addLoaded(element: com.google.gson.JsonElement) {
        val value = runCatching { element.asString }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        synchronized(lock) { ids.add(value) }
    }

    private fun persist() {
        runCatching {
            val array = JsonArray()
            synchronized(lock) { ids.forEach { array.add(it) } }
            val root = JsonObject().apply { add("ids", array) }
            DownloadSupport.writeAtomic(file, root.toString())
        }
    }
}