package com.unshoo.pixelmusic.data.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal fun Player.shouldArchiveQueueBeforeReplacing(replacement: List<MediaItem>): Boolean {
    val count = mediaItemCount
    if (count < 2 || replacement.isEmpty()) return false
    return replacement.size != count ||
        replacement.indices.any { replacement[it].mediaId != getMediaItemAt(it).mediaId }
}
