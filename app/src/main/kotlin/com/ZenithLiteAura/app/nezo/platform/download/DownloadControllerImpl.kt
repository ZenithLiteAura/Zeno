package com.ZenithLiteAura.app.nezo.platform.download

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.net.toUri
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import com.ZenithLiteAura.app.nezo.data.DownloadController
import com.ZenithLiteAura.app.nezo.data.DownloadTaskUi
import com.ZenithLiteAura.app.nezo.data.DownloadWorkUi
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import com.clipnest.core.douyin.DouyinWork
import com.clipnest.core.platform.PlatformResolvers
import com.clipnest.core.download.DownloadNaming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载模块的 UI 门面（MainActivity 注入，替换 `StubDownloadController`）。
 *
 * - `resolve` 异步调用 [DouyinResolver]（在 IO 线程，60s 预算），结果回 [work]；
 * - 下载动作只是「入队 + 拉起前台服务」，真正的下载在 [DownloadService] 里跑；
 * - `tasks` 直接来自 [DownloadQueue]，跨页面/跨进程重启都能保持。
 */
class DownloadControllerImpl(context: Context) : DownloadController {

    private val appContext: Context = context.applicationContext
    private val queue: DownloadQueue = DownloadQueue.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _work = MutableStateFlow<DownloadWorkUi?>(null)
    override val work: StateFlow<DownloadWorkUi?> = _work.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    override val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    override val tasks: StateFlow<List<DownloadTaskUi>> = queue.tasks

    /** 当前解析成功的结果（下载动作用它取图片/视频地址） */
    private var currentWork: DouyinWork? = null

    /** 当前选中的清晰度（null = 用解析结果默认项） */
    private var selectedQualityLabel: String? = null

    /** 实际用于下载的视频地址：优先选中清晰度，否则默认项 */
    private fun videoUrlFor(work: DouyinWork): String {
        val label = selectedQualityLabel
        if (label != null) {
            work.qualities.firstOrNull { it.label == label && !it.watermark }?.url?.let { return it }
            work.qualities.firstOrNull { it.label == label }?.url?.let { return it }
        }
        return work.videoUrl
    }

    override fun selectQuality(label: String) {
        val work = currentWork ?: return
        val hit = work.qualities.firstOrNull { it.label == label } ?: return
        selectedQualityLabel = hit.label
        _work.value = work.toUi().copy(
            selectedQuality = hit.label,
            videoQuality = hit.label,
            videoSize = hit.size.takeIf { it > 0 } ?: work.toUi().videoSize,
        )
        PlatformLog.info("切换清晰度：${hit.label}（watermark=${hit.watermark}）")
    }

    /** 本次解析完成时间：抖音图片直链有签名时效，超过 10 分钟提醒重新解析 */
    private var resolvedAt = 0L

    private var resolveSeq = 0
    private var resolveJob: Job? = null

    init {
        runCatching { ClipRepo.init(appContext) }
        // 应用在前台被创建：把上次没跑完的队列接上（后台启动 dataSync 前台服务会被系统拒绝）
        DownloadQueue.kickPending(appContext)
        PlatformLog.info("下载模块就绪（队列 ${queue.tasks.value.size} 条）")
    }

    /** MainActivity.onStart 可以调一次：把后台攒下的排队任务补跑起来（幂等） */
    fun kickPending() {
        DownloadQueue.kickPending(appContext)
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    override fun resolve(text: String) {
        val input = text.trim()
        if (input.isEmpty()) {
            currentWork = null
            _work.value = DownloadWorkUi(ok = false, message = "先粘贴作品分享链接或文案（抖音 / 小红书 / B站 / 快手）")
            return
        }

        resolveJob?.cancel()
        val seq = ++resolveSeq

        resolveJob = scope.launch {
            _resolving.value = true
            try {
                val resolved = withContext(Dispatchers.IO) { PlatformResolvers.resolve(input).toDouyinWork() }
                if (seq != resolveSeq) return@launch   // 已被更新的解析请求取代

                currentWork = resolved
                selectedQualityLabel = null
                resolvedAt = System.currentTimeMillis()
                _work.value = resolved.toUi()

                if (resolved.ok) {
                    PlatformLog.info(
                        "解析成功：type=${resolved.type} awemeId=${resolved.awemeId} " +
                            "pics=${resolved.pics.size} quality=${resolved.videoQuality} size=${resolved.videoSize}"
                    )
                } else {
                    PlatformLog.warn("解析失败：code=${resolved.code} message=${resolved.message}")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                PlatformLog.error("解析异常", t)
                if (seq == resolveSeq) {
                    currentWork = null
                    _work.value = DownloadWorkUi(ok = false, message = "解析失败：${t.message ?: "请稍后重试"}")
                }
            } finally {
                if (seq == resolveSeq) _resolving.value = false
            }
        }
    }

    override fun clearWork() {
        resolveJob?.cancel()
        resolveSeq++
        currentWork = null
        _work.value = null
        _resolving.value = false
    }

    // ── 下载动作 ──────────────────────────────────────────────────────────

    override fun downloadImagesZip(rule: Int) {
        val work = currentWork
        if (work == null || !work.ok) {
            toast("先解析作品再下载")
            return
        }
        if (work.pics.isEmpty()) {
            toast("这个作品没有图片")
            return
        }
        warnIfStale()

        runCatching {
            queue.enqueueImageZip(work.awemeId, titleOf(work), work.pics, rule.coerceIn(1, 4))
            toast("已加入队列：${work.pics.size} 张图片打包成 ZIP")
        }.onFailure {
            PlatformLog.error("图片 ZIP 入队失败", it)
            toast("加入下载队列失败：${it.message ?: "未知错误"}")
        }
    }

    override fun downloadVideoZip(rule: Int) {
        val work = currentWork
        if (work == null || !work.ok || videoUrlFor(work).isBlank()) {
            toast("这个作品没有可下载的视频")
            return
        }
        warnIfStale()

        runCatching {
            queue.enqueueVideoZip(work.awemeId, titleOf(work), videoUrlFor(work), rule.coerceIn(1, 4), selectedQualityLabel ?: work.videoQuality)
            toast("已加入队列：视频打包成 ZIP")
        }.onFailure {
            PlatformLog.error("视频 ZIP 入队失败", it)
            toast("加入下载队列失败：${it.message ?: "未知错误"}")
        }
    }

    override fun downloadImagesIndividually() {
        val work = currentWork
        if (work == null || !work.ok) {
            toast("先解析作品再下载")
            return
        }
        if (work.pics.isEmpty()) {
            toast("这个作品没有图片")
            return
        }
        warnIfStale()

        runCatching {
            val rule = runCatching { ClipRepo.settings.value.zipNamingRule.toIntOrNull() ?: 1 }.getOrDefault(1)
            work.pics.forEachIndexed { index, url ->
                queue.enqueueImage(work.awemeId, titleOf(work), url, rule.coerceIn(1, 4), seq = index + 1)
                // 多张图逐个入队时给个轻微序号区分，日志里能看出顺序
                PlatformLog.info("逐张入队 ${index + 1}/${work.pics.size}")
            }
            toast("已加入 ${work.pics.size} 个图片任务")
        }.onFailure {
            PlatformLog.error("逐张图片入队失败", it)
            toast("加入下载队列失败：${it.message ?: "未知错误"}")
        }
    }

    override fun downloadVideo() {
        val work = currentWork
        if (work == null || !work.ok || videoUrlFor(work).isBlank()) {
            toast("这个作品没有可下载的视频")
            return
        }
        warnIfStale()

        runCatching {
            queue.enqueueVideo(work.awemeId, titleOf(work), videoUrlFor(work), selectedQualityLabel ?: work.videoQuality)
            toast("已加入队列：保存视频")
        }.onFailure {
            PlatformLog.error("视频入队失败", it)
            toast("加入下载队列失败：${it.message ?: "未知错误"}")
        }
    }

    override fun cancel(taskId: String) {
        runCatching { queue.cancel(taskId) }
            .onFailure { PlatformLog.warn("取消失败：${it.message}") }
    }

    override fun retry(taskId: String): Boolean = runCatching {
        val ok = queue.retry(taskId)
        if (ok) DownloadQueue.kickPending(appContext)
        ok
    }.getOrDefault(false)

    override fun clearFinished() {
        runCatching { queue.clearFinished() }
            .onFailure { PlatformLog.warn("清理任务失败：${it.message}") }
    }

    // ── 打开已保存文件 ────────────────────────────────────────────────────

    override fun openSaved(savedUri: String) {
        if (savedUri.isBlank()) {
            toast("这个任务还没有保存结果")
            return
        }

        runCatching {
            val uri = savedUri.toUri()
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DownloadSupport.mimeOfName(savedUri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            appContext.startActivity(intent)
        }.onFailure {
            PlatformLog.warn("打开文件失败：${it.message}")
            toast("打不开文件：${it.message ?: "没有可用的查看器"}")
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private fun titleOf(work: DouyinWork): String =
        DownloadNaming.sanitizeTitle(
            work.title.ifBlank { work.authorName.ifBlank { "抖音作品" } }
        )

    /** 解析结果放太久（>10 分钟）时提醒：抖音图片直链带签名，过期会 403 */
    private fun warnIfStale() {
        if (resolvedAt <= 0L) return
        val age = System.currentTimeMillis() - resolvedAt
        if (age > DownloadQueue.STALE_LINK_MS) {
            toast("提示：解析结果已超过 ${age / 60_000} 分钟，下载链接可能过期，建议重新解析")
        }
    }

    private fun DouyinWork.toUi(): DownloadWorkUi = DownloadWorkUi(
        ok = ok,
        awemeId = awemeId,
        type = type,
        title = title,
        author = authorName,
        picCount = pics.size,
        coverUrl = coverUrl,
        videoUrl = videoUrl,
        videoQuality = videoQuality,
        videoSize = videoSize,
        message = message.ifBlank { if (ok) "" else codeMessage(code) },
        qualities = qualities,
        selectedQuality = selectedQuality.ifBlank { videoQuality },
    )

    private fun codeMessage(code: String): String = when (code) {
        "not_found" -> "作品不存在或已删除"
        "parse_failed" -> "解析失败，请稍后重试"
        "timeout" -> "解析超时，请检查网络后重试"
        "network" -> "网络异常，请检查网络"
        "no_media" -> "没有找到可下载的媒体"
        else -> "解析失败，请稍后重试"
    }

    private fun toast(message: String) {
        runCatching {
            val show = Runnable {
                runCatching { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) show.run()
            else Handler(Looper.getMainLooper()).post(show)
        }
    }
}