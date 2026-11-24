package com.unshoo.pixelmusic.data.service.cast

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.unshoo.pixelmusic.data.service.player.PlaybackNavigationHistory
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(UnstableApi::class)
class PlaybackNavigationTest {
    private val history = PlaybackNavigationHistory()

    @Test
    fun `rapid next and previous commands visit every track even while buffering`() {
        val queue = QueuePlayer(listOf("1", "2", "3", "4", "5"))
        val pendingChanges = ArrayDeque<() -> Unit>()
        val session = sessionFor(queue).apply {
            skipTransition = { pendingChanges.addLast(it) }
        }

        repeat(4) { session.seekToNextMediaItem() }
        assertEquals(0, queue.index)
        while (pendingChanges.isNotEmpty()) pendingChanges.removeFirst().invoke()
        assertEquals(4, queue.index)

        repeat(4) { session.seekToPrevious() }
        assertEquals(4, queue.index)
        while (pendingChanges.isNotEmpty()) pendingChanges.removeFirst().invoke()

        assertEquals(listOf(0, 1, 2, 3, 4, 3, 2, 1, 0), queue.visited)
        session.skipTransition = null
        session.seekToPrevious()
        assertEquals(0, queue.index)
    }

    @Test
    fun `previous follows the visited shuffle order through an append and a player swap`() {
        val queue = QueuePlayer(listOf("A", "B", "C", "D"))
        val session = sessionFor(queue)
        queue.nextIndices.addAll(listOf(2, 1, 3))
        repeat(3) { session.seekToNextMediaItem() }
        queue.append("Auto queue item")

        // Crossfade swaps the ExoPlayer and recreates queue UIDs.
        val replacement = QueuePlayer(queue.mediaIds, initialIndex = 4)
        val newSession = sessionFor(replacement)
        session.release()
        repeat(4) { newSession.seekToPrevious() }

        assertEquals(listOf(4, 3, 1, 2, 0), replacement.visited)
    }

    @Test
    fun `shuffle toggle keeps history when rebuilding the queue around the current track`() {
        val queue = QueuePlayer(listOf("A", "B", "C", "D"))
        val session = sessionFor(queue)
        repeat(2) { session.seekToNextMediaItem() }

        queue.reorderWithNewUids(listOf("C", "A", "D", "B"))
        session.seekToPrevious()
        assertEquals(3, queue.index)
        session.seekToPrevious()
        assertEquals(1, queue.index)
    }

    @Test
    fun `previous uses queue order when starting mid queue without history`() {
        val queue = QueuePlayer(listOf("1", "2", "3", "4", "5"), initialIndex = 4)
        val session = sessionFor(queue)

        repeat(4) { session.seekToPrevious() }

        assertEquals(listOf(4, 3, 2, 1, 0), queue.visited)
    }

    @Test
    fun `same media IDs remain distinct and removed occurrences are skipped`() {
        val queue = QueuePlayer(listOf("same", "removed", "same", "last"))
        val session = sessionFor(queue)
        repeat(3) { session.seekToNextMediaItem() }
        queue.remove(1)

        session.seekToPrevious()
        assertEquals(1, queue.index)
        session.seekToPrevious()
        assertEquals(0, queue.index)
    }

    @Test
    fun `restart and repeat one do not consume the earlier track`() {
        val queue = QueuePlayer(listOf("1", "2"))
        val session = sessionFor(queue)
        session.seekToNextMediaItem()
        queue.repeatCurrent()
        queue.positionMs = 5_000

        session.seekToPrevious()
        assertEquals(1, queue.index)
        assertEquals(0L, queue.positionMs)
        session.seekToPrevious()
        assertEquals(0, queue.index)
    }

    @Test
    fun `a repeat all wrap is reversed through the visited occurrence`() {
        val queue = QueuePlayer(listOf("A", "B", "C"), initialIndex = 2)
        val session = sessionFor(queue)
        queue.nextIndices.addAll(listOf(0, 1))
        repeat(2) { session.seekToNextMediaItem() }

        repeat(2) { session.seekToPrevious() }

        assertEquals(listOf(2, 0, 1, 0, 2), queue.visited)
    }

    private fun sessionFor(queue: QueuePlayer): CastSessionPlayer {
        val cast = mockk<CastPlayback>(relaxed = true)
        every { cast.active } returns false
        return CastSessionPlayer(queue.player, cast, history, isSkipFadeActive = { false })
    }

    private class QueuePlayer(ids: List<String>, initialIndex: Int = 0) {
        private val listeners = mutableListOf<Player.Listener>()
        private val items = ids.map(::newItem).toMutableList()
        val mediaIds get() = items.map { it.second.mediaId }

        var index = initialIndex
            private set
        val visited = mutableListOf(initialIndex)
        var positionMs = 0L
        val nextIndices = ArrayDeque<Int>()
        private var timeline: Timeline = QueueTimeline(items.toList())

        val player = mockk<Player>(relaxed = true).also { player ->
            every { player.currentTimeline } answers { timeline }
            every { player.currentMediaItemIndex } answers { index }
            every { player.currentMediaItem } answers { items[index].second }
            every { player.currentPosition } answers { positionMs }
            every { player.playbackState } returns Player.STATE_BUFFERING
            every { player.addListener(any()) } answers { listeners.add(firstArg()); Unit }
            every { player.removeListener(any()) } answers { listeners.remove(firstArg()); Unit }
            every { player.hasPreviousMediaItem() } answers { index > 0 }
            every { player.seekToPreviousMediaItem() } answers { move(index - 1) }
            every { player.seekToNextMediaItem() } answers { move(nextIndices.removeFirstOrNull() ?: index + 1) }
            every { player.seekTo(any<Int>(), any<Long>()) } answers { move(firstArg()) }
            every { player.seekTo(any<Long>()) } answers { positionMs = firstArg(); Unit }
        }

        fun append(id: String) {
            items.add(newItem(id))
            refreshTimeline()
        }

        fun remove(index: Int) {
            items.removeAt(index)
            if (this.index > index) this.index--
            refreshTimeline()
        }

        fun reorderWithNewUids(ids: List<String>) {
            val current = items[index].second
            items.clear()
            items.addAll(ids.map { id ->
                if (id == current.mediaId) Any() to current else newItem(id)
            })
            index = ids.indexOf(current.mediaId)
            refreshTimeline()
        }

        fun repeatCurrent() {
            listeners.forEach { it.onMediaItemTransition(items[index].second, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) }
        }

        private fun refreshTimeline() {
            timeline = QueueTimeline(items.toList())
            listeners.forEach { it.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
        }

        private fun move(target: Int) {
            index = target
            positionMs = 0
            visited.add(target)
            listeners.forEach { it.onMediaItemTransition(items[target].second, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) }
        }

        private fun newItem(id: String) = Any() to MediaItem.Builder().setMediaId(id).build()
    }

    private class QueueTimeline(private val items: List<Pair<Any, MediaItem>>) : Timeline() {
        override fun getWindowCount() = items.size
        override fun getPeriodCount() = items.size
        override fun getUidOfPeriod(periodIndex: Int) = items[periodIndex].first
        override fun getIndexOfPeriod(uid: Any) = items.indexOfFirst { it.first == uid }
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(null, items[periodIndex].first, periodIndex, C.TIME_UNSET, 0)

        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            val (uid, item) = items[windowIndex]
            window.uid = uid
            window.mediaItem = item
            return window
        }
    }
}
