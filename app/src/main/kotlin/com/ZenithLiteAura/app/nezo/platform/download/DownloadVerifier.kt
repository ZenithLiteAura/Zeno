package com.ZenithLiteAura.app.nezo.platform.download

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import java.io.File

/**
 * 下载完成后的「校验 + 打标 + 自动归档」。
 *
 * 用户需求：下载完成后要有个特殊标识，然后校验产物，通过后把这条链接自动移入回收站（而不是直接删除）。
 *
 * 校验结果（写进 `ClipItem.downloadCheck`）：
 * - `ok`：产物存在且大小正常；
 * - `size_mismatch`：产物存在但明显偏小（视频按解析出的 expectedSize 判断截断）；
 * - `missing`：产物不存在 / 0 字节 / 拿不到 uri。
 *
 * 归档策略（`AppSettings`）：
 * - `autoTrashAfterDownload = true`（默认）且 `check == ok` → 移入回收站；
 * - `autoTrashIgnoreCheck = true` → 校验不通过也归档；
 * - 失败/取消的任务不动条目，只记日志。
 */
internal object DownloadVerifier {

    private const val TAG = "NezoDownload"

    /** 允许的实际大小 / 期望大小 下限比例（低于它视为被截断） */
    private const val MIN_SIZE_RATIO = 0.95

    /** 任务成功后的统一入口（被 DownloadQueue 在 state=DONE 时调用，内部全程 try/catch） */
    fun onJobFinished(context: Context, job: DownloadJob) {
        runCatching {
            if (job.state != DownloadState.DONE) {
                PlatformLog.info("下载未成功（${job.state.wire}），跳过校验与归档：task=${job.id}")
                return
            }

            val check = verify(context, job)
            PlatformLog.info(
                "$TAG 下载校验：task=${job.id} kind=${job.kind.wire} check=$check " +
                    "bytes=${job.bytes} expected=${job.expectedSize} zipEntries=${job.zipEntryCount} uri=${job.savedUri.take(70)}",
            )

            // 给「这次下载相关」的收件箱条目打标（可能 0 条：手动粘贴链接、条目已被删除等）
            val matchedIds = runCatching {
                ClipRepo.markDownloaded(job.sourceUrl, job.awemeId, check)
            }.getOrElse {
                PlatformLog.warn("$TAG 打下载标记失败：${it.message}")
                emptyList()
            }
            PlatformLog.info("$TAG 已打标 ${matchedIds.size} 条（sourceUrl=${job.sourceUrl.take(60)} awemeId=${job.awemeId}）")

            if (matchedIds.isEmpty()) return

            val settings = runCatching { ClipRepo.settings.value }.getOrNull() ?: return
            if (!settings.autoTrashAfterDownload) {
                PlatformLog.info("$TAG 未开启「下载后自动移入回收站」，保留在收件箱")
                return
            }
            if (check != "ok" && !settings.autoTrashIgnoreCheck) {
                PlatformLog.info("$TAG 校验未通过（$check）且未勾选「忽略校验」，不归档")
                return
            }

            var archived = 0
            matchedIds.forEach { id ->
                if (runCatching { ClipRepo.moveToTrash(id) }.getOrDefault(false)) archived++
            }
            job.archivedCount = archived
            PlatformLog.info("$TAG 已自动移入回收站 $archived 条（check=$check）")
        }.onFailure {
            PlatformLog.error("$TAG 下载完成处理失败（不影响下载结果）：${it.message}", it)
        }
    }

    /**
     * 校验产物，返回 `ok` / `size_mismatch` / `missing`。
     * 全程不抛异常：拿不到信息时按「能拿到的大小」判断，都拿不到才算 missing。
     */
    fun verify(context: Context, job: DownloadJob): String {
        if (job.savedUri.isBlank()) return "missing"

        val savedBytes = job.bytes
        val storedBytes = queryStoredSize(context, job.savedUri)
        // 媒体库元数据可能略滞后，取两者较大值作为「实际落盘大小」
        val actualBytes = maxOf(savedBytes, storedBytes)

        val isZip = job.kind == DownloadKind.IMAGE_ZIP || job.kind == DownloadKind.VIDEO_ZIP
        if (isZip) {
            if (job.zipEntryCount <= 0) {
                PlatformLog.warn("$TAG ZIP 条目数为 0（task=${job.id}）")
                return if (actualBytes > 0) "size_mismatch" else "missing"
            }
            return if (actualBytes > 0) "ok" else "missing"
        }

        if (actualBytes <= 0) return "missing"

        // 单文件：有期望大小时，明显偏小视为截断
        if (job.expectedSize > 0) {
            val ratio = actualBytes.toDouble() / job.expectedSize.toDouble()
            if (ratio < MIN_SIZE_RATIO) {
                PlatformLog.warn(
                    "$TAG 大小不符：actual=$actualBytes expected=${job.expectedSize} ratio=${
                        String.format(java.util.Locale.US, "%.3f", ratio)
                    }",
                )
                return "size_mismatch"
            }
        }
        return "ok"
    }

    /** 查产物大小：content:// 走 OpenableColumns.SIZE，file:// 直接 File.length()；失败返回 -1 */
    private fun queryStoredSize(context: Context, uriString: String): Long = runCatching {
        val uri = Uri.parse(uriString)
        when {
            uri.scheme.equals("file", ignoreCase = true) -> uri.path?.let { File(it).length() } ?: -1L

            else -> {
                val resolver = context.contentResolver
                val fromQuery = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
                    } ?: -1L
                }.getOrDefault(-1L)
                if (fromQuery > 0) {
                    fromQuery
                } else {
                    runCatching {
                        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    }.getOrDefault(-1L)
                }
            }
        }
    }.onFailure {
        PlatformLog.warn("$TAG 查询产物大小失败：${it.message}")
    }.getOrDefault(-1L)
}