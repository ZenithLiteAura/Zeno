package com.ZenithLiteAura.app.nezo.platform.download

import android.annotation.SuppressLint
import android.content.Context
import com.ZenithLiteAura.app.nezo.data.DownloadTaskUi
import com.ZenithLiteAura.app.nezo.platform.AppVisibility
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * 下载队列：串行执行任务（图片/N 张并发交给 [ZipWriter] 内部 3 路）、持久化 `files/clipnest/downloads.json`、
 * 支持取消 / 重试 / 清理已完成；对外只暴露 `StateFlow<List<DownloadTaskUi>>`。
 *
 * 执行由前台服务 [DownloadService] 驱动：`enqueue` → `DownloadService.start` → `drain()`。
 */
internal class DownloadQueue private constructor(private val context: Context) {

    companion object {
        /** 已完成（done/failed/canceled）任务最多保留条数 */
        private const val MAX_FINISHED = 50

        /** 抖音图片直链带签名、有时效：超过这个时间就当作已过期，别浪费流量硬试 */
        const val STALE_LINK_MS = 10 * 60 * 1000L

        // 全局单例只持有 applicationContext（不会泄漏 Activity），跨进程生命周期复用同一份队列
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DownloadQueue? = null

        fun get(context: Context): DownloadQueue {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: DownloadQueue(context.applicationContext).also { instance = it }
            }
        }

        /**
         * App 回到前台时补跑排队任务。
         * `dataSync` 前台服务在 Android 12+ 不允许后台启动，所以后台只在队列里躺着，回前台再跑。
         * MainActivity.onStart 调一次即可（幂等）。
         */
        fun kickPending(context: Context) {
            runCatching {
                val queue = get(context)
                if (!queue.hasPending()) return@runCatching
                if (!AppVisibility.isForeground) {
                    PlatformLog.info("下载任务排队中（应用在后台），等回到前台再启动服务")
                    return@runCatching
                }
                DownloadService.start(context)
                PlatformLog.info("已启动下载服务（排队 ${queue.queuedCount()} 个任务）")
            }.onFailure { PlatformLog.warn("补跑下载任务失败：${it.message}") }
        }
    }

    private val lock = Any()
    private val jobs = ArrayList<DownloadJob>()
    private val drainLock = Mutex()
    private val gson = Gson()

    private val _tasks = MutableStateFlow<List<DownloadTaskUi>>(emptyList())
    val tasks: StateFlow<List<DownloadTaskUi>> = _tasks.asStateFlow()

    private var lastPersistAt = 0L

    init {
        load()
    }

    // ── 入队 ──────────────────────────────────────────────────────────────

    fun enqueueImageZip(awemeId: String, title: String, pics: List<String>, rule: Int): String =
        enqueueJob(
            DownloadJob(
                id = newId(),
                awemeId = awemeId,
                title = title,
                kind = DownloadKind.IMAGE_ZIP,
                rule = rule,
                pics = pics,
            )
        )

    fun enqueueVideoZip(awemeId: String, title: String, videoUrl: String, rule: Int, quality: String = ""): String =
        enqueueJob(
            DownloadJob(
                id = newId(),
                awemeId = awemeId,
                title = title,
                kind = DownloadKind.VIDEO_ZIP,
                rule = rule,
                videoUrl = videoUrl,
                videoQuality = quality,
            )
        )

    fun enqueueImage(awemeId: String, title: String, picUrl: String, rule: Int, seq: Int = 1): String =
        enqueueJob(
            DownloadJob(
                id = newId(),
                awemeId = awemeId,
                title = title,
                kind = DownloadKind.IMAGE,
                rule = rule,
                pics = listOf(picUrl),
                seq = seq.coerceAtLeast(1),
            )
        )

    fun enqueueVideo(awemeId: String, title: String, videoUrl: String, quality: String = ""): String =
        enqueueJob(
            DownloadJob(
                id = newId(),
                awemeId = awemeId,
                title = title,
                kind = DownloadKind.VIDEO,
                rule = 3,
                videoUrl = videoUrl,
                videoQuality = quality,
            )
        )

    private fun enqueueJob(job: DownloadJob): String {
        synchronized(lock) {
            jobs.add(0, job)
            trimLocked()
        }
        publish()
        persist(force = true)
        PlatformLog.info("下载入队：${job.kind.wire} 「${job.title.take(24)}」id=${job.id}")
        maybeStartService()
        return job.id
    }

    /**
     * 只在前台才启动下载服务：Android 12+ 后台启动 `dataSync` 前台服务会被系统直接拒绝
     * （`ForegroundServiceStartNotAllowedException`），任务留在 queued，等回前台再跑。
     */
    private fun maybeStartService() {
        if (AppVisibility.isForeground) {
            DownloadService.start(context)   // 内部已 try/catch，失败也只会留在队列里
        } else {
            PlatformLog.info("应用在后台：任务保持排队，等回到前台再下载（排队 ${queuedCount()} 个）")
        }
    }

    // ── 取消 / 重试 / 清理 ────────────────────────────────────────────────

    fun cancel(taskId: String) {
        val target = synchronized(lock) { jobs.firstOrNull { it.id == taskId } } ?: return
        if (target.state.finished) return

        target.cancelRequested = true
        if (target.state == DownloadState.QUEUED) {
            target.state = DownloadState.CANCELED
            publish()
            persist(force = true)
            PlatformLog.info("已取消排队任务：$taskId")
        }
        // RUNNING 的任务由 DownloadRunner 轮询 cancelRequested 后自行收尾
    }

    fun cancelRunning() {
        runningJob()?.let { cancel(it.id) }
    }

    fun retry(taskId: String): Boolean {
        val target = synchronized(lock) { jobs.firstOrNull { it.id == taskId } } ?: return false
        if (!target.state.finished) return false

        target.resetForRetry()
        synchronized(lock) {
            jobs.remove(target)
            jobs.add(0, target)
            trimLocked()
        }
        publish()
        persist(force = true)
        PlatformLog.info("重试任务：$taskId")
        maybeStartService()
        return true
    }

    fun clearFinished() {
        synchronized(lock) { jobs.removeAll { it.state.finished } }
        publish()
        persist(force = true)
        PlatformLog.info("已清理完成/失败任务")
    }

    fun hasPending(): Boolean = synchronized(lock) { jobs.any { it.state == DownloadState.QUEUED } }

    fun queuedCount(): Int = synchronized(lock) { jobs.count { it.state == DownloadState.QUEUED } }

    fun hasActiveFor(awemeId: String): Boolean = synchronized(lock) {
        awemeId.isNotBlank() && jobs.any {
            it.awemeId == awemeId && (it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING)
        }
    }

    fun runningJob(): DownloadJob? = synchronized(lock) { jobs.firstOrNull { it.state == DownloadState.RUNNING } }

    // ── 执行 ──────────────────────────────────────────────────────────────

    /**
     * 串行跑完所有排队任务；被 [DownloadService] 调用。
     * 并发保护：同一时刻只允许一个 drain，重复调用直接返回（在跑的那个会接上新任务）。
     */
    suspend fun drain() {
        if (!drainLock.tryLock()) return
        try {
            while (true) {
                val job = nextQueued() ?: break

                job.cancelRequested = false
                job.state = DownloadState.RUNNING
                job.startedAt = System.currentTimeMillis()
                job.error = ""
                job.savedUri = ""
                job.done = 0
                job.total = job.plannedTotal.coerceAtLeast(1)
                job.bytes = 0L
                job.speedPerSec = 0L
                publish()
                persist(force = true)

                try {
                    // 抖音图片直链带签名、有时效，排太久再下基本 403：直接给出明确文案，让用户重新解析
                    val age = System.currentTimeMillis() - job.createdAt
                    if (age > STALE_LINK_MS) {
                        throw DownloadError(
                            "解析结果已超过 ${age / 60_000} 分钟，下载链接可能已过期，请重新解析后再下载"
                        )
                    }
                    DownloadRunner.run(context, job)
                    if (job.cancelRequested) {
                        job.state = DownloadState.CANCELED
                        PlatformLog.info("任务已取消：${job.id}")
                    } else {
                        job.done = job.total.coerceAtLeast(job.done)
                        job.state = DownloadState.DONE
                        PlatformLog.info("任务完成：${job.id} uri=${job.savedUri}")
                    }
                } catch (t: CancellationException) {
                    job.state = DownloadState.CANCELED
                    if (!job.cancelRequested) {
                        // 不是用户取消，而是服务/协程被销毁：记好状态后继续抛出，正常退出循环
                        PlatformLog.info("任务因服务停止而中断：${job.id}")
                        throw t
                    }
                    // 用户主动取消：只结束当前任务，继续跑后面的
                    PlatformLog.info("任务已按用户请求取消：${job.id}")
                } catch (t: Throwable) {
                    job.state = DownloadState.FAILED
                    job.error = DownloadSupport.errorMessage(t)
                    PlatformLog.error("任务失败：${job.id}「${job.title.take(24)}」", t)
                } finally {
                    job.cancelRequested = false
                    job.speedPerSec = 0L
                    publish()
                    persist(force = true)
                    runCatching { DownloadNotifier.notifyResult(context, job) }
                }
            }
        } finally {
            drainLock.unlock()
        }
    }

    private fun nextQueued(): DownloadJob? = synchronized(lock) {
        jobs.filter { it.state == DownloadState.QUEUED }.minByOrNull { it.createdAt }
    }

    /** [DownloadRunner.ProgressReporter] 的回调：刷新 UI + 限流落盘 */
    fun progressChanged(job: DownloadJob) {
        publish()
        persist(force = false)
    }

    // ── 状态发布 / 持久化 ─────────────────────────────────────────────────

    private fun publish() {
        val snapshot = synchronized(lock) { jobs.map { it.toUi() } }
        _tasks.value = snapshot
    }

    private fun persist(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < 3000L) return
        lastPersistAt = now

        val snapshot = synchronized(lock) { jobs.map { it.toJson() } }
        val root = JsonObject().apply {
            addProperty("version", 1)
            add("jobs", JsonArray().apply { snapshot.forEach { add(it) } })
        }
        DownloadSupport.writeAtomic(DownloadSupport.queueFile(context), gson.toJson(root))
    }

    private fun load() {
        runCatching {
            val text = DownloadSupport.readText(DownloadSupport.queueFile(context)) ?: return@runCatching
            val root = JsonParser.parseString(text)
            if (!root.isJsonObject) return@runCatching
            val array = root.asJsonObject.getAsJsonArray("jobs") ?: return@runCatching

            array.forEach { element ->
                val job = runCatching {
                    if (element.isJsonObject) DownloadJob.fromJson(element.asJsonObject) else null
                }.getOrNull()
                if (job != null) synchronized(lock) { jobs.add(job) }
            }

            synchronized(lock) {
                jobs.sortByDescending { it.createdAt }
                trimLocked()
            }
            publish()
            PlatformLog.info("下载队列已恢复：${jobs.size} 条（排队 ${jobs.count { it.state == DownloadState.QUEUED }}）")
        }.onFailure { PlatformLog.warn("下载队列读取失败：${it.message}") }
    }

    /** 只保留最近 [MAX_FINISHED] 条已完成任务，未完成的一律保留 */
    private fun trimLocked() {
        val finished = jobs.filter { it.state.finished }
        if (finished.size <= MAX_FINISHED) return
        val keep = finished.sortedByDescending { it.createdAt }.take(MAX_FINISHED).map { it.id }.toSet()
        jobs.removeAll { it.state.finished && it.id !in keep }
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")
}