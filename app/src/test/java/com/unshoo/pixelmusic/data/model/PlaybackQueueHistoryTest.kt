package com.unshoo.pixelmusic.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueHistoryTest {
    @Test
    fun singleTrackDoesNotBecomeSavedQueue() {
        assertEquals(emptyList<PlaybackQueueHistoryEntry>(), emptyList<PlaybackQueueHistoryEntry>()
            .recordQueue(entry("single", "one"), limit = 12))
    }

    @Test
    fun replayRefreshesTheSameQueueAndPreservesItsStartingTrack() {
        val saved = entry("original", "one", "two", "three", start = 1)
        val replayed = saved.copy(id = "repeat", savedAtEpochMs = 2)

        val history = listOf(saved).recordQueue(replayed, limit = 12)

        assertEquals(listOf(replayed.copy(id = "original")), history)
        assertEquals("two", history.single().firstTrack.mediaId)
    }

    @Test
    fun newestQueuesWinWhenWritesArriveOutOfOrder() {
        val newest = entry("new", "one", "two", time = 50)
        val middle = entry("middle", "two", "one", time = 40)
        val oldest = entry("old", "three", "four", time = 30)

        val history = listOf(newest).recordQueue(oldest, limit = 2).recordQueue(middle, limit = 2)
        assertEquals(listOf(newest, middle), history)
        assertEquals(history, history.recordQueue(newest.copy(savedAtEpochMs = 10), limit = 2))
    }

    private fun entry(
        id: String,
        vararg ids: String,
        start: Int = 0,
        time: Long = 1,
    ) = PlaybackQueueHistoryEntry(
        id = id,
        name = null,
        items = ids.map { PlaybackQueueItemSnapshot(mediaId = it, uri = "youtube://$it") },
        startIndex = start,
        savedAtEpochMs = time,
    )
}
