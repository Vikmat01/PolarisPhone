package com.polaris.phone

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads contact photos off the main thread, downsampled to the size they're shown at, with a small cache. */
object Photos {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(uri: String, px: Int): ImageBitmap? = cache.get("$uri@${bucket(px)}")

    /** Rounds the requested size up so close sizes share one cached bitmap. */
    private fun bucket(px: Int) = when {
        px <= 160 -> 160
        px <= 320 -> 320
        else -> 640
    }

    suspend fun load(ctx: Context, uri: String, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val b = bucket(px)
        val key = "$uri@$b"
        cache.get(key)?.let { return@withContext it }
        runCatching {
            val u = Uri.parse(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= b && bounds.outHeight / (sample * 2) >= b) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?.asImageBitmap()
        }.getOrNull()?.also { cache.put(key, it) }
    }

    fun clear() = cache.evictAll()
}
