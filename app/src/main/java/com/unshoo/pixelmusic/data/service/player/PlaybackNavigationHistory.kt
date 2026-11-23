package com.unshoo.pixelmusic.data.service.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi

/** Tracks queue occurrences on the session's playback looper, including tracks skipped while buffering. */
@OptIn(UnstableApi::class)
internal class PlaybackNavigationHistory {
    private data class Entry(val uid: Any, val mediaId: String, val index: Int)

    private val history = ArrayDeque<Entry>()
    private var timeline = Timeline.EMPTY
    private var currentItem: MediaItem? = null
    private val window = Timeline.Window()

    fun bind(player: Player) {
        val newTimeline = player.currentTimeline
        // An engine swap or rebuild recreates the timeline with the same queue order.
        // Match old occurrences by their latest index and media ID to adopt the new UIDs.
        if (history.isNotEmpty() && !newTimeline.isEmpty) {
            val oldIndices = indicesByUid(timeline)
            val remapped = history.mapNotNull { entry ->
                val index = oldIndices[entry.uid] ?: return@mapNotNull null
                getEntry(newTimeline, index)?.takeIf { it.mediaId == entry.mediaId }
            }
            history.clear()
            history.addAll(remapped)
        }
        recordCurrent(player)
    }

    fun onPlaylistChanged(player: Player) {
        val newTimeline = player.currentTimeline
        val current = getEntry(newTimeline, player.currentMediaItemIndex)
        val previous = history.lastOrNull()
        // The app toggles shuffle by replacing the playlist while reusing the current item.
        // A changed UID for that same item means the other occurrences may need new UIDs too.
        if (current != null && previous != null && current.uid != previous.uid &&
            currentItem != null && currentItem === player.currentMediaItem
        ) {
            val oldUniqueIds = uniqueEntriesByMediaId(timeline)
            val newUniqueIds = uniqueEntriesByMediaId(newTimeline)
            val remapped = history.mapNotNull { entry ->
                when {
                    entry.uid == previous.uid -> current
                    oldUniqueIds[entry.mediaId]?.uid == entry.uid -> newUniqueIds[entry.mediaId]
                    else -> null
                }
            }
            history.clear()
            history.addAll(remapped)
        }
        recordCurrent(player)
    }

    fun recordCurrent(player: Player) {
        timeline = player.currentTimeline
        currentItem = player.currentMediaItem
        if (timeline.isEmpty) history.clear()
        val current = getEntry(timeline, player.currentMediaItemIndex) ?: return
        if (history.lastOrNull()?.uid == current.uid) return
        if (history.size == MAX_HISTORY_SIZE) history.removeFirst()
        history.addLast(current)
    }

    fun takePreviousIndex(player: Player): Int {
        recordCurrent(player)
        if (history.isEmpty()) return C.INDEX_UNSET
        val current = history.removeLast()
        val indices by lazy(LazyThreadSafetyMode.NONE) { indicesByUid(timeline) }
        while (history.isNotEmpty()) {
            val previous = history.last()
            val index = if (getEntry(timeline, previous.index)?.uid == previous.uid) {
                previous.index
            } else {
                indices[previous.uid] ?: C.INDEX_UNSET
            }
            if (index != C.INDEX_UNSET && previous.uid != current.uid) return index
            history.removeLast()
        }
        // The caller can now use normal Previous. If it moves, the resulting transition
        // becomes the new history tail without adding the track we just moved back from.
        return C.INDEX_UNSET
    }

    private fun uniqueEntriesByMediaId(timeline: Timeline): Map<String, Entry?> = buildMap {
        for (index in 0 until timeline.windowCount) {
            val entry = getEntry(timeline, index) ?: continue
            put(entry.mediaId, if (containsKey(entry.mediaId)) null else entry)
        }
    }

    private fun indicesByUid(timeline: Timeline): Map<Any, Int> = buildMap {
        for (index in 0 until timeline.windowCount) {
            put(timeline.getWindow(index, window).uid, index)
        }
    }

    private fun getEntry(timeline: Timeline, index: Int): Entry? {
        if (index !in 0 until timeline.windowCount) return null
        timeline.getWindow(index, window)
        return Entry(window.uid, window.mediaItem.mediaId, index)
    }

    private companion object {
        const val MAX_HISTORY_SIZE = 200
    }
}
