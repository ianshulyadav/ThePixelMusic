package com.unshoo.pixelmusic.presentation.components.player

import com.unshoo.pixelmusic.presentation.components.fullBackdropSource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.unshoo.pixelmusic.data.preferences.AlbumArtQuality
import com.unshoo.pixelmusic.presentation.components.AlbumArtPreviewSize
import com.unshoo.pixelmusic.presentation.components.SafeOriginalAlbumArtSize
import com.unshoo.pixelmusic.presentation.components.SmartImage
import com.unshoo.pixelmusic.presentation.components.albumArtMemoryCacheKey
import com.unshoo.pixelmusic.presentation.components.albumArtPreviewUrl

/**
 * Cover art drawn edge-to-edge from the very top of the full player (behind the status bar and
 * top buttons), fading smoothly into the player background at its bottom edge.
 */
@Composable
internal fun ImmersiveCoverBackdrop(
    artworkModel: String?,
    albumArtQuality: AlbumArtQuality,
    visibleProvider: () -> Boolean,
    modifier: Modifier = Modifier,
    /** How far below the top edge the artwork starts (it still fills the full width). */
    topOffset: androidx.compose.ui.unit.Dp = 0.dp
) {
    val context = LocalContext.current
    val previewRequest = remember(context, artworkModel) {
        albumArtPreviewUrl(artworkModel)?.let { preview ->
            ImageRequest.Builder(context)
                .data(preview)
                .size(AlbumArtPreviewSize)
                .memoryCacheKey(albumArtMemoryCacheKey(preview, AlbumArtPreviewSize))
                .crossfade(false)
                .build()
        }
    }
    val targetSize = remember(albumArtQuality) {
        if (albumArtQuality.maxSize == 0) SafeOriginalAlbumArtSize
        else coil.size.Size(albumArtQuality.maxSize, albumArtQuality.maxSize)
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = if (visibleProvider()) 1f else 0f
                // Offscreen so the DstIn gradient below masks only the artwork.
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                // Full-bleed cover, nudged down by [topOffset]: fade it in from the top so the
                // poster's upper edge sits clear of the status bar / top buttons, and keep the
                // soft fade into the title at the bottom.
                val h = size.height.coerceAtLeast(1f)
                val topStart = (topOffset.toPx() * 0.35f / h).coerceIn(0f, 0.3f)
                val topEnd = ((topOffset.toPx() + 28.dp.toPx()) / h).coerceIn(topStart, 0.4f)
                val mask = Brush.verticalGradient(
                    0f to Color.Transparent,
                    topStart to Color.Black.copy(alpha = 0.35f),
                    topEnd to Color.Black,
                    0.72f.coerceAtLeast(topEnd) to Color.Black,
                    0.86f.coerceAtLeast(topEnd) to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Transparent
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = mask, blendMode = BlendMode.DstIn)
                }
            }
    ) {
        var settledModel by remember { mutableStateOf<String?>(null) }
        var loadState by remember(artworkModel, targetSize) { mutableStateOf<AsyncImagePainter.State?>(null) }
        val outgoingModel = settledModel?.takeIf { it != artworkModel }
        val reveal = remember(artworkModel, targetSize) {
            Animatable(if (artworkModel != null && artworkModel == settledModel) 1f else 0f)
        }
        val missingArtwork = artworkModel.isNullOrBlank()
        val hasFullCover = loadState is AsyncImagePainter.State.Success
        LaunchedEffect(artworkModel, targetSize, hasFullCover) {
            if (missingArtwork || hasFullCover) {
                reveal.animateTo(1f, tween(350))
                settledModel = artworkModel.takeUnless { missingArtwork }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topOffset)
                .fullBackdropSource(com.unshoo.pixelmusic.presentation.components.LocalCoverBackdropSource.current)
        ) {
            if (outgoingModel != null) {
                SmartImage(
                    model = outgoingModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    targetSize = targetSize,
                    placeholderResId = 0,
                    errorResId = 0,
                    placeHolderBackgroundColor = Color.Transparent,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (missingArtwork) 1f - reveal.value else 1f
                    },
                )
            }
            if (previewRequest != null && settledModel != artworkModel) {
                AsyncImage(
                    model = previewRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!missingArtwork) {
                SmartImage(
                    model = artworkModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    targetSize = targetSize,
                    placeholderResId = 0,
                    errorResId = 0,
                    placeHolderBackgroundColor = Color.Transparent,
                    crossfadeDurationMillis = 0,
                    onState = { loadState = it },
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value },
                )
            }
        }
        // Soft top scrim keeps the status bar and top buttons readable on bright art.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent)
                    )
                )
        )
    }
}
