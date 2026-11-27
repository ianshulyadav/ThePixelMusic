package com.unshoo.pixelmusic.presentation.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.image.YouTubeThumbnailCache
import com.unshoo.pixelmusic.data.preferences.AlbumArtQuality
import com.unshoo.pixelmusic.data.remote.youtube.upgradeThumbnailUrlToQuality
import com.unshoo.pixelmusic.utils.LocalArtworkUri

// Keep original album art bounded to avoid large decoded images in the player.
internal const val MaxSafeAlbumArtDimensionPx = 1280
internal val SafeOriginalAlbumArtSize = Size(MaxSafeAlbumArtDimensionPx, MaxSafeAlbumArtDimensionPx)
internal val AlbumArtPreviewSize = Size(256, 256)

internal fun albumArtPreviewUrl(model: String?): String? =
    model?.takeIf { it.startsWith("http") }
        ?.let { upgradeThumbnailUrlToQuality(it, AlbumArtQuality.LOW) }
        ?.takeIf { it != model }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OptimizedAlbumArt(
    uri: Any?,
    title: String,
    modifier: Modifier = Modifier,
    targetSize: Size = SafeOriginalAlbumArtSize,
    placeholderModel: Any? = null,
    /** False keeps showing the low-quality preview; flip to true to swap in full resolution. */
    loadHighQuality: Boolean = true,
) {
    val context = LocalContext.current
    val performanceModeEnabled = com.unshoo.pixelmusic.ui.theme.LocalPerformanceModeEnabled.current || SmartImageCache.performanceModeEnabled
    // Progressive remote art: a small preview (fast, usually already cached from lists) is drawn
    // first and the full-resolution cover fades in over it once loaded. If the full-size image
    // is already in Coil's memory cache, skip the extra preview request altogether.
    val effectiveUri: Any? = remember(uri) {
        if (uri is String) YouTubeThumbnailCache.rewriteIfMaxResMissing(uri) else uri
    }
    val lowQualityUrl = remember(effectiveUri) {
        albumArtPreviewUrl(effectiveUri as? String)
    }
    if (lowQualityUrl != null) {
        val highSize = remember(targetSize) {
            val safe = safeAlbumArtTargetSize(targetSize)
            val w = (safe.width as? Dimension.Pixels)?.px?.coerceAtMost(MaxSafeAlbumArtDimensionPx)
            val h = (safe.height as? Dimension.Pixels)?.px?.coerceAtMost(MaxSafeAlbumArtDimensionPx)
            if (w != null && h != null) Size(w, h) else safe
        }
        var highRetryNonce by remember(effectiveUri) { mutableStateOf(0) }
        val highData = remember(effectiveUri, highRetryNonce) {
            val raw = effectiveUri as String
            if (highRetryNonce > 0) {
                YouTubeThumbnailCache.fallbackCandidates(raw).drop(highRetryNonce).firstOrNull()
                    ?: YouTubeThumbnailCache.rewriteIfMaxResMissing(raw)
            } else {
                YouTubeThumbnailCache.rewriteIfMaxResMissing(raw)
            }
        }
        val highMemoryKey = remember(effectiveUri, highSize) {
            albumArtMemoryCacheKey(effectiveUri, highSize)
        }
        val skipPreviewFetch = remember(effectiveUri, highMemoryKey, highSize, loadHighQuality, performanceModeEnabled) {
            loadHighQuality && !performanceModeEnabled && highMemoryKey != null &&
                context.imageLoader.memoryCache?.get(MemoryCache.Key(highMemoryKey)) != null
        }
        Box(modifier = modifier) {
            if (!skipPreviewFetch) {
                AsyncImage(
                    model = remember(context, lowQualityUrl) {
                        ImageRequest.Builder(context)
                            .data(lowQualityUrl)
                            .size(AlbumArtPreviewSize)
                            .memoryCacheKey(albumArtMemoryCacheKey(lowQualityUrl, AlbumArtPreviewSize))
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = "Album art of $title",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.ic_music_placeholder),
                    error = painterResource(R.drawable.ic_music_placeholder)
                )
            }
            if (loadHighQuality && !performanceModeEnabled) {
                AsyncImage(
                    model = remember(highData, highSize, highMemoryKey, skipPreviewFetch) {
                        ImageRequest.Builder(context)
                            .data(highData)
                            .size(highSize)
                            .crossfade(if (skipPreviewFetch) 0 else 250)
                            .apply { highMemoryKey?.let { memoryCacheKey(it) } }
                            .build()
                    },
                    contentDescription = if (skipPreviewFetch) "Album art of $title" else null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = {
                        if (YouTubeThumbnailCache.isMaxResUrl(highData) && highRetryNonce == 0) {
                            YouTubeThumbnailCache.markMaxResMissingForUrl(highData)
                            highRetryNonce = 1
                        }
                    }
                )
            }
        }
        return
    }
    // `density` is deliberately no longer read here: targetSize is already in pixels.
    val requestTargetSize = remember(targetSize, performanceModeEnabled) {
        val safeSize = safeAlbumArtTargetSize(targetSize)
        val w = (safeSize.width as? Dimension.Pixels)?.px
        val h = (safeSize.height as? Dimension.Pixels)?.px
        if (w != null && h != null) {
            val maxDim = if (performanceModeEnabled) 256 else MaxSafeAlbumArtDimensionPx
            val scaledW = w.coerceAtMost(maxDim)
            val scaledH = h.coerceAtMost(maxDim)
            Size(scaledW, scaledH)
        } else {
            safeSize
        }
    }
    val isStableLocalArtwork = remember(uri) {
        when (uri) {
            is String -> LocalArtworkUri.isLocalArtworkUri(uri)
            is Uri -> LocalArtworkUri.isLocalArtworkUri(uri)
            is ImageRequest -> {
                val data = uri.data
                (data as? String)?.let(LocalArtworkUri::isLocalArtworkUri) == true ||
                    LocalArtworkUri.isLocalArtworkUri(data as? Uri)
            }
            else -> false
        }
    }

    if (renderDirectAlbumArt(
            model = uri,
            title = title,
            modifier = modifier
        )
    ) {
        return
    }

    val memoryCacheKey = remember(uri, requestTargetSize) {
        albumArtMemoryCacheKey(uri, requestTargetSize)
    }
    val placeholderMemoryCacheKey = remember(memoryCacheKey, uri) {
        when (uri) {
            is ImageRequest -> uri.placeholderMemoryCacheKey
                ?: uri.memoryCacheKey
                ?: memoryCacheKey?.let { MemoryCache.Key(it) }
            else -> memoryCacheKey?.let { MemoryCache.Key(it) }
        }
    }
    val requestModel = remember(context, uri, requestTargetSize, performanceModeEnabled) {
        when (uri) {
            is ImageRequest -> uri.newBuilder(context).apply {
                size(requestTargetSize)
                if (performanceModeEnabled) crossfade(0)
                if (uri.memoryCacheKey == null) {
                    memoryCacheKey(memoryCacheKey)
                }
                placeholderMemoryCacheKey(placeholderMemoryCacheKey)
            }.build()
            else -> ImageRequest.Builder(context)
                .data(uri)
                .crossfade(if (performanceModeEnabled) 0 else 350) // Use Coil's native crossfade unless Performance Mode is active
                .error(R.drawable.ic_music_placeholder)
                .placeholder(R.drawable.ic_music_placeholder)
                .size(requestTargetSize)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(if (isStableLocalArtwork) CachePolicy.DISABLED else CachePolicy.ENABLED)
                .apply {
                    if (memoryCacheKey != null) {
                        memoryCacheKey(memoryCacheKey)
                    }
                    if (placeholderMemoryCacheKey != null) {
                        placeholderMemoryCacheKey(placeholderMemoryCacheKey)
                    }
                }
                .build()
        }
    }

    AsyncImage(
        model = requestModel,
        contentDescription = "Album art of $title",
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = painterResource(R.drawable.ic_music_placeholder),
        error = painterResource(R.drawable.ic_music_placeholder)
    )
}

@Composable
private fun PlaceholderContent(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_music_placeholder),
            contentDescription = "$title placeholder",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(96.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                MaterialTheme.colorScheme.onSurfaceVariant
            ),
        )
    }
}

@Composable
private fun renderDirectAlbumArt(
    model: Any?,
    title: String,
    modifier: Modifier
): Boolean {
    return when (model) {
        is ImageRequest -> renderDirectAlbumArt(model.data, title, modifier)
        is ImageVector -> {
            Image(
                imageVector = model,
                contentDescription = "Album art of $title",
                contentScale = ContentScale.Crop,
                modifier = modifier.fillMaxSize()
            )
            true
        }
        is Painter -> {
            Image(
                painter = model,
                contentDescription = "Album art of $title",
                contentScale = ContentScale.Crop,
                modifier = modifier.fillMaxSize()
            )
            true
        }
        is ImageBitmap -> {
            Image(
                bitmap = model,
                contentDescription = "Album art of $title",
                contentScale = ContentScale.Crop,
                modifier = modifier.fillMaxSize()
            )
            true
        }
        is Bitmap -> {
            Image(
                bitmap = model.asImageBitmap(),
                contentDescription = "Album art of $title",
                contentScale = ContentScale.Crop,
                modifier = modifier.fillMaxSize()
            )
            true
        }
        else -> false
    }
}

internal fun safeAlbumArtTargetSize(targetSize: Size): Size {
    return if (targetSize == Size.ORIGINAL) {
        SafeOriginalAlbumArtSize
    } else {
        targetSize
    }
}

internal fun albumArtMemoryCacheKey(model: Any?, targetSize: Size): String? {
    val data = when (model) {
        is ImageRequest -> model.data
        else -> model
    } ?: return null

    val baseKey = when (data) {
        is String -> data.takeIf { it.isNotBlank() }
        is Uri -> data.toString().takeIf { it.isNotBlank() }
        else -> null
    } ?: return null

    val safeSize = safeAlbumArtTargetSize(targetSize)
    val width = (safeSize.width as? Dimension.Pixels)?.px
    val height = (safeSize.height as? Dimension.Pixels)?.px
    return if (width != null && height != null) {
        "${baseKey}_${width}x${height}"
    } else {
        "${baseKey}_${safeSize.width}x${safeSize.height}"
    }
}

