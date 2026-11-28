package com.unshoo.pixelmusic.presentation.viewmodel

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.utils.QueueUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages queue shuffle state.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * This class handles the original queue order for shuffle/unshuffle operations.
 */
@Singleton
class QueueStateHolder @Inject constructor() {

    private companion object {
        const val BULK_REPLACE_THRESHOLD = 80
    }

    private var originalMediaIds: List<String> = emptyList()
    private var originalEntries: List<Pair<String, String?>>? = null

    /**
     * Set original queue order (for updates during playback).
     */
    fun setOriginalQueueOrder(queue: List<Song>) {
        originalMediaIds = queue.map(Song::id)
        originalEntries = null
    }

    /** Applies PixelPlayer's anchored shuffle directly to the live playback timeline. */
    fun applyLocalShuffle(player: Player, enabled: Boolean): Boolean {
        val count = player.mediaItemCount
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until count) return !enabled

        val items = List(count, player::getMediaItemAt)
        val savedEntries = originalEntries
        val order = if (enabled) {
            originalEntries = items.map(::queueEntry)
            QueueUtils.generateShuffleOrder(count, currentIndex)
        } else if (savedEntries != null) {
            QueueUtils.restoreQueueOrder(items.map(::queueEntry), savedEntries)
        } else {
            val currentIds = items.map { it.mediaId.removePrefix("song:") }
            QueueUtils.restoreQueueOrder(currentIds, originalMediaIds)
        }

        if (count > BULK_REPLACE_THRESHOLD) {
            // Leave the playing media source in place on both sides of the reorder.
            val targetIndex = order.indexOf(currentIndex)
            val reordered = order.map(items::get)
            val after = reordered.subList(targetIndex + 1, count)
            val before = reordered.subList(0, targetIndex)
            if (after != items.subList(currentIndex + 1, count)) {
                player.replaceMediaItems(currentIndex + 1, count, after)
            }
            if (before != items.subList(0, currentIndex)) {
                player.replaceMediaItems(0, currentIndex, before)
            }
        } else {
            val liveOrder = items.indices.toMutableList()
            for ((toIndex, itemIndex) in order.withIndex()) {
                val fromIndex = liveOrder.indexOf(itemIndex)
                if (fromIndex == toIndex) continue
                player.moveMediaItem(fromIndex, toIndex)
                liveOrder.add(toIndex, liveOrder.removeAt(fromIndex))
            }
        }

        if (!enabled) {
            originalMediaIds = emptyList()
            originalEntries = null
        }
        return true
    }

    private fun queueEntry(item: MediaItem) =
        item.mediaId.removePrefix("song:") to item.mediaMetadata.extras?.getString("playlistId")

    /**
     * Prepares a list for shuffled playback.
     * 1. Saves original queue.
     * 2. Picks a random start song.
     * 3. Creates a shuffled list starting with that song.
     * Runs the heavy shuffle computation on Default dispatcher to avoid UI stalls.
     */
    suspend fun prepareShuffledQueueSuspending(
        songs: List<Song>,
        startAtZero: Boolean = true
    ): Pair<List<Song>, Song>? {
        if (songs.isEmpty()) return null

        val startSong = songs.random()
        setOriginalQueueOrder(songs)

        val startIndex = songs.indexOfFirst { it.id == startSong.id }.coerceAtLeast(0)
        val shuffledQueue = withContext(Dispatchers.Default) {
            QueueUtils.buildAnchoredShuffleQueueSuspending(songs, startIndex, startAtZero)
        }
        return Pair(shuffledQueue, startSong)
    }
}
