package com.ZenithLiteAura.app.nezo

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自诊断：把未捕获异常落盘，并拉起一个「纯 Android 视图」的展示页。
 *
 * 为什么用纯 Android 视图而不是 Compose：如果崩溃发生在 Compose/MiUiX 组合期，
 * 再用 Compose 画崩溃页会二次崩溃。展示页还跑在独立进程 :crash 里，彻底隔离。
 */
object CrashGuard {

    private const val PREFS = "crash_guard"
    private const val KEY_LAST = "last_trace"
    private const val EXTRA_TRACE = "trace"
    private const val EXTRA_PATH = "path"

    @Volatile
    private var installed = false

    @Volatile
    private var handling = false

    fun install(app: Application) {
        if (installed) return
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (!handling) {
                handling = true
                runCatching { handle(app, thread, error) }
            }
            // 交回系统默认处理，让进程正常结束（不要吞掉，否则 ANR）
            runCatching { previous?.uncaughtException(thread, error) }
        }
    }

    private fun handle(app: Application, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { pw ->
                pw.println("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
                pw.println("线程：${thread.name}")
                pw.println("设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                pw.println("应用：${app.packageName} · 进程：${currentProcessName(app)}")
                pw.println()
                error.printStackTrace(pw)
            }
        }.toString()

        val path = save(app, stack)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST, stack).putString(KEY_LAST + "_path", path ?: "").apply()

        // 启动独立进程的展示页
        runCatching {
            app.startActivity(
                Intent(app, CrashActivity::class.java)
                    .putExtra(EXTRA_TRACE, stack)
                    .putExtra(EXTRA_PATH, path ?: "")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }

    private fun save(app: Application, text: String): String? = runCatching {
        val dir = app.getExternalFilesDir("crash") ?: File(app.filesDir, "crash").apply { mkdirs() }
        dir.mkdirs()
        val file = File(dir, "crash-${System.currentTimeMillis()}.txt")
        file.writeText(text, Charsets.UTF_8)
        File(dir, "crash-last.txt").writeText(text, Charsets.UTF_8)
        file.absolutePath
    }.getOrNull()

    /** 上一次崩溃记录（设置页可选展示） */
    fun lastTrace(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    private fun currentProcessName(app: Application): String = runCatching {
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else run {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }
    }.getOrNull() ?: app.packageName
}

/** 崩溃展示页：纯 Android 视图 + 可复制，不用 Compose。 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val trace = intent.getStringExtra("trace")
            ?: CrashGuard.lastTrace(this)
            ?: "(没有拿到堆栈)"
        val path = intent.getStringExtra("path").orEmpty()

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "Nezo · 崩溃报告"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "请把下面这段（或截图）发给开发者，就能定位问题。\n日志文件：${path.ifEmpty { "（未生成，可用下方复制）" }}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, dp(14))
        })

        root.addView(Button(this).apply {
            text = "一键复制全部"
            setOnClickListener {
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Nezo 崩溃报告", trace))
                    Toast.makeText(this@CrashActivity, "已复制，去粘给开发者", Toast.LENGTH_SHORT).show()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "重新打开Nezo"
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(this@CrashActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                }
                finish()
            }
        })

        root.addView(TextView(this).apply {
            text = trace
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }
}