package com.unshoo.pixelmusic.presentation.components

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.model.Song
import kotlinx.collections.immutable.ImmutableList

private val RecentlyAddedArtShape = RoundedCornerShape(28.dp)
private val RecentlyAddedImageSize = coil.size.Size(420, 420)
private const val NEW_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Home "Recently added" row: newest songs on the device, large artwork cards with a small
 * "NEW" tag for anything added in the last week and a relative "added" time.
 */
@Composable
fun RecentlyAddedSection(
    songs: ImmutableList<Song>,
    currentSongId: String?,
    onSongClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) return
    val now = remember(songs) { System.currentTimeMillis() }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.home_recently_added_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 12.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(songs, key = { it.id }, contentType = { "recently_added_card" }) { song ->
                val addedMs = remember(song.dateAdded) {
                    if (song.dateAdded in 1 until 100_000_000_000L) song.dateAdded * 1000 else song.dateAdded
                }
                RecentlyAddedCard(
                    song = song,
                    isNew = addedMs > 0 && now - addedMs < NEW_WINDOW_MS,
                    addedLabel = remember(addedMs, now) { relativeAddedLabel(addedMs, now) },
                    isCurrent = song.id == currentSongId,
                    onClick = { onSongClick(song) }
                )
            }
        }
    }
}

@Composable
private fun RecentlyAddedCard(
    song: Song,
    isNew: Boolean,
    addedLabel: String?,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
    ) {
        Box {
            SmartImage(
                model = song.albumArtUriString,
                contentDescription = song.title,
                shape = RecentlyAddedArtShape,
                targetSize = RecentlyAddedImageSize,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
            if (isNew) {
                Text(
                    text = stringResource(R.string.home_recently_added_new),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onPrimaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isCurrent) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Row(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = listOfNotNull(song.displayArtist.takeIf { it.isNotBlank() }, addedLabel).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun relativeAddedLabel(addedMs: Long, now: Long): String? {
    if (addedMs <= 0L) return null
    val days = ((now - addedMs) / (24L * 60 * 60 * 1000)).coerceAtLeast(0)
    return when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} wk ago"
        days < 365 -> "${days / 30} mo ago"
        else -> "${days / 365} yr ago"
    }
}
