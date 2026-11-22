package com.unshoo.pixelmusic.presentation.components.subcomps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.utils.OfflineAudioResolver

/**
 * True when an online-catalog song has a downloaded copy on device.
 * Pure on-device songs are not marked: the badge only answers "is this online song offline?".
 * Cheap (no disk I/O) so it is safe to call inside lazy list items.
 */
val Song.isDownloadedOnlineSong: Boolean
    get() = !isLocal && (isYouTube || id.startsWith("youtube_")) &&
        (path.isNotBlank() || contentUriString.startsWith("file://") || contentUriString.startsWith("content://"))

private val Song.onlineYoutubeId: String?
    get() = if (isLocal) null else youtubeId ?: id.removePrefix("youtube_").takeIf { it != id }

/**
 * Same as [isDownloadedOnlineSong] but also knows about downloads that the [Song] row itself
 * does not carry (e.g. playlist rows, or files saved into a custom download folder).
 */
@Composable
fun rememberIsDownloaded(song: Song): Boolean {
    if (song.isDownloadedOnlineSong) return true
    val ytId = remember(song.id, song.youtubeId, song.isLocal) { song.onlineYoutubeId } ?: return false
    val downloadedIds by OfflineAudioResolver.downloadedIds.collectAsState()
    return ytId in downloadedIds
}

/**
 * App-icon style "downloaded" badge: a small circle that sits on the bottom-right corner of the
 * album art, half on the artwork and half outside it, with a ring in [ringColor] (the colour
 * behind the artwork) so it looks cut out of the art like a launcher notification badge.
 *
 * Place it inside the (unclipped) album-art box; it aligns itself to the bottom-end corner.
 */
@Composable
fun DownloadedBadge(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    ringColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    ringWidth: Dp = 2.dp,
) {
    val colors = MaterialTheme.colorScheme
    val outer = size + ringWidth * 2
    // Centre the badge roughly on the corner: ~45% of it hangs outside the artwork.
    val overhang = outer * 0.45f
    Box(
        modifier = modifier
            .offset(x = overhang, y = overhang)
            .size(outer)
            .background(ringColor, CircleShape)
            .padding(ringWidth)
            .background(colors.primary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = colors.onPrimary,
            modifier = Modifier.size(size * 0.7f)
        )
    }
}

/** Convenience: the badge pinned to the bottom-end corner of the enclosing artwork Box. */
@Composable
fun androidx.compose.foundation.layout.BoxScope.ArtworkDownloadedBadge(
    ringColor: Color,
    size: Dp = 16.dp,
) {
    DownloadedBadge(
        modifier = Modifier.align(Alignment.BottomEnd),
        size = size,
        ringColor = ringColor,
    )
}
