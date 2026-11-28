package com.unshoo.pixelmusic.presentation.viewmodel

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.common.Player
import androidx.media3.common.C
import com.unshoo.pixelmusic.data.service.player.DualPlayerEngine
import com.unshoo.pixelmusic.data.preferences.DEFAULT_LONG_PRESS_SEEK_SPEED
import com.unshoo.pixelmusic.data.preferences.DEFAULT_LONG_PRESS_SEEK_TIME_RATE_SECONDS
import com.unshoo.pixelmusic.data.preferences.LongPressSeekMode
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.preferences.sanitizeLongPressSeekSpeed
import com.unshoo.pixelmusic.data.preferences.sanitizeLongPressSeekTimeRateSeconds
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.unshoo.pixelmusic.data.model.Song
import com.google.android.gms.cast.MediaStatus
import com.unshoo.pixelmusic.di.AppScope
import timber.log.Timber
import com.unshoo.pixelmusic.data.service.MusicNotificationProvider
import kotlin.math.abs

@Singleton
class PlaybackStateHolder @Inject constructor(
    private val dualPlayerEngine: DualPlayerEngine,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val castStateHolder: CastStateHolder,
    private val queueStateHolder: QueueStateHolder,
    @param:ApplicationContext private val appContext: Context,
    @AppScope private val appScope: CoroutineScope
) {
    companion object {
        private const val TAG = "PlaybackStateHolder"
        private const val DURATION_MISMATCH_TOLERANCE_MS = 1500L
        // Cap how long we trust a pending seek override against an out-of-date player position.
        // The override exists to mask the few ticks between seekTo() and the player actually
        // reporting the new position. If we never see drift converge within this window we
        // assume the seek will not land and fall back to the reported position rather than
        // pinning the UI on a stale value forever.
        private const val PAUSED_OVERRIDE_MAX_AGE_MS = 4_000L
        // 250 ms keeps the slider/time display visibly smooth. We tried 500 ms to lower
        // Compose recomposition pressure, but the smooth-progress sampler does not actually
        // interpolate between source samples — it polls — so a 500 ms source cadence made the
        // slider stutter in half-second jumps. Background tick is throttled to 1 s since the
        // screen is off and no slider is visible.
        private const val FOREGROUND_PROGRESS_TICK_MS = 250L
        private const val BACKGROUND_PROGRESS_TICK_MS = 1000L
        /**
         * Tick used while the screen is on but *nothing is observing* position - the user is
         * on Library / Search / Settings, or the app is backgrounded with the display still lit.
         */
        private const val UNOBSERVED_PROGRESS_TICK_MS = 1000L
        private const val SHUFFLE_TOGGLE_COOLDOWN_MS = 400L
    }

    private val scope: CoroutineScope = appScope
    
    // MediaController
    var mediaController: MediaController? = null
        private set

    // Player State
    private val _stablePlayerState = MutableStateFlow(StablePlayerState())
    val stablePlayerState: StateFlow<StablePlayerState> = _stablePlayerState.asStateFlow()
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    // Internal State
    private var isSeeking = false
    private var localSeekUnlockJob: Job? = null
    private var remoteSeekUnlockJob: Job? = null
    private var activePositionOccurrenceMediaId: String? = null
    private var activePositionOccurrenceToken: Long = 0L
    private var nextPositionOccurrenceToken: Long = 1L
    private var pausedPositionOverrideMediaId: String? = null
    private var pausedPositionOverrideToken: Long? = null
    private var pausedPositionOverrideMs: Long? = null
    private var pausedPositionOverrideSetAtMs: Long = 0L
    private var coldStartSnapshotMediaId: String? = null
    private var coldStartSnapshotToken: Long? = null
    private var coldStartSnapshotPositionMs: Long? = null
    private var shuffleToggleJob: Job? = null
    private var lastShuffleToggleFinishedAtMs: Long = 0L

    private enum class SpeedSeekDirection { NONE, FORWARD, REVERSE }

    private var activeSpeedSeekDirection = SpeedSeekDirection.NONE
    private var activeLongPressSeekMode: LongPressSeekMode = LongPressSeekMode.SPEED_BASED
    private var speedSeekMultiplier: Float = DEFAULT_LONG_PRESS_SEEK_SPEED
    private var speedSeekPreHoldSpeed: Float = 1f
    private var speedSeekPreHoldVolume: Float = 1f
    private var speedSeekPreHoldWasPlaying: Boolean = false
    private var speedSeekRemoteFallback: Boolean = false
    private var speedSeekBasePosMs: Long = 0L
    private var speedSeekStartElapsedMs: Long = 0L
    private var speedSeekDurationMs: Long = 0L
    private var speedSeekPreviewJob: Job? = null

    private val _isLongPressSeeking = MutableStateFlow(false)
    val isLongPressSeeking: StateFlow<Boolean> = _isLongPressSeeking.asStateFlow()

    internal var elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }
    private val powerManager: PowerManager by lazy(LazyThreadSafetyMode.NONE) {
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    private fun clearColdStartSnapshot() {
        coldStartSnapshotMediaId = null
        coldStartSnapshotToken = null
        coldStartSnapshotPositionMs = null
    }

    /**
     * Binds a restored snapshot to the active playback occurrence when possible.
     *
     * If the first occurrence was already activated before the snapshot finished loading, we
     * attach the snapshot to that token so resume still works. If playback has already advanced
     * past the first occurrence, the snapshot is stale and must be discarded.
     */
    private fun rememberColdStartSnapshot(mediaId: String, positionMs: Long): Boolean {
        coldStartSnapshotMediaId = mediaId
        coldStartSnapshotToken = null
        coldStartSnapshotPositionMs = positionMs

        if (nextPositionOccurrenceToken == 1L) {
            return true
        }

        if (
            activePositionOccurrenceToken == 1L &&
            nextPositionOccurrenceToken == 2L &&
            activePositionOccurrenceMediaId == mediaId
        ) {
            coldStartSnapshotToken = activePositionOccurrenceToken
            return true
        }

        clearColdStartSnapshot()
        return false
    }

    fun initialize(coroutineScope: CoroutineScope) {
        coroutineScope.launch {
            val snapshot = runCatching {
                userPreferencesRepository.getPlaybackQueueSnapshotOnce()
            }.getOrNull() ?: return@launch

            val snapshotMediaId = snapshot.currentMediaId
                ?: snapshot.items.getOrNull(snapshot.currentIndex)?.mediaId
                ?: return@launch
            val snapshotPositionMs = snapshot.currentPositionMs.coerceAtLeast(0L)
            if (snapshotPositionMs <= 0L) return@launch
            if (!rememberColdStartSnapshot(snapshotMediaId, snapshotPositionMs)) {
                return@launch
            }

            val controller = mediaController
            if (controller != null) {
                val (isPlaying, currentMediaId) = withContext(Dispatchers.Main) {
                    try {
                        controller.isPlaying to controller.currentMediaItem?.mediaId
                    } catch (_: IllegalStateException) {
                        false to null
                    }
                }
                if (!isPlaying && currentMediaId == snapshotMediaId && _currentPosition.value == 0L) {
                    _currentPosition.value = snapshotPositionMs
                }
            }
        }
    }

    fun setMediaController(controller: MediaController?) {
        this.mediaController = controller
    }

    /** Clears only the controller owned by the caller, never a newer controller binding. */
    fun clearMediaController(controller: MediaController) {
        if (mediaController === controller) {
            mediaController = null
        }
    }
    
    // Manual skip guard. MediaController shows the next song immediately (masking), but a
    // stale update from the service can briefly report the song we just left, so the player
    // flicked new -> old -> new on every skip. For a short window, never switch BACK to it.
    @Volatile private var skippedFromSongId: String? = null
    @Volatile private var skipGuardUntilMs: Long = 0L

    private fun markManualSkip() {
        skippedFromSongId = _stablePlayerState.value.currentSong?.id
        skipGuardUntilMs = SystemClock.uptimeMillis() + 2_000L
    }

    fun updateStablePlayerState(update: (StablePlayerState) -> StablePlayerState) {
        _stablePlayerState.update { current ->
            val proposed = update(current)
            val guardId = skippedFromSongId
            val updated = if (
                guardId != null &&
                SystemClock.uptimeMillis() < skipGuardUntilMs &&
                current.currentSong != null &&
                current.currentSong.id != guardId &&
                proposed.currentSong?.id == guardId
            ) {
                proposed.copy(
                    currentSong = current.currentSong,
                    currentMediaItemIndex = current.currentMediaItemIndex,
                    totalDuration = current.totalDuration
                )
            } else proposed
            // Auto-populate index from MediaController if not explicitly set by the update
            if (updated.currentMediaItemIndex == -1) {
                val controller = mediaController
                if (controller != null) {
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                        try {
                            updated.copy(currentMediaItemIndex = controller.currentMediaItemIndex)
                        } catch (_: IllegalStateException) {
                            updated
                        }
                    } else {
                        // We are on a background thread. Asynchronously fetch on Main thread and update state flow.
                        scope.launch(Dispatchers.Main) {
                            val mainController = mediaController
                            if (mainController != null) {
                                try {
                                    val index = mainController.currentMediaItemIndex
                                    _stablePlayerState.update { state ->
                                        if (state.currentMediaItemIndex == -1) {
                                            state.copy(currentMediaItemIndex = index)
                                        } else {
                                            state
                                        }
                                    }
                                } catch (_: IllegalStateException) {
                                    // Controller released or in invalid state
                                }
                            }
                        }
                        updated
                    }
                } else {
                    updated
                }
            } else {
                updated
            }
        }
    }

    fun setCurrentPosition(positionMs: Long) {
        _currentPosition.value = positionMs.coerceAtLeast(0L)
    }

    fun syncCurrentPositionFromPlayer(mediaId: String?, reportedPositionMs: Long) {
        val displayedId = _stablePlayerState.value.currentSong?.id?.removePrefix("song:")
        if (displayedId != null && mediaId?.removePrefix("song:") != displayedId) return
        val guardId = skippedFromSongId
        if (guardId != null && SystemClock.uptimeMillis() < skipGuardUntilMs &&
            mediaId != null && (mediaId == guardId || mediaId.removePrefix("song:") == guardId) &&
            _stablePlayerState.value.currentSong?.id != guardId
        ) return // stale position of the song we just skipped away from
        _currentPosition.value = resolveUiPosition(mediaId, reportedPositionMs)
    }

    fun ensureCurrentPlaybackOccurrence(mediaId: String?) {
        activatePlaybackOccurrence(mediaId, forceNewOccurrence = false)
    }

    fun onPlaybackOccurrenceTransition(mediaId: String?) {
        if (activeSpeedSeekDirection != SpeedSeekDirection.NONE) {
            stopSpeedSeek(commitReverseSeek = false)
        }
        val controller = mediaController
        if (controller != null && activeSpeedSeekDirection == SpeedSeekDirection.NONE) {
            try {
                if (abs(controller.playbackParameters.speed - 1f) > 0.01f) {
                    controller.setPlaybackSpeed(1f)
                }
            } catch (_: IllegalStateException) {
            }
        }
        activatePlaybackOccurrence(mediaId, forceNewOccurrence = true)
    }

    fun rememberPausedPositionOverride(mediaId: String?, positionMs: Long) {
        val safeMediaId = mediaId?.takeIf { it.isNotBlank() } ?: return
        val activeToken = activatePlaybackOccurrence(safeMediaId, forceNewOccurrence = false) ?: return
        val safePosition = positionMs.coerceAtLeast(0L)
        pausedPositionOverrideMediaId = safeMediaId
        pausedPositionOverrideToken = activeToken
        pausedPositionOverrideMs = safePosition
        pausedPositionOverrideSetAtMs = SystemClock.elapsedRealtime()
        _currentPosition.value = safePosition
    }

    fun clearCurrentPositionHints(mediaId: String? = null) {
        if (mediaId == null || pausedPositionOverrideMediaId == mediaId) {
            pausedPositionOverrideMediaId = null
            pausedPositionOverrideToken = null
            pausedPositionOverrideMs = null
            pausedPositionOverrideSetAtMs = 0L
        }
        if (mediaId == null || coldStartSnapshotMediaId == mediaId) {
            clearColdStartSnapshot()
        }
    }

    private fun resolveUiPosition(mediaId: String?, reportedPositionMs: Long): Long {
        val safeReportedPosition = reportedPositionMs.coerceAtLeast(0L)
        val safeMediaId = mediaId?.takeIf { it.isNotBlank() }
        if (safeMediaId == null) {
            return safeReportedPosition
        }

        val activeToken = activatePlaybackOccurrence(safeMediaId, forceNewOccurrence = false)
            ?: return safeReportedPosition

        val pausedOverride = pausedPositionOverrideMs
            ?.takeIf {
                pausedPositionOverrideMediaId == safeMediaId &&
                    pausedPositionOverrideToken == activeToken
            }
        val coldStartSeed = coldStartSnapshotPositionMs
            ?.takeIf {
                coldStartSnapshotMediaId == safeMediaId &&
                    coldStartSnapshotToken == activeToken
            }
        val preferredPosition = pausedOverride ?: coldStartSeed

        if (preferredPosition == null) {
            return safeReportedPosition
        }

        if (safeReportedPosition <= 0L) {
            return preferredPosition
        }

        val drift = abs(safeReportedPosition - preferredPosition)
        val pausedOverrideOwnsThisToken =
            pausedPositionOverrideMediaId == safeMediaId &&
                pausedPositionOverrideToken == activeToken
        val pausedOverrideActive = pausedOverride != null
        // Stale override fallback: if the player never converges on a freshly-issued seek
        // we don't want to pin the UI on the requested position forever. After this window
        // we trust the reported position again.
        val overrideIsStale = pausedOverrideActive &&
            pausedPositionOverrideSetAtMs > 0L &&
            SystemClock.elapsedRealtime() - pausedPositionOverrideSetAtMs > PAUSED_OVERRIDE_MAX_AGE_MS
        // The `reported >= preferred` shortcut is only safe for the cold-start seed (where
        // preferred represents "where playback should start" and the player passing it means
        // the seed has served its purpose). Applying the same shortcut to an active paused
        // override broke backward seeks — the player still reports the pre-seek (larger)
        // position for a tick or two after seekTo(), wiping the override before the seek
        // had landed and snapping the UI back to the old position.
        val coldStartPassed = !pausedOverrideActive && safeReportedPosition >= preferredPosition
        if (drift <= DURATION_MISMATCH_TOLERANCE_MS || overrideIsStale || coldStartPassed) {
            if (pausedOverrideOwnsThisToken) {
                pausedPositionOverrideMediaId = null
                pausedPositionOverrideToken = null
                pausedPositionOverrideMs = null
                pausedPositionOverrideSetAtMs = 0L
            }
            if (coldStartSnapshotMediaId == safeMediaId && coldStartSnapshotToken == activeToken) {
                clearColdStartSnapshot()
            }
            return safeReportedPosition
        }

        return preferredPosition
    }

    private fun activatePlaybackOccurrence(
        mediaId: String?,
        forceNewOccurrence: Boolean
    ): Long? {
        val safeMediaId = mediaId?.takeIf { it.isNotBlank() } ?: run {
            activePositionOccurrenceMediaId = null
            activePositionOccurrenceToken = 0L
            if (forceNewOccurrence) {
                pausedPositionOverrideMediaId = null
                pausedPositionOverrideToken = null
                pausedPositionOverrideMs = null
                pausedPositionOverrideSetAtMs = 0L
            }
            return null
        }

        val shouldAdvance =
            forceNewOccurrence ||
                activePositionOccurrenceToken == 0L ||
                activePositionOccurrenceMediaId != safeMediaId

        if (!shouldAdvance) {
            return activePositionOccurrenceToken
        }

        activePositionOccurrenceMediaId = safeMediaId
        activePositionOccurrenceToken = nextPositionOccurrenceToken++

        pausedPositionOverrideMediaId = null
        pausedPositionOverrideToken = null
        pausedPositionOverrideMs = null
        pausedPositionOverrideSetAtMs = 0L

        if (coldStartSnapshotToken != null) {
            clearColdStartSnapshot()
        } else if (coldStartSnapshotMediaId == safeMediaId && coldStartSnapshotPositionMs != null) {
            coldStartSnapshotToken = activePositionOccurrenceToken
        } else if (coldStartSnapshotMediaId != null) {
            clearColdStartSnapshot()
        }

        return activePositionOccurrenceToken
    }
    
    /* -------------------------------------------------------------------------- */
    /*                               Playback Controls                            */
    /* -------------------------------------------------------------------------- */

    fun playPause() {
        val castSession = castStateHolder.castSession.value
        val remoteMediaClient = castSession?.remoteMediaClient

        if (castSession != null && remoteMediaClient != null) {
            if (remoteMediaClient.isPlaying) {
                castStateHolder.castPlayer?.pause()
                _stablePlayerState.update {
                    it.copy(
                        isPlaying = false,
                        playWhenReady = false
                    )
                }
            } else {
                if (remoteMediaClient.mediaQueue.itemCount > 0) {
                    castStateHolder.castPlayer?.play()
                    _stablePlayerState.update {
                        it.copy(
                            isPlaying = true,
                            playWhenReady = true
                        )
                    }
                } else {
                    Timber.w("Remote queue empty, cannot resume.")
                }
            }
        } else {
            val controller = mediaController ?: return
            if (controller.isPlaying) {
                controller.pause()
            } else {
                scope.launch {
                    try {
                        dualPlayerEngine.ensureFreshStreamForResume()
                        withContext(Dispatchers.Main.immediate) {
                            val activeCtrl = mediaController ?: controller
                            val player = dualPlayerEngine.masterPlayer
                            when (player.playbackState) {
                                androidx.media3.exoplayer.ExoPlayer.STATE_IDLE,
                                androidx.media3.exoplayer.ExoPlayer.STATE_ENDED -> {
                                    // Player lost its session; re-prepare before playing.
                                    activeCtrl.prepare()
                                }
                                androidx.media3.exoplayer.ExoPlayer.STATE_BUFFERING -> {
                                    // BUGFIX: Any source can stall in BUFFERING with
                                    // playWhenReady=false when the underlying connection
                                    // dies (DRM handshake timeout, expired CDN segments,
                                    // dropped HTTP keep-alive, etc.). The first play tap
                                    // just sets playWhenReady=true which does nothing if
                                    // the source is dead. Force stop()+prepare() to reset
                                    // the pipeline and restart from the (now-fresh) media item.
                                    // Only when the pipeline is genuinely dead (nothing buffered
                                    // ahead). ExoPlayer keeps loading while paused, so a healthy
                                    // paused-while-buffering player has data ahead; stopping it threw
                                    // that buffer away and caused a full reload on every resume.
                                    val bufferedAheadMs = player.bufferedPosition - player.currentPosition
                                    if (!player.playWhenReady && bufferedAheadMs < 500L) {
                                        player.stop()
                                        activeCtrl.prepare()
                                    }
                                }
                                else -> { /* STATE_READY — just play */ }
                            }
                            if (activeSpeedSeekDirection == SpeedSeekDirection.NONE) {
                                val currentSpeed = try {
                                    activeCtrl.playbackParameters.speed
                                } catch (_: IllegalStateException) {
                                    1f
                                }
                                if (abs(currentSpeed - 1f) > 0.01f) {
                                    activeCtrl.setPlaybackSpeed(1f)
                                }
                            }
                            activeCtrl.play()
                        }
                    } catch (e: Exception) {
                        timber.log.Timber.w(e, "ensureFreshStreamForResume failed; playing anyway")
                        withContext(Dispatchers.Main.immediate) {
                            val activeCtrl = mediaController ?: controller
                            activeCtrl.play()
                        }
                    }
                }
            }
        }
    }

    fun onSeekStarted() {
        isSeeking = true
    }

    fun onSeekFinished(position: Long) {
        seekTo(position)
    }

    fun setIsSeeking(seeking: Boolean) {
        isSeeking = seeking
        if (!seeking) {
            localSeekUnlockJob?.cancel()
        }
    }

    fun seekTo(position: Long) {
        val targetPosition = position.coerceAtLeast(0L)
        _currentPosition.value = targetPosition
        isSeeking = true
        localSeekUnlockJob?.cancel()
        localSeekUnlockJob = scope.launch(Dispatchers.Main) {
            // Retain seeking lock briefly so UI progress ticks do not snap back
            delay(1000)
            isSeeking = false
        }

        val castSession = castStateHolder.castSession.value
        if (castSession != null && castSession.remoteMediaClient != null) {
            castStateHolder.setRemotelySeeking(true)
            castStateHolder.setRemotePosition(targetPosition)
            setCurrentPosition(targetPosition)
            castStateHolder.castPlayer?.seek(targetPosition)

            remoteSeekUnlockJob?.cancel()
            remoteSeekUnlockJob = scope.launch {
                // Fail-safe: never keep remote seeking lock indefinitely.
                delay(1800)
                castStateHolder.setRemotelySeeking(false)
                castSession.remoteMediaClient?.requestStatus()
            }
        } else {
            remoteSeekUnlockJob?.cancel()
            castStateHolder.setRemotelySeeking(false)
            val currentMediaId = try {
                mediaController?.currentMediaItem?.mediaId
            } catch (_: IllegalStateException) {
                null
            }
            rememberPausedPositionOverride(currentMediaId, targetPosition)
            // Mark the seek before dispatching so the engine's HAL-reset heuristic does
            // not misinterpret the resulting STATE_BUFFERING as an audio HAL underflow and
            // rebuild the players (which would race with the in-flight seek command).
            dualPlayerEngine.notifyExternalSeekInitiated()
            try {
                mediaController?.seekTo(targetPosition)
            } catch (e: IllegalStateException) {
                Timber.w(e, "seekTo failed on released/invalid mediaController")
            }
        }
    }

    /**
     * Plays continuously at [speed] (e.g. 2.0x) while Next is held down, avoiding repeated seekTo
     * decoder flushes. Cast sessions smoothly advance preview position and commit once on release.
     */
    fun startForwardSpeedSeek(speed: Float = DEFAULT_LONG_PRESS_SEEK_SPEED) {
        startForwardLongPressSeek(
            mode = LongPressSeekMode.SPEED_BASED,
            speed = speed,
            timeRateSeconds = DEFAULT_LONG_PRESS_SEEK_TIME_RATE_SECONDS
        )
    }

    fun startForwardLongPressSeek(
        mode: LongPressSeekMode = LongPressSeekMode.SPEED_BASED,
        speed: Float = DEFAULT_LONG_PRESS_SEEK_SPEED,
        timeRateSeconds: Int = DEFAULT_LONG_PRESS_SEEK_TIME_RATE_SECONDS
    ) {
        if (activeSpeedSeekDirection == SpeedSeekDirection.FORWARD) return
        if (activeSpeedSeekDirection == SpeedSeekDirection.REVERSE) {
            stopSpeedSeek(commitReverseSeek = false)
        }
        val effectiveRate = resolveLongPressSeekRate(mode, speed, timeRateSeconds)
        val castSession = castStateHolder.castSession.value
        val remoteClient = castSession?.remoteMediaClient
        val controller = mediaController

        activeSpeedSeekDirection = SpeedSeekDirection.FORWARD
        activeLongPressSeekMode = mode
        speedSeekMultiplier = effectiveRate
        _isLongPressSeeking.value = true

        if (mode == LongPressSeekMode.SPEED_BASED && remoteClient == null && controller != null) {
            speedSeekRemoteFallback = false
            speedSeekPreHoldSpeed = 1f
            speedSeekPreHoldVolume = try {
                controller.volume.takeIf { it > 0.001f } ?: 1f
            } catch (_: IllegalStateException) {
                1f
            }
            speedSeekPreHoldWasPlaying = try {
                controller.playWhenReady || controller.isPlaying
            } catch (_: IllegalStateException) {
                false
            }
            try {
                controller.setPlaybackSpeed(effectiveRate)
                if (!speedSeekPreHoldWasPlaying) {
                    controller.play()
                }
            } catch (e: IllegalStateException) {
                Timber.w(e, "startForwardLongPressSeek failed on invalid mediaController")
                activeSpeedSeekDirection = SpeedSeekDirection.NONE
                _isLongPressSeeking.value = false
                return
            }
            progressPoke.trySend(Unit)
            startForwardSpeedVerificationLoop(effectiveRate)
        } else {
            // Time-based continuous mode or remote Cast: treat one hold as ONE continuous
            // timeline scrub in memory (muting local playback briefly if playing) and commit
            // a single seekTo on release with zero intermediate decoder skips.
            speedSeekRemoteFallback = true
            val currentPos = resolveActivePositionForSpeedSeek(controller, remoteClient)
            val durationMs = resolveDurationForSpeedSeek(controller, remoteClient, currentPos)
            speedSeekBasePosMs = currentPos
            speedSeekDurationMs = durationMs
            speedSeekStartElapsedMs = elapsedRealtimeMs()
            isSeeking = true
            localSeekUnlockJob?.cancel()
            _currentPosition.value = currentPos
            if (remoteClient == null && controller != null) {
                speedSeekPreHoldVolume = try {
                    controller.volume.takeIf { it > 0.001f } ?: 1f
                } catch (_: IllegalStateException) {
                    1f
                }
                speedSeekPreHoldWasPlaying = try {
                    controller.isPlaying
                } catch (_: IllegalStateException) {
                    false
                }
                if (speedSeekPreHoldWasPlaying) {
                    try {
                        controller.volume = 0f
                    } catch (_: IllegalStateException) {
                    }
                }
            } else {
                speedSeekPreHoldWasPlaying = false
            }
            startSpeedSeekPreviewLoop()
        }
    }

    /**
     * Scrubs smoothly backward in memory while Previous is held (muting output if playing
     * so forward audio is suppressed without triggering pause/resume telemetry), then commits ONE
     * clean seek on [stopSpeedSeek].
     */
    fun startReverseSpeedSeek(speed: Float = DEFAULT_LONG_PRESS_SEEK_SPEED): Long =
        startReverseLongPressSeek(
            mode = LongPressSeekMode.SPEED_BASED,
            speed = speed,
            timeRateSeconds = DEFAULT_LONG_PRESS_SEEK_TIME_RATE_SECONDS
        )

    fun startReverseLongPressSeek(
        mode: LongPressSeekMode = LongPressSeekMode.SPEED_BASED,
        speed: Float = DEFAULT_LONG_PRESS_SEEK_SPEED,
        timeRateSeconds: Int = DEFAULT_LONG_PRESS_SEEK_TIME_RATE_SECONDS
    ): Long {
        if (activeSpeedSeekDirection == SpeedSeekDirection.REVERSE) {
            return updateReverseSpeedSeek()
        }
        if (activeSpeedSeekDirection == SpeedSeekDirection.FORWARD) {
            stopSpeedSeek(commitReverseSeek = false)
        }
        val effectiveRate = resolveLongPressSeekRate(mode, speed, timeRateSeconds)
        val castSession = castStateHolder.castSession.value
        val remoteClient = castSession?.remoteMediaClient
        val controller = mediaController
        val currentPos = resolveActivePositionForSpeedSeek(controller, remoteClient)
        val durationMs = resolveDurationForSpeedSeek(controller, remoteClient, currentPos)

        activeSpeedSeekDirection = SpeedSeekDirection.REVERSE
        activeLongPressSeekMode = mode
        speedSeekRemoteFallback = remoteClient != null || controller == null
        speedSeekMultiplier = effectiveRate
        speedSeekBasePosMs = currentPos
        speedSeekDurationMs = durationMs
        speedSeekStartElapsedMs = elapsedRealtimeMs()
        isSeeking = true
        _isLongPressSeeking.value = true
        localSeekUnlockJob?.cancel()
        _currentPosition.value = currentPos

        if (remoteClient == null && controller != null) {
            speedSeekPreHoldVolume = try {
                controller.volume.takeIf { it > 0.001f } ?: 1f
            } catch (_: IllegalStateException) {
                1f
            }
            speedSeekPreHoldWasPlaying = try {
                controller.isPlaying
            } catch (_: IllegalStateException) {
                false
            }
            if (speedSeekPreHoldWasPlaying) {
                try {
                    controller.volume = 0f
                } catch (_: IllegalStateException) {
                }
            }
        }
        startSpeedSeekPreviewLoop()
        return currentPos
    }

    private fun resolveLongPressSeekRate(
        mode: LongPressSeekMode,
        speed: Float,
        timeRateSeconds: Int
    ): Float = when (mode) {
        LongPressSeekMode.SPEED_BASED -> sanitizeLongPressSeekSpeed(speed)
        LongPressSeekMode.TIME_BASED -> sanitizeLongPressSeekTimeRateSeconds(timeRateSeconds).toFloat()
    }

    internal var speedVerificationDelayMs: Long = 240L

    private fun startForwardSpeedVerificationLoop(requestedSpeed: Float) {
        speedSeekPreviewJob?.cancel()
        speedSeekPreviewJob = scope.launch(Dispatchers.Main) {
            if (speedVerificationDelayMs > 0L) {
                delay(speedVerificationDelayMs)
            }
            while (activeSpeedSeekDirection == SpeedSeekDirection.FORWARD && !speedSeekRemoteFallback) {
                val activeController = mediaController ?: break
                val actualSpeed = try {
                    activeController.playbackParameters.speed
                } catch (_: IllegalStateException) {
                    break
                }
                val isPlayingNow = try {
                    activeController.isPlaying
                } catch (_: IllegalStateException) {
                    false
                }
                val isBufferingNow = try {
                    activeController.playbackState == Player.STATE_BUFFERING
                } catch (_: IllegalStateException) {
                    false
                }
                val canSeekNow = try {
                    activeController.isCurrentMediaItemSeekable
                } catch (_: IllegalStateException) {
                    false
                }
                // Offload DSP, BT HAL, float PCM limits, or slow Hi-Res FLAC buffering can hold speed at 1x
                // or stall during 2x-4x. Fall back seamlessly to continuous scrub + single seek commit.
                if (canSeekNow &&
                    (abs(actualSpeed - requestedSpeed) > 0.15f || isBufferingNow || !isPlayingNow)
                ) {
                    promoteActiveForwardHoldToContinuousScrub(activeController)
                    return@launch
                }
                delay(150L)
            }
        }
    }

    private fun promoteActiveForwardHoldToContinuousScrub(controller: MediaController) {
        val currentPos = resolveActivePositionForSpeedSeek(controller, remoteClient = null)
        val durationMs = resolveDurationForSpeedSeek(controller, remoteClient = null, currentPos)
        speedSeekRemoteFallback = true
        speedSeekBasePosMs = currentPos
        speedSeekDurationMs = durationMs
        speedSeekStartElapsedMs = elapsedRealtimeMs()
        isSeeking = true
        localSeekUnlockJob?.cancel()
        _currentPosition.value = currentPos
        try {
            controller.setPlaybackSpeed(speedSeekPreHoldSpeed)
            if (!speedSeekPreHoldWasPlaying) {
                controller.pause()
            } else {
                controller.volume = 0f
            }
        } catch (_: IllegalStateException) {
        }
        startSpeedSeekPreviewLoop()
    }

    private fun startSpeedSeekPreviewLoop() {
        speedSeekPreviewJob?.cancel()
        speedSeekPreviewJob = scope.launch(Dispatchers.Main) {
            while (activeSpeedSeekDirection != SpeedSeekDirection.NONE) {
                val updatedPos = updateSpeedSeekPreview()
                val reachedBoundary =
                    (activeSpeedSeekDirection == SpeedSeekDirection.REVERSE && updatedPos <= 0L) ||
                        (activeSpeedSeekDirection == SpeedSeekDirection.FORWARD &&
                            speedSeekDurationMs > 0L &&
                            updatedPos >= (speedSeekDurationMs - 1_000L).coerceAtLeast(0L))
                if (reachedBoundary) break
                delay(50L)
            }
        }
    }

    fun updateSpeedSeekPreview(): Long {
        val direction = activeSpeedSeekDirection
        if (direction == SpeedSeekDirection.NONE) return _currentPosition.value
        if (direction == SpeedSeekDirection.FORWARD && !speedSeekRemoteFallback) {
            return _currentPosition.value
        }
        val elapsedMs = (elapsedRealtimeMs() - speedSeekStartElapsedMs).coerceAtLeast(0L)
        val deltaMs = (elapsedMs * speedSeekMultiplier).toLong()
        val target = if (direction == SpeedSeekDirection.FORWARD) {
            val rawTarget = speedSeekBasePosMs + deltaMs
            if (speedSeekDurationMs > 1_000L) {
                rawTarget.coerceAtMost(speedSeekDurationMs - 1_000L)
            } else if (speedSeekDurationMs > 0L) {
                rawTarget.coerceAtMost(speedSeekDurationMs)
            } else {
                rawTarget
            }
        } else {
            (speedSeekBasePosMs - deltaMs).coerceAtLeast(0L)
        }
        if (_currentPosition.value != target) {
            _currentPosition.value = target
        }
        return target
    }

    fun updateReverseSpeedSeek(): Long = updateSpeedSeekPreview()

    fun stopSpeedSeek(commitReverseSeek: Boolean = true) {
        val direction = activeSpeedSeekDirection
        if (direction == SpeedSeekDirection.NONE) return

        speedSeekPreviewJob?.cancel()
        speedSeekPreviewJob = null

        val previewTarget = if (direction == SpeedSeekDirection.REVERSE || speedSeekRemoteFallback) {
            updateSpeedSeekPreview()
        } else {
            _currentPosition.value
        }

        activeSpeedSeekDirection = SpeedSeekDirection.NONE
        activeLongPressSeekMode = LongPressSeekMode.SPEED_BASED
        val wasRemoteFallback = speedSeekRemoteFallback
        speedSeekRemoteFallback = false
        _isLongPressSeeking.value = false
        val controller = mediaController

        when (direction) {
            SpeedSeekDirection.FORWARD -> {
                if (wasRemoteFallback) {
                    if (commitReverseSeek) {
                        seekTo(previewTarget)
                    } else {
                        isSeeking = false
                    }
                    if (controller != null) {
                        try {
                            if (abs(controller.playbackParameters.speed - speedSeekPreHoldSpeed) > 0.01f) {
                                controller.setPlaybackSpeed(speedSeekPreHoldSpeed)
                            }
                            if (speedSeekPreHoldWasPlaying) {
                                controller.volume = speedSeekPreHoldVolume
                            }
                        } catch (_: IllegalStateException) {
                        }
                    }
                } else if (controller != null) {
                    try {
                        controller.setPlaybackSpeed(speedSeekPreHoldSpeed)
                        if (!speedSeekPreHoldWasPlaying) {
                            controller.pause()
                        }
                    } catch (e: IllegalStateException) {
                        Timber.w(e, "stopSpeedSeek(FORWARD) failed on invalid mediaController")
                    }
                }
            }
            SpeedSeekDirection.REVERSE -> {
                if (commitReverseSeek) {
                    seekTo(previewTarget)
                } else {
                    isSeeking = false
                }
                if (!wasRemoteFallback && controller != null && speedSeekPreHoldWasPlaying) {
                    try {
                        controller.volume = speedSeekPreHoldVolume
                    } catch (_: IllegalStateException) {
                    }
                }
            }
            SpeedSeekDirection.NONE -> Unit
        }
    }

    private fun resolveActivePositionForSpeedSeek(
        controller: MediaController?,
        remoteClient: com.google.android.gms.cast.framework.media.RemoteMediaClient?
    ): Long {
        val activeMediaId = try {
            controller?.currentMediaItem?.mediaId
        } catch (_: IllegalStateException) {
            null
        }
        return when {
            isSeeking -> _currentPosition.value
            remoteClient != null -> remoteClient.approximateStreamPosition
            controller != null && controller.isPlaying -> controller.currentPosition
            pausedPositionOverrideMs != null &&
                pausedPositionOverrideMediaId != null &&
                pausedPositionOverrideMediaId == activeMediaId ->
                pausedPositionOverrideMs!!
            else -> _currentPosition.value
        }.coerceAtLeast(0L)
    }

    private fun resolveDurationForSpeedSeek(
        controller: MediaController?,
        remoteClient: com.google.android.gms.cast.framework.media.RemoteMediaClient?,
        currentPos: Long
    ): Long {
        val state = _stablePlayerState.value
        val reportedDuration = when {
            remoteClient != null -> remoteClient.streamDuration
            controller != null -> controller.duration
            else -> C.TIME_UNSET
        }
        return resolveEffectiveDuration(
            reportedDurationMs = reportedDuration,
            songDurationHintMs = state.currentSong?.duration ?: state.totalDuration,
            currentPositionMs = currentPos
        )
    }

    fun previousSong() {
        stopSpeedSeek(commitReverseSeek = false)
        val castSession = castStateHolder.castSession.value
        if (castSession != null && castSession.remoteMediaClient != null) {
            castStateHolder.castPlayer?.previous()
            return
        }
        val controller = mediaController ?: return
        // A deliberate move back may revisit the track protected by the forward-skip guard.
        skippedFromSongId = null
        controller.sendCustomCommand(
            androidx.media3.session.SessionCommand(
                MusicNotificationProvider.CUSTOM_COMMAND_SKIP_PREVIOUS, android.os.Bundle.EMPTY
            ),
            android.os.Bundle.EMPTY
        )
    }

    fun nextSong() {
        stopSpeedSeek(commitReverseSeek = false)
        val castSession = castStateHolder.castSession.value
        if (castSession != null && castSession.remoteMediaClient != null) {
            castStateHolder.castPlayer?.next()
        } else {
            markManualSkip()
            val controller = mediaController ?: return
            if (controller.hasNextMediaItem()) {
                controller.seekToNextMediaItem()
            } else {
                val nextIndex = controller.currentMediaItemIndex + 1
                if (nextIndex < controller.mediaItemCount) {
                    controller.seekTo(nextIndex, 0L)
                } else if (controller.repeatMode == Player.REPEAT_MODE_ALL && controller.mediaItemCount > 0) {
                    controller.seekTo(0, 0L)
                } else {
                    controller.sendCustomCommand(
                        androidx.media3.session.SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_SKIP_NEXT, android.os.Bundle.EMPTY),
                        android.os.Bundle.EMPTY
                    )
                }
            }
        }
    }

    fun cycleRepeatMode() {
        val castSession = castStateHolder.castSession.value
        val remoteMediaClient = castSession?.remoteMediaClient

        if (castSession != null && remoteMediaClient != null) {
            val currentRepeatMode = remoteMediaClient.mediaStatus?.getQueueRepeatMode() ?: MediaStatus.REPEAT_MODE_REPEAT_OFF
            val newMode = when (currentRepeatMode) {
                MediaStatus.REPEAT_MODE_REPEAT_OFF -> MediaStatus.REPEAT_MODE_REPEAT_ALL
                MediaStatus.REPEAT_MODE_REPEAT_ALL -> MediaStatus.REPEAT_MODE_REPEAT_SINGLE
                MediaStatus.REPEAT_MODE_REPEAT_SINGLE -> MediaStatus.REPEAT_MODE_REPEAT_OFF
                MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE -> MediaStatus.REPEAT_MODE_REPEAT_OFF
                else -> MediaStatus.REPEAT_MODE_REPEAT_OFF
            }
            castStateHolder.castPlayer?.setRepeatMode(newMode)
            
            // Map remote mode back to local constant for persistence/UI
            val mappedLocalMode = when (newMode) {
                MediaStatus.REPEAT_MODE_REPEAT_SINGLE -> Player.REPEAT_MODE_ONE
                MediaStatus.REPEAT_MODE_REPEAT_ALL, MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            scope?.launch { userPreferencesRepository.setRepeatMode(mappedLocalMode) }
            _stablePlayerState.update { it.copy(repeatMode = mappedLocalMode) }
        } else {
            val currentMode = _stablePlayerState.value.repeatMode
            val newMode = when (currentMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_OFF
            }
            mediaController?.repeatMode = newMode
            scope?.launch { userPreferencesRepository.setRepeatMode(newMode) }
            _stablePlayerState.update { it.copy(repeatMode = newMode) }
        }
    }

    fun setRepeatMode(mode: Int) {
        val castSession = castStateHolder.castSession.value
        val remoteMediaClient = castSession?.remoteMediaClient

        if (castSession != null && remoteMediaClient != null) {
            val remoteMode = when (mode) {
                Player.REPEAT_MODE_ONE -> MediaStatus.REPEAT_MODE_REPEAT_SINGLE
                Player.REPEAT_MODE_ALL -> MediaStatus.REPEAT_MODE_REPEAT_ALL
                else -> MediaStatus.REPEAT_MODE_REPEAT_OFF
            }
            castStateHolder.castPlayer?.setRepeatMode(remoteMode)
        } else {
             mediaController?.repeatMode = mode
        }
        
        scope?.launch { userPreferencesRepository.setRepeatMode(mode) }
        _stablePlayerState.update { it.copy(repeatMode = mode) }
    }

    /* -------------------------------------------------------------------------- */
    /*                               Progress Updates                             */
    /* -------------------------------------------------------------------------- */
    
    private var progressJob: Job? = null
    private var progressSubscriptionWatcherJob: Job? = null
    private val progressPoke = Channel<Unit>(Channel.CONFLATED)

    /**
     * Reconciles duration reported by the player with the current song metadata duration.
     *
     * Why:
     * - During some transitions (notably crossfade player swaps), the reported duration can lag
     *   behind the currently visible track for a short period.
     * - Relying only on one source can make progress run too slow/fast.
     */
    private fun resolveEffectiveDuration(
        reportedDurationMs: Long,
        songDurationHintMs: Long,
        currentPositionMs: Long
    ): Long {
        val reported = when {
            reportedDurationMs == C.TIME_UNSET -> 0L
            reportedDurationMs < 0L -> 0L
            else -> reportedDurationMs
        }
        val hint = songDurationHintMs.coerceAtLeast(0L)
        val position = currentPositionMs.coerceAtLeast(0L)

        if (reported <= 0L) return hint
        if (hint <= 0L) return reported

        val diff = abs(reported - hint)
        if (diff <= DURATION_MISMATCH_TOLERANCE_MS) return reported

        // If playback already passed the metadata hint, trust the reported duration to avoid clipping.
        if (position > hint + DURATION_MISMATCH_TOLERANCE_MS && reported >= position) {
            return reported
        }

        // Otherwise prefer the shorter duration to avoid stale longer values after swaps.
        val resolved = minOf(reported, hint)
        if (diff > 10_000L) {
            Timber.tag(TAG).w(
                "Duration mismatch resolved (reported=%dms, hint=%dms, pos=%dms, resolved=%dms)",
                reported, hint, position, resolved
            )
        }
        return resolved
    }

    fun resolveDurationForPlaybackState(
        reportedDurationMs: Long,
        songDurationHintMs: Long,
        currentPositionMs: Long
    ): Long = resolveEffectiveDuration(
        reportedDurationMs = reportedDurationMs,
        songDurationHintMs = songDurationHintMs,
        currentPositionMs = currentPositionMs
    )

    fun startProgressUpdates() {
        stopProgressUpdates()
        progressSubscriptionWatcherJob = scope?.launch(Dispatchers.Main.immediate) {
            _currentPosition.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .filter { it }   // only the 0 -> >0 edge; losing a subscriber needs no refresh
                .collect { progressPoke.trySend(Unit) }
        }
        progressJob = scope?.launch(Dispatchers.Main) {
            while (true) {
                val tickMs = currentProgressTickMs()
                try {
                    val castSession = castStateHolder.castSession.value
                    val isRemote = castSession?.remoteMediaClient != null
                    
                    if (isRemote) {
                        val remoteClient = castSession?.remoteMediaClient
                        if (remoteClient != null) {
                            val isRemotePlaying = remoteClient.isPlaying
                            val currentPosition = remoteClient.approximateStreamPosition.coerceAtLeast(0L)
                            val songDurationHint = _stablePlayerState.value.currentSong?.duration ?: 0L
                            val duration = resolveEffectiveDuration(
                                reportedDurationMs = remoteClient.streamDuration,
                                songDurationHintMs = songDurationHint,
                                currentPositionMs = currentPosition
                            )
                            val isRemotelySeeking = castStateHolder.isRemotelySeeking.value
                            if (!isRemotelySeeking) {
                                castStateHolder.setRemotePosition(currentPosition)
                            }

                            val nextPosition = if (isRemotelySeeking) _currentPosition.value else currentPosition
                            if (_currentPosition.value != nextPosition) {
                                _currentPosition.value = nextPosition
                            }

                            _stablePlayerState.update { state ->
                                if (
                                    state.totalDuration == duration &&
                                    state.isPlaying == isRemotePlaying &&
                                    state.playWhenReady == isRemotePlaying
                                ) {
                                    state
                                } else {
                                    state.copy(
                                        totalDuration = duration,
                                        isPlaying = isRemotePlaying,
                                        playWhenReady = isRemotePlaying
                                    )
                                }
                            }
                        }
                    } else {
                         val controller = mediaController
                         if (controller != null && activeSpeedSeekDirection == SpeedSeekDirection.NONE) {
                             val currentSpeed = try {
                                 controller.playbackParameters.speed
                             } catch (_: IllegalStateException) {
                                 1f
                             }
                             if (abs(currentSpeed - 1f) > 0.01f) {
                                 try {
                                     controller.setPlaybackSpeed(1f)
                                 } catch (_: IllegalStateException) {
                                 }
                             }
                         }
                         // Media3: Check isPlaying or playbackState == READY/BUFFERING
                         if (controller != null && controller.isPlaying && !isSeeking) {
                             val visibleSong = _stablePlayerState.value.currentSong
                             val currentMediaId = controller.currentMediaItem?.mediaId
                             val hasMediaMismatch = visibleSong?.id != null &&
                                 currentMediaId != null &&
                                 visibleSong.id.removePrefix("song:") != currentMediaId.removePrefix("song:")

                             if (hasMediaMismatch) {
                                Timber.tag(TAG).v(
                                    "Skipping local progress tick due media mismatch (visible=%s, player=%s)",
                                    visibleSong?.id, currentMediaId
                                )
                                delay(tickMs)
                                continue
                            }

                              val currentPosition = controller.currentPosition.coerceAtLeast(0L)
                              val songDurationHint = visibleSong?.duration ?: 0L
                              val duration = resolveEffectiveDuration(
                                  reportedDurationMs = controller.duration,
                                  songDurationHintMs = songDurationHint,
                                  currentPositionMs = currentPosition
                              )

                              val resolvedPosition = resolveUiPosition(currentMediaId, currentPosition)
                              if (_currentPosition.value != resolvedPosition) {
                                  _currentPosition.value = resolvedPosition
                              }

                              _stablePlayerState.update { state ->
                                  if (state.totalDuration == duration) {
                                      state
                                  } else {
                                      state.copy(totalDuration = duration)
                                  }
                             }
                          }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error in progress update loop")
                }
                // Interruptible wait: returns immediately when a UI surface starts observing position
                withTimeoutOrNull(tickMs) { progressPoke.receive() }
            }
        }
    }

    private fun currentProgressTickMs(): Long {
        if (activeSpeedSeekDirection == SpeedSeekDirection.FORWARD && !speedSeekRemoteFallback) {
            return 60L
        }
        if (!powerManager.isInteractive) return BACKGROUND_PROGRESS_TICK_MS
        // If nothing is rendering the playback slider/position, poll at 1s to stop 4 Binder IPCs/sec
        if (_currentPosition.subscriptionCount.value == 0) return UNOBSERVED_PROGRESS_TICK_MS
        return FOREGROUND_PROGRESS_TICK_MS
    }

    fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
        progressSubscriptionWatcherJob?.cancel()
        progressSubscriptionWatcherJob = null
    }

    /* -------------------------------------------------------------------------- */
    /*                               Shuffle & Repeat                             */
    /* -------------------------------------------------------------------------- */

    fun toggleShuffle() {
        val castPlayer = castStateHolder.castSession.value?.remoteMediaClient
        if (castPlayer == null) {
            setLocalShuffleEnabled(!_stablePlayerState.value.isShuffleEnabled)
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        if (shuffleToggleJob?.isActive == true ||
            nowMs - lastShuffleToggleFinishedAtMs < SHUFFLE_TOGGLE_COOLDOWN_MS
        ) return

        shuffleToggleJob = scope.launch(Dispatchers.Main.immediate) {
            val currentShuffle = _stablePlayerState.value.isShuffleEnabled
            _stablePlayerState.update { it.copy(isShuffleTransitionInProgress = true) }
            try {
                val repeatMode = if (castPlayer.mediaStatus?.getQueueRepeatMode() ==
                    MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE
                ) {
                    MediaStatus.REPEAT_MODE_REPEAT_ALL
                } else {
                    MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE
                }
                castStateHolder.castPlayer?.setRepeatMode(repeatMode)
                _stablePlayerState.update { it.copy(isShuffleEnabled = !currentShuffle) }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error toggling cast shuffle")
            } finally {
                lastShuffleToggleFinishedAtMs = SystemClock.elapsedRealtime()
                _stablePlayerState.update { it.copy(isShuffleTransitionInProgress = false) }
            }
        }
    }

    /** The local timeline carries the shuffle order used by playback and queue edits. */
    fun setLocalShuffleEnabled(enabled: Boolean): Boolean {
        val oldState = _stablePlayerState.value
        if (oldState.isShuffleEnabled == enabled || oldState.isShuffleTransitionInProgress) {
            return oldState.isShuffleEnabled
        }

        _stablePlayerState.update { it.copy(isShuffleTransitionInProgress = true) }
        try {
            dualPlayerEngine.cancelNext()
            val player = dualPlayerEngine.masterPlayer
            if (!queueStateHolder.applyLocalShuffle(player, enabled)) return oldState.isShuffleEnabled
            _stablePlayerState.update {
                it.copy(isShuffleEnabled = enabled, currentMediaItemIndex = player.currentMediaItemIndex)
            }
            return enabled
        } catch (e: IllegalStateException) {
            Timber.tag(TAG).e(e, "Could not shuffle the local queue")
            return oldState.isShuffleEnabled
        } finally {
            _stablePlayerState.update { it.copy(isShuffleTransitionInProgress = false) }
        }
    }

    fun onCleared() {
        stopProgressUpdates()
        remoteSeekUnlockJob?.cancel()
        remoteSeekUnlockJob = null
        shuffleToggleJob?.cancel()
        shuffleToggleJob = null
        // scope = null // scope in our class is private val scope: CoroutineScope = appScope, so we don't need to nullify it if it is not mutable, or we can just skip nullifying it.
    }
}

