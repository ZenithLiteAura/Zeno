package com.ZenithLiteAura.app.nezo.platform

import android.util.Log
import com.ZenithLiteAura.app.nezo.data.ClipRepo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 平台层日志：Logcat + 数据目录 clipnest.log。
 * 日志失败绝不影响主流程；文件超过 512KB 自动清空重来。
 */
object PlatformLog {

    private const val TAG = "ClipNest"
    private val FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun info(message: String) = write("I", message, null)

    fun warn(message: String) = write("W", message, null)

    fun error(message: String, throwable: Throwable? = null) = write("E", message, throwable)

    private fun write(level: String, message: String, throwable: Throwable?) {
        runCatching {
            when (level) {
                "E" -> Log.e(TAG, message, throwable)
                "W" -> Log.w(TAG, message, throwable)
                else -> Log.i(TAG, message)
            }
        }

        runCatching {
            val file = ClipRepo.logFile
            file.parentFile?.mkdirs()
            if (file.isFile && file.length() > 512L * 1024L) file.writeText("")
            val detail = throwable?.let { " | ${it::class.java.simpleName}: ${it.message}" } ?: ""
            file.appendText("[${FORMAT.format(Date())}] [$level] $message$detail\n")
        }
    }
}