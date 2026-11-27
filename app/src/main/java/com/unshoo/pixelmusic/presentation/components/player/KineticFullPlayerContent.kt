package com.unshoo.pixelmusic.presentation.components.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.presentation.components.LocalMaterialTheme
import com.unshoo.pixelmusic.presentation.components.SmartImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * "Kinetic" Now Playing layout: header with the queue name, square cover art floating on a
 * scalloped "cookie" shape that turns while music plays, title/artists with a like button, the
 * shared progress bar, a pill play button between rounded rewind/forward buttons and a pill
 * toolbar (queue, lyrics, add to playlist, sleep timer). Everything is tinted by the album
 * colour scheme ([LocalMaterialTheme]).
 *
 * Only the visible layout lives here; sheets, dialogs and player state are owned by
 * [ExpressiveFullPlayerContent], which renders this when the Kinetic style is selected.
 */
internal class KineticPlayerActions(
    val onCollapse: () -> Unit,
    val onMore: () -> Unit,
    val onShowQueue: () -> Unit,
    val onLyrics: () -> Unit,
    val onFavoriteToggle: () -> Unit,
    val onAddToPlaylist: () -> Unit,
    val onSleepTimer: () -> Unit,
    val onSongInfo: () -> Unit,
    val onShuffleToggle: () -> Unit,
    val onRepeatToggle: () -> Unit,
    val isShuffleEnabledProvider: () -> Boolean,
    val repeatModeProvider: () -> Int,
    val onArtistClick: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit
)

@Composable
internal fun KineticPlayerLayout(
    isLandscape: Boolean,
    paddingValues: PaddingValues,
    song: Song,
    artistsText: String,
    queueName: String,
    isPlayingProvider: () -> Boolean,
    isBufferingProvider: () -> Boolean,
    isFavoriteProvider: () -> Boolean,
    isSleepTimerActive: Boolean,
    progressSection: @Composable () -> Unit,
    actions: KineticPlayerActions
) {
    val scheme = LocalMaterialTheme.current
    val isPlaying = isPlayingProvider()
    val isFavorite = isFavoriteProvider()
    // No backdrop of its own: the player sheet's normal colour shows through, so the sheet edges
    // (visible while it expands/collapses) and the player are one colour with no extra tint.
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                KineticArtwork(
                    song = song,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    KineticHeader(queueName = queueName, actions = actions)
                    KineticTitle(
                        song = song,
                        artistsText = artistsText,
                        isFavorite = isFavorite,
                        actions = actions,
                        compact = true
                    )
                    progressSection()
                    KineticTransport(
                        isPlaying = isPlaying,
                        isBufferingProvider = isBufferingProvider,
                        actions = actions,
                        height = 58.dp
                    )
                    KineticToolbar(
                        isSleepTimerActive = isSleepTimerActive,
                        actions = actions,
                        height = 50.dp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                KineticHeader(queueName = queueName, actions = actions)
                // Takes the leftover height so short screens shrink the art, not the controls.
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val side = min(maxWidth.value, maxHeight.value).dp
                    KineticArtwork(song = song, isPlaying = isPlaying, modifier = Modifier.size(side))
                }
                Spacer(Modifier.height(10.dp))
                KineticTitle(
                    song = song,
                    artistsText = artistsText,
                    isFavorite = isFavorite,
                    actions = actions,
                    compact = false
                )
                Spacer(Modifier.height(12.dp))
                progressSection()
                Spacer(Modifier.height(14.dp))
                KineticTransport(
                    isPlaying = isPlaying,
                    isBufferingProvider = isBufferingProvider,
                    actions = actions,
                    height = 76.dp
                )
                Spacer(Modifier.height(18.dp))
                KineticToolbar(
                    isSleepTimerActive = isSleepTimerActive,
                    actions = actions,
                    height = 64.dp
                )
            }
        }
    }
}

@Composable
private fun KineticHeader(queueName: String, actions: KineticPlayerActions) {
    val scheme = LocalMaterialTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KineticIconButton(
            painter = R.drawable.rounded_keyboard_arrow_down_24,
            contentDescription = "Collapse player",
            tint = scheme.primary,
            onClick = actions.onCollapse
        )
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Now Playing",
                color = scheme.primary.copy(alpha = 0.85f),
                fontSize = 15.sp,
                maxLines = 1
            )
            if (queueName.isNotBlank()) {
                Text(
                    text = queueName,
                    color = scheme.primary,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = actions.onShowQueue)
                )
            }
        }
        KineticIconButton(
            painter = R.drawable.rounded_more_vert_24,
            contentDescription = "More options",
            tint = scheme.primary,
            // Opens the player's options sheet (play next, add to playlist, sleep timer, share).
            onClick = actions.onMore
        )
    }
}

@Composable
private fun KineticIconButton(
    painter: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(painter),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(28.dp)
        )
    }
}

/**
 * Cover art on a scalloped "cookie" shape. The shape turns while music plays and eases to a
 * stop when paused. Until the cover has loaded only the shape is shown (no placeholder box),
 * then the art scales in on top of it.
 */
@Composable
private fun KineticArtwork(song: Song, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val scheme = LocalMaterialTheme.current
    val performanceModeEnabled = com.unshoo.pixelmusic.ui.theme.LocalPerformanceModeEnabled.current
    val shapeColor = scheme.secondaryContainer
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying, performanceModeEnabled) {
        if (performanceModeEnabled) {
            rotation.stop()
        } else if (isPlaying) {
            while (true) {
                val start = rotation.value % 360f
                rotation.snapTo(start)
                rotation.animateTo(start + 360f, tween(durationMillis = 24_000, easing = LinearEasing))
            }
        } else {
            // Coast to a stop instead of freezing mid-turn.
            rotation.animateTo(
                rotation.value + 8f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow)
            )
        }
    }
    // Same artwork model the mini player uses, so the two share one cached bitmap.
    val currentArt = com.unshoo.pixelmusic.utils.MediaItemBuilder.resolveArtworkUri(song.albumArtUriString, song.id, song.youtubeId)
    // Last cover that finished loading. On a song change it stays on screen until the new cover
    // has loaded, then the new one cross-fades in over it. Before, the old art vanished at once
    // and the empty shape showed for ~1s, which read as the player flashing/glitching.
    var settledArt by remember { mutableStateOf<String?>(null) }
    var artLoaded by remember(currentArt) { mutableStateOf(false) }
    var artFailed by remember(currentArt) { mutableStateOf(currentArt.isNullOrBlank()) }
    val firstReveal = settledArt == null
    val fadeIn = remember(currentArt) { Animatable(if (currentArt != null && currentArt == settledArt) 1f else 0f) }
    LaunchedEffect(currentArt, artLoaded, artFailed, performanceModeEnabled) {
        when {
            artLoaded -> {
                if (performanceModeEnabled) {
                    fadeIn.snapTo(1f)
                } else {
                    fadeIn.animateTo(
                        1f,
                        if (firstReveal) spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
                        else tween(durationMillis = 320, easing = LinearEasing)
                    )
                }
                settledArt = currentArt
            }
            artFailed -> settledArt = null
        }
    }
    val playScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.95f,
        animationSpec = if (performanceModeEnabled) androidx.compose.animation.core.snap() else tween(420),
        label = "kineticArtScale"
    )
    val artShape = RoundedCornerShape(28.dp)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation.value }
        ) {
            drawPath(cookiePath(size.width, size.height), shapeColor)
        }
        if (artFailed && settledArt == null) {
            Icon(
                painter = painterResource(R.drawable.ic_music_placeholder),
                contentDescription = null,
                tint = scheme.onSecondaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxSize(0.3f)
            )
        }
        val outgoing = settledArt
        if (outgoing != null && outgoing != currentArt) {
            // Previous cover, held fully opaque underneath (it is already in the memory cache).
            SmartImage(
                model = outgoing,
                contentDescription = null,
                shape = artShape,
                placeholderResId = 0,
                errorResId = 0,
                placeHolderBackgroundColor = Color.Transparent,
                crossfadeDurationMillis = 0,
                targetSize = com.unshoo.pixelmusic.presentation.components.PlayerCoverTargetSize,
                modifier = Modifier
                    .fillMaxSize(0.82f)
                    .graphicsLayer {
                        scaleX = playScale
                        scaleY = playScale
                        shadowElevation = 18.dp.toPx()
                        shape = artShape
                        clip = true
                    }
            )
        }
        SmartImage(
            model = currentArt,
            contentDescription = song.title,
            shape = artShape,
            placeholderResId = 0,
            errorResId = 0,
            placeHolderBackgroundColor = Color.Transparent,
            crossfadeDurationMillis = 0,
            targetSize = com.unshoo.pixelmusic.presentation.components.PlayerCoverTargetSize,
            onState = { state ->
                artLoaded = state is AsyncImagePainter.State.Success
                artFailed = state is AsyncImagePainter.State.Error
            },
            modifier = Modifier
                .fillMaxSize(0.82f)
                .graphicsLayer {
                    val reveal = fadeIn.value
                    // Only the very first cover scales in; later covers just cross-fade.
                    val s = if (firstReveal) playScale * (0.86f + 0.14f * reveal) else playScale
                    scaleX = s
                    scaleY = s
                    alpha = reveal
                    // Shadow only once there is art, so nothing box-shaped shows while loading.
                    shadowElevation = if (outgoing != null && outgoing != currentArt) 0f else 18.dp.toPx() * reveal
                    shape = artShape
                    clip = true
                }
        )
    }
}

/** Scalloped circle ("cookie"): [LOBES] rounded bumps around the edge. */
private const val LOBES = 9

private fun cookiePath(width: Float, height: Float): Path {
    val center = Offset(width / 2f, height / 2f)
    val outer = min(width, height) / 2f
    val amplitude = outer * 0.055f
    val base = outer - amplitude
    val path = Path()
    val steps = 360
    for (i in 0..steps) {
        val angle = (i.toFloat() / steps) * 2f * PI.toFloat()
        // A cosine ripple around the circle gives evenly spaced rounded lobes (M3 "cookie").
        val r = base + amplitude * cos(LOBES * angle)
        val x = center.x + r * cos(angle)
        val y = center.y + r * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

@Composable
private fun KineticTitle(
    song: Song,
    artistsText: String,
    isFavorite: Boolean,
    actions: KineticPlayerActions,
    compact: Boolean
) {
    val scheme = LocalMaterialTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = scheme.primary,
                fontSize = if (compact) 24.sp else 30.sp,
                lineHeight = if (compact) 28.sp else 34.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = artistsText,
                color = scheme.primary.copy(alpha = 0.85f),
                fontSize = if (compact) 15.sp else 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = actions.onArtistClick)
            )
        }
        Spacer(Modifier.width(12.dp))
        val likeContainer by animateColorAsState(
            if (isFavorite) scheme.primary else scheme.secondaryContainer,
            label = "kineticLikeBg"
        )
        val likeContent by animateColorAsState(
            if (isFavorite) scheme.onPrimary else scheme.onSecondaryContainer,
            label = "kineticLikeFg"
        )
        val likeScale by animateFloatAsState(
            targetValue = if (isFavorite) 1f else 0.94f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "kineticLikeScale"
        )
        Surface(
            onClick = actions.onFavoriteToggle,
            shape = CircleShape,
            color = likeContainer,
            modifier = Modifier
                .size(if (compact) 48.dp else 54.dp)
                .graphicsLayer {
                    scaleX = likeScale
                    scaleY = likeScale
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isFavorite) "Unlike" else "Like",
                    tint = likeContent,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun KineticTransport(
    isPlaying: Boolean,
    isBufferingProvider: () -> Boolean,
    actions: KineticPlayerActions,
    height: Dp
) {
    val scheme = LocalMaterialTheme.current
    val longPressSeekConfig = LocalLongPressSeekConfig.current
    val latestOnHoldBackwardStart = androidx.compose.runtime.rememberUpdatedState(longPressSeekConfig.onHoldBackwardStart)
    val latestOnHoldForwardStart = androidx.compose.runtime.rememberUpdatedState(longPressSeekConfig.onHoldForwardStart)
    val latestOnHoldEnd = androidx.compose.runtime.rememberUpdatedState(longPressSeekConfig.onHoldEnd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KineticTransportButton(
            onClick = actions.onPrevious,
            longPressEnabled = longPressSeekConfig.enabled,
            onHoldStart = { latestOnHoldBackwardStart.value() },
            onHoldEnd = { latestOnHoldEnd.value() },
            container = scheme.secondaryContainer,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.weight(0.8f)
        ) {
            Icon(
                imageVector = Icons.Rounded.FastRewind,
                contentDescription = "Previous",
                tint = scheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
        KineticTransportButton(
            onClick = actions.onPlayPause,
            container = scheme.primary,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.weight(1.45f)
        ) {
            if (isBufferingProvider() && !isPlaying) {
                CircularProgressIndicator(
                    color = scheme.onPrimary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Icon(
                    painter = painterResource(
                        if (isPlaying) R.drawable.rounded_pause_filled_24 else R.drawable.rounded_play_arrow_filled_24
                    ),
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        KineticTransportButton(
            onClick = actions.onNext,
            longPressEnabled = longPressSeekConfig.enabled,
            onHoldStart = { latestOnHoldForwardStart.value() },
            onHoldEnd = { latestOnHoldEnd.value() },
            container = scheme.secondaryContainer,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.weight(0.8f)
        ) {
            Icon(
                imageVector = Icons.Rounded.FastForward,
                contentDescription = "Next",
                tint = scheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun KineticTransportButton(
    onClick: () -> Unit,
    container: Color,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    longPressEnabled: Boolean = false,
    onHoldStart: () -> Unit = {},
    onHoldEnd: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(120), label = "kineticPress")
    Surface(
        shape = shape,
        color = container,
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .transportHoldToSeek(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                longPressEnabled = longPressEnabled,
                onHoldStart = onHoldStart,
                onHoldEnd = onHoldEnd,
                onTap = onClick
            )
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun KineticToolbar(
    isSleepTimerActive: Boolean,
    actions: KineticPlayerActions,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val scheme = LocalMaterialTheme.current
    Surface(
        color = scheme.primary,
        contentColor = scheme.onPrimary,
        shape = CircleShape,
        modifier = modifier.height(height)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Shuffle, repeat, lyrics, queue. Add to playlist and the sleep timer live in the
            // header's three-dot menu.
            val shuffleOn = actions.isShuffleEnabledProvider()
            val repeatMode = actions.repeatModeProvider()
            KineticToolbarItem(
                painter = R.drawable.rounded_shuffle_24,
                description = if (shuffleOn) "Shuffle on" else "Shuffle off",
                selected = shuffleOn,
                onClick = actions.onShuffleToggle
            )
            KineticToolbarItem(
                painter = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) {
                    R.drawable.rounded_repeat_one_24
                } else {
                    R.drawable.rounded_repeat_24
                },
                description = when (repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ONE -> "Repeat one"
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
                selected = repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF,
                onClick = actions.onRepeatToggle
            )
            KineticToolbarItem(painter = R.drawable.rounded_lyrics_24, description = "Lyrics", onClick = actions.onLyrics)
            KineticToolbarItem(painter = R.drawable.rounded_queue_music_24, description = "Queue", onClick = actions.onShowQueue)
        }
    }
}

@Composable
private fun KineticToolbarItem(
    description: String,
    onClick: () -> Unit,
    painter: Int? = null,
    vector: ImageVector? = null,
    selected: Boolean = false
) {
    val scheme = LocalMaterialTheme.current
    // On: a solid pill in the toolbar's content colour with the icon in the toolbar colour
    // (clear on/off contrast), animated so toggling feels smooth.
    val container by animateColorAsState(
        if (selected) scheme.onPrimary else Color.Transparent, label = "kineticItemBg"
    )
    val tint by animateColorAsState(
        if (selected) scheme.primary else scheme.onPrimary, label = "kineticItemTint"
    )
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 40.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (vector != null) {
            Icon(vector, contentDescription = description, tint = tint, modifier = Modifier.size(26.dp))
        } else if (painter != null) {
            Icon(painterResource(painter), contentDescription = description, tint = tint, modifier = Modifier.size(26.dp))
        }
    }
}
