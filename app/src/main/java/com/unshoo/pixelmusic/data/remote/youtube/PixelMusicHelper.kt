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
        return withContext(Dispatchers.IO) {
            try {
                val client = YoutubeHelper.client

                val request = Request.Builder()
                    .url(url)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes()?.cappedTo() else null
                }
            } catch (e: Exception) {
                Log.e("Artwork", "Failed to fetch artwork: ${e.message}")
                null
            }
        }
    }
}

fun ByteArray.cappedTo(maxSize: Int = PixelMusicHelper.WEAROS_MAX_IMAGE_SIZE): ByteArray? {
    val bitmap = BitmapFactory.decodeByteArray(this, 0, size) ?: return null
    try {
        val capped = if (bitmap.width <= maxSize && bitmap.height <= maxSize) bitmap else {
            val scale = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
            bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
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
