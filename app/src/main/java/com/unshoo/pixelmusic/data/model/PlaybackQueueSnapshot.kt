package com.unshoo.pixelmusic.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class PlaybackQueueItemSnapshot(
    val mediaId: String,
    val uri: String,
    val title: String? = null,
    val artist: String? = null,
    val albumTitle: String? = null,
    val artworkUri: String? = null,
    val durationMs: Long? = null,
)

@Immutable
@Serializable
data class PlaybackQueueSnapshot(
    val items: List<PlaybackQueueItemSnapshot>,
    val currentMediaId: String? = null,
    val currentIndex: Int = 0,
    val currentPositionMs: Long = 0L,
    val playWhenReady: Boolean = false,
    val repeatMode: Int = 0,
    val shuffleEnabled: Boolean = false,
    val savedAtEpochMs: Long = System.currentTimeMillis(),
)

@Immutable
@Serializable
data class PlaybackQueueHistoryEntry(
    val id: String,
    val name: String?,
    val items: List<PlaybackQueueItemSnapshot>,
    val startIndex: Int,
    val savedAtEpochMs: Long,
) {
    val firstTrack: PlaybackQueueItemSnapshot get() = items.getOrElse(startIndex) { items.first() }
}

internal fun List<PlaybackQueueHistoryEntry>.recordQueue(
    entry: PlaybackQueueHistoryEntry,
    limit: Int,
): List<PlaybackQueueHistoryEntry> {
    if (entry.items.size < 2 || limit <= 0) return this
    val sameQueue = firstOrNull { saved ->
        saved.startIndex == entry.startIndex && saved.items.size == entry.items.size &&
            saved.items.indices.all { saved.items[it].mediaId == entry.items[it].mediaId }
    }
    if (sameQueue != null && sameQueue.savedAtEpochMs > entry.savedAtEpochMs) return this
    val latest = if (sameQueue == null) entry else entry.copy(id = sameQueue.id)
    return (listOf(latest) + filterNot { it.id == latest.id })
        .sortedByDescending { it.savedAtEpochMs }
        .take(limit)
}
