package com.ZenithLiteAura.app.nezo.platform.update

import android.content.Context
import com.ZenithLiteAura.app.nezo.BuildConfig
import com.ZenithLiteAura.app.nezo.platform.AppVisibility
import com.ZenithLiteAura.app.nezo.platform.Notifier
import com.ZenithLiteAura.app.nezo.platform.PlatformLog
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 检查更新（GitHub Releases）。
 *
 * 流程：`api.github.com/repos/ZenithLiteAura/Zeno/releases/latest`（GET，带 UA）
 * → 解析 `tag_name` / `html_url` / `assets[].browser_download_url(.apk)`
 * → 按 major.minor.patch 数字比较，只有更高才算有更新 → 结果写 [state] + SharedPreferences 缓存。
 *
 * 约束：
 * - 网络请求全在 IO 协程，异常一律收敛成 [UpdateState.error] 中文文案，不抛；
 * - `manual=false` 时 24 小时内不重复请求（GitHub 未认证限流 60 次/小时）；
 * - 失败时也会记一次尝试时间，避免每次冷启动都打一次网络（1 小时内不重试失败请求）。
 */
object UpdateChecker {

    // ── 常量 ──────────────────────────────────────────────────────────────

    private const val API_URL = "https://api.github.com/repos/ZenithLiteAura/Zeno/releases/latest"

    /** Release 列表页（拿不到具体 Release 时的兜底） */
    const val REPO_RELEASES_URL = "https://github.com/ZenithLiteAura/Zeno/releases"

    private const val PREFS_NAME = "nezo_update"
    private const val KEY_LAST_SUCCESS = "last_success_at"
    private const val KEY_LAST_ATTEMPT = "last_attempt_at"
    private const val KEY_LATEST = "latest_version"
    private const val KEY_HAS_UPDATE = "has_update"
    private const val KEY_RELEASE_URL = "release_url"
    private const val KEY_APK_URL = "apk_url"
    private const val KEY_ERROR = "last_error"
    private const val KEY_NOTIFIED_VERSION = "notified_version"

    /** 非手动检查的最小间隔：24 小时 */
    private const val MIN_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** 失败后（非手动）1 小时内不再重试，避免冷启动狂打 GitHub */
    private const val MIN_RETRY_INTERVAL_MS = 60 * 60 * 1000L

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_BODY_CHARS = 1024 * 1024

    private val USER_AGENT = "Nezo/" + BuildConfig.VERSION_NAME

    // ── 状态 ──────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile
    private var checking = false

    /** 当前 App 版本（= BuildConfig.VERSION_NAME） */
    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /** 启动时先把上次缓存读出来，UI 不会从空白开始 */
    fun loadCached(context: Context) {
        runCatching {
            val prefs = prefs(context)
            val latest = prefs.getString(KEY_LATEST, "").orEmpty()
            val lastSuccess = prefs.getLong(KEY_LAST_SUCCESS, 0L)
            if (latest.isBlank() && lastSuccess == 0L) return@runCatching

            _state.value = UpdateState(
                checking = false,
                latestVersion = latest,
                hasUpdate = prefs.getBoolean(KEY_HAS_UPDATE, false),
                releaseUrl = prefs.getString(KEY_RELEASE_URL, "").orEmpty(),
                apkUrl = prefs.getString(KEY_APK_URL, "").orEmpty(),
                error = prefs.getString(KEY_ERROR, "").orEmpty(),
            )
        }.onFailure { PlatformLog.warn("读取更新缓存失败：${it.message}") }
    }

    /**
     * 检查更新。
     * @param manual true = 用户主动点「检查更新」（忽略 24h 缓存）；false = 启动时自动检查
     */
    fun check(context: Context, manual: Boolean = true) {
        val appContext = context.applicationContext

        if (checking) {
            PlatformLog.info("已有检查更新任务在进行，忽略本次请求")
            return
        }

        val now = System.currentTimeMillis()
        val prefs = prefs(appContext)
        if (!manual) {
            val lastSuccess = prefs.getLong(KEY_LAST_SUCCESS, 0L)
            val lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
            val successFresh = lastSuccess > 0L && now - lastSuccess < MIN_CHECK_INTERVAL_MS
            val attemptFresh = lastAttempt > 0L && now - lastAttempt < MIN_RETRY_INTERVAL_MS
            if (successFresh || attemptFresh) {
                loadCached(appContext)
                PlatformLog.info("更新检查仍在缓存期内（成功 ${(now - lastSuccess) / 60_000} 分钟前），跳过网络请求")
                return
            }
        }

        checking = true
        _state.value = _state.value.copy(checking = true, error = "")
        runCatching { prefs.edit().putLong(KEY_LAST_ATTEMPT, now).apply() }

        scope.launch {
            try {
                val result = fetchLatest()
                persistSuccess(appContext, result)
                _state.value = result
                PlatformLog.info(
                    "检查更新完成：latest=${result.latestVersion} " +
                        "current=${currentVersion()} hasUpdate=${result.hasUpdate} apk=${result.apkUrl.isNotBlank()}"
                )
                maybeNotify(appContext, result, manual)
            } catch (t: Throwable) {
                val message = errorMessage(t)
                PlatformLog.warn("检查更新失败：$message")
                persistError(appContext, message)
                _state.value = _state.value.copy(checking = false, error = message)
            } finally {
                checking = false
            }
        }
    }

    // ── 网络 ──────────────────────────────────────────────────────────────

    private fun fetchLatest(): UpdateState {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText().take(300) }
                }.getOrNull()
                PlatformLog.warn("GitHub API HTTP $code：${errorBody.orEmpty()}")
                throw UpdateHttpException(code)
            }

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = reader.readText()
                if (text.length > MAX_BODY_CHARS) text.substring(0, MAX_BODY_CHARS) else text
            }

            val info = parseLatestRelease(body)
                ?: throw IllegalStateException("返回数据里没有可识别的版本号（tag_name）")

            return UpdateState(
                checking = false,
                latestVersion = info.version,
                hasUpdate = isNewer(info.version, currentVersion()),
                releaseUrl = info.releaseUrl,
                apkUrl = info.apkUrl,
                error = "",
            )
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ── 通知 ──────────────────────────────────────────────────────────────

    private fun maybeNotify(context: Context, state: UpdateState, manual: Boolean) {
        if (!state.hasUpdate) return

        val prefs = prefs(context)
        val notified = prefs.getString(KEY_NOTIFIED_VERSION, "").orEmpty()
        if (notified == state.latestVersion) return

        // 用户主动检查且 App 就在前台：UI 自己会显示「发现 vX」，不再重复弹通知
        if (manual && AppVisibility.isForeground) {
            PlatformLog.info("前台手动检查发现新版本，交给 UI 提示")
            return
        }

        Notifier.notifyUpdate(context, state.latestVersion, state.releaseUrl)
        runCatching { prefs.edit().putString(KEY_NOTIFIED_VERSION, state.latestVersion).apply() }
    }

    // ── 缓存 ──────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun persistSuccess(context: Context, state: UpdateState) {
        runCatching {
            prefs(context).edit()
                .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                .putString(KEY_LATEST, state.latestVersion)
                .putBoolean(KEY_HAS_UPDATE, state.hasUpdate)
                .putString(KEY_RELEASE_URL, state.releaseUrl)
                .putString(KEY_APK_URL, state.apkUrl)
                .putString(KEY_ERROR, "")
                .apply()
        }
    }

    private fun persistError(context: Context, message: String) {
        runCatching {
            // 故意只写 error，不更新 last_success（保留上次成功结果，UI 仍能看到上次版本）
            prefs(context).edit().putString(KEY_ERROR, message).apply()
        }
    }

    private fun errorMessage(t: Throwable): String = when (t) {
        is UpdateHttpException -> when (t.code) {
            403, 429 -> "检查更新失败：请求过于频繁（GitHub 限流），请稍后再试"
            404 -> "检查更新失败：仓库还没有发布 Release"
            in 500..599 -> "检查更新失败：GitHub 服务异常（HTTP ${t.code}），请稍后再试"
            else -> "检查更新失败：HTTP ${t.code}"
        }

        is UnknownHostException -> "检查更新失败：网络不可用，请检查网络"
        is SocketTimeoutException -> "检查更新失败：网络超时，请稍后再试"
        is javax.net.ssl.SSLException -> "检查更新失败：安全连接失败，请稍后再试"
        is com.google.gson.JsonParseException -> "检查更新失败：返回数据格式异常"
        else -> "检查更新失败：${t.message?.takeIf { it.isNotBlank() } ?: "未知错误"}"
    }
}

// ── 纯函数（无 Android 依赖，可单测） ─────────────────────────────────────

/** HTTP 状态非 200（区分限流 / 未发布 / 服务异常） */
internal class UpdateHttpException(val code: Int) : Exception("HTTP $code")

/** GitHub Release 解析结果 */
internal data class ReleaseInfo(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String,
)

/**
 * 解析 `releases/latest` 的 JSON：
 * `tag_name`（`v1.2.0` / `1.2.0` 都认）、`html_url`、`assets[]` 里第一个 `.apk` 的下载地址。
 * 解析失败 / 没有版本号返回 null（不抛）。
 */
internal fun parseLatestRelease(body: String): ReleaseInfo? = runCatching {
    val root = JsonParser.parseString(body)
    if (!root.isJsonObject) return@runCatching null

    val obj = root.asJsonObject
    val version = parseTagVersion(obj.stringOf("tag_name"))
    if (version.isBlank()) return@runCatching null

    var apkUrl = ""
    val assets = obj.getAsJsonArray("assets")
    if (assets != null) {
        for (element in assets) {
            if (!element.isJsonObject) continue
            val asset = element.asJsonObject
            val name = asset.stringOf("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            apkUrl = asset.stringOf("browser_download_url")
            if (apkUrl.isNotBlank()) break
        }
    }

    ReleaseInfo(
        version = version,
        releaseUrl = obj.stringOf("html_url"),
        apkUrl = apkUrl,
    )
}.getOrNull()

/** `v1.2.0` / `V1.2.0` / `release-1.2.0` / `1.2.0` → `1.2.0`；拿不到数字返回空串 */
internal fun parseTagVersion(tag: String): String {
    val match = VERSION_PATTERN.find(tag) ?: return ""
    return match.value
}

private val VERSION_PATTERN = Regex("""\d+(?:\.\d+)*""")

/**
 * 按 `major.minor.patch` 逐段数字比较：`1.2.0` < `1.2.1` < `1.10.0`。
 * 段数不同按 0 补齐（`1.2` == `1.2.0`）。返回 -1 / 0 / 1。
 */
internal fun compareVersions(left: String, right: String): Int {
    val a = versionNumbers(left)
    val b = versionNumbers(right)
    val size = maxOf(a.size, b.size)

    for (index in 0 until size) {
        val x = a.getOrElse(index) { 0 }
        val y = b.getOrElse(index) { 0 }
        if (x != y) return if (x > y) 1 else -1
    }
    return 0
}

/** 最新版本是否比当前版本更高（只有更高才算「有新版本」） */
internal fun isNewer(latest: String, current: String): Boolean =
    compareVersions(latest, current) > 0

private fun versionNumbers(value: String): List<Int> =
    parseTagVersion(value).split('.').mapNotNull { it.toIntOrNull() }

/** 只认 JSON 字符串（GitHub 的 tag_name/html_url 都是字符串；数字/对象一律当缺失） */
private fun JsonObject.stringOf(name: String): String = runCatching {
    val element = get(name) ?: return ""
    if (element.isJsonNull || !element.isJsonPrimitive) return ""
    val primitive = element.asJsonPrimitive
    if (!primitive.isString) return ""
    primitive.asString
}.getOrDefault("")