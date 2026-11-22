package com.unshoo.pixelmusic.data.service

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.unshoo.pixelmusic.data.provider.SharedArtworkContentProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Loads artwork for the media notification, lock screen and other system media surfaces.
 *
 * Slow-network behaviour matters here: system surfaces ask for a bitmap exactly once per
 * metadata change. Previously remote art was routed through the artwork ContentProvider,
 * which answers "not found" while it downloads in the background — so on a slow connection
 * the full player showed the cover while the notification / lock screen stayed blank forever.
 *
 * Now remote URLs are unwrapped and loaded through Coil directly, so they share the very same
 * memory/disk cache the in-app player already filled, and transient failures are retried.
 */
@OptIn(UnstableApi::class)
class CoilBitmapLoader(private val context: Context, private val scope: CoroutineScope) : BitmapLoader {

    companion object {
        // Large enough for lock screen / media surfaces, but bounded so we never hand
        // unbounded original artwork to MediaSession/SystemUI IPC.
        private const val MAX_NOTIFICATION_ARTWORK_SIZE_PX = 1024
        private val RETRY_DELAYS_MS = longArrayOf(0L, 1_500L, 4_000L, 9_000L)
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val unwrapped = SharedArtworkContentProvider.unwrapRemoteUrl(uri.toString())
        val data: Any = if (unwrapped != null && (unwrapped.startsWith("http://") || unwrapped.startsWith("https://"))) {
            // Ask for the very URL the in-app player and mini player load, so the lock screen /
            // notification reuse that download from the disk cache instead of fetching again.
            val playerSize = com.unshoo.pixelmusic.presentation.components.smartImageRequestSize(
                com.unshoo.pixelmusic.presentation.components.PlayerCoverTargetSize
            )
            (com.unshoo.pixelmusic.presentation.components.smartImageOptimizedModel(
                unwrapped, playerSize, isThumbnail = false
            ) as? String) ?: unwrapped
        } else {
            uri
        }
        return loadBitmapInternal(data)
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return loadBitmapInternal(data)
    }

    private fun loadBitmapInternal(data: Any): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        val isRemote = data is String

        scope.launch {
            var lastError: Throwable? = null
            val attempts = if (isRemote) RETRY_DELAYS_MS.size else 1
            // YouTube only has "maxresdefault" for some videos: step down through smaller
            // thumbnails instead of retrying a URL that will always 404.
            val candidates = (data as? String)?.let(::youtubeThumbnailFallbacks) ?: listOf(data)
            for (attempt in 0 until attempts) {
                if (future.isCancelled) return@launch
                val wait = RETRY_DELAYS_MS[attempt]
                if (wait > 0) delay(wait)
                for (candidate in candidates) try {
                    val request = ImageRequest.Builder(context)
                        .data(candidate)
                        .size(MAX_NOTIFICATION_ARTWORK_SIZE_PX, MAX_NOTIFICATION_ARTWORK_SIZE_PX)
                        .precision(Precision.INEXACT)
                        .allowHardware(false) // Bitmap must not be hardware for MediaSession
                        // Read from the shared memory cache (the player may already hold this
                        // cover) but never write: we hand out a private copy below, so Coil
                        // recycling its own entry can never break MediaSession IPC.
                        .memoryCachePolicy(CachePolicy.READ_ONLY)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()

                    val result = context.imageLoader.execute(request)
                    val drawable = (result as? SuccessResult)?.drawable ?: result.drawable
                    if (drawable != null) {
                        val raw = drawable.toBitmap()
                        // Private, software copy owned exclusively by Media3.
                        val owned = if (raw.config == Bitmap.Config.HARDWARE || raw.isRecycled) {
                            raw.copy(Bitmap.Config.ARGB_8888, false)
                        } else {
                            raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, false)
                        }
                        if (owned != null) {
                            future.set(owned)
                            return@launch
                        }
                    }
                    lastError = IllegalStateException("Coil returned no image for: $data")
                } catch (e: Exception) {
                    lastError = e
                }
            }
            future.setException(lastError ?: IllegalStateException("Artwork load failed: $data"))
        }
        return future
    }

    private fun youtubeThumbnailFallbacks(url: String): List<String> {
        val match = Regex("""i\.ytimg\.com/vi(?:_webp)?/([^/]+)/(maxresdefault|sddefault|hq720)""").find(url)
            ?: return listOf(url)
        val id = match.groupValues[1]
        return listOf(url, "https://i.ytimg.com/vi/$id/hqdefault.jpg", "https://i.ytimg.com/vi/$id/mqdefault.jpg")
    }

    override fun supportsMimeType(mimeType: String): Boolean {
        return true // Coil supports most image types
    }
}
