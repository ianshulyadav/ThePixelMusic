package com.unshoo.pixelmusic.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import coil.size.Size
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.preferences.CarouselStyle
import com.unshoo.pixelmusic.presentation.components.scoped.PrefetchAlbumNeighbors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.first

import com.unshoo.pixelmusic.data.preferences.AlbumArtQuality
import kotlin.math.abs

// ====== TIPOS/STATE DEL CARRUSEL (wrapper para mantener compatibilidad) ======

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberRoundedParallaxCarouselState(
    initialPage: Int,
    pageCount: () -> Int
): CarouselState = rememberCarouselState(initialItem = initialPage, itemCount = pageCount)

// ====== TU SECCIÓN: ACOPLADA AL NUEVO API ======

private const val MAX_ANIMATED_CAROUSEL_JUMP = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumCarouselSection(
    currentSong: Song?,
    queue: ImmutableList<Song>,
    expansionFraction: Float,
    currentMediaItemIndex: Int = -1,
    requestedScrollIndex: Int? = null,
    onSongSelected: (Song) -> Unit,
    onAlbumClick: (Song) -> Unit = {},
    modifier: Modifier = Modifier,
    carouselStyle: String = CarouselStyle.NO_PEEK,
    itemSpacing: Dp = 8.dp,
    albumArtQuality: AlbumArtQuality = AlbumArtQuality.ORIGINAL,
    /** True once the current song is audibly playing; full-resolution art waits for it. */
    playbackStartedProvider: () -> Boolean = { true },
) {
    val targetSize = remember(albumArtQuality) {
        if (albumArtQuality.maxSize == 0) PlayerCoverTargetSize
        else Size(albumArtQuality.maxSize, albumArtQuality.maxSize)
    }
    val currentSongIndex = remember(currentSong?.id, currentMediaItemIndex, queue) {
        resolveCurrentQueueIndex(currentSong, currentMediaItemIndex, queue)
    }
    if (currentSongIndex < 0) {
        // The MediaSession can expose the incoming crossfade song before its queue
        // snapshot catches up. Never show queue[0] as a stand-in for its cover.
        currentSong?.let { song ->
            val artModel = remember(song.id, song.albumArtUriString, song.youtubeId) {
                com.unshoo.pixelmusic.utils.MediaItemBuilder.resolveArtworkUri(
                    song.albumArtUriString, song.id, song.youtubeId
                ) ?: song.albumArtUriString
            }
            OptimizedAlbumArt(
                uri = artModel,
                title = song.title,
                modifier = modifier.clip(RoundedCornerShape(18.dp)),
                targetSize = targetSize
            )
        }
        return
    }

    // Keep the carousel state stable when queue contents refresh. Keying the whole
    // subtree by the queue object recreates pager state and can leave horizontal
    // swipes feeling dead after large library/queue updates.
    // Mantiene compatibilidad con tu llamada actual
    val initialIndex = currentSongIndex

    val carouselState = rememberRoundedParallaxCarouselState(
        initialPage = initialIndex,
        pageCount = { queue.size }
    )

    // Player -> Carousel
    val requestedTargetIndex = remember(requestedScrollIndex, queue) {
        requestedScrollIndex?.takeIf { it in queue.indices }
    }
    val effectiveTargetIndex = requestedTargetIndex ?: currentSongIndex
    val carouselItemKey: (Int) -> Any = remember(queue) {
        { index ->
            queue.getOrNull(index)?.let { song ->
                "queue_carousel_${song.id}_${song.albumArtUriString.orEmpty().hashCode()}_$index"
            } ?: "queue_item_$index"
        }
    }

    PrefetchAlbumNeighbors(
        isActive = expansionFraction > 0.08f,
        pagerState = carouselState.pagerState,
        queue = queue,
        radius = 1,
        targetSize = targetSize,
        anchorIndex = effectiveTargetIndex
    )
    var ignoreNextSettledSelectionForPage by remember { mutableStateOf<Int?>(null) }
    var programmaticScrollInProgress by remember { mutableStateOf(false) }
    var lastSettledSongId by remember { mutableStateOf(currentSong?.id) }
    LaunchedEffect(effectiveTargetIndex, requestedTargetIndex, queue) {
        val currentPage = carouselState.pagerState.currentPage
        if (currentPage != effectiveTargetIndex) {
            val songChanged = currentSong?.id != lastSettledSongId
            // Announce programmatic movement before the pager can emit an idle event.
            // Otherwise the old page can be mistaken for a user selection mid-crossfade.
            ignoreNextSettledSelectionForPage = effectiveTargetIndex
            programmaticScrollInProgress = true
            try {
                if (songChanged || abs(currentPage - effectiveTargetIndex) > MAX_ANIMATED_CAROUSEL_JUMP) {
                    // Playback has already changed: show its cover immediately, including
                    // when the incoming crossfade deck becomes the displayed song.
                    carouselState.scrollToItem(effectiveTargetIndex)
                } else {
                    carouselState.animateScrollToItem(effectiveTargetIndex)
                }
            } finally {
                programmaticScrollInProgress = false
            }
        }
        lastSettledSongId = currentSong?.id
    }

    val hapticFeedback = LocalHapticFeedback.current
    // Carousel -> Player (cuando se detiene el scroll)
    LaunchedEffect(carouselState, currentSongIndex, queue) {
        var observedScroll = false
        snapshotFlow { carouselState.pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) {
                    observedScroll = true
                    return@collect
                }
                // Initial idle and stale idle callbacks are not swipes.
                if (!observedScroll) return@collect
                observedScroll = false
                if (programmaticScrollInProgress) return@collect // Prevent settled event races during transitions
                val settled = carouselState.pagerState.currentPage
                if (ignoreNextSettledSelectionForPage == settled) {
                    ignoreNextSettledSelectionForPage = null
                    return@collect
                }
                if (settled != currentSongIndex) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    queue.getOrNull(settled)?.let(onSongSelected)
                }
            }
    }

    val corner = 18.dp//lerp(36.dp, 15.dp, expansionFraction.coerceIn(0f, 1f))

    BoxWithConstraints(modifier = modifier) {
        val availableWidth = this.maxWidth

        RoundedHorizontalMultiBrowseCarousel(
            state = carouselState,
            modifier = Modifier.fillMaxSize(), // Fill the space provided by the parent's modifier
            itemSpacing = itemSpacing,
            itemCornerRadius = corner,
            suppressNoPeekSettleCorrection = requestedTargetIndex != null || programmaticScrollInProgress,
            carouselStyle = if (carouselState.pagerState.pageCount == 1) CarouselStyle.NO_PEEK else carouselStyle, // Handle single-item case
            carouselWidth = availableWidth, // Pass the full width for layout calculations
            itemKey = carouselItemKey,
            content = { index ->
                // During an automatic transition the displayed song changes before the
                // pager settles. Render that song on the focused page so the old page
                // (often queue index 0) cannot flash its artwork for a frame.
                val queuedSong = queue[index]
                val song = if (index == carouselState.pagerState.currentPage &&
                    !carouselState.pagerState.isScrollInProgress &&
                    currentSong != null &&
                    queuedSong.id.removePrefix("song:") != currentSong.id.removePrefix("song:")
                ) currentSong else queuedSong
                val isFocusedItem = carouselState.pagerState.currentPage == index
                Box(
                    Modifier
                        .fillMaxSize()
                        .aspectRatio(1f)
                        .clickable(
                            enabled = isFocusedItem && song.albumId != -1L,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onAlbumClick(song) }
                        )
                ) { // Enforce 1:1 aspect ratio for the item itself
                    // Songs saved without a cover URL (common for YouTube rows) fall back to the
                    // video thumbnail / local embedded art instead of the blank placeholder.
                    val artModel = remember(song.id, song.albumArtUriString, song.youtubeId) {
                        com.unshoo.pixelmusic.utils.MediaItemBuilder.resolveArtworkUri(
                            song.albumArtUriString, song.id, song.youtubeId
                        ) ?: song.albumArtUriString
                    }
                    // Low-quality cover first; the focused song's full-resolution cover is only
                    // fetched once playback has started (or after a short grace period), so it
                    // never competes with the audio stream for bandwidth at song start.
                    var highQualityUnlocked by remember(song.id) { mutableStateOf(!isFocusedItem) }
                    if (isFocusedItem && !highQualityUnlocked) {
                        LaunchedEffect(song.id) {
                            kotlinx.coroutines.withTimeoutOrNull(HIGH_QUALITY_ART_GRACE_MS) {
                                snapshotFlow { playbackStartedProvider() }.first { it }
                            }
                            highQualityUnlocked = true
                        }
                    }
                    OptimizedAlbumArt(
                        uri = artModel,
                        title = song.title,
                        modifier = Modifier.fillMaxSize(),
                        targetSize = targetSize,
                        loadHighQuality = highQualityUnlocked,
                        placeholderModel = if (song.albumArtUriString?.startsWith("telegram_art") == true) {
                             "${song.albumArtUriString}?quality=thumb"
                        } else null
                    )
                }
            }
        )
    }
}

private fun resolveCurrentQueueIndex(
    currentSong: Song?,
    currentMediaItemIndex: Int,
    queue: ImmutableList<Song>
): Int {
    val songId = currentSong?.id?.removePrefix("song:") ?: return -1
    if (currentMediaItemIndex in queue.indices && queue[currentMediaItemIndex].id.removePrefix("song:") == songId) {
        return currentMediaItemIndex
    }

    // Most player updates move by one item. Search a small window around the media3 index
    // before falling back to an O(n) scan, which keeps very large queues smooth.
    if (currentMediaItemIndex in queue.indices) {
        val from = (currentMediaItemIndex - 6).coerceAtLeast(0)
        val to = (currentMediaItemIndex + 6).coerceAtMost(queue.lastIndex)
        for (index in from..to) {
            if (queue[index].id.removePrefix("song:") == songId) return index
        }
    }

    return queue.indexOfFirst { it.id.removePrefix("song:") == songId }
}

/** How long the focused cover stays low-quality when playback has not started yet. */
private const val HIGH_QUALITY_ART_GRACE_MS = 700L
