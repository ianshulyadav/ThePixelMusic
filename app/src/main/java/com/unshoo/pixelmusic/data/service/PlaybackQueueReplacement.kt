package com.unshoo.pixelmusic.data.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal fun Player.shouldArchiveQueueBeforeReplacing(replacement: List<MediaItem>): Boolean {
    val count = mediaItemCount
    if (count < 2 || replacement.isEmpty()) return false
    return replacement.size != count ||
        replacement.indices.any { replacement[it].mediaId != getMediaItemAt(it).mediaId }
}

/** Restores the tracks outside a crossfade deck's window and preserves the playing source. */
internal fun Player.restoreQueueWindow(snapshot: List<MediaItem>, start: Int): Boolean {
    val count = mediaItemCount
    if (start !in snapshot.indices || count !in 1..(snapshot.size - start)) return false
    if ((0 until count).any { getMediaItemAt(it).mediaId != snapshot[start + it].mediaId }) return false

    val end = start + count
    if (end < snapshot.size) addMediaItems(snapshot.subList(end, snapshot.size))
    if (start > 0) addMediaItems(0, snapshot.subList(0, start))
    return true
}
