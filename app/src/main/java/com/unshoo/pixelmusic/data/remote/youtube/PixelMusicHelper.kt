package com.unshoo.pixelmusic.data.remote.youtube

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Paths
import kotlin.time.measureTimedValue

object PixelMusicHelper {
    const val TAG = "PixelMusicPrint"
    const val WEAROS_MAX_IMAGE_SIZE = 720

    inline fun <T> benchmark(
        label: String,
        block: () -> T
    ): T {
        val result = measureTimedValue(block)
        printd(tag = "PixelMusicBench", message = "$label: ${result.duration.inWholeMilliseconds} ms")
        return result.value
    }

    fun printd(message: String, tag: String = TAG) {
        Log.d(tag, message)
    }

    fun printe(message: String, tag: String = TAG, exception: java.lang.Exception? = null) {
        Log.e(TAG, message, exception)
    }

    fun getDownloadDirectory(context: Context, directory: String? = null): File {
        val dir = File(
            context.filesDir,
            if (directory == null)
                Constants.Downloads.DIRECTORY
            else
                Paths.get(Constants.Downloads.DIRECTORY, directory)
                    .toString()
        )
        dir.mkdirs()
        return dir
    }

    suspend fun fetchArtworkBytes(url: String): ByteArray? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return withContext(Dispatchers.IO) {
            val client = YoutubeHelper.client
            for (candidate in com.unshoo.pixelmusic.data.image.YouTubeThumbnailCache.fallbackCandidates(url)) {
                try {
                    val request = Request.Builder()
                        .url(candidate)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bytes = response.body.bytes().cappedTo()
                            if (bytes != null) return@withContext bytes
                        } else if (com.unshoo.pixelmusic.data.image.YouTubeThumbnailCache.isMaxResUrl(candidate)) {
                            com.unshoo.pixelmusic.data.image.YouTubeThumbnailCache.markMaxResMissingForUrl(candidate)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Artwork", "Failed to fetch artwork: ${e.message}")
                }
            }
            null
        }
    }
}

fun ByteArray.cappedTo(maxSize: Int = PixelMusicHelper.WEAROS_MAX_IMAGE_SIZE): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(this, 0, size, bounds)
    val srcWidth = bounds.outWidth
    val srcHeight = bounds.outHeight
    if (srcWidth <= 0 || srcHeight <= 0) return null
    // Already within the size bound: return raw compressed bytes without a full bitmap decode/re-encode.
    if (srcWidth <= maxSize && srcHeight <= maxSize) return this

    var sampleSize = 1
    while (srcWidth / (sampleSize * 2) >= maxSize && srcHeight / (sampleSize * 2) >= maxSize) {
        sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = BitmapFactory.decodeByteArray(this, 0, size, decodeOpts) ?: return null
    try {
        val capped = if (bitmap.width <= maxSize && bitmap.height <= maxSize) bitmap else {
            val scale = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
            bitmap.scale((bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1))
        }
        try {
            val stream = ByteArrayOutputStream()
            return if (capped.compress(Bitmap.CompressFormat.JPEG, 90, stream)) {
                stream.toByteArray()
            } else null
        } finally {
            if (capped !== bitmap) capped.recycle()
        }
    } finally {
        bitmap.recycle()
    }
}
