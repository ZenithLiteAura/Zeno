package com.ZenithLiteAura.app.nezo.platform.download

import com.ZenithLiteAura.app.nezo.data.DownloadTaskUi
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** 任务类型（与 DownloadTaskUi.kind 的 wire 值一致） */
internal enum class DownloadKind(val wire: String) {
    IMAGE_ZIP("image_zip"),
    VIDEO_ZIP("video_zip"),
    IMAGE("image"),
    VIDEO("video");

    companion object {
        fun fromWire(value: String): DownloadKind? = entries.firstOrNull { it.wire == value }
    }
}

/** 任务状态（与 DownloadTaskUi.state 的 wire 值一致） */
internal enum class DownloadState(val wire: String) {
    QUEUED("queued"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    CANCELED("canceled");

    val finished: Boolean get() = this == DONE || this == FAILED || this == CANCELED

    companion object {
        fun fromWire(value: String): DownloadState? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * 内部任务模型：比 DownloadTaskUi 多带下载所需的源数据（图片列表 / 视频地址 / 命名规则），
 * 因为要能持久化到 downloads.json 并在应用重启后重试。
 */
internal class DownloadJob(
    val id: String,
    val awemeId: String,
    val title: String,
    val kind: DownloadKind,
    val rule: Int = 1,
    val pics: List<String> = emptyList(),
    val videoUrl: String = "",
    val videoQuality: String = "",
    val coverUrl: String = "",
    /** 逐张下载时的序号（1 起），用于 01_/02_ 命名；ZIP 任务恒为 1 */
    val seq: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
) {
    @Volatile
    var state: DownloadState = DownloadState.QUEUED

    @Volatile
    var done: Int = 0

    @Volatile
    var total: Int = 0

    @Volatile
    var bytes: Long = 0L

    @Volatile
    var error: String = ""

    @Volatile
    var savedUri: String = ""

    /** 最近一次测到的速度（字节/秒），只用于通知展示 */
    @Volatile
    var speedPerSec: Long = 0L

    @Volatile
    var cancelRequested: Boolean = false

    @Volatile
    var startedAt: Long = 0L

    /** 预计算的总条目数，进 RUNNING 时写入 total */
    val plannedTotal: Int
        get() = when (kind) {
            DownloadKind.IMAGE_ZIP -> pics.size
            else -> 1
        }

    fun resetForRetry() {
        state = DownloadState.QUEUED
        done = 0
        total = 0
        bytes = 0L
        error = ""
        savedUri = ""
        speedPerSec = 0L
        cancelRequested = false
        startedAt = 0L
    }

    fun toUi(): DownloadTaskUi = DownloadTaskUi(
        id = id,
        awemeId = awemeId,
        title = title,
        kind = kind.wire,
        state = state.wire,
        done = done,
        total = total,
        bytes = bytes,
        error = error,
        savedUri = savedUri,
        createdAt = createdAt,
    )

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("awemeId", awemeId)
        addProperty("title", title)
        addProperty("kind", kind.wire)
        addProperty("rule", rule)
        add("pics", JsonArray().apply { pics.forEach { add(it) } })
        addProperty("videoUrl", videoUrl)
        addProperty("videoQuality", videoQuality)
        addProperty("coverUrl", coverUrl)
        addProperty("seq", seq)
        addProperty("state", state.wire)
        addProperty("done", done)
        addProperty("total", total)
        addProperty("bytes", bytes)
        addProperty("error", error)
        addProperty("savedUri", savedUri)
        addProperty("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JsonObject): DownloadJob? {
            val id = o.stringOf("id")
            if (id.isBlank()) return null
            val kind = DownloadKind.fromWire(o.stringOf("kind")) ?: return null
            val pics = runCatching {
                o.getAsJsonArray("pics")?.mapNotNull { element ->
                    runCatching { element.asString }.getOrNull()?.takeIf { it.isNotBlank() }
                }
            }.getOrNull() ?: emptyList()

            val job = DownloadJob(
                id = id,
                awemeId = o.stringOf("awemeId"),
                title = o.stringOf("title"),
                kind = kind,
                rule = o.intOf("rule", 1),
                pics = pics,
                videoUrl = o.stringOf("videoUrl"),
                videoQuality = o.stringOf("videoQuality"),
                coverUrl = o.stringOf("coverUrl"),
                seq = o.intOf("seq", 1),
                createdAt = o.longOf("createdAt", System.currentTimeMillis()),
            )

            val savedState = DownloadState.fromWire(o.stringOf("state")) ?: DownloadState.QUEUED
            job.done = o.intOf("done", 0)
            job.total = o.intOf("total", 0)
            job.bytes = o.longOf("bytes", 0L)
            job.error = o.stringOf("error")
            job.savedUri = o.stringOf("savedUri")

            // 上次进程被杀时还在 running 的任务：标记为中断失败，避免永远转圈
            job.state = if (savedState == DownloadState.RUNNING) {
                job.error = "应用重启，下载已中断"
                DownloadState.FAILED
            } else {
                savedState
            }
            return job
        }

        private fun JsonObject.stringOf(name: String): String =
            runCatching { if (has(name) && !get(name).isJsonNull) get(name).asString else "" }
                .getOrDefault("")

        private fun JsonObject.intOf(name: String, fallback: Int): Int =
            runCatching { if (has(name) && !get(name).isJsonNull) get(name).asInt else fallback }
                .getOrDefault(fallback)

        private fun JsonObject.longOf(name: String, fallback: Long): Long =
            runCatching { if (has(name) && !get(name).isJsonNull) get(name).asLong else fallback }
                .getOrDefault(fallback)
    }
}