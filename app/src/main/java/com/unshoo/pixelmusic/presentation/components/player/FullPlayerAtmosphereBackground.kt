package com.unshoo.pixelmusic.presentation.components.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.lerp as lerpFloat
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.presentation.components.blur.ArtworkGaussianBlur
import com.unshoo.pixelmusic.presentation.components.blur.BlurredArtworkImage
import com.unshoo.pixelmusic.ui.theme.LocalPixelMusicDarkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// Easing for the artwork dissolve.
private val AtmosphereEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

/**
 * Dynamic album-art-based atmospheric blur background for the player sheet (mini and full).
 *
 * Light Theme:
 * - Anchored 100% to the bottom sheet color (albumColorScheme.primaryContainer)
 *   with vibrant hardware Gaussian blur of the artwork, with no white gradient wash or dimming.
 *
 * Dark Theme:
 * - Vibrant hardware Gaussian blur of the artwork over the solid tinted base,
 *   with no muddy dark overlay. Clean contrast scrim only at the bottom controls.
 *
 * Transitions:
 * - A dissolve that keeps the outgoing artwork fully visible until the incoming artwork is ready.
 * - Upcoming song artwork is proactively cached by PlayerViewModel for instant cache hits.
 *
 * Mini player:
 * - Preserved untouched in both themes.
 */
@Composable
fun FullPlayerAtmosphereBackground(
    currentSong: Song?,
    albumColorScheme: ColorScheme,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = LocalPixelMusicDarkTheme.current,
    expansionFractionProvider: (() -> Float)? = null,
    showTopVignette: Boolean = true
) {
    val context = LocalContext.current.applicationContext
    val artwork = currentSong?.albumArtUriString?.takeIf(String::isNotBlank)
    var readyArtwork by remember { mutableStateOf(ArtworkGaussianBlur.cached(artwork)) }
    var displayedBlur by remember { mutableStateOf(readyArtwork) }
    var previousBlur by remember { mutableStateOf<ImageBitmap?>(null) }
    val crossfadeProgress = remember { Animatable(1f) }

    LaunchedEffect(context, artwork) {
        readyArtwork = if (artwork == null) {
            // Track metadata can temporarily arrive without artwork during hydration.
            delay(180L)
            null
        } else {
            try {
                ArtworkGaussianBlur.load(context, artwork)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                timber.log.Timber.w(error, "Unable to load player background artwork")
                null
            }
        }
    }

    LaunchedEffect(Unit) {
        // Finish the current dissolve before displaying the latest decoded frame, even when
        // tracks are skipped rapidly. Both layers hold their bitmaps throughout the animation.
        snapshotFlow { readyArtwork }.collect { incoming ->
            if (incoming === displayedBlur) return@collect
            crossfadeProgress.snapTo(0f)
            previousBlur = displayedBlur
            displayedBlur = incoming
            crossfadeProgress.animateTo(1f, tween(durationMillis = 650, easing = AtmosphereEasing))
            previousBlur = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(albumColorScheme.primaryContainer)
    ) {

        // 2. GAUSSIAN BLURRED ALBUM ARTWORK LAYER WITH LIQUID CROSSFADE
        // Perf: the artwork is Gaussian-blurred ONCE at 64px on a background thread
        // (ArtworkGaussianBlur) and drawn upscaled with bilinear filtering. The previous
        // Modifier.blur(52-60dp) re-ran a full-screen RenderEffect blur on every frame of the
        // sheet drag (the alpha below changes per frame), which was the main source of dropped
        // frames at 120Hz, and it rendered no blur at all below Android 12. Alpha is applied in
        // the layer/draw phase, so expansion never recomposes this tree.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val expansionFraction = expansionFractionProvider?.invoke() ?: 1f
                    alpha = if (isDarkTheme) {
                        lerpFloat(0.45f, 0.65f, expansionFraction)
                    } else {
                        lerpFloat(0.18f, 0.45f, expansionFraction)
                    }
                    // Outside an artwork crossfade only one image is drawn, so applying the alpha
                    // per draw is pixel-identical and avoids a full-screen offscreen layer every
                    // frame. During the crossfade (two overlapping images) keep the offscreen pass.
                    compositingStrategy = if (previousBlur == null) {
                        androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    } else {
                        androidx.compose.ui.graphics.CompositingStrategy.Auto
                    }
                }
        ) {
            val outgoing = previousBlur
            val incoming = displayedBlur
            if (outgoing != null) {
                BlurredArtworkImage(
                    image = outgoing,
                    modifier = Modifier.fillMaxSize(),
                    alphaProvider = { if (incoming == null) 1f - crossfadeProgress.value else 1f }
                )
            }
            if (incoming != null) {
                BlurredArtworkImage(
                    image = incoming,
                    modifier = Modifier.fillMaxSize(),
                    alphaProvider = { crossfadeProgress.value }
                )
            }
        }

        // 3. CONTRAST & HARMONIZATION LAYERS
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val expansionFraction = expansionFractionProvider?.invoke() ?: 1f
                    val fullPlayerAlpha = ((expansionFraction - 0.20f) / 0.80f).coerceIn(0f, 1f)
                    val scrimAlpha = ((expansionFraction - 0.30f) / 0.70f).coerceIn(0f, 1f)

                    if (!isDarkTheme) {
                        // Light theme: gentle harmonization wash of the player sheet solid color (primaryContainer)
                        // over the blur so dark artwork details are harmonized without muting the blur.
                        // In mini player (fullPlayerAlpha == 0), this is completely inactive.
                        if (fullPlayerAlpha > 0f) {
                            drawRect(
                                color = albumColorScheme.primaryContainer.copy(alpha = 0.20f * fullPlayerAlpha)
                            )
                        }
                    } else {
                        // Dark theme: deep atmosphere tint restoring rich dark depth in full player
                        // In mini player (fullPlayerAlpha == 0), this is completely inactive.
                        if (fullPlayerAlpha > 0f) {
                            drawRect(
                                color = Color(0xFF090D14).copy(alpha = 0.28f * fullPlayerAlpha)
                            )

                            // Top subtle vignette for header contrast in full player only
                            if (showTopVignette) {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0.0f to Color.Black.copy(alpha = 0.18f * fullPlayerAlpha),
                                        0.35f to Color.Transparent,
                                        1.0f to Color.Transparent
                                    )
                                )
                            }
                        }

                        if (scrimAlpha > 0f) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.65f to Color.Transparent,
                                    0.82f to Color.Black.copy(alpha = 0.42f * scrimAlpha),
                                    0.92f to Color.Black.copy(alpha = 0.68f * scrimAlpha),
                                    1.0f to Color(0xFF090D13).copy(alpha = 0.82f * scrimAlpha)
                                )
                            )
                        }
                    }
                }
        )
    }
}
