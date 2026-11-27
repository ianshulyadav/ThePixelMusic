package com.unshoo.pixelmusic.presentation.viewmodel

import android.content.Context
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import com.unshoo.pixelmusic.MainCoroutineExtension
import com.unshoo.pixelmusic.data.model.PlaybackQueueItemSnapshot
import com.unshoo.pixelmusic.data.model.PlaybackQueueSnapshot
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.service.player.DualPlayerEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class PlaybackStateHolderTest {

    private val dualPlayerEngine: DualPlayerEngine = mockk(relaxed = true)
    private val userPreferencesRepository: UserPreferencesRepository = mockk(relaxed = true)
    private val castStateHolder: CastStateHolder = mockk(relaxed = true)
    private val queueStateHolder: QueueStateHolder = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)
    private val powerManager: PowerManager = mockk(relaxed = true)

    private fun createHolder() = PlaybackStateHolder(
        dualPlayerEngine = dualPlayerEngine,
        userPreferencesRepository = userPreferencesRepository,
        castStateHolder = castStateHolder,
        queueStateHolder = queueStateHolder,
        appContext = appContext,
        appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    )

    private fun snapshot(
        mediaId: String = "duplicate-song",
        positionMs: Long = 48_000L
    ) = PlaybackQueueSnapshot(
        items = listOf(
            PlaybackQueueItemSnapshot(
                mediaId = mediaId,
                uri = "file:///music/$mediaId.mp3"
            )
        ),
        currentMediaId = mediaId,
        currentIndex = 0,
        currentPositionMs = positionMs
    )

    init {
        every { appContext.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { powerManager.isInteractive } returns true
        every { castStateHolder.castSession } returns MutableStateFlow(null)
    }

    @Test
    fun `paused override does not bleed into later occurrence with same media id`() {
        val holder = createHolder()

        holder.ensureCurrentPlaybackOccurrence("duplicate-song")
        holder.rememberPausedPositionOverride("duplicate-song", 91_000L)

        holder.onPlaybackOccurrenceTransition("another-song")
        holder.onPlaybackOccurrenceTransition("duplicate-song")
        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)

        assertEquals(0L, holder.currentPosition.value)
    }

    @Test
    fun `cold start snapshot only applies to the first matching occurrence`() = runTest {
        coEvery { userPreferencesRepository.getPlaybackQueueSnapshotOnce() } returns snapshot()

        val holder = createHolder()
        holder.initialize(this)
        advanceUntilIdle()

        holder.ensureCurrentPlaybackOccurrence("duplicate-song")
        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)
        assertEquals(48_000L, holder.currentPosition.value)

        holder.onPlaybackOccurrenceTransition("another-song")
        holder.onPlaybackOccurrenceTransition("duplicate-song")
        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)

        assertEquals(0L, holder.currentPosition.value)
    }

    @Test
    fun `late cold start snapshot binds to the already active first occurrence`() = runTest {
        coEvery { userPreferencesRepository.getPlaybackQueueSnapshotOnce() } returns snapshot()

        val holder = createHolder()
        holder.initialize(this)
        holder.ensureCurrentPlaybackOccurrence("duplicate-song")
        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)
        assertEquals(0L, holder.currentPosition.value)

        advanceUntilIdle()

        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)

        assertEquals(48_000L, holder.currentPosition.value)
    }

    @Test
    fun `late cold start snapshot is discarded after playback occurrence advances`() = runTest {
        coEvery { userPreferencesRepository.getPlaybackQueueSnapshotOnce() } returns snapshot()

        val holder = createHolder()
        holder.initialize(this)
        holder.ensureCurrentPlaybackOccurrence("duplicate-song")
        holder.onPlaybackOccurrenceTransition("another-song")
        holder.onPlaybackOccurrenceTransition("duplicate-song")

        advanceUntilIdle()

        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)

        assertEquals(0L, holder.currentPosition.value)
    }

    @Test
    fun `late cold start snapshot is discarded after first occurrence already ended`() = runTest {
        coEvery { userPreferencesRepository.getPlaybackQueueSnapshotOnce() } returns snapshot()

        val holder = createHolder()
        holder.initialize(this)
        holder.ensureCurrentPlaybackOccurrence("duplicate-song")
        holder.onPlaybackOccurrenceTransition(null)

        advanceUntilIdle()

        holder.ensureCurrentPlaybackOccurrence("duplicate-song")
        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)

        assertEquals(0L, holder.currentPosition.value)
    }

    @Test
    fun `forward speed seek plays continuously at configured speed and restores pre-hold speed and play state`() {
        val controller = mockk<MediaController>(relaxed = true)
        every { controller.playbackParameters } returns PlaybackParameters(1.0f)
        every { controller.isPlaying } returns false
        every { controller.playWhenReady } returns false

        val holder = createHolder()
        holder.setMediaController(controller)

        holder.startForwardSpeedSeek(2.0f)
        verify(exactly = 1) { controller.setPlaybackSpeed(2.0f) }
        verify(exactly = 1) { controller.play() }
        verify(exactly = 0) { controller.seekTo(any()) }

        holder.stopSpeedSeek()
        verify(exactly = 1) { controller.setPlaybackSpeed(1.0f) }
        verify(exactly = 1) { controller.pause() }
    }

    @Test
    fun `reverse speed seek scrubs smoothly without decoder seeks until release`() {
        var virtualClockMs = 1_000L
        val controller = mockk<MediaController>(relaxed = true)
        every { controller.currentMediaItem } returns MediaItem.Builder().setMediaId("track-1").build()
        every { controller.duration } returns 120_000L
        every { controller.isPlaying } returns true
        every { controller.volume } returns 0.8f
        every { controller.currentPosition } returns 40_000L

        val holder = createHolder()
        holder.elapsedRealtimeMs = { virtualClockMs }
        holder.setMediaController(controller)
        holder.updateStablePlayerState { it.copy(totalDuration = 120_000L) }

        holder.startReverseSpeedSeek(2.0f)
        verify(exactly = 1) { controller.volume = 0f }

        virtualClockMs = 3_500L
        assertEquals(35_000L, holder.updateSpeedSeekPreview())
        verify(exactly = 0) { controller.seekTo(any()) }

        virtualClockMs = 6_000L
        holder.stopSpeedSeek()
        verify(exactly = 1) { controller.volume = 0.8f }
        verify(exactly = 1) { controller.seekTo(30_000L) }
    }

    @Test
    fun `time based continuous hold seeks once per hold forward and backward at configured rate`() {
        var virtualClockMs = 2_000L
        val controller = mockk<MediaController>(relaxed = true)
        every { controller.currentMediaItem } returns MediaItem.Builder().setMediaId("track-lossless").build()
        every { controller.duration } returns 200_000L
        every { controller.isPlaying } returns true
        every { controller.volume } returns 0.9f
        every { controller.currentPosition } returns 40_000L

        val holder = createHolder()
        holder.elapsedRealtimeMs = { virtualClockMs }
        holder.setMediaController(controller)
        holder.updateStablePlayerState { it.copy(totalDuration = 200_000L) }

        holder.startForwardLongPressSeek(
            mode = com.unshoo.pixelmusic.data.preferences.LongPressSeekMode.TIME_BASED,
            timeRateSeconds = 10
        )
        org.junit.jupiter.api.Assertions.assertTrue(holder.isLongPressSeeking.value)
        verify(exactly = 0) { controller.setPlaybackSpeed(any()) }
        verify(exactly = 1) { controller.volume = 0f }

        virtualClockMs = 4_500L
        assertEquals(65_000L, holder.updateSpeedSeekPreview())
        verify(exactly = 0) { controller.seekTo(any()) }

        virtualClockMs = 6_000L
        holder.stopSpeedSeek()
        org.junit.jupiter.api.Assertions.assertFalse(holder.isLongPressSeeking.value)
        verify(exactly = 1) { controller.seekTo(80_000L) }
        verify(exactly = 1) { controller.volume = 0.9f }

        every { controller.currentPosition } returns 80_000L
        holder.startReverseLongPressSeek(
            mode = com.unshoo.pixelmusic.data.preferences.LongPressSeekMode.TIME_BASED,
            timeRateSeconds = 15
        )
        virtualClockMs = 8_000L
        assertEquals(50_000L, holder.updateSpeedSeekPreview())
        verify(exactly = 1) { controller.seekTo(any()) }

        virtualClockMs = 9_000L
        holder.stopSpeedSeek()
        verify(exactly = 1) { controller.seekTo(35_000L) }
    }

    @Test
    fun `forward speed seek falls back to continuous speed scrub when player refuses speed`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var virtualClockMs = 1_000L
        val controller = mockk<MediaController>(relaxed = true)
        every { controller.currentMediaItem } returns MediaItem.Builder().setMediaId("track-hifi").build()
        every { controller.duration } returns 180_000L
        every { controller.isPlaying } returns true
        every { controller.playWhenReady } returns true
        every { controller.isCurrentMediaItemSeekable } returns true
        every { controller.playbackParameters } returns PlaybackParameters(1.0f)
        every { controller.volume } returns 1.0f
        every { controller.currentPosition } returns 20_000L

        val holder = createHolder()
        holder.elapsedRealtimeMs = { virtualClockMs }
        holder.speedVerificationDelayMs = 0L
        holder.setMediaController(controller)
        holder.updateStablePlayerState { it.copy(totalDuration = 180_000L) }

        holder.startForwardLongPressSeek(
            mode = com.unshoo.pixelmusic.data.preferences.LongPressSeekMode.SPEED_BASED,
            speed = 2.0f
        )
        verify(exactly = 1) { controller.setPlaybackSpeed(2.0f) }
        verify(exactly = 1) { controller.volume = 0f }

        virtualClockMs = 4_000L
        holder.stopSpeedSeek()
        verify(exactly = 1) { controller.seekTo(26_000L) }
    }

    @Test
    fun `second hold while playing uses live player position rather than stale seek override`() {
        var virtualClockMs = 1_000L
        val controller = mockk<MediaController>(relaxed = true)
        every { controller.currentMediaItem } returns MediaItem.Builder().setMediaId("track-1").build()
        every { controller.duration } returns 200_000L
        every { controller.isPlaying } returns true
        every { controller.volume } returns 1.0f
        every { controller.currentPosition } returns 20_000L

        val holder = createHolder()
        holder.elapsedRealtimeMs = { virtualClockMs }
        holder.setMediaController(controller)
        holder.updateStablePlayerState { it.copy(totalDuration = 200_000L) }

        // Prior seek stored a paused position override, then playback advanced to 90_000 ms
        holder.seekTo(50_000L)
        holder.setIsSeeking(false)
        every { controller.currentPosition } returns 90_000L

        holder.startForwardLongPressSeek(
            mode = com.unshoo.pixelmusic.data.preferences.LongPressSeekMode.TIME_BASED,
            timeRateSeconds = 10
        )
        virtualClockMs = 2_500L
        assertEquals(105_000L, holder.updateSpeedSeekPreview())
        holder.stopSpeedSeek()
        verify(exactly = 1) { controller.seekTo(105_000L) }
    }
}
