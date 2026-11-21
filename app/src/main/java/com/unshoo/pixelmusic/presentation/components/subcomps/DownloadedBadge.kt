package com.unshoo.pixelmusic.presentation.components.subcomps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.data.model.Song

/** Squircle-ish shape used for the tiny downloaded marker. */
private val DownloadedBadgeShape = RoundedCornerShape(30)

/**
 * True when an online-catalog song has a downloaded copy on device.
 * Pure on-device songs are not marked: the badge only answers "is this online song offline?".
 * Cheap (no disk I/O) so it is safe to call inside lazy list items.
 */
val Song.isDownloadedOnlineSong: Boolean
    get() = !isLocal && (isYouTube || id.startsWith("youtube_")) &&
        (path.isNotBlank() || contentUriString.startsWith("file://") || contentUriString.startsWith("content://"))

/**
 * Very small Material 3 tonal container with a "download done" glyph. Place it inside the
 * album-art box aligned to [Alignment.BottomEnd].
 */
@Composable
fun DownloadedBadge(
    modifier: Modifier = Modifier,
    size: Dp = 15.dp
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .border(1.dp, colors.surface, DownloadedBadgeShape)
            .background(colors.primaryContainer, DownloadedBadgeShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.DownloadDone,
            contentDescription = null,
            tint = colors.onPrimaryContainer,
            modifier = Modifier.size(size * 0.68f)
        )
    }
}
