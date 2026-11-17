package com.clipnest.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内部文件工具：原子写 + 坏文件备份。
 * 所有异常都在这里吞掉——落盘失败不能影响主流程。
 */
internal object FileIo {

    /** 读 UTF-8 文本；不存在/读失败返回 null。 */
    fun readTextOrNull(file: File): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }

    /** 临时文件 + rename 原子写。 */
    fun writeAtomic(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            // 忽略：落盘失败不能影响主流程
        }
    }

    /** 把坏文件复制成 `xxx.corrupt-yyyyMMdd-HHmmss`，返回备份文件（失败返回 null）。 */
    fun backupCorrupt(file: File): File? = try {
        if (!file.isFile) {
            null
        } else {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val backup = File(file.parentFile, file.name + ".corrupt-" + stamp)
            file.copyTo(backup, overwrite = true)
            backup
        }
    } catch (_: Exception) {
        null
    }

    /** 备份坏文件后删除原文件（避免下次 reload 重复备份），返回备份文件。 */
    fun backupAndRemoveCorrupt(file: File): File? {
        val backup = backupCorrupt(file) ?: return null
        try {
            file.delete()
        } catch (_: Exception) {
            // 删除失败就留着，备份已经完成
        }
        return backup
    }
}