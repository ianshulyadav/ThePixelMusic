package com.unshoo.pixelmusic.presentation.components.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.data.preferences.AlbumArtQuality
import com.unshoo.pixelmusic.presentation.components.SafeOriginalAlbumArtSize
import com.unshoo.pixelmusic.presentation.components.SmartImage

/**
 * Cover art drawn edge-to-edge from the very top of the full player (behind the status bar and
 * top buttons), fading smoothly into the player background at its bottom edge.
 */
@Composable
internal fun ImmersiveCoverBackdrop(
    artworkModel: String?,
    albumArtQuality: AlbumArtQuality,
    visibleProvider: () -> Boolean,
    modifier: Modifier = Modifier
) {
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
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black,
                        0.72f to Color.Black,
                        0.86f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        Crossfade(
            targetState = artworkModel,
            animationSpec = tween(350),
            label = "immersiveCover"
        ) { model ->
            SmartImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                targetSize = targetSize,
                crossfadeDurationMillis = 0,
                modifier = Modifier.fillMaxSize()
            )
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
