package com.ZenithLiteAura.app.nezo.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 极简远程图片加载：HttpURLConnection + BitmapFactory + LruCache（不引第三方图片库）。
 * 只用于下载页封面/缩略图，失败就显示占位图标，绝不抛异常。
 */
private object RemoteImageCache {
    private const val MAX_BYTES = 16 * 1024 * 1024

    val lru: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
}

private const val MOBILE_UA =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val cs = MiuixTheme.colorScheme
    var bitmap by remember(url) { mutableStateOf(RemoteImageCache.lru.get(url)) }

    LaunchedEffect(url) {
        if (url.isBlank() || bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { loadBitmap(url) }
        if (loaded != null) {
            RemoteImageCache.lru.put(url, loaded)
            bitmap = loaded
        }
    }

    Box(
        modifier = modifier.background(cs.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(MiuixIcons.Image, null, Modifier.size(28.dp), tint = cs.onSurfaceVariantSummary.copy(alpha = 0.4f))
        }
    }
}

private fun loadBitmap(url: String): Bitmap? = runCatching {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", MOBILE_UA)
        // 各家 CDN 防盗链要求不同：B站/小红书没有对应 Referer 会 403（封面就会显示占位图）
        setRequestProperty("Referer", refererFor(url))
    }
    try {
        if (conn.responseCode !in 200..299) return@runCatching null
        val bytes = conn.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) return@runCatching null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } finally {
        conn.disconnect()
    }
}.getOrNull()

/** 按图片 URL 的 host 选 Referer（防盗链）。 */
private fun refererFor(url: String): String {
    val u = url.lowercase()
    return when {
        "hdslb" in u || "bilibili" in u -> "https://www.bilibili.com/"
        "xhscdn" in u || "xiaohongshu" in u -> "https://www.xiaohongshu.com/"
        "kuaishou" in u || "kwaicdn" in u -> "https://www.kuaishou.com/"
        "douyinpic" in u || "douyin" in u || "iesdouyin" in u || "snssdk" in u || "byteimg" in u -> "https://www.iesdouyin.com/"
        else -> "https://www.douyin.com/"
    }
}
