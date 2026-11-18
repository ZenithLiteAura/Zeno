package com.ZenithLiteAura.app.nezo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 【冻结契约 · Lead 所有】下载模块的面向上层的数据与动作。
 *
 * UI（ui-web）只依赖本文件里的类型；实现（host-shell）在
 * `platform/download/DownloadControllerImpl.kt`，由 MainActivity 注入。
 * 改签名前先找 Lead。
 */

/** 一次解析结果（抖音作品） */
data class DownloadWorkUi(
    val ok: Boolean = false,
    val awemeId: String = "",
    /** image | video */
    val type: String = "",
    val title: String = "",
    val author: String = "",
    val picCount: Int = 0,
    val coverUrl: String = "",
    val videoUrl: String = "",
    val videoQuality: String = "",
    val videoSize: Long = 0,
    val message: String = "",
    /** 可选清晰度（从高到低）；图片作品为空 */
    val qualities: List<com.clipnest.core.platform.MediaQuality> = emptyList(),
    /** 当前选中的清晰度 label（默认 = 平台推荐项） */
    val selectedQuality: String = "",
)

/** 一个下载任务（ZIP 或单文件） */
data class DownloadTaskUi(
    val id: String = "",
    val awemeId: String = "",
    val title: String = "",
    /** image_zip | video_zip | image | video */
    val kind: String = "",
    /** queued | running | done | failed | canceled */
    val state: String = "queued",
    val done: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0,
    /** 当前速度（字节/秒），0 表示未知 */
    val bytesPerSec: Long = 0,
    val error: String = "",
    /** 完成后的 MediaStore uri（字符串），供「打开」用 */
    val savedUri: String = "",
    val createdAt: Long = 0,
)

interface DownloadController {
    /** 最近一次解析结果；未解析为 null */
    val work: StateFlow<DownloadWorkUi?>
    val resolving: StateFlow<Boolean>
    val tasks: StateFlow<List<DownloadTaskUi>>

    /** 解析一段链接/分享文案（异步，结果回 [work]） */
    fun resolve(text: String)

    /** 清掉当前解析结果 */
    fun clearWork()

    /** 下载所有图片并打包 ZIP（rule = 1..4，见 ZipNamingRule） */
    fun downloadImagesZip(rule: Int)

    /** 切换下载清晰度（label 需来自 work.qualities）；切换只影响之后的下载 */
    fun selectQuality(label: String)

    /** 下载视频并打包 ZIP */
    fun downloadVideoZip(rule: Int)

    /** 逐个保存图片（不打包） */
    fun downloadImagesIndividually()

    /** 直接保存视频 mp4 */
    fun downloadVideo()

    fun cancel(taskId: String)

    /** 重试失败/已取消的任务；返回是否成功入队 */
    fun retry(taskId: String): Boolean

    fun clearFinished()

    /** 打开已保存的文件 */
    fun openSaved(savedUri: String)
}

/**
 * 占位实现：host-shell 的 [DownloadControllerImpl] 落地前，界面能正常编译/运行。
 * 所有动作只更新一个提示式的空结果，不触网。
 */
object StubDownloadController : DownloadController {

    private val _work = MutableStateFlow<DownloadWorkUi?>(null)
    override val work: StateFlow<DownloadWorkUi?> = _work.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    override val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val _tasks = MutableStateFlow<List<DownloadTaskUi>>(emptyList())
    override val tasks: StateFlow<List<DownloadTaskUi>> = _tasks.asStateFlow()

    override fun resolve(text: String) {
        _work.value = DownloadWorkUi(ok = false, message = "下载模块尚未就绪")
    }

    override fun clearWork() {
        _work.value = null
    }

    override fun downloadImagesZip(rule: Int) = Unit
    override fun selectQuality(label: String) = Unit
    override fun downloadVideoZip(rule: Int) = Unit
    override fun downloadImagesIndividually() = Unit
    override fun downloadVideo() = Unit
    override fun cancel(taskId: String) = Unit
    override fun retry(taskId: String): Boolean = false
    override fun clearFinished() = Unit
    override fun openSaved(savedUri: String) = Unit

    internal fun setWork(value: DownloadWorkUi?) {
        _work.value = value
    }
}