package com.unshoo.pixelmusic.presentation.components.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.presentation.components.LocalMaterialTheme
import com.unshoo.pixelmusic.ui.theme.LocalPixelMusicDarkTheme

/**
 * Material 3 Expressive transport controls:
 *  [  play / pause (wide)  ] [ prev ] [ next ]
 *  ( shuffle | repeat | lyrics )          ( + )
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressivePlayerControls(
    isPlayingProvider: () -> Boolean,
    isBufferingProvider: () -> Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onLyricsClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    playPauseContainer: Color,
    playPauseContent: Color,
    skipContainer: Color,
    skipContent: Color,
    isBlurActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val longPressSeekConfig = LocalLongPressSeekConfig.current
    val latestOnHoldBackwardStart = androidx.compose.runtime.rememberUpdatedState(longPressSeekConfig.onHoldBackwardStart)
    val latestOnHoldForwardStart = androidx.compose.runtime.rememberUpdatedState(longPressSeekConfig.onHoldForwardStart)
    val latestOnHoldEnd = androidx.compose.runtime.rememberUpdatedState(longPressSeekConfig.onHoldEnd)
    val isPlaying = isPlayingProvider()
    val isBuffering = isBufferingProvider()
    val isDark = LocalPixelMusicDarkTheme.current
    val currentTheme = LocalMaterialTheme.current

    // Toolbar colours: in dark theme, dark frosted tint of the player background with light segments;
    // in light theme, solid white (blur off) or subtly frosted white (blur on) with grey segments.
    val toolbarContainer = if (isDark) {
        if (isBlurActive) Color.Black.copy(alpha = 0.30f) else currentTheme.surfaceContainer.copy(alpha = 0.90f)
    } else {
        // Light theme: solid white with blur off; with blur on only lightly influenced by
        // the frosted backdrop so the row still reads as clean white.
        if (isBlurActive) Color.White.copy(alpha = 0.80f) else Color.White
    }
    val segmentInactive = if (isDark) {
        Color.White.copy(alpha = 0.07f)
    } else {
        // Neutral grey segments (not album-tinted) on the white bar.
        Color(0xFF1F1F1F).copy(alpha = 0.07f)
    }
    val segmentActive = skipContainer
    val segmentActiveContent = skipContent
    val segmentInactiveContent = if (isDark) {
        Color.White.copy(alpha = 0.72f)
    } else {
        Color(0xFF1F1F1F).copy(alpha = 0.78f)
    }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier) {
    // Auto-size to the display width: bigger buttons on wide phones, compact on small ones.
    val transportHeight = (maxWidth * 0.30f).coerceIn(68.dp, 118.dp)
    // Reference proportions: round button ≈ 18% of the width, bar fills the rest.
    val toolbarHeight = (maxWidth * 0.18f).coerceIn(52.dp, 68.dp)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(transportHeight),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val playCorner by animateDpAsState(
                targetValue = if (isPlaying) 26.dp else transportHeight / 2,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "playPauseCorner"
            )
            ExpressiveTile(
                modifier = Modifier.weight(1.75f),
                shape = RoundedCornerShape(playCorner),
                color = playPauseContainer,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPlayPause()
                }
            ) {
                if (isBuffering) {
                    // Same M3 Expressive morphing loader used elsewhere in the app.
                    androidx.compose.material3.LoadingIndicator(
                        modifier = Modifier.size(52.dp),
                        color = playPauseContent
                    )
                } else {
                    AnimatedContent(
                        targetState = isPlaying,
                        transitionSpec = {
                            (fadeIn() + scaleIn(initialScale = 0.7f)) togetherWith
                                (fadeOut() + scaleOut(targetScale = 0.7f))
                        },
                        label = "playPauseIcon"
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = playPauseContent,
                            modifier = Modifier.size(46.dp)
                        )
                    }
                }
            }
            ExpressiveTile(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                color = skipContainer,
                longPressEnabled = longPressSeekConfig.enabled,
                onHoldStart = { latestOnHoldBackwardStart.value() },
                onHoldEnd = { latestOnHoldEnd.value() },
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPrevious()
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.rounded_skip_previous_filled_24),
                    contentDescription = "Previous",
                    tint = skipContent,
                    modifier = Modifier.size(38.dp)
                )
            }
            ExpressiveTile(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                color = skipContainer,
                longPressEnabled = longPressSeekConfig.enabled,
                onHoldStart = { latestOnHoldForwardStart.value() },
                onHoldEnd = { latestOnHoldEnd.value() },
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onNext()
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.rounded_skip_next_filled_24),
                    contentDescription = "Next",
                    tint = skipContent,
                    modifier = Modifier.size(38.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(toolbarHeight),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(toolbarContainer)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ToolbarSegment(
                    position = SegmentPosition.First,
                    iconRes = R.drawable.rounded_shuffle_24,
                    contentDescription = "Shuffle",
                    active = isShuffleEnabled,
                    activeColor = segmentActive,
                    activeContent = segmentActiveContent,
                    inactiveColor = segmentInactive,
                    inactiveContent = segmentInactiveContent,
                    onClick = onShuffleToggle
                )
                ToolbarSegment(
                    position = SegmentPosition.Middle,
                    iconRes = if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.rounded_repeat_one_24 else R.drawable.rounded_repeat_24,
                    contentDescription = "Repeat",
                    active = repeatMode != Player.REPEAT_MODE_OFF,
                    activeColor = segmentActive,
                    activeContent = segmentActiveContent,
                    inactiveColor = segmentInactive,
                    inactiveContent = segmentInactiveContent,
                    onClick = onRepeatToggle
                )
                ToolbarSegment(
                    position = SegmentPosition.Last,
                    iconRes = R.drawable.rounded_lyrics_24,
                    contentDescription = "Lyrics",
                    active = false,
                    activeColor = segmentActive,
                    activeContent = segmentActiveContent,
                    inactiveColor = segmentInactive,
                    inactiveContent = segmentInactiveContent,
                    onClick = onLyricsClick
                )
            }
            ExpressiveTile(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
                shape = CircleShape,
                color = toolbarContainer,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onAddToPlaylistClick()
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.rounded_more_vert_24),
                    contentDescription = "More options",
                    tint = segmentInactiveContent,
                    modifier = Modifier.size(25.dp)
                )
            }
        }
    }
    }
}

@Composable
private fun ExpressiveTile(
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    color: Color,
    longPressEnabled: Boolean = false,
    onHoldStart: () -> Unit = {},
    onHoldEnd: () -> Unit = {},
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tileScale"
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(color)
            .transportHoldToSeek(
                interactionSource = interaction,
                indication = null,
                longPressEnabled = longPressEnabled,
                onHoldStart = onHoldStart,
                onHoldEnd = onHoldEnd,
                onTap = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun RowScope.ToolbarSegment(
    position: SegmentPosition,
    iconRes: Int,
    contentDescription: String,
    active: Boolean,
    activeColor: Color,
    activeContent: Color,
    inactiveColor: Color,
    inactiveContent: Color,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val bg by animateColorAsState(if (active) activeColor else inactiveColor, label = "segmentBg")
    val fg by animateColorAsState(if (active) activeContent else inactiveContent, label = "segmentFg")
    // Connected-button-group corners: outer edges follow the pill, inner edges stay tight;
    // the active segment morphs into a full pill.
    val bouncy = spring<Dp>(dampingRatio = Spring.DampingRatioMediumBouncy)
    val outer = 40.dp
    val inner = 10.dp
    val startCorner by animateDpAsState(
        if (active || position == SegmentPosition.First) outer else inner, bouncy, label = "segStart"
    )
    val endCorner by animateDpAsState(
        if (active || position == SegmentPosition.Last) outer else inner, bouncy, label = "segEnd"
    )
    ExpressiveTile(
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(
            topStart = startCorner, bottomStart = startCorner,
            topEnd = endCorner, bottomEnd = endCorner
        ),
        color = bg,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = fg,
            modifier = Modifier.size(25.dp)
        )
    }
}

private enum class SegmentPosition { First, Middle, Last }
