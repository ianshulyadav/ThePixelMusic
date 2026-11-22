package com.unshoo.pixelmusic.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.size.Size
import com.unshoo.pixelmusic.data.model.Song

/** The two Material 3 carousel layouts offered for Quick Picks. */
enum class QuickPicksM3CarouselStyle {
    /** M3 "multi-browse": one large, one medium and one small item visible (3 songs). */
    MULTI_BROWSE,

    /** M3 "hero" (centered): one large item between two small peeking items (3 songs). */
    HERO,
}

/** Same height as the Home Recently Played shelf (3 pills x 58dp + 2 x 8dp gaps). */
private val QuickPicksM3CarouselHeight = 190.dp
private val QuickPicksM3ItemSpacing = 8.dp

/**
 * Quick Picks rendered with the official Material 3 Expressive carousels
 * (m3.material.io/components/carousel): items keep their size along the scroll axis and are
 * masked by the carousel, so neighbours shrink/grow smoothly as you swipe. Three songs are
 * visible at a time. Tap plays, long-press opens quick actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickPicksM3Carousel(
    songs: List<Song>,
    style: QuickPicksM3CarouselStyle,
    activePlayingSongId: String?,
    onSongClick: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) return
    val state = rememberCarouselState { songs.size }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val contentPadding = PaddingValues(horizontal = 16.dp)
        val available = maxWidth - 32.dp
        when (style) {
            QuickPicksM3CarouselStyle.MULTI_BROWSE -> HorizontalMultiBrowseCarousel(
                state = state,
                // ~55% of the width for the large item leaves room for one medium + one small.
                preferredItemWidth = available * 0.55f,
                itemSpacing = QuickPicksM3ItemSpacing,
                contentPadding = contentPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(QuickPicksM3CarouselHeight),
            ) { index ->
                QuickPicksM3Item(songs[index], songs[index].id == activePlayingSongId, onSongClick, onPlayNext, onAddToQueue, onDownload)
            }

            QuickPicksM3CarouselStyle.HERO -> HorizontalCenteredHeroCarousel(
                state = state,
                maxItemWidth = available * 0.68f,
                // Side items were 40-56dp slivers; keep them wide enough to read as cards.
                minSmallItemWidth = 56.dp,
                maxSmallItemWidth = 72.dp,
                itemSpacing = QuickPicksM3ItemSpacing,
                contentPadding = contentPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(QuickPicksM3CarouselHeight),
            ) { index ->
                QuickPicksM3Item(songs[index], songs[index].id == activePlayingSongId, onSongClick, onPlayNext, onAddToQueue, onDownload)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CarouselItemScope.QuickPicksM3Item(
    song: Song,
    isPlaying: Boolean,
    onSongClick: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onDownload: (Song) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .maskClip(MaterialTheme.shapes.extraLarge)
            .background(colors.surfaceContainerHigh)
            .combinedClickable(
                onClick = { onSongClick(song) },
                onLongClick = { menuOpen = true },
            )
    ) {
        SmartImage(
            model = song.albumArtUriString,
            contentDescription = song.title,
            // Full-size art: the large item is ~2/3 of the screen wide. This used to request a
            // 128px thumbnail (YouTube mqdefault) and stretch it, which made the cards blurry.
            targetSize = Size(1024, 1024),
            isThumbnail = false,
            modifier = Modifier.fillMaxSize(),
        )
        // Title fades in as the item grows to its large size and out as it shrinks.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .graphicsLayer {
                    val info = carouselItemDrawInfo
                    val range = (info.maxSize - info.minSize).coerceAtLeast(1f)
                    alpha = ((info.size - info.minSize) / range).coerceIn(0f, 1f)
                }
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))
                )
                .padding(start = 14.dp, end = 14.dp, top = 24.dp, bottom = 12.dp)
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isPlaying) colors.primaryContainer else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isPlaying) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(10.dp)
                    .background(colors.primary, MaterialTheme.shapes.extraLarge)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Play next") }, onClick = { menuOpen = false; onPlayNext(song) })
            DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menuOpen = false; onAddToQueue(song) })
            DropdownMenuItem(text = { Text("Download") }, onClick = { menuOpen = false; onDownload(song) })
        }
    }
}
