@file:kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.unshoo.pixelmusic.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.size.Size
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded

internal val LocalMaterialTheme = staticCompositionLocalOf<ColorScheme> { error("No ColorScheme provided") }

val MiniPlayerHeight = 64.dp
const val ANIMATION_DURATION_MS = 255
val MiniPlayerBottomSpacer = 8.dp

@Composable
fun getNavigationBarHeight(): Dp {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    return sanitizeNavigationBarBottomInset(insets.calculateBottomPadding())
}

@Composable
internal fun MiniPlayerContentInternal(
    song: Song,
    isPlaying: Boolean,
    isCastConnecting: Boolean,
    isPreparingPlayback: Boolean,
    isBuffering: Boolean = false,
    currentPositionProvider: (() -> Long)? = null,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    cornerRadiusAlb: Dp = 12.dp,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    val controlsEnabled = !isCastConnecting && !isPreparingPlayback && !isBuffering

    val previousInteraction = remember { MutableInteractionSource() }
    val playPauseInteraction = remember { MutableInteractionSource() }
    val nextInteraction = remember { MutableInteractionSource() }
    val latestOnPrevious = rememberUpdatedState(onPrevious)
    val latestOnPlayPause = rememberUpdatedState(onPlayPause)
    val latestOnNext = rememberUpdatedState(onNext)
    val miniPlayerIndication = remember { ripple(bounded = false) }

    // Album art with clean rounded corners
    val artShape = remember(cornerRadiusAlb) { RoundedCornerShape(cornerRadiusAlb) }

    // Hero Play/Pause button shape morphing:
    // Morphs between squircle (14dp) when playing and circle (22dp) when paused.
    val playPauseCornerRadius by animateDpAsState(
        targetValue = if (isPlaying) 14.dp else 22.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "miniPlayPauseCorner"
    )
    val playPauseShape = remember(playPauseCornerRadius) { RoundedCornerShape(playPauseCornerRadius) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
    ) {
        val availableWidth = maxWidth
        val showPreviousButton = availableWidth >= 380.dp

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val albumArtModel = song.albumArtUriString?.takeIf { it.isNotBlank() }
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                key(song.id) {
                    SmartImage(
                        model = albumArtModel,
                        contentDescription = "Carátula de ${song.title}",
                        shape = artShape,
                        targetSize = SmartImageIntermediateTargetSize,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(artShape),
                        placeholderModel = if (albumArtModel?.startsWith("telegram_art") == true) {
                            "$albumArtModel?quality=thumb"
                        } else null
                    )
                }
                if (isCastConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = LocalMaterialTheme.current.onPrimaryContainer
                    )
                } else if (isPreparingPlayback) {
                    CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                val titleStyle = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                    fontFamily = GoogleSansRounded,
                    color = LocalMaterialTheme.current.onPrimaryContainer
                )
                val artistStyle = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.sp,
                    fontFamily = GoogleSansRounded,
                    color = LocalMaterialTheme.current.onPrimaryContainer.copy(alpha = 0.72f)
                )

                AutoScrollingText(
                    text = when {
                        isCastConnecting -> "Connecting to device…"
                        else -> song.title
                    },
                    style = titleStyle,
                    gradientEdgeColor = LocalMaterialTheme.current.primaryContainer
                )
                AutoScrollingText(
                    text = song.displayArtist,
                    style = artistStyle,
                    gradientEdgeColor = LocalMaterialTheme.current.primaryContainer
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showPreviousButton) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(LocalMaterialTheme.current.onPrimaryContainer.copy(alpha = 0.08f))
                            .clickable(
                                interactionSource = previousInteraction,
                                indication = miniPlayerIndication,
                                enabled = controlsEnabled
                            ) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                latestOnPrevious.value()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = "Anterior",
                            tint = LocalMaterialTheme.current.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Primary Hero Play/Pause Button with morphing shape
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(playPauseShape)
                        .background(LocalMaterialTheme.current.primary)
                        .clickable(
                            interactionSource = playPauseInteraction,
                            indication = miniPlayerIndication,
                            enabled = controlsEnabled
                        ) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnPlayPause.value()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if ((isPreparingPlayback || isBuffering) && !isPlaying) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = LocalMaterialTheme.current.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                            tint = LocalMaterialTheme.current.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Next Button
                val nextButtonSize = if (showPreviousButton) 36.dp else 40.dp
                Box(
                    modifier = Modifier
                        .size(nextButtonSize)
                        .clip(CircleShape)
                        .background(LocalMaterialTheme.current.onPrimaryContainer.copy(alpha = 0.08f))
                        .clickable(
                            interactionSource = nextInteraction,
                            indication = miniPlayerIndication,
                            enabled = controlsEnabled
                        ) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnNext.value()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Siguiente",
                        tint = LocalMaterialTheme.current.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
