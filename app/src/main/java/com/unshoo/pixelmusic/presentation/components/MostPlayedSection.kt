package com.unshoo.pixelmusic.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository

private data class RankedItem(
    val key: String,
    val title: String,
    val playCount: Int,
    val imageModel: Any?,
    val circular: Boolean
)

/**
 * "Most played" home section: segmented Songs / Albums / Artists tabs and a horizontal row
 * of ranked cards. The #1 card is highlighted with the dynamic primary container colour.
 */
@Composable
fun MostPlayedSection(
    summary: PlaybackStatsRepository.PlaybackStatsSummary,
    onSeeAllClick: () -> Unit,
    modifier: Modifier = Modifier,
    artistImages: Map<String, String> = emptyMap()
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val songs = remember(summary.topSongs) {
        summary.topSongs.take(10).map {
            RankedItem("s_${it.songId}", it.title, it.playCount, it.albumArtUri, circular = false)
        }
    }
    val albums = remember(summary.topAlbums) {
        summary.topAlbums.take(10).map {
            RankedItem("a_${it.album}", it.album, it.playCount, it.albumArtUri, circular = false)
        }
    }
    val artists = remember(summary.topArtists, summary.topSongs, summary.songs, artistImages) {
        val allSongs = summary.topSongs + summary.songs
        summary.topArtists
            .filterNot { a ->
                val n = a.artist.trim().lowercase()
                n.isEmpty() || n == "<unknown>" || n == "unknown" || n == "unknown artist"
            }
            .take(10).map { artist ->
            // Real artist photo when available; otherwise the art of their most played track.
            val art = artistImages[artist.artist.trim().lowercase()] ?: allSongs.firstOrNull { song ->
                song.artist.split(",", "&", " feat.", " ft.").any { it.trim().equals(artist.artist, ignoreCase = true) } ||
                    song.artist.equals(artist.artist, ignoreCase = true)
            }?.albumArtUri
            RankedItem("r_${artist.artist}", artist.artist, artist.playCount, art, circular = true)
        }
    }
    if (songs.isEmpty() && albums.isEmpty() && artists.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_most_played_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .width(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(onClick = onSeeAllClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val labels = listOf(
                Triple(R.string.home_most_played_songs, Icons.Rounded.MusicNote, 0),
                Triple(R.string.home_most_played_albums, Icons.Rounded.Album, 1),
                Triple(R.string.home_most_played_artists, Icons.Rounded.Person, 2)
            )
            labels.forEach { (labelRes, icon, index) ->
                MostPlayedTab(
                    label = stringResource(labelRes),
                    icon = icon,
                    selected = tab == index,
                    first = index == 0,
                    last = index == labels.lastIndex,
                    onClick = { tab = index },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "mostPlayedTab"
        ) { selected ->
            val items = when (selected) {
                0 -> songs
                1 -> albums
                else -> artists
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                    MostPlayedCard(rank = index + 1, item = item, onClick = onSeeAllClick)
                }
            }
        }
    }
}

@Composable
private fun MostPlayedTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) scheme.inverseSurface else scheme.surfaceContainerHigh, label = "tabBg")
    val fg by animateColorAsState(if (selected) scheme.inverseOnSurface else scheme.onSurface, label = "tabFg")
    val outer = 24.dp
    val inner = 8.dp
    val shape = if (selected) CircleShape else RoundedCornerShape(
        topStart = if (first) outer else inner,
        bottomStart = if (first) outer else inner,
        topEnd = if (last) outer else inner,
        bottomEnd = if (last) outer else inner
    )
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Card colour picked from the artwork itself: the palette's vibrant swatch, re-toned so it
 * stays rich but soft (fixed lightness, capped saturation) and readable in light/dark themes.
 */
@Composable
private fun rememberArtworkCardColor(imageUrl: String?, fallback: Color, isDark: Boolean): Color {
    val context = androidx.compose.ui.platform.LocalContext.current
    var target by androidx.compose.runtime.remember(imageUrl, isDark) {
        androidx.compose.runtime.mutableStateOf(fallback)
    }
    androidx.compose.runtime.LaunchedEffect(imageUrl, isDark) {
        val argb = com.unshoo.pixelmusic.presentation.utils.CardColorExtractor.extractColorArgb(context, imageUrl)
            ?: return@LaunchedEffect
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(argb, hsl)
        hsl[1] = hsl[1].coerceIn(0.28f, 0.62f)
        hsl[2] = if (isDark) 0.30f else 0.80f
        target = Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
    }
    val animated by androidx.compose.animation.animateColorAsState(target, label = "artCardColor")
    return animated
}

@Composable
private fun MostPlayedCard(rank: Int, item: RankedItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val isDark = com.unshoo.pixelmusic.ui.theme.LocalPixelMusicDarkTheme.current
    val container = rememberArtworkCardColor(item.imageModel as? String, scheme.surfaceContainerHigh, isDark)
    val lightContainer = container.luminance() > 0.45f
    val content = if (lightContainer) Color(0xFF1B1B1F) else Color(0xFFF6F2FA)
    val subContent = content.copy(alpha = 0.80f)
    val badgeBg = androidx.compose.ui.graphics.lerp(container, Color.White, if (lightContainer) 0.45f else 0.62f)
    val badgeFg = Color(0xFF1B1B1F)
    val cardShape = RoundedCornerShape(30.dp)

    val badge: @Composable (Modifier) -> Unit = { m ->
        Box(
            modifier = m
                .size(40.dp)
                .clip(CircleShape)
                .background(badgeBg),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "#$rank", color = badgeFg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
    val texts: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(
                text = item.title,
                color = content,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.presentation_batch_g_stats_n_plays, item.playCount),
                color = subContent,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
        }
    }

    if (!item.circular) {
        // Songs / albums: artwork fills the whole card edge to edge and fades softly into the
        // colour picked from it, with the title on the faded part.
        Box(
            modifier = Modifier
                .width(186.dp)
                .height(226.dp)
                .clip(cardShape)
                .background(container)
                .clickable(onClick = onClick)
        ) {
            SmartImage(
                model = item.imageModel,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.42f to Color.Transparent,
                            0.72f to container.copy(alpha = 0.78f),
                            1f to container
                        )
                    )
            )
            badge(Modifier.align(Alignment.TopStart).padding(12.dp))
            texts(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 12.dp, bottom = 16.dp))
        }
    } else {
        // Artists: round photo on a card coloured from the photo itself.
        Column(
            modifier = Modifier
                .width(186.dp)
                .clip(cardShape)
                .background(container)
                .clickable(onClick = onClick)
                .padding(12.dp)
        ) {
            Box {
                SmartImage(
                    model = item.imageModel,
                    contentDescription = item.title,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
                badge(Modifier.padding(2.dp))
            }
            Spacer(Modifier.height(10.dp))
            texts(Modifier.padding(horizontal = 6.dp).padding(bottom = 6.dp))
        }
    }
}
