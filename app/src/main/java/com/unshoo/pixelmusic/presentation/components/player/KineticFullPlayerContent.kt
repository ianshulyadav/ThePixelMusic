package com.unshoo.pixelmusic.presentation.components.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
 * slowly turning wavy blob, title/artists, the shared progress bar, a pill play button between
 * rounded skip buttons and a pill toolbar (queue, lyrics, like, add to playlist, sleep timer).
 * Everything is tinted by the album colour scheme ([LocalMaterialTheme]).
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
    val isPlaying = isPlayingProvider()
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
                KineticTitle(song = song, artistsText = artistsText, onArtistClick = actions.onArtistClick, compact = true)
                progressSection()
                KineticTransport(
                    isPlaying = isPlaying,
                    isBufferingProvider = isBufferingProvider,
                    actions = actions,
                    height = 58.dp
                )
                KineticToolbar(
                    isFavorite = isFavoriteProvider(),
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            KineticHeader(queueName = queueName, actions = actions)
            Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(12.dp))
            KineticTitle(song = song, artistsText = artistsText, onArtistClick = actions.onArtistClick, compact = false)
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
                isFavorite = isFavoriteProvider(),
                isSleepTimerActive = isSleepTimerActive,
                actions = actions,
                height = 60.dp
            )
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
            tint = scheme.onSurface,
            onClick = actions.onCollapse
        )
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Now Playing",
                color = scheme.onSurfaceVariant,
                fontSize = 14.sp,
                maxLines = 1
            )
            if (queueName.isNotBlank()) {
                Text(
                    text = queueName,
                    color = scheme.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = actions.onShowQueue)
                )
            }
        }
        KineticIconButton(
            painter = R.drawable.rounded_more_vert_24,
            contentDescription = "More options",
            tint = scheme.onSurface,
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
            modifier = Modifier.size(26.dp)
        )
    }
}

/** Cover art on a wavy blob that turns slowly while music plays. */
@Composable
private fun KineticArtwork(song: Song, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val scheme = LocalMaterialTheme.current
    // A muted, slightly lighter tone of the album colour so the blob reads on any background.
    val blobColor = remember(scheme.secondaryContainer, scheme.onSurface) {
        androidx.compose.ui.graphics.lerp(scheme.secondaryContainer, scheme.onSurface, 0.16f)
    }
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val start = rotation.value % 360f
            rotation.snapTo(start)
            rotation.animateTo(start + 360f, tween(durationMillis = 40_000, easing = LinearEasing))
        }
    }
    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.94f,
        animationSpec = tween(420),
        label = "kineticArtScale"
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation.value }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val base = min(size.width, size.height) / 2f * 0.96f
            val waves = 7
            val amplitude = base * 0.045f
            val path = Path()
            val steps = 180
            for (i in 0..steps) {
                val angle = (i.toFloat() / steps) * 2f * PI.toFloat()
                val r = base - amplitude + amplitude * sin(waves * angle)
                val x = center.x + r * cos(angle)
                val y = center.y + r * sin(angle)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, blobColor)
        }
        SmartImage(
            model = song.albumArtUriString,
            contentDescription = song.title,
            shape = RoundedCornerShape(28.dp),
            crossfadeDurationMillis = 200,
            targetSize = coil.size.Size(1024, 1024),
            modifier = Modifier
                .fillMaxSize(0.84f)
                .graphicsLayer {
                    scaleX = artScale
                    scaleY = artScale
                    shadowElevation = 18.dp.toPx()
                    shape = RoundedCornerShape(28.dp)
                    clip = true
                }
        )
    }
}

@Composable
private fun KineticTitle(song: Song, artistsText: String, onArtistClick: () -> Unit, compact: Boolean) {
    val scheme = LocalMaterialTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = song.title,
            color = scheme.onSurface,
            fontSize = if (compact) 24.sp else 30.sp,
            lineHeight = if (compact) 28.sp else 34.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = artistsText,
            color = scheme.primary,
            fontSize = if (compact) 15.sp else 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onArtistClick)
        )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KineticTransportButton(
            onClick = actions.onPrevious,
            container = scheme.secondaryContainer,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.weight(0.8f)
        ) {
            Icon(
                painter = painterResource(R.drawable.rounded_skip_previous_filled_24),
                contentDescription = "Previous",
                tint = scheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp)
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
            container = scheme.secondaryContainer,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.weight(0.8f)
        ) {
            Icon(
                painter = painterResource(R.drawable.rounded_skip_next_filled_24),
                contentDescription = "Next",
                tint = scheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp)
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
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(120), label = "kineticPress")
    Surface(
        onClick = onClick,
        shape = shape,
        color = container,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun KineticToolbar(
    isFavorite: Boolean,
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
        modifier = modifier
            .height(height)
            .widthIn(max = 360.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            KineticToolbarItem(painter = R.drawable.rounded_queue_music_24, description = "Queue", onClick = actions.onShowQueue)
            KineticToolbarItem(painter = R.drawable.rounded_lyrics_24, description = "Lyrics", onClick = actions.onLyrics)
            val favTint by animateColorAsState(
                if (isFavorite) scheme.onPrimary else scheme.onPrimary.copy(alpha = 0.9f),
                label = "kineticFavTint"
            )
            KineticToolbarItem(
                vector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                description = if (isFavorite) "Unlike" else "Like",
                tint = favTint,
                onClick = actions.onFavoriteToggle
            )
            KineticToolbarItem(painter = R.drawable.rounded_playlist_add_24, description = "Add to playlist", onClick = actions.onAddToPlaylist)
            KineticToolbarItem(
                vector = Icons.Rounded.Bedtime,
                description = if (isSleepTimerActive) "Sleep timer (on)" else "Sleep timer",
                // A running timer shows as a filled chip behind the moon.
                selected = isSleepTimerActive,
                onClick = actions.onSleepTimer
            )
        }
    }
}

@Composable
private fun KineticToolbarItem(
    description: String,
    onClick: () -> Unit,
    painter: Int? = null,
    vector: ImageVector? = null,
    tint: Color = LocalMaterialTheme.current.onPrimary,
    selected: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 44.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(LocalMaterialTheme.current.onPrimary.copy(alpha = 0.16f))
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (vector != null) {
            Icon(vector, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
        } else if (painter != null) {
            Icon(painterResource(painter), contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

