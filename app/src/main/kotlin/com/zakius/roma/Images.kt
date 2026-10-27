package com.zakius.roma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Covers: downloaded once into cacheDir/img, decoded at the size they're shown (a video's 480×360 still in a 48 dp row
 * takes a 48 dp bitmap, not a full one), and kept in memory while there's room. Hardware bitmaps unless the pixels are
 * read. One download and decode per picture and size, however many rows ask for it at once.
 */
object Images {
    private lateinit var dir: File
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val loading = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    // ponytail: 6 at a time (6 connections over HTTP/1.1); a priority queue for the visible rows if scrolling lags.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(6))

    fun init(c: Context) {
        dir = File(c.cacheDir, "img")
        scope.launch {
            File(c.cacheDir, "image_cache").deleteRecursively() // Coil's, up to 0.1.39
            trim()
        }
    }

    // ponytail: oldest files out past 100 MB, checked once per start; Coil kept 2% of free space.
    private fun trim() {
        val files = dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        var kept = 0L
        for (f in files) { kept += f.length(); if (kept > 100L shl 20) f.delete() }
    }

    private fun key(url: String, px: Int, exact: Boolean, software: Boolean) = "$url@$px${if (exact) "e" else ""}${if (software) "s" else ""}"

    /** In memory already: drawn in the first frame, no flash of an empty box. */
    fun peek(url: String, px: Int, exact: Boolean = false) = memory.get(key(url, px, exact, false))

    /**
     * [px]: the side it's shown at; the bitmap is at least that, so it stays sharp. [exact]: scaled to [px] across
     * (the blurred backdrop, the colour sample). [software]: pixels readable with getPixel.
     */
    suspend fun load(url: String, px: Int, exact: Boolean = false, software: Boolean = false): Bitmap? {
        val k = key(url, px, exact, software)
        memory.get(k)?.let { return it }
        val job = loading.computeIfAbsent(k) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    runCatching { decode(bytes(url), px, exact, software) }.getOrNull()?.also { memory.put(k, it) }
                } finally {
                    loading.remove(k)
                }
            }
        }
        return job.await()
    }

    fun prefetch(url: String, px: Int) {
        scope.launch { load(url, px) }
    }

    private fun bytes(url: String): ByteArray {
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(dir, name)
        if (f.exists()) {
            f.setLastModified(System.currentTimeMillis())
            return f.readBytes()
        }
        val c = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
        val data = c.inputStream.use { it.readBytes() }
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(data)
        tmp.renameTo(f)
        return data
    }

    private fun decode(data: ByteArray, px: Int, exact: Boolean, software: Boolean): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (software || exact) Bitmap.Config.ARGB_8888 else Bitmap.Config.HARDWARE
        }
        val b = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
        if (!exact) return b
        val h = maxOf(1, px * b.height / b.width)
        return Bitmap.createScaledBitmap(b, px, h, true)
    }
}

/** A cover at [size]; empty until it's loaded, or [placeholder] meanwhile. */
@Composable
fun Thumb(
    url: String?,
    size: Dp,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
    exact: Boolean = false,
    placeholder: Bitmap? = null,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    ThumbPx(url, px, modifier, contentScale, alpha, exact, placeholder)
}

@Composable
fun ThumbPx(
    url: String?,
    px: Int,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
    exact: Boolean = false,
    placeholder: Bitmap? = null,
) {
    // Keyed on the picture: a row reused for another song never shows the previous one's cover.
    val state = remember(url, px) { mutableStateOf(url?.let { Images.peek(it, px, exact) } ?: placeholder) }
    // Also when the picture arrived in memory after the first composition: a decode started at launch may finish
    // between composing this and running the effect, and the box then stayed empty until something recomposed it.
    LaunchedEffect(url, px) { if (url != null) Images.load(url, px, exact)?.let { state.value = it } }
    val b = state.value
    if (b == null) Box(modifier) else Image(remember(b) { b.asImageBitmap() }, null, modifier, contentScale = contentScale, alpha = alpha)
}
