package com.unshoo.pixelmusic.data.service.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver
import com.unshoo.pixelmusic.data.lossless.applemusic.AppleMusicDrm
import com.unshoo.pixelmusic.data.lossless.playback.LosslessSchemeRoutingDataSource
import com.unshoo.pixelmusic.data.lossless.cache.LosslessCachingDataSource
import com.unshoo.pixelmusic.data.lossless.cache.LosslessFileCache
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import unshoo.ianshulyadav.pixelmusic.innertube.utils.StreamClientUtils
import com.unshoo.pixelmusic.data.model.TransitionSettings
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.remote.youtube.Constants
import com.unshoo.pixelmusic.data.remote.youtube.PixelMusicHelper
import com.unshoo.pixelmusic.data.telegram.TelegramRepository
import com.unshoo.pixelmusic.utils.envelope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume



data class ActiveDecoderInfo(
    val name: String,
    val isHardware: Boolean
)

/**
 * Manages two ExoPlayer instances (A and B) to enable seamless transitions.
 *
 * Player A is the designated "master" player. During a crossfade the MediaSession can
 * expose Player B early for UI continuity, while Player A remains alive to fade out.
 * Player B is the auxiliary player used to pre-buffer and fade in the next track.
 * After a transition, Player A adopts the state of Player B, ensuring continuity.
 */
@OptIn(UnstableApi::class)
@Singleton
class DualPlayerEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val telegramRepository: TelegramRepository,
    private val telegramStreamProxy: com.unshoo.pixelmusic.data.telegram.TelegramStreamProxy,

    private val gdriveStreamProxy: com.unshoo.pixelmusic.data.gdrive.GDriveStreamProxy,
    private val telegramCacheManager: com.unshoo.pixelmusic.data.telegram.TelegramCacheManager,
    private val connectivityStateHolder: com.unshoo.pixelmusic.presentation.viewmodel.ConnectivityStateHolder,
    private val exoCache: com.unshoo.pixelmusic.data.remote.youtube.ExoCache,
    private val userPreferencesRepository: UserPreferencesRepository
) {
    private companion object {
        private const val LOSSLESS_STALL_FALLBACK_TIMEOUT_MS = 4_000L
        private const val DUCK_FACTOR = 0.2f
        /** Manual skip: short fade-out so a tap never feels laggy (Mochy caps it at 1.5 s). */
        private const val SKIP_FADE_OUT_MS = 450L
        private const val SKIP_FADE_IN_MS = 700L
        private const val SKIP_FADE_FRAME_MS = 16L
        private const val SKIP_FADE_START_WAIT_MS = 6_000L
        private const val DEAD_URL_TTL_MS = 6 * 60 * 60 * 1000L
        private const val FORCED_REFRESH_MIN_INTERVAL_MS = 15_000L
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val MONOCHROME_RATE_LIMIT_BACKOFF_MS = 2 * 60 * 1000L
        private val REFUSED_STREAM_CODES = setOf(401, 403, 404, 410, HTTP_TOO_MANY_REQUESTS)
        private const val AUDIO_OFFLOAD_BUFFERING_FALLBACK_MS = 4_000L
        private const val MAX_AUXILIARY_TIMELINE_ITEMS = 200
        private const val STREAM_RESOLVE_TIMEOUT_MS = 8_000L
        // BUGFIX: was 5_000L (shorter than normal!). Weak networks need MORE time, not less.
        private const val STREAM_RESOLVE_TIMEOUT_LOW_CONNECTIVITY_MS = 12_000L
        /**
         * How long the ExoPlayer load thread may cooperatively wait for a stream resolve to
         * finish before giving up. Must exceed [STREAM_RESOLVE_TIMEOUT_MS] so the load thread
         * never aborts while a legitimate resolution is still making progress. ExoPlayer runs
         * data-source opens on its background Loader thread, so waiting here does NOT touch the
         * UI thread â€” whereas throwing early pushed ProgressiveMediaSource into exponential
         * retry back-off (1s â†’ 2s â†’ 4s), which was the multi-second audio-start regression.
         */
        private const val LOAD_THREAD_RESOLVE_AWAIT_MS = 14_000L
        /** Extra resolve time granted when the lossless chain runs before the YouTube step. */
        private const val LOSSLESS_RESOLVE_EXTRA_MS = 3_500L
        /**
         * Byte ceiling for the forward buffer. With prioritizeTimeOverSizeThresholds=true the player
         * always fills minBufferMs, then keeps loading up to maxBufferMs only while under this cap.
         * AAC/Opus (~4.8MB per 150s) never reach it, so they keep the long time-based buffer; FLAC
         * (16/44 ~15MB per 120s, 24/96 ~24MB per 64s) is bounded by bytes. This makes one profile
         * safe for every source, independent of the lossless toggle.
         */
        private const val TARGET_BUFFER_BYTES = 24 * 1024 * 1024
        // Upgraded HTTP timeouts (InnerTune/ViMusic parity): 15s connect / 20s read to prevent
        // premature SocketTimeoutException on weak connections or during concurrent downloads.
        private const val HTTP_CONNECT_TIMEOUT_MS = 15_000
        private const val HTTP_READ_TIMEOUT_MS = 20_000
        private const val FIRST_CHUNK_PRECACHE_BYTES = 1024L * 1024L // 1MB â€” standard audio (~50s audio cushion)
        private val LOCAL_MEDIA_SCHEMES = setOf("content", "file", "android.resource")
        private val YOUTUBE_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

        /** Shared streaming client: no call timeout (a track streams for minutes), pooled connections. */
        private val playbackHttpClient: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(HTTP_CONNECT_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(HTTP_READ_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .connectionPool(okhttp3.ConnectionPool(8, 5, java.util.concurrent.TimeUnit.MINUTES))
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                // Same routing as before: the default ProxySelector honours the system properties
                // AppProxyManager sets; HTTP proxy credentials (HttpURLConnection used the JVM
                // Authenticator) are supplied here. SOCKS auth still goes through the JVM Authenticator.
                .proxyAuthenticator { _, response ->
                    val cfg = com.unshoo.pixelmusic.data.network.AppProxyManager.currentConfig
                    if (!cfg.isValid || cfg.username.isBlank() ||
                        response.request.header("Proxy-Authorization") != null
                    ) {
                        null
                    } else {
                        response.request.newBuilder()
                            .header("Proxy-Authorization", okhttp3.Credentials.basic(cfg.username, cfg.password))
                            .build()
                    }
                }
                .build()
        }
        private val REMOTE_MEDIA_SCHEMES = setOf("http", "https", "telegram", "gdrive", "youtube", "monochrome")
    }

    data class TransitionTarget(
        val mediaItem: MediaItem,
        val absoluteIndex: Int,
        val queueSize: Int
    )

    // BUGFIX: was `val` â€” release() cancels the SupervisorJob, but initialize() never
    // recreated it. Since DualPlayerEngine is @Singleton, the dead scope persisted across
    // MusicService destroy/create cycles, silently dropping all scope.launch calls
    // (kickBackgroundResolve, preResolve, preference collectors).
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    var hiFiModeEnabled: Boolean = false
        private set
    private var audioOffloadEnabled = false
    private var transitionJob: Job? = null
    private var bufferingFallbackJob: Job? = null
    private var transitionRunning = false
    private var preResolutionJob: Job? = null
    private var queueSnapshot: List<MediaItem> = emptyList()
    private var activeWindowStartIndex = 0
    private var activePlayerUsesWindowedQueue = false
    private var preparedWindowStartIndex = 0
    private var preparedPlayerUsesWindowedQueue = false

    private lateinit var playerA: ExoPlayer
    private var playerB: ExoPlayer? = null

    private val onPlayerSwappedListeners = mutableListOf<(Player) -> Unit>()
    internal var beforeQueueReplacement: ((Player, List<MediaItem>?, Int, String?) -> Boolean)? = null

    internal fun archiveQueueBeforeDiscard(): Boolean =
        beforeQueueReplacement?.invoke(masterPlayer, null, 0, null) == true

    internal fun replacePlaybackQueue(items: List<MediaItem>, startIndex: Int, sourceName: String) {
        val player = masterPlayer
        beforeQueueReplacement?.invoke(player, items, startIndex, sourceName)
        player.setMediaItems(items, startIndex, 0L)
    }

    private val onNextPlayerPreparedListeners = mutableListOf<(Player) -> Unit>()
    private val onTransitionDisplayPlayerListeners = mutableListOf<(Player) -> Unit>()
    private val onTransitionFinishedListeners = mutableListOf<() -> Unit>()

    private var onPlayerAboutToBeReleasedListener: ((Player) -> Unit)? = null

    fun setOnPlayerAboutToBeReleasedListener(listener: (Player) -> Unit) {
        onPlayerAboutToBeReleasedListener = listener
    }
    
    private val _activeAudioSessionId = MutableStateFlow(0)
    val activeAudioSessionId: StateFlow<Int> = _activeAudioSessionId.asStateFlow()

    /** The master deck's audio session (0 when unknown). */
    private fun currentAudioSessionId(): Int {
        if (!::playerA.isInitialized) return 0
        val id = playerA.audioSessionId
        return if (id != C.AUDIO_SESSION_ID_UNSET) id else 0
    }

    private val _activeDecoderInfo = MutableStateFlow<ActiveDecoderInfo?>(null)
    val activeDecoderInfo: StateFlow<ActiveDecoderInfo?> = _activeDecoderInfo.asStateFlow()

    private val _hiFiModeEnabledState = MutableStateFlow(false)
    val hiFiModeEnabledState: StateFlow<Boolean> = _hiFiModeEnabledState.asStateFlow()

    private val _isPlaybackBufferingState = MutableStateFlow(false)
    val isPlaybackBufferingState: StateFlow<Boolean> = _isPlaybackBufferingState.asStateFlow()

    val isPlaybackBuffering: Boolean
        get() = _isPlaybackBufferingState.value

    val bufferedAheadMs: Long
        get() {
            if (!::playerA.isInitialized) return 0L
            if (Looper.myLooper() != Looper.getMainLooper()) {
                return 30_000L
            }
            return (playerA.bufferedPosition - playerA.currentPosition).coerceAtLeast(0L)
        }

    // Audio Focus Management
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isFocusLossPause = false
    val isFocusLossPaused: Boolean
        get() = isFocusLossPause
    private var isDucked = false
    private var preDuckVolumeA = 1f
    private var lastPlayWhenReadyAtMs: Long = 0L
    private var lastPausedAtMs: Long = 0L
    // Ignore the transient buffering that follows a seek when checking for lossless stalls.
    private var lastSeekAtMs: Long = 0L

    /**
     * Set by MusicService once ReplayGain for the incoming track is known.
     * The crossfade loop reads this at the end instead of hard-coding 1f,
     * so the incoming track reaches its correct RG volume without a jump.
     * Reset to null after each transition.
     */
    var incomingTrackReplayGainVolume: Float? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (isReleased || !::playerA.isInitialized || audioFocusRequest == null) return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Timber.tag("TransitionDebug").d("AudioFocus LOSS. Pausing.")
                isFocusLossPause = false
                playerA.playWhenReady = false
                playerB?.playWhenReady = false
                clearDucking()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Timber.tag("TransitionDebug").d("AudioFocus LOSS_TRANSIENT (%d). Pausing.", focusChange)
                val auxiliaryPlayer = playerB
                isFocusLossPause = isFocusLossPause ||
                    (::playerA.isInitialized && (playerA.playWhenReady || playerA.isPlaying)) ||
                    (transitionRunning && auxiliaryPlayer != null && (auxiliaryPlayer.playWhenReady || auxiliaryPlayer.isPlaying))
                playerA.playWhenReady = false
                auxiliaryPlayer?.playWhenReady = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Timber.tag("TransitionDebug").d("AudioFocus LOSS_TRANSIENT_CAN_DUCK. Ducking.")
                if (!isDucked) {
                    isDucked = true
                    preDuckVolumeA = if (skipFadeActive) skipFadeRestVolume else playerA.volume
                    if (!skipFadeActive) playerA.volume *= DUCK_FACTOR
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Timber.tag("TransitionDebug").d("AudioFocus GAIN. Restoring volume and resuming if paused.")
                clearDucking()
                if (isFocusLossPause) {
                    isFocusLossPause = false
                    if (playerA.playbackState == Player.STATE_IDLE || playerA.playbackState == Player.STATE_ENDED) {
                        playerA.prepare()
                    }
                    playerA.playWhenReady = true
                    if (transitionRunning) playerB?.playWhenReady = true
                }
            }
        }
    }

    private fun clearDucking() {
        if (!isDucked) return
        if (!skipFadeActive && !transitionRunning) {
            playerA.volume = preDuckVolumeA
        }
        isDucked = false
    }

    // Listener to attach to the active master player (playerA)
    private val masterPlayerListener = object : Player.Listener, AnalyticsListener {
        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            Timber.tag("DualPlayerEngine").w(audioSinkError,
                "Audio sink error: id=%s positionMs=%d state=%d audioSelected=%b volume=%.3f offloadSleep=%b overlap=%b",
                playerA.currentMediaItem?.mediaId, playerA.currentPosition, playerA.playbackState,
                playerA.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO), playerA.volume,
                playerA.isSleepingForOffload, transitionRunning)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                lastPlayWhenReadyAtMs = SystemClock.elapsedRealtime()
                requestAudioFocus()
                checkAndRecoverStalePlaybackOnResume()
            } else {
                lastPausedAtMs = SystemClock.elapsedRealtime()
                cancelAudioOffloadFallback()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                lastPausedAtMs = 0L
                cancelAudioOffloadFallback()
            }
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
        ) {
            lastAudioInputSampleRate = format.sampleRate
            Timber.tag("AudioRateCheck").i(
                "input format: mime=%s codecs=%s rate=%d ch=%d pcm=%d container=%s",
                format.sampleMimeType, format.codecs, format.sampleRate, format.channelCount,
                format.pcmEncoding, format.containerMimeType
            )
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
        ) {
            Timber.tag("AudioRateCheck").i(
                "audio track: rate=%d encoding=%d offload=%b (decoder input rate=%d)",
                audioTrackConfig.sampleRate, audioTrackConfig.encoding, audioTrackConfig.offload,
                lastAudioInputSampleRate
            )
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            val current = _activeDecoderInfo.value
            if (current == null || current.name != decoderName) {
                val isHardware = AudioDecoderPolicy.isLikelyHardwareDecoder(decoderName)
                _activeDecoderInfo.value = ActiveDecoderInfo(decoderName, isHardware)
                Timber.tag("DualPlayerEngine").d("Audio decoder initialized: %s (Hardware: %b)", decoderName, isHardware)
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId != 0 && audioSessionId != C.AUDIO_SESSION_ID_UNSET &&
                _activeAudioSessionId.value != audioSessionId
            ) {
                _activeAudioSessionId.value = audioSessionId
                Timber.tag("TransitionDebug").d("Master audio session changed: %d", audioSessionId)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Only evict the OUTGOING item's resolved URI from the active-playback lock.
            // Clearing ALL entries here caused BUG 1: ExoPlayer's data-source re-entered
            // resolveDataSpec() ~1 second into a new YouTube track, got a fresh (different)
            // URL, and restarted playback from position 0.
            // We intentionally keep the INCOMING item's lock alive so subsequent data
            // reads for the same URI always return the same cached URL.
            // The lock for any truly stale entries will expire naturally when those
            // video-IDs are no longer the active item.
            val incomingUri = mediaItem?.localConfiguration?.uri
            activePlaybackResolvedUris.keys
                .filter { it != incomingUri?.toString() && activePlaybackResolvedUris[it] != incomingUri }
                .forEach { activePlaybackResolvedUris.remove(it) }
            cancelAudioOffloadFallback()

            // A track transition (auto-advance or manual skip/seek) must always begin at 1.0x
            // even if a hold-to-seek gesture was still active or recently tore down.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                resetPlaybackSpeedToNormal(playerA)
            }
            
            // If the transition was not automatic (e.g. user skip or playlist change),
            // immediately cancel any background crossfade logic to ensure responsiveness.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                cancelNext()
            }

            currentMasterYoutubeId = mediaItem?.let(::youtubeIdOf)
            val uri = mediaItem?.localConfiguration?.uri
            if (uri?.scheme == "telegram") {
                scope.launch {
                    val result = telegramRepository.resolveTelegramUri(uri.toString())
                    val fileId = result?.first
                    telegramCacheManager.setActivePlayback(fileId)
                    Timber.tag("DualPlayerEngine").d("Telegram playback active: fileId=$fileId")
                }
            } else {
                telegramCacheManager.setActivePlayback(null)
            }

            // --- Pre-Resolve Next/Prev Tracks with Debounce to prevent flooding ---
            preResolutionJob?.cancel()
            preResolutionJob = scope.launch {
                delay(150) // Reduced from 600ms for faster online stream pre-resolution
                try {
                    val currentIndex = playerA.currentMediaItemIndex
                    if (currentIndex != C.INDEX_UNSET) {
                        val nextUri = if (currentIndex + 1 < playerA.mediaItemCount) {
                            playerA.getMediaItemAt(currentIndex + 1).localConfiguration?.uri
                        } else null
                        val prevUri = if (currentIndex - 1 >= 0) {
                            playerA.getMediaItemAt(currentIndex - 1).localConfiguration?.uri
                        } else null
                        val itemsToPreResolve = listOfNotNull(nextUri, prevUri)

                        for (uriToResolve in itemsToPreResolve) {
                            val scheme = uriToResolve.scheme
                            if (scheme == "telegram" || scheme == "netease" || scheme == "qqmusic" || scheme == "gdrive") {
                                resolveCloudUri(uriToResolve)
                            } else if (scheme == "youtube" && uriToResolve == nextUri) {
                                // Pre-resolve immediate next track outside activeResolutions with
                                // isNextInQueue=true (9s lossless budget) so it never poisons the cache
                                // with a 3s YouTube fallback and fast skips abort it immediately.
                                preResolveNeighborYoutube(uriToResolve)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("DualPlayerEngine").w(e, "Error during pre-resolution in onMediaItemTransition")
                }
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // BUG 2 FIX: Do NOT skip snapshot refresh when a transition is running.
            // AutoQueueManager calls player.addMediaItems() during a crossfade, which
            // fires PLAYLIST_CHANGED here. The old guard caused queueSnapshot to stay
            // stale, so getNextTransitionTarget() returned the wrong track.
            // We still refresh the snapshot but do NOT cancel any running transition â€”
            // that is TransitionController's responsibility.
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED || queueSnapshot.isEmpty()) {
                refreshQueueSnapshotFromMaster(windowStartIndex = 0, usesWindowedQueue = false)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isPlaybackBufferingState.value = (playbackState == Player.STATE_BUFFERING)
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    scheduleAudioOffloadFallbackIfNeeded(playerA)
                    scheduleLosslessStallFallbackIfNeeded(playerA)
                }
                Player.STATE_READY, Player.STATE_IDLE, Player.STATE_ENDED -> {
                    cancelAudioOffloadFallback()
                    cancelLosslessStallFallback()
                }
            }
        }


        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                lastSeekAtMs = SystemClock.elapsedRealtime()
            }
        }
    }

    fun addPlayerSwapListener(listener: (Player) -> Unit) {
        onPlayerSwappedListeners.add(listener)
    }

    fun removePlayerSwapListener(listener: (Player) -> Unit) {
        onPlayerSwappedListeners.remove(listener)
    }

    fun addNextPlayerPreparedListener(listener: (Player) -> Unit) {
        onNextPlayerPreparedListeners.add(listener)
    }

    fun removeNextPlayerPreparedListener(listener: (Player) -> Unit) {
        onNextPlayerPreparedListeners.remove(listener)
    }

    fun addTransitionDisplayPlayerListener(listener: (Player) -> Unit) {
        onTransitionDisplayPlayerListeners.add(listener)
    }

    fun removeTransitionDisplayPlayerListener(listener: (Player) -> Unit) {
        onTransitionDisplayPlayerListeners.remove(listener)
    }

    fun addTransitionFinishedListener(listener: () -> Unit) {
        onTransitionFinishedListeners.add(listener)
    }

    /**
     * Notifies the engine that an external caller (UI seek, etc.) is about to issue a
     * seek through the MediaController. Used to mark the upcoming STATE_BUFFERING as
     * seek-driven so the HAL-reset heuristic does not trigger a player rebuild that
     * would race with the in-flight seek command.
     *
     * Setting this here (synchronously, before the seek dispatches) is more reliable
     * than waiting for onPositionDiscontinuity, which is delivered on the next event
     * batch and can race with onPlaybackStateChanged on some Media3 versions.
     */

    private fun checkAndRecoverStalePlaybackOnResume() {
        val player = if (::playerA.isInitialized) playerA else return
        if (player.isPlaying) return
        val currentItem = player.currentMediaItem ?: return
        val currentUri = currentItem.localConfiguration?.uri ?: return
        if (currentUri.scheme == "file" || currentUri.scheme == "content") return
        val uriStr = currentUri.toString()
        val isAppleHlsStream = uriStr.startsWith("data:application/vnd.apple.mpegurl") ||
            uriStr.startsWith("data:application/x-mpegurl")
        val isRemoteStream = isAppleHlsStream ||
            uriStr.startsWith("youtube://") ||
            uriStr.contains("googlevideo.com") ||
            uriStr.contains("saavncdn.com") ||
            uriStr.contains("jiosaavn.com") ||
            currentItem.mediaId.startsWith("youtube_")

        if (!isRemoteStream) return

        // After a long pause (>20 min) or cold start, remote CDN tokens (JioSaavn, YouTube, etc.)
        // are almost certainly expired. Force a proactive re-resolve instead of waiting for
        // the player to buffer-timeout.
        val pausedForMs = when {
            lastPausedAtMs > 0L -> SystemClock.elapsedRealtime() - lastPausedAtMs
            currentUri.scheme == "http" || currentUri.scheme == "https" || isAppleHlsStream -> Long.MAX_VALUE
            else -> 0L
        }
        val isLongPause = pausedForMs > 20 * 60 * 1000L
        if (isLongPause) {
            Timber.tag("DualPlayerEngine").i("Long pause (%ds) detected, forcing re-resolve for %s", pausedForMs / 1000, currentItem.mediaId)
            scope.launch(Dispatchers.Main.immediate) {
                ensureFreshStreamForResume()
            }
            return
        }

        // Apple HLS data: URIs are self-contained (no resolved-uri cache entry);
        // check freshness directly via LosslessStreamResolver.
        if (isAppleHlsStream) {
            if (!LosslessStreamResolver.isFreshUri(uriStr)) {
                scope.launch(Dispatchers.Main.immediate) {
                    ensureFreshStreamForResume()
                }
            }
            return
        }

        val cleanId = currentItem.mediaId.removePrefix("youtube_")
        val resolvedUri = activePlaybackResolvedUris[uriStr]
            ?: resolvedUriCache.get(uriStr)
            ?: StreamChoice.of(cleanId)?.let { Uri.parse(it.url) }

        // Unknown (null) is not stale: the data source resolves on demand. Treating "no cached
        // entry" as stale rebuilt the item and dropped the buffer on every resume.
        if (resolvedUri != null && (!isResolvedUriFresh(uriStr, resolvedUri) || isDeadStreamUrl(resolvedUri.toString()))) {
            scope.launch(Dispatchers.Main.immediate) {
                ensureFreshStreamForResume()
            }
        }
    }

    fun notifyExternalSeekInitiated() {
        lastSeekAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * Forces an immediate refresh of the internal queue snapshot from the current
     * master player timeline. Call this after programmatically adding/removing items
     * to the player queue from outside the engine (e.g. AutoQueueManager) to ensure
     * [getNextTransitionTarget] returns the correct next track immediately.
     */
    fun forceRefreshQueueSnapshot() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            refreshQueueSnapshotFromMaster(windowStartIndex = 0, usesWindowedQueue = false)
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                refreshQueueSnapshotFromMaster(windowStartIndex = 0, usesWindowedQueue = false)
            }
        }
    }

    fun removeTransitionFinishedListener(listener: () -> Unit) {
        onTransitionFinishedListeners.remove(listener)
    }

    val masterPlayer: Player
        get() = playerA

    fun isTransitionRunning(): Boolean = transitionRunning

    // ---- Manual skip fade (modelled on Mochy Music's TransportTransitions) ----------------
    // A tapped next/previous no longer hard-cuts: the playing song fades out quickly, the
    // switch happens once it is silent, and the new song swells in only once it is really
    // audible (a slow start used to come in at full volume with no fade at all). A second tap
    // while a fade runs acts at once. One player, volume only: no second decoder, no overlap.
    private var skipFadeJob: Job? = null
    private var skipFadeToken = 0
    @Volatile private var skipFadeActive = false
    /** Volume the song should rest at (ReplayGain / user level) while a skip fade owns the volume. */
    @Volatile private var skipFadeRestVolume = 1f
    /** A skip whose fade-out was interrupted before its song change ran. */
    private var pendingSkipChange: (() -> Unit)? = null

    /** True while a manual-skip fade is driving the master volume. */
    fun isSkipFadeActive(): Boolean = skipFadeActive

    fun isVolumeDucked(): Boolean = isDucked

    /** Apply the intended listening level with the current focus or skip-fade attenuation. */
    fun setMasterRestVolume(volume: Float) {
        if (isDucked) preDuckVolumeA = volume
        if (skipFadeActive) {
            skipFadeRestVolume = volume
        } else {
            playerA.volume = if (isDucked) volume * DUCK_FACTOR else volume
        }
    }

    private fun skipFadeTargetVolume(): Float =
        if (isDucked) skipFadeRestVolume * DUCK_FACTOR else skipFadeRestVolume

    /**
     * Runs [change] (a seek to another song) wrapped in a fade-out / fade-in. Falls back to
     * running it at once when nothing is audibly playing or a crossfade owns the players.
     */
    fun fadedSkip(change: () -> Unit) {
        if (isReleased || !::playerA.isInitialized) {
            change()
            return
        }
        val player = playerA
        val wasFading = skipFadeActive
        if (!wasFading && (!player.playWhenReady || !player.isPlaying || transitionRunning)) {
            change()
            return
        }
        val token = ++skipFadeToken
        skipFadeJob?.cancel()
        // A second tap means "now": run the first skip's change if its fade had not got there.
        val interrupted = pendingSkipChange
        pendingSkipChange = change
        if (!wasFading) {
            skipFadeRestVolume = if (isDucked) preDuckVolumeA else player.volume
        }
        skipFadeActive = true
        // Consume an interrupted command before another tap can cancel this job.
        skipFadeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (!wasFading) {
                    runSkipVolumeFade(player, from = 1f, to = 0f, lengthMs = SKIP_FADE_OUT_MS)
                }
                player.volume = 0f
                interrupted?.invoke()
                pendingSkipChange = null
                val oldId = player.currentMediaItem?.mediaId
                val oldIndex = player.currentMediaItemIndex
                change()
                awaitSkipTargetAudible(player, oldId, oldIndex)
                runSkipVolumeFade(player, from = 0f, to = 1f, lengthMs = SKIP_FADE_IN_MS)
            } finally {
                if (skipFadeToken == token) {
                    // Finished, or stopped by something other than another tap: hand the volume back.
                    pendingSkipChange?.let { leftover ->
                        pendingSkipChange = null
                        runCatching { leftover() }
                    }
                    if (!isReleased && player === playerA) {
                        if (isDucked) preDuckVolumeA = skipFadeRestVolume
                        runCatching { player.volume = skipFadeTargetVolume() }
                    }
                    skipFadeActive = false
                }
            }
        }
    }

    private fun cancelSkipFade() {
        skipFadeToken++
        pendingSkipChange = null
        skipFadeActive = false
        skipFadeJob?.cancel()
    }

    private suspend fun runSkipVolumeFade(player: ExoPlayer, from: Float, to: Float, lengthMs: Long) {
        val start = System.nanoTime()
        while (true) {
            if (transitionRunning) return
            val p = ((System.nanoTime() - start) / 1_000_000f / lengthMs).coerceIn(0f, 1f)
            val eased = p * p * (3f - 2f * p) // smoothstep, as Mochy
            val level = from + (to - from) * eased
            player.volume = (skipFadeTargetVolume() * level).coerceIn(0f, 1f)
            if (p >= 1f) return
            delay(SKIP_FADE_FRAME_MS)
        }
    }

    /** Waits (silent) until the new song is actually playing, at most [SKIP_FADE_START_WAIT_MS]. */
    private suspend fun awaitSkipTargetAudible(player: ExoPlayer, oldId: String?, oldIndex: Int) {
        var waited = 0L
        while (waited < SKIP_FADE_START_WAIT_MS) {
            val moved = player.currentMediaItemIndex != oldIndex ||
                player.currentMediaItem?.mediaId != oldId ||
                player.currentPosition < 1_000L
            if (player.isPlaying && moved) return
            if (!player.playWhenReady) return
            delay(SKIP_FADE_FRAME_MS)
            waited += SKIP_FADE_FRAME_MS
        }
    }

    /** True when the standby player holds [expectedMediaId] fully buffered and ready to fade in. */
    fun isPreparedNextReady(expectedMediaId: String?): Boolean {
        val aux = playerB ?: return false
        if (aux.mediaItemCount == 0) return false
        if (aux.playbackState != Player.STATE_READY || !aux.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) return false
        return expectedMediaId == null || aux.currentMediaItem?.mediaId == expectedMediaId
    }

    fun getAudioSessionId(): Int = currentAudioSessionId()

    fun setAudioSessionId(sessionId: Int) {
        if (sessionId <= 0) return
        if (::playerA.isInitialized) {
            try { playerA.audioSessionId = sessionId } catch (_: Exception) {}
        }
        _activeAudioSessionId.value = sessionId
    }

    fun invalidateResolvedUri(uriString: String) {
        resolvedUriCache.remove(uriString)
        activePlaybackResolvedUris.remove(uriString)
        activeResolutions.remove(uriString)?.cancel()
        StreamChoice.forget(uriString)
        val cleanId = when {
            uriString.startsWith("youtube://") -> uriString.removePrefix("youtube://")
            uriString.startsWith("youtube_") -> uriString.removePrefix("youtube_")
            uriString.startsWith("monochrome://") -> uriString.removePrefix("monochrome://")
            uriString.startsWith("monochrome_") -> uriString.removePrefix("monochrome_")
            else -> null
        }
        if (cleanId != null) {
            StreamChoice.forget(cleanId)
            activePlaybackResolvedUris.remove("youtube://$cleanId")
            activePlaybackResolvedUris.remove(cleanId)
            resolvedUriCache.remove("youtube://$cleanId")
            resolvedUriCache.remove(cleanId)
            com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.invalidateStreamCache(cleanId)
        }
    }

    // â”€â”€ Stream expiry / dead-link handling â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Links the CDN refused (401/403/404/410/429) even though their `expire=` time has not passed:
     * revoked tokens, rate-limited endpoints, IP changes, or throttled sessions. Treated as stale
     * so no cache layer hands them out again.
     */
    private val deadStreamUrls = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastForcedRefreshAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun markStreamUrlDead(url: String) {
        if (url.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        deadStreamUrls[url] = now + DEAD_URL_TTL_MS
        if (deadStreamUrls.size > 64) deadStreamUrls.entries.removeIf { it.value <= now }
    }

    private fun isDeadStreamUrl(url: String): Boolean {
        val until = deadStreamUrls[url] ?: return false
        if (until <= SystemClock.elapsedRealtime()) {
            deadStreamUrls.remove(url)
            return false
        }
        return true
    }

    internal fun isRefusedHttpCode(code: Int): Boolean = code in REFUSED_STREAM_CODES

    internal fun findHttpResponseException(
        error: Throwable?,
    ): androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return cause
            }
            cause = cause.cause
        }
        return null
    }

    internal fun peekResolvedPlaybackUri(originalUriString: String): String? =
        (activePlaybackResolvedUris[originalUriString] ?: resolvedUriCache.get(originalUriString))?.toString()

    private fun findYoutubeIdByResolvedUrl(failingUrl: String): String? {
        if (failingUrl.isBlank()) return null
        val fromActive = activePlaybackResolvedUris.entries
            .firstOrNull { it.key.startsWith("youtube://") && it.value.toString() == failingUrl }
            ?.key
            ?.removePrefix("youtube://")
        if (fromActive != null) return fromActive
        return resolvedUriCache.snapshot().entries
            .firstOrNull { it.key.startsWith("youtube://") && it.value.toString() == failingUrl }
            ?.key
            ?.removePrefix("youtube://")
    }

    internal fun recordRefusedStreamFailure(
        failingUrl: String,
        responseCode: Int?,
        videoId: String?,
    ): Boolean? {
        if (failingUrl.isNotBlank()) markStreamUrlDead(failingUrl)
        val isMonoUrl = failingUrl.contains("monochrome.st", ignoreCase = true) ||
            failingUrl.startsWith("monochrome://", ignoreCase = true)
        if (isMonoUrl && responseCode == HTTP_TOO_MANY_REQUESTS) {
            LosslessStreamResolver.markMonochromeDown(MONOCHROME_RATE_LIMIT_BACKOFF_MS)
        }
        var stepDown: Boolean? = null
        if (!videoId.isNullOrBlank()) {
            val isLosslessOrMono = isMonoUrl || LosslessStreamResolver.isLosslessUri(failingUrl)
            if (isLosslessOrMono) {
                stepDown = LosslessStreamResolver.markCurrentSourceFailedForTrack(
                    mediaId = videoId,
                    reason = "HTTP ${responseCode ?: "error"}",
                    failingUri = failingUrl.takeIf { it.isNotBlank() },
                )
            }
            invalidateResolvedUri("youtube://$videoId")
        }
        if (failingUrl.isNotBlank()) invalidateResolvedUri(failingUrl)
        return stepDown
    }

    /**
     * Cache key for a stream's bytes: video id plus format (itag), or the source for non-YouTube
     * CDNs. Keying by video id alone let a refreshed URL in a different format (Opus vs AAC, or a
     * JioSaavn file) stitch onto the old format's cached spans: corrupt audio, parser errors and a
     * reload loop. Same-format refreshes keep reusing the cached bytes.
     */
    internal fun streamCacheKey(videoId: String, url: String?): String =
        StreamCacheKeys.forStream(videoId, url)

    private fun headersForStream(url: String): Map<String, String> {
        val addonHeaders = com.unshoo.pixelmusic.data.addons.AddonStreamRegistry.headersFor(url)
        if (addonHeaders.isNotEmpty()) return addonHeaders
        if (url.contains("googlevideo.com") || url.contains("youtube.com")) {
            val profile = StreamClientUtils.resolveRequestProfile(url)
            return buildMap {
                put("User-Agent", profile.userAgent)
                profile.origin?.let { put("Origin", it) }
                profile.referer?.let { put("Referer", it) }
            }
        }
        return mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
    }

    /**
     * Purges every cached decision for [videoId] and resolves a fresh stream, waiting on the
     * loader thread (never the UI thread). At most one forced refresh per track per
     * [FORCED_REFRESH_MIN_INTERVAL_MS], so a source that keeps returning bad links cannot spin the
     * loader; the error then reaches MusicService's bounded recovery (retry, skip or pause).
     */
    private fun refreshStreamOnLoadThread(videoId: String): Uri? {
        val now = SystemClock.elapsedRealtime()
        val last = lastForcedRefreshAtMs[videoId]
        if (last != null && now - last < FORCED_REFRESH_MIN_INTERVAL_MS) return null
        lastForcedRefreshAtMs[videoId] = now
        Timber.tag("DualPlayerEngine").i("Stream link for %s expired or refused; re-resolving", videoId)
        invalidateResolvedUri("youtube://$videoId")
        val source = Uri.parse("youtube://$videoId")
        val fresh = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(LOAD_THREAD_RESOLVE_AWAIT_MS) { resolveCloudUri(source) }
            }
        }.getOrNull() ?: return null
        if (fresh == source || isDeadStreamUrl(fresh.toString())) return null
        return fresh
    }

    /**
     * On a refused link: mark it dead and purge the track's cached decisions so the very next retry
     * re-resolves; allow two quick retries, then hand the error to the player (and MusicService's
     * bounded recovery) instead of backing off for ~15s on a URL that cannot work.
     */
    private val streamRecoveryLoadErrorPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(
            loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
        ): Long {
            val http = findHttpResponseException(loadErrorInfo.exception)
            // Every attempt already ran the full resolver chain (cache, Saavn, all YouTube
            // clients, up to LOAD_THREAD_RESOLVE_AWAIT_MS). Retrying that forever is what kept
            // unplayable tracks "buffering" indefinitely; after one retry hand the error to the
            // player so MusicService's bounded recovery can retry/skip/pause.
            var unresolvedCause: Throwable? = loadErrorInfo.exception
            while (unresolvedCause != null && unresolvedCause !is UnresolvableStreamException) {
                unresolvedCause = unresolvedCause.cause
            }
            if (unresolvedCause != null) {
                return if (loadErrorInfo.errorCount <= 1) 1_000L else C.TIME_UNSET
            }
            if (http != null && http.responseCode in REFUSED_STREAM_CODES) {
                val failingUrl = http.dataSpec.uri.toString()
                val originalSpec = loadErrorInfo.loadEventInfo.dataSpec
                val videoId = originalSpec.key?.substringBefore('#')
                    ?.takeIf { YOUTUBE_ID_REGEX.matches(it) && !it.all(Char::isDigit) }
                    ?: originalSpec.uri.toString().takeIf { it.startsWith("youtube://") }?.removePrefix("youtube://")
                    ?: findYoutubeIdByResolvedUrl(failingUrl)
                recordRefusedStreamFailure(failingUrl, http.responseCode, videoId)
                val isMonoOrLossless = LosslessStreamResolver.isLosslessUri(failingUrl) ||
                    failingUrl.contains("monochrome.st", ignoreCase = true)
                val canQuickRetryInPlace = http.responseCode != HTTP_TOO_MANY_REQUESTS &&
                    originalSpec.uri.scheme == "youtube" &&
                    !isMonoOrLossless
                return if (canQuickRetryInPlace && loadErrorInfo.errorCount <= 2) 250L else C.TIME_UNSET
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    /** mediaId of the master player's current item, readable from IO threads. */
    @Volatile private var currentMasterYoutubeId: String? = null

    private var isReleased = false
    private var auxiliaryErrorListener: Player.Listener? = null
    internal val resolvedUriCache = LruCache<String, Uri>(100)
    private val activePlaybackResolvedUris = java.util.concurrent.ConcurrentHashMap<String, Uri>()
    private val localFilePathCache = LruCache<String, String>(200)
    private val activeResolutions = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Uri>>()
    private val failedLocalYoutubeSources = LruCache<String, Boolean>(128)

    /** Fire-and-forget stream resolve used by the non-blocking ResolvingDataSource path. */
    fun kickBackgroundResolve(uri: Uri) {
        val uriString = uri.toString()
        // A cached entry only short-circuits while still usable; an expired or refused one must
        // trigger a fresh resolve instead of silently blocking it.
        resolvedUriCache.get(uriString)?.let { cached ->
            if (isResolvedUriFresh(uriString, cached) && !isDeadStreamUrl(cached.toString())) return
        }
        // Always funnel through resolveCloudUri so in-flight requests are deduped.
        scope.launch(Dispatchers.IO) {
            runCatching { resolveCloudUri(uri) }
                .onFailure { Timber.tag("DualPlayerEngine").w(it, "Background resolve failed for %s", uriString) }
        }
    }

    fun getCachedResolvedUri(uri: Uri): Uri? {
        val uriString = uri.toString()
        if (failedLocalYoutubeSources.get(uriString) != true) {
            localFilePathCache[uriString]?.let { localPath ->
                if (localPath.startsWith("content://")) return Uri.parse(localPath)
                val file = File(localPath)
                if (file.isFile && file.length() > 0L) return Uri.fromFile(file)
            }
        }
        if (uri.scheme == "youtube" && failedLocalYoutubeSources.get(uriString) != true) {
            com.unshoo.pixelmusic.utils.OfflineAudioResolver
                .peekDownloadedAudioUri(uriString.removePrefix("youtube://"))?.let { downloaded ->
                    if (downloaded.scheme == "content" ||
                        downloaded.path?.let { File(it).isFile && File(it).length() > 0L } == true
                    ) return downloaded
                }
        }
        val cached = resolvedUriCache.get(uriString) ?: activePlaybackResolvedUris[uriString]
        return if (cached != null && isResolvedUriFresh(uriString, cached)) cached else null
    }

    fun getCachedResolvedMediaItem(mediaItem: MediaItem): MediaItem? {
        val uri = mediaItem.localConfiguration?.uri ?: return mediaItem
        if (uri.scheme == "file" || uri.scheme == "content") return mediaItem
        if (uri.scheme == "youtube" && failedLocalYoutubeSources.get(uri.toString()) != true &&
            !mediaItem.mediaMetadata.extras
                ?.getString(com.unshoo.pixelmusic.utils.MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH).isNullOrBlank()
        ) return null // Check the mapped file before returning a cached stream.
        val resolved = getCachedResolvedUri(uri) ?: return null
        val builder = mediaItem.buildUpon().setUri(resolved)
        if (resolved.scheme == "content") return null // Verify document access on the I/O path.
        if (resolved.scheme == "file") {
            return builder.setMimeType(null).setCustomCacheKey(null).build()
        }
        val cleanId = mediaItem.mediaId.removePrefix("youtube_").removePrefix("monochrome_")
        val containerMime = LosslessStreamResolver.resultFor(cleanId)?.containerMimeType
            ?: com.unshoo.pixelmusic.data.addons.AddonStreamRegistry.metaFor(resolved.toString())
                ?.let { com.unshoo.pixelmusic.data.addons.AddonStreamRegistry.containerMimeOf(it.transport) }
        if (containerMime != null) {
            builder.setMimeType(containerMime)
        }
        return builder.build()
    }

    /**
     * Pre-resolve a media item on a background dispatcher BEFORE prepare/play.
     * This keeps ExoPlayer's load thread free of network work and eliminates miniplayer freezes
     * when users tap song cards.
     */
    suspend fun preResolveForPlayback(mediaItem: MediaItem, isNextInQueue: Boolean = false): MediaItem {
        val uri = mediaItem.localConfiguration?.uri ?: return mediaItem
        val scheme = uri.scheme
        val uriString = uri.toString()
        val originalUriString = mediaItem.mediaMetadata.extras?.getString("com.unshoo.pixelmusic.external.CONTENT_URI") ?: uriString
        // A queued item that was resolved to a stream before the song finished downloading.
        if (scheme == "http" || scheme == "https") {
            youtubeIdOf(mediaItem)?.let { id ->
                withContext(Dispatchers.IO) { resolveLocalDiskFile("youtube://$id") }?.let { diskUri ->
                    StreamChoice.forget(id)
                    return mediaItem.buildUpon().setUri(diskUri).setMimeType(null).build()
                }
            }
        }
        if (scheme == "http" || scheme == "https") {
            val ytId = youtubeIdOf(mediaItem)
            val needsUpgrade = isNextInQueue && ytId != null &&
                com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.shouldUpgradeCachedStream(
                    context = context,
                    videoId = ytId,
                    cachedUrl = uriString,
                    isNextInQueue = true,
                )
            if (isResolvedUriFresh(originalUriString, uri) && !needsUpgrade) {
                return mediaItem
            }
            // If the URL has expired or is nearing expiration (<15m left), re-resolve from source URI.
            // BUGFIX: nothing sets the CONTENT_URI extra for streamed items, so originalUriString was
            // the stale https URL itself and the item was returned unchanged (expired googlevideo /
            // saavn links handed straight to ExoPlayer -> 403 -> error-recovery loop). Fall back to
            // the item's YouTube id, the same way ensureFreshStreamForResume() does.
            val sourceUri = Uri.parse(originalUriString).takeIf { it.scheme in REMOTE_MEDIA_SCHEMES }
                ?: ytId?.let { Uri.parse("youtube://$it") }
            if (sourceUri != null) {
                invalidateResolvedUri(uriString)
                return resolveMediaItem(
                    mediaItem.buildUpon()
                        .setUri(sourceUri)
                        .setCustomCacheKey(null)
                        .setMimeType(null)
                        .build(),
                    isNextInQueue = isNextInQueue,
                )
            }
            return mediaItem
        }
        if (scheme !in REMOTE_MEDIA_SCHEMES) {
            return mediaItem
        }
        return resolveMediaItem(mediaItem, isNextInQueue = isNextInQueue)
    }

    /** YouTube video id for [item], or null for local / non-YouTube sources. */
    internal fun youtubeIdOf(item: MediaItem): String? {
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        if (uri.startsWith("youtube://")) return uri.removePrefix("youtube://").takeIf { it.isNotBlank() }
        val extras = item.mediaMetadata.extras
        extras?.getString(com.unshoo.pixelmusic.utils.MediaItemBuilder.EXTERNAL_EXTRA_YOUTUBE_ID)
            ?.takeIf { it.isNotBlank() }?.let { return it }
        val originalUri = extras?.getString(com.unshoo.pixelmusic.utils.MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)
        if (originalUri?.startsWith("youtube://") == true) {
            return originalUri.substringAfter("youtube://").substringBefore('?').takeIf { it.isNotBlank() }
        }
        val id = item.mediaId.removePrefix("youtube_")
        return id.takeIf { YOUTUBE_ID_REGEX.matches(it) && !it.all(Char::isDigit) }
    }

    /** Retries the same queue item from YouTube after its mapped file fails to open or decode. */
    fun youtubeFallbackForLocalError(item: MediaItem): MediaItem? {
        val uri = item.localConfiguration?.uri ?: return null
        val youtubeId = youtubeIdOf(item) ?: return null
        val source = "youtube://$youtubeId"
        val usedLocalAudio = uri.scheme == "file" || uri.scheme == "content" ||
            (uri.scheme == "youtube" && localFilePathCache[source] != null)
        if (!usedLocalAudio || failedLocalYoutubeSources.get(source) == true) return null
        failedLocalYoutubeSources.put(source, true)
        invalidateResolvedUri(source)
        Timber.tag("DualPlayerEngine").w("Retrying %s via YouTube after local playback failed", youtubeId)
        return item.buildUpon().setUri(Uri.parse(source)).setMimeType(null).setCustomCacheKey(null).build()
    }

    /**
     * Called when the effective lossless switch changes (master toggle or enabled-addon set).
     * Every cached decision was made under the previous policy, so purge them and point queued
     * items that were already pre-resolved back at their youtube:// source; they re-resolve under
     * the new policy when they play. When lossless is switched OFF while a lossless stream is
     * playing, hand the current track over to YouTube in place so the toggle takes effect now.
     */
    private fun onLosslessPolicyChanged(enabled: Boolean) {
        Timber.tag("DualPlayerEngine").i("Lossless policy changed -> enabled=%b; purging stream decisions", enabled)
        com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.invalidateAllStreamCaches()
        LosslessStreamResolver.clearAll()
        resolvedUriCache.evictAll()
        activeResolutions.values.forEach { it.cancel() }
        activeResolutions.clear()
        cancelPreCacheJobs()
        if (!::playerA.isInitialized || playerA.mediaItemCount == 0) {
            activePlaybackResolvedUris.clear()
            return
        }
        if (!transitionRunning) cancelNext()

        val currentIndex = playerA.currentMediaItemIndex
        val currentItem = playerA.currentMediaItem
        val currentUri = currentItem?.localConfiguration?.uri?.toString()
        activePlaybackResolvedUris.keys.retainAll { key ->
            currentItem != null && (key == currentUri || key == "youtube://${youtubeIdOf(currentItem)}")
        }

        fun resetIfPreResolved(item: MediaItem): MediaItem? {
            val uri = item.localConfiguration?.uri ?: return null
            val isPreResolved = uri.scheme == "http" || uri.scheme == "https" ||
                uri.scheme == "data" || uri.scheme == "apple-pending" ||
                LosslessSchemeRoutingDataSource.isCustomLosslessScheme(uri)
            if (!isPreResolved) return null
            val id = youtubeIdOf(item) ?: return null
            return item.buildUpon()
                .setUri(Uri.parse("youtube://$id"))
                .setCustomCacheKey(null)
                .setMimeType(null)
                .setDrmConfiguration(null)
                .build()
        }

        val count = playerA.mediaItemCount
        fun resetRange(from: Int, to: Int) {
            if (from >= to) return
            var changed = false
            val items = (from until to).map { idx ->
                val original = playerA.getMediaItemAt(idx)
                resetIfPreResolved(original)?.also { changed = true } ?: original
            }
            if (changed) playerA.replaceMediaItems(from, to, items)
        }
        if (currentIndex != C.INDEX_UNSET) {
            resetRange(currentIndex + 1, count)
            resetRange(0, currentIndex)
        }

        // The current song is intentionally left untouched: swapping its source mid-song means a
        // gap plus a re-buffer. The new policy applies seamlessly from the next track (whose queue
        // entry was reset above and whose crossfade standby was cancelled).
    }

    suspend fun preResolveForPlayback(mediaItems: List<MediaItem>, startIndex: Int): List<MediaItem> {
        if (mediaItems.isEmpty()) return mediaItems
        val safeStart = startIndex.coerceIn(0, mediaItems.lastIndex)
        val result = mediaItems.toMutableList()
        // CRITICAL PATH ONLY: resolve the tapped track so play can start immediately.
        // Next/prev are warmed in the background AFTER we return â€” waiting on them
        // was adding multi-second delay on low connectivity before audio started.
        result[safeStart] = preResolveForPlayback(result[safeStart])

        val warm = buildList {
            if (safeStart + 1 <= result.lastIndex) add(safeStart + 1)
            if (safeStart - 1 >= 0) add(safeStart - 1)
        }
        for (idx in warm) {
            val isImmediateNext = (idx == safeStart + 1)
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val resolved = preResolveForPlayback(result[idx], isNextInQueue = isImmediateNext)
                    // Cache only — do not mutate the already-returned list.
                    resolved.localConfiguration?.uri?.toString()?.let { u ->
                        if (!LosslessStreamResolver.isLosslessUri(u) && !u.contains("monochrome.st/track/")) {
                            val key = result[idx].mediaId.removePrefix("youtube_").takeIf { it.isNotBlank() }
                            if (u.startsWith("http")) preCacheFirstChunk(u, key)
                        }
                    }
                }
            }
        }
        return result
    }

    private val preCacheHttpFactory by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            )
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
    }

    private val preCacheDataSourceFactory by lazy {
        val upstream = DefaultDataSource.Factory(context, preCacheHttpFactory)
        CacheDataSource.Factory()
            .setCache(exoCache.cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private val activePreCacheJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun cancelPreCacheJobs() {
        activePreCacheJobs.values.forEach { it.cancel() }
        activePreCacheJobs.clear()
    }

    /**
     * Pre-cache the first chunk of a stream into SimpleCache for instant playback start.
     * Ported from SpatialFlow AudioPlaybackService.preCacheFirstChunk.
     */
    fun preCacheFirstChunk(streamUrl: String, cacheKey: String? = null) {
        if (!streamUrl.startsWith("http")) return
        // Lossless streams must never be chunk-cached; they must stream as one continuous file.
        if (LosslessStreamResolver.isLosslessUri(streamUrl) ||
            streamUrl.contains("monochrome.st/track/") ||
            streamUrl.endsWith(".flac", ignoreCase = true) ||
            LosslessSchemeRoutingDataSource.isCustomLosslessScheme(streamUrl)
        ) {
            return
        }
        val chunkSize = FIRST_CHUNK_PRECACHE_BYTES
        // Must match the key the player reads with (format-aware); otherwise the warmed bytes are
        // never found, or a different format's bytes are found under the bare video id.
        val formatKey = cacheKey?.let { streamCacheKey(it.substringBefore('#'), streamUrl) }
        if (formatKey != null && runCatching { exoCache.cache.isCached(formatKey, 0, chunkSize) }.getOrDefault(false)) return
        val jobKey = formatKey ?: streamUrl
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                currentCoroutineContext().ensureActive()
                val dataSource = preCacheDataSourceFactory.createDataSource()
                val dataSpecBuilder = DataSpec.Builder()
                    .setUri(Uri.parse(streamUrl))
                    .setPosition(0)
                    .setLength(chunkSize)
                if (formatKey != null) {
                    dataSpecBuilder.setKey(formatKey)
                }
                val profile = StreamClientUtils.resolveRequestProfile(streamUrl)
                val headers = buildMap {
                    put("User-Agent", profile.userAgent)
                    profile.origin?.let { put("Origin", it) }
                    profile.referer?.let { put("Referer", it) }
                }
                if (headers.isNotEmpty()) {
                    dataSpecBuilder.setHttpRequestHeaders(headers)
                }
                val dataSpec = dataSpecBuilder.build()
                currentCoroutineContext().ensureActive()
                androidx.media3.datasource.cache.CacheWriter(
                    dataSource,
                    dataSpec,
                    /* temporaryBuffer= */ null,
                    /* progressListener= */ null
                ).cache()
                Timber.tag("DualPlayerEngine").d(
                    "Pre-cached first %dKB of stream for key=%s",
                    chunkSize / 1024,
                    formatKey
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.tag("DualPlayerEngine").d("preCacheFirstChunk skipped: %s", e.message)
            }
        }
        if (activePreCacheJobs.putIfAbsent(jobKey, job) != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { activePreCacheJobs.remove(jobKey, job) }
        job.start()
    }


    fun registerLocalPath(youtubeUri: String, filePath: String) {
        if (filePath.isNotBlank()) {
            localFilePathCache.put(youtubeUri, filePath)
        }
    }

    fun resolveLocalDiskFile(uriString: String): Uri? {
        if (failedLocalYoutubeSources.get(uriString) == true) return null
        // 1. Check in-memory local file cache with disk validation
        localFilePathCache[uriString]?.let { path ->
            val readable = com.unshoo.pixelmusic.utils.OfflineAudioResolver.readableAudioPath(context, path)
            if (readable != null) {
                return if (readable.startsWith("content://")) Uri.parse(readable) else Uri.fromFile(File(readable))
            }
        }

        // 2. Check YouTube downloads folder directly on disk
        if (uriString.startsWith("youtube://")) {
            val videoId = uriString.removePrefix("youtube://")
            // Downloads index: app folder AND the user's custom (SAF) download folder.
            com.unshoo.pixelmusic.utils.OfflineAudioResolver.getDownloadedAudioUri(context, videoId)?.let { indexed ->
                com.unshoo.pixelmusic.utils.OfflineAudioResolver.readableAudioPath(context, indexed.toString())?.let { path ->
                    localFilePathCache.put(uriString, path)
                    return indexed
                }
            }
            if (videoId.isNotBlank()) {
                val candidateDirs = listOf(
                    PixelMusicHelper.getDownloadDirectory(context, Constants.Downloads.AUDIO_FILES_FOLDER),
                    File(context.filesDir, "audio_files")
                )
                val extensions = listOf(".flac", ".webm", ".m4a", ".opus", ".mp3", ".ogg", ".aac")
                for (audioDir in candidateDirs) {
                    for (ext in extensions) {
                        val diskFile = java.io.File(audioDir, "$videoId$ext")
                        if (diskFile.isFile && diskFile.length() > 0L) {
                            val path = diskFile.absolutePath
                            localFilePathCache.put(uriString, path)
                            return Uri.fromFile(diskFile)
                        }
                    }
                }
            }
        }

        // 3. Check cloud downloads directory by SHA-256 ID
        if (uriString.startsWith("youtube://")) {
            val cloudDir = java.io.File(context.filesDir, "cloud_downloads")
            if (cloudDir.isDirectory) {
                try {
                    val downloadId = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(uriString.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    val match = cloudDir.listFiles()?.firstOrNull { file ->
                        file.isFile && file.length() > 0L && file.name.startsWith(downloadId) &&
                            !file.name.endsWith(".part") && !file.name.endsWith(".jpg") && !file.name.endsWith(".lrc")
                    }
                    if (match != null) {
                        localFilePathCache.put(uriString, match.absolutePath)
                        return Uri.fromFile(match)
                    }
                } catch (_: Exception) {}
            }
        }

        return null
    }

    /**
     * Offloaded audio is decoded/mixed on the DSP and on most devices bypasses the session's
     * effect chain, so the EQ (and the rest of the effects) would go silent. While any effect is
     * enabled, both decks are kept off offload so the shared session's effects process them.
     */
    private var audioEffectsActive = false

    fun setAudioEffectsActive(active: Boolean) {
        if (audioEffectsActive == active) return
        audioEffectsActive = active
        if (isReleased || !::playerA.isInitialized) return
        if (hiFiModeEnabled && HiFiCapabilityChecker.isSupported()) {
            // Float output bypasses the in-app EQ processor; rebuild the decks with/without it.
            rebuildPlayersPreservingMasterState("Audio effects active=$active (Hi-Fi float output toggled)")
            return
        }
        applyAudioOffload(playerA)
        playerB?.let { applyAudioOffload(it) }
    }

    private fun applyAudioOffload(player: ExoPlayer) {
        val enabled = audioOffloadEnabled && !hiFiModeEnabled && !audioEffectsActive
        val mode = if (enabled) {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        }
        val offloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(mode)
            .build()
        if (player.trackSelectionParameters.audioOffloadPreferences != offloadPreferences) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setAudioOffloadPreferences(offloadPreferences)
                .build()
        }
        val schedulingEnabled = enabled && !transitionRunning
        listOf("experimentalSetOffloadSchedulingEnabled", "setOffloadSchedulingEnabled", "setOffloadEnabled")
            .firstNotNullOfOrNull { name ->
                runCatching { player.javaClass.getMethod(name, Boolean::class.javaPrimitiveType) }.getOrNull()
            }
            ?.let { method -> runCatching { method.invoke(player, schedulingEnabled) } }
    }

    private fun startPreferenceCollectors() {
        scope.launch {
            userPreferencesRepository.audioOffloadEnabledFlow.collect { enabled ->
                if (audioOffloadEnabled != enabled) {
                    audioOffloadEnabled = enabled
                    cancelAudioOffloadFallback()
                    if (!isReleased && ::playerA.isInitialized) {
                        applyAudioOffload(playerA)
                    }
                    playerB?.let { if (!isReleased) applyAudioOffload(it) }
                }
            }
        }
        scope.launch {
            var previous: Boolean? = null
            LosslessStreamResolver.enabledFlow(context).collect { enabled ->
                val before = previous
                previous = enabled
                if (before != null && before != enabled) {
                    runCatching { onLosslessPolicyChanged(enabled) }
                        .onFailure { Timber.tag("DualPlayerEngine").w(it, "Lossless policy refresh failed") }
                }
            }
        }
    }

    init {
        // BUGFIX (was: initialize() called eagerly in init on the calling
        // thread, which is the main thread for the service path):
        // DualPlayerEngine is a Hilt @Singleton constructed by Hilt the
        // first time anything injects it. For MusicService that's the
        // main thread of `onCreate`, and `initialize()` builds two
        // ExoPlayer instances â€” each of which loads extractors,
        // decoders, audio sinks, and the LRU cache. Combined with the
        // rest of MusicService.onCreate, this pushed the foreground
        // service over its 5s start-up budget on slower devices.
        // We now defer the actual ExoPlayer build to the first
        // `initialize()` call from MusicService.onCreate, which itself
        // has been moved to run as the last step. The audioOffload
        // preferences collector stays since it doesn't allocate.
        startPreferenceCollectors()
        // Purge any stale monochrome chunk-cache entries from prior sessions so playback is clean
        scope.launch(Dispatchers.IO) {
            runCatching {
                val monoKeys = exoCache.cache.keys.filter { it.startsWith("monochrome_") }
                for (k in monoKeys) {
                    exoCache.cache.removeResource(k)
                }
            }
        }
    }

    fun initialize() {
        if (!isReleased && ::playerA.isInitialized && playerA.applicationLooper.thread.isAlive) return

        // Recreate scope if it was cancelled by a previous release() call.
        // DualPlayerEngine is @Singleton, so the scope must survive across
        // MusicService destroy/create cycles.
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            startPreferenceCollectors()
        }

        if (::playerA.isInitialized) {
            onPlayerAboutToBeReleasedListener?.invoke(playerA)
            try { playerA.release() } catch (e: Exception) { /* Ignore */ }
        }
        playerB?.let {
            try { it.release() } catch (e: Exception) { /* Ignore */ }
        }
        playerB = null

        playerA = buildPlayer()

        _activeAudioSessionId.value = currentAudioSessionId()

        playerA.addListener(masterPlayerListener)
        playerA.addAnalyticsListener(masterPlayerListener)

        isReleased = false
        queueSnapshot = emptyList()
        activeWindowStartIndex = 0
        activePlayerUsesWindowedQueue = false
        resetPreparedWindowState()
    }

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return

        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusChangeListener)
            // Let the system queue our request behind a transient holder instead of failing.
            // Pairs with the AUDIOFOCUS_GAIN handler below: on DELAYED we pause and mark the
            // pause as focus-driven so the eventual GAIN callback resumes playback.
            .setAcceptsDelayedFocusGain(true)
            .build()

        val result = audioManager.requestAudioFocus(request)
        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                audioFocusRequest = request
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                audioFocusRequest = request
                isFocusLossPause = true
                if (::playerA.isInitialized) playerA.playWhenReady = false
                if (transitionRunning) playerB?.playWhenReady = false
            }
            else -> {
                Timber.tag("TransitionDebug").w("AudioFocus Request Failed: $result")
                if (::playerA.isInitialized) playerA.playWhenReady = false
            }
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    private fun scheduleAudioOffloadFallbackIfNeeded(player: ExoPlayer) {
        cancelAudioOffloadFallback()
        if (!audioOffloadEnabled || transitionRunning || !player.playWhenReady) return
        if (!isLikelyLocalMedia(player.currentMediaItem)) return

        val watchedMediaId = player.currentMediaItem?.mediaId ?: return
        bufferingFallbackJob = scope.launch {
            delay(AUDIO_OFFLOAD_BUFFERING_FALLBACK_MS)

            val currentMediaId = player.currentMediaItem?.mediaId
            if (!audioOffloadEnabled || transitionRunning || player !== playerA) return@launch
            if (currentMediaId != watchedMediaId) return@launch
            if (player.playbackState != Player.STATE_BUFFERING || player.isPlaying || !player.playWhenReady) return@launch
            if (player.currentPosition > 1_000L) return@launch

            disableAudioOffloadForSession(
                reason = "Local media stayed buffering for ${AUDIO_OFFLOAD_BUFFERING_FALLBACK_MS}ms"
            )
        }
    }

    private fun cancelAudioOffloadFallback() {
        bufferingFallbackJob?.cancel()
        bufferingFallbackJob = null
    }

    @Volatile private var lastAudioInputSampleRate: Int = -1

    private var losslessStallFallbackJob: Job? = null

    private fun scheduleLosslessStallFallbackIfNeeded(player: ExoPlayer) {
        cancelLosslessStallFallback()
        if (transitionRunning || !player.playWhenReady) return
        // A seek into an unbuffered region of a lossless file is a normal re-buffer, not a dead
        // source. Swapping to YouTube there only adds a resolve + a second buffer-up (slower,
        // not faster), so seeks never trigger the source swap. Real failures still surface
        // through onPlayerError, which steps down the source chain.
        if (lastSeekAtMs > 0L && SystemClock.elapsedRealtime() - lastSeekAtMs < 2_000L) return
        val currentItem = player.currentMediaItem ?: return
        val currentUri = currentItem.localConfiguration?.uri?.toString() ?: return
        val playingUri = activePlaybackResolvedUris[currentUri]?.toString()
            ?: resolvedUriCache.get(currentUri)?.toString()
            ?: currentUri
        if (!LosslessStreamResolver.isLosslessUri(playingUri)) return

        val watchedMediaId = currentItem.mediaId
        val timeoutMs = LOSSLESS_STALL_FALLBACK_TIMEOUT_MS
        losslessStallFallbackJob = scope.launch {
            delay(timeoutMs)
            if (transitionRunning || player !== playerA) return@launch
            if (player.currentMediaItem?.mediaId != watchedMediaId) return@launch
            if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady || player.isPlaying) return@launch

            Timber.tag("DualPlayerEngine").w(
                "Lossless stream stalled for >%ds on %s; triggering source failover",
                timeoutMs / 1000,
                watchedMediaId
            )
            failoverLosslessToYoutube(currentItem, player, "Playback stall >${timeoutMs / 1000}s")
        }
    }

    private fun cancelLosslessStallFallback() {
        losslessStallFallbackJob?.cancel()
        losslessStallFallbackJob = null
    }

    fun failoverLosslessToYoutube(
        mediaItem: MediaItem,
        targetPlayer: ExoPlayer? = null,
        reason: String = "Lossless stream failed or stalled",
        failingUri: String? = null,
        stepDownOverride: Boolean? = null,
    ) {
        val player = targetPlayer ?: if (::playerA.isInitialized) playerA else return
        val rawUri = mediaItem.localConfiguration?.uri?.toString().orEmpty()
        // shortcut: only tracks with 11-char YouTube IDs can failover to YouTube; pure custom streams are unhandled, upgrade if custom streaming services get alternate fallback mirrors
        val videoId = youtubeIdOf(mediaItem)
            ?: queueSnapshot.firstOrNull { it.mediaId == mediaItem.mediaId }?.let(::youtubeIdOf)

        if (videoId.isNullOrBlank()) {
            Timber.tag("DualPlayerEngine").w("failoverLosslessToYoutube: could not determine YouTube videoId for %s", mediaItem.mediaId)
            return
        }

        val resolvedFailingUri = failingUri ?: peekResolvedPlaybackUri(rawUri) ?: rawUri.takeIf { it.isNotBlank() }
        val shouldStepDown = stepDownOverride ?: LosslessStreamResolver.markCurrentSourceFailedForTrack(
            mediaId = videoId,
            reason = reason,
            failingUri = resolvedFailingUri,
        )
        Timber.tag("DualPlayerEngine").w(
            "Source Failover: %s (%s) -> stepDown=%s",
            videoId,
            reason,
            shouldStepDown,
        )
        invalidateResolvedUri("youtube://$videoId")
        invalidateResolvedUri(rawUri)
        com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.invalidateStreamCache(videoId)

        scope.launch(Dispatchers.IO) {
            try {
                val durMs = mediaItem.mediaMetadata.extras
                    ?.getLong(com.unshoo.pixelmusic.utils.MediaItemBuilder.EXTERNAL_EXTRA_DURATION, 0L) ?: 0L
                val song = com.unshoo.pixelmusic.data.model.youtube.Song(
                    youtubeId = videoId,
                    title = mediaItem.mediaMetadata.title?.toString().orEmpty(),
                    artist = mediaItem.mediaMetadata.artist?.toString().orEmpty(),
                    album = mediaItem.mediaMetadata.albumTitle?.toString().orEmpty(),
                    duration = if (durMs > 0L) (durMs / 1_000L).toString() else "",
                )
                val isAuxiliaryTarget = withContext(Dispatchers.Main.immediate) {
                    player === playerB
                }
                val ytUrl = com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.getSongPlayerUrl(
                    context = context,
                    song = song,
                    allowLocal = true,
                    allowLossless = shouldStepDown,
                    isNextInQueue = isAuxiliaryTarget,
                )
                if (ytUrl.isBlank()) {
                    Timber.tag("DualPlayerEngine").e("failoverLosslessToYoutube: fallback URL resolution returned blank for %s", videoId)
                    return@launch
                }

                val fallbackUri = if (ytUrl.startsWith("http") || ytUrl.contains("://")) {
                    Uri.parse(ytUrl)
                } else {
                    Uri.fromFile(java.io.File(ytUrl))
                }
                val isStillLossless = LosslessStreamResolver.isLosslessUri(ytUrl)
                if (fallbackUri.scheme != "file" && fallbackUri.scheme != "content") {
                    resolvedUriCache.put("youtube://$videoId", fallbackUri)
                }
                val containerMime = LosslessStreamResolver.resultFor(videoId)?.containerMimeType
                    ?: com.unshoo.pixelmusic.data.addons.AddonStreamRegistry.metaFor(ytUrl)
                        ?.let { com.unshoo.pixelmusic.data.addons.AddonStreamRegistry.containerMimeOf(it.transport) }
                val replacement = mediaItem.buildUpon()
                    .setUri(fallbackUri)
                    .setMimeType(containerMime)
                    .setCustomCacheKey(if (!isStillLossless) streamCacheKey(videoId, ytUrl) else null)
                    .apply {
                        if (AppleMusicDrm.infoFor(videoId) == null && AppleMusicDrm.infoFor(mediaItem.mediaId) == null) {
                            setDrmConfiguration(null)
                        }
                    }
                    .build()

                withContext(Dispatchers.Main.immediate) {
                    val currentIdx = player.currentMediaItemIndex
                    val currentItem = if (currentIdx != C.INDEX_UNSET && currentIdx < player.mediaItemCount) {
                        player.getMediaItemAt(currentIdx)
                    } else null

                    // Verify if this track is the active playing item, or elsewhere in the timeline
                    val targetIdx = when {
                        currentItem?.mediaId == mediaItem.mediaId -> currentIdx
                        else -> (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaItem.mediaId }
                    }

                    if (targetIdx == null) {
                        // User skipped away or the track was removed during async resolution; do not touch active track
                        Timber.tag("DualPlayerEngine").w("failoverLosslessToYoutube: %s is no longer in player; aborting replacement", mediaItem.mediaId)
                        return@withContext
                    }

                    if (targetIdx == currentIdx) {
                        val wasPlaying = player.playWhenReady
                        val currentPos = player.currentPosition.coerceAtLeast(0L)
                        player.replaceMediaItem(targetIdx, replacement)
                        player.seekTo(targetIdx, currentPos)
                        player.prepare()
                        player.playWhenReady = wasPlaying
                        if (wasPlaying) player.play()
                        Timber.tag("DualPlayerEngine").i("Source Failover COMPLETE: %s transitioned to YouTube at active index %d (%dms)", videoId, targetIdx, currentPos)
                    } else {
                        // Background / queued track replacement: update timeline entry without interrupting current playback
                        player.replaceMediaItem(targetIdx, replacement)
                        Timber.tag("DualPlayerEngine").i("Source Failover COMPLETE: queued item %s replaced with YouTube at index %d", videoId, targetIdx)
                    }
                }
            } catch (e: Exception) {
                Timber.tag("DualPlayerEngine").e(e, "failoverLosslessToYoutube failed for %s", videoId)
            }
        }
    }

    private fun isLikelyLocalMedia(mediaItem: MediaItem?): Boolean {
        val scheme = mediaItem?.localConfiguration?.uri?.scheme?.lowercase()
        return scheme == null || scheme in LOCAL_MEDIA_SCHEMES
    }

    private fun disableAudioOffloadForSession(reason: String) {
        if (!audioOffloadEnabled) return
        if (transitionRunning) {
            Timber.tag("DualPlayerEngine").w("Skipping offload fallback during active transition. %s", reason)
            return
        }

        audioOffloadEnabled = false
        rebuildPlayersPreservingMasterState(
            logMessage = "Audio offload disabled for current session. $reason"
        )
    }

    private fun rebuildPlayersPreservingMasterState(logMessage: String) {
        cancelAudioOffloadFallback()

        cancelNext()
        val desiredPlayWhenReady = playerA.playWhenReady
        val positionMs = playerA.currentPosition
        val currentIndex = playerA.currentMediaItemIndex.coerceAtLeast(0)
        val mediaItems = (0 until playerA.mediaItemCount).map { playerA.getMediaItemAt(it) }
        val repeatMode = playerA.repeatMode
        val shuffleMode = playerA.shuffleModeEnabled
        val volume = if (skipFadeActive) {
            if (isDucked) preDuckVolumeA = skipFadeRestVolume
            skipFadeTargetVolume()
        } else {
            playerA.volume
        }
        val pauseAtEnd = playerA.pauseAtEndOfMediaItems

        cancelSkipFade()
        playerA.removeListener(masterPlayerListener)
        playerA.removeAnalyticsListener(masterPlayerListener)
        onPlayerAboutToBeReleasedListener?.invoke(playerA)
        playerA.release()
        playerB?.let {
            try { it.release() } catch (e: Exception) { /* Ignore */ }
        }
        playerB = null

        playerA = buildPlayer()

        playerA.addListener(masterPlayerListener)
        playerA.addAnalyticsListener(masterPlayerListener)
        playerA.volume = volume
        playerA.pauseAtEndOfMediaItems = pauseAtEnd
        playerA.playbackParameters = PlaybackParameters.DEFAULT

        if (mediaItems.isNotEmpty()) {
            playerA.setMediaItems(mediaItems, currentIndex, positionMs)
            playerA.repeatMode = repeatMode
            playerA.shuffleModeEnabled = shuffleMode
            playerA.prepare()
            playerA.playWhenReady = desiredPlayWhenReady
        }

        _activeAudioSessionId.value = currentAudioSessionId()
        onPlayerSwappedListeners.forEach { it(playerA) }

        Timber.tag("DualPlayerEngine").d(logMessage)
    }

    private fun buildPlayer(): ExoPlayer {
        // DefaultAudioSink skips custom audio processors (our EQ) on the float-output path, so
        // Hi-Fi float output is only used while no effect is enabled.
        val floatEnabled = hiFiModeEnabled && HiFiCapabilityChecker.isSupported() && !audioEffectsActive
        val mediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoderInfos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )

            AudioDecoderPolicy.selectPlatformDecoders(mimeType, decoderInfos, floatEnabled)
        }
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(floatEnabled)
                    // DefaultAudioSink bypasses SonicAudioProcessor whenever outputting float PCM
                    // (Hi-Fi / 24-bit+ lossless), so enable AudioTrack playback parameters there to
                    // prevent setPlaybackSpeed from resetting to 1.0x.
                    .setEnableAudioOutputPlaybackParameters(floatEnabled || enableAudioOutputPlaybackParams)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            HiResSampleRateCapAudioProcessor(),
                            SurroundDownmixProcessor(),
                            // In-app EQ: each deck processes its own audio, so the EQ covers
                            // both during a crossfade without touching session volume.
                            com.unshoo.pixelmusic.data.equalizer.EqualizerAudioProcessor()
                        )
                    )
                    .build()
            }

            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                // Audio-only player: skip video renderers to save memory and "renderers" count.
            }

            override fun buildTextRenderers(
                context: Context,
                eventListener: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
                // Audio-only player: skip text renderers.
            }

            override fun buildCameraMotionRenderers(
                context: Context,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
                // Audio-only player: skip camera motion renderers.
            }
        }.setEnableAudioFloatOutput(floatEnabled)
         .setMediaCodecSelector(mediaCodecSelector)
         .setEnableDecoderFallback(true)
         .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val audioAttributes = Media3AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
            
        val resolver = object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                val uri = dataSpec.uri
                val scheme = uri.scheme
                val uriString = uri.toString()

                // dataSpec.key is the format-aware cache key ("<videoId>#<itag|saavn>", see
                // streamCacheKey); everything else here works on the bare video id.
                val rawKey = dataSpec.key
                val cleanId = rawKey?.substringBefore('#') ?: when {
                    scheme == "youtube" -> uri.host?.takeIf { it.isNotBlank() } ?: uriString.removePrefix("youtube://")
                    uriString.startsWith("youtube://") -> uriString.removePrefix("youtube://")
                    else -> null
                }

                // 0. Downloaded songs never touch the network: a finished download (app folder
                // or custom download folder) wins over a pinned/cached stream and over lossless.
                if (scheme == "youtube" || uriString.startsWith("youtube://")) {
                    resolveLocalDiskFile(uriString)?.let { diskUri ->
                        if (cleanId != null) StreamChoice.forget(cleanId)
                        return dataSpec.buildUpon().setUri(diskUri).setKey(null).build()
                    }
                }

                // 1. Session Stream Pinning: Check StreamChoice first (BitChord parity)
                // Ensures continuation reads, seeking, and cache fill never switch to a different format/codec mid-song.
                if (cleanId != null) {
                    StreamChoice.of(cleanId)?.let { pinned ->
                        val pinnedUri = Uri.parse(pinned.url)
                        if (isResolvedUriFresh(uriString, pinnedUri) && !isDeadStreamUrl(pinned.url)) {
                            return dataSpec.buildUpon()
                                .setUri(pinnedUri)
                                .setKey(streamCacheKey(cleanId, pinned.url))
                                .apply {
                                    if (pinned.headers.isNotEmpty()) {
                                        setHttpRequestHeaders(pinned.headers)
                                    } else if (pinned.url.contains("googlevideo.com") || pinned.url.contains("youtube.com")) {
                                        val profile = StreamClientUtils.resolveRequestProfile(pinned.url)
                                        setHttpRequestHeaders(
                                            buildMap {
                                                put("User-Agent", profile.userAgent)
                                                profile.origin?.let { put("Origin", it) }
                                                profile.referer?.let { put("Referer", it) }
                                            }
                                        )
                                    } else if (pinned.url.startsWith("http")) {
                                        setHttpRequestHeaders(
                                            mapOf(
                                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                                            )
                                        )
                                    }
                                }
                                .build()
                        } else {
                            StreamChoice.forget(cleanId)
                        }
                    }
                }

                // 2. Instant Start: Only return early without resolving URI/headers if the ENTIRE remaining stream is cached in ExoCache.
                // If only the pre-cached header chunk is cached, CacheDataSource still needs the resolved HTTP upstream URI to continue smoothly past it.
                if (cleanId != null) {
                    val isFullyCached = runCatching {
                        val totalLen = androidx.media3.datasource.cache.ContentMetadata.getContentLength(
                            exoCache.cache.getContentMetadata(rawKey ?: cleanId)
                        )
                        val remainingLen = when {
                            dataSpec.length > 0 -> dataSpec.length
                            totalLen > dataSpec.position -> totalLen - dataSpec.position
                            else -> -1L
                        }
                        remainingLen > 0L && exoCache.cache.isCached(rawKey ?: cleanId, dataSpec.position, remainingLen)
                    }.getOrDefault(false)

                    val isCloudScheme = scheme == "youtube" || uriString.startsWith("youtube://") ||
                        scheme == "telegram" || uriString.startsWith("telegram:") ||
                        scheme == "gdrive" || uriString.startsWith("gdrive:")

                    if (isFullyCached && !isCloudScheme) {
                        return dataSpec.buildUpon().setKey(rawKey ?: cleanId).build()
                    }
                }

                // 3. Custom cloud schemes
                if (scheme == "monochrome") {
                    val trackId = uri.host?.takeIf { it.isNotBlank() } ?: uriString.removePrefix("monochrome://")
                    val directUri = Uri.parse("https://tracks.monochrome.st/track/$trackId")
                    LosslessStreamResolver.markUriFresh(directUri.toString())
                    activePlaybackResolvedUris[uriString] = directUri
                    resolvedUriCache.put(uriString, directUri)
                    return dataSpec.buildUpon()
                        .setUri(directUri)
                        .setHttpRequestHeaders(
                            mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                            )
                        )
                        .build()
                }

                if (scheme == "telegram" || scheme == "gdrive" || scheme == "youtube") {
                    val originalUri = uriString
                    val diskUri = if (scheme != "youtube") resolveLocalDiskFile(originalUri) else null
                    if (diskUri != null) {
                        return dataSpec.buildUpon()
                            .setUri(diskUri)
                            .build()
                    }

                    fun DataSpec.Builder.applyStreamHeaders(resolvedUri: Uri): DataSpec.Builder {
                        val urlStr = resolvedUri.toString()
                        val addonHeaders = com.unshoo.pixelmusic.data.addons.AddonStreamRegistry.headersFor(urlStr)
                        val isYouTubeStream = urlStr.contains("googlevideo.com") || urlStr.contains("youtube.com")
                        if (addonHeaders.isNotEmpty()) {
                            val merged = buildMap {
                                put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                                putAll(addonHeaders)
                            }
                            this.setHttpRequestHeaders(merged)
                            if (cleanId != null) {
                                StreamChoice.remember(cleanId, urlStr, merged)
                            }
                        } else if (isYouTubeStream) {
                            val profile = StreamClientUtils.resolveRequestProfile(urlStr)
                            val headers = buildMap {
                                put("User-Agent", profile.userAgent)
                                profile.origin?.let { put("Origin", it) }
                                profile.referer?.let { put("Referer", it) }
                            }
                            this.setHttpRequestHeaders(headers)
                            if (cleanId != null) {
                                StreamChoice.remember(cleanId, urlStr, headers)
                            }
                        } else if (urlStr.startsWith("http")) {
                            this.setHttpRequestHeaders(
                                mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                                )
                            )
                        }
                        return this
                    }

                    activePlaybackResolvedUris[originalUri]?.let { locked ->
                        if (isResolvedUriFresh(originalUri, locked) && !isDeadStreamUrl(locked.toString())) {
                            return dataSpec.buildUpon()
                                .setUri(locked)
                                .apply { if (cleanId != null) setKey(streamCacheKey(cleanId, locked.toString())) }
                                .applyStreamHeaders(locked)
                                .build()
                        } else {
                            activePlaybackResolvedUris.remove(originalUri)
                            resolvedUriCache.remove(originalUri)
                            if (cleanId != null) StreamChoice.forget(cleanId)
                        }
                    }

                    val resolved = resolvedUriCache.get(originalUri)
                    if (resolved != null) {
                        if (isResolvedUriFresh(originalUri, resolved) && !isDeadStreamUrl(resolved.toString())) {
                            activePlaybackResolvedUris[originalUri] = resolved
                            return dataSpec.buildUpon()
                                .setUri(resolved)
                                .apply { if (cleanId != null) setKey(streamCacheKey(cleanId, resolved.toString())) }
                                .applyStreamHeaders(resolved)
                                .build()
                        } else {
                            resolvedUriCache.remove(originalUri)
                            activePlaybackResolvedUris.remove(originalUri)
                            if (cleanId != null) StreamChoice.forget(cleanId)
                        }
                    }

                    kickBackgroundResolve(uri)
                    try {
                        val awaited = kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.withTimeoutOrNull(LOAD_THREAD_RESOLVE_AWAIT_MS) {
                                resolveCloudUri(uri)
                            }
                        }
                        if (awaited?.scheme == "file" || awaited?.scheme == "content") {
                            return dataSpec.buildUpon().setUri(awaited).setKey(null).build()
                        }
                        val ready = resolvedUriCache.get(originalUri)
                            ?: activePlaybackResolvedUris[originalUri]
                        if (awaited != null && ready != null && ready != uri &&
                            isResolvedUriFresh(originalUri, ready)
                        ) {
                            activePlaybackResolvedUris[originalUri] = ready
                            return dataSpec.buildUpon()
                                .setUri(ready)
                                .apply { if (cleanId != null) setKey(streamCacheKey(cleanId, ready.toString())) }
                                .applyStreamHeaders(ready)
                                .build()
                        }
                    } catch (e: Exception) {
                        Timber.tag("DualPlayerEngine").w(
                            e, "resolveDataSpec: cooperative resolve failed for %s", originalUri
                        )
                    }
                    throw UnresolvableStreamException(
                        "Stream URL could not be resolved for $scheme://â€¦"
                    )
                }

                // BUGFIX (looped loading on expiry): queue items pre-resolved to an https URL were
                // handed to the network as-is forever. Once that link expired (or YouTube revoked it,
                // e.g. after an IP change) every ExoPlayer retry re-sent the same dead URL and the
                // player spun until error recovery eventually replaced the item. Re-resolve such
                // links right here, once, so the retry already uses a fresh URL.
                val isSaavnStream = uriString.contains("saavncdn.com") || uriString.contains("jiosaavn.com")
                val isGoogleVideoStream = uriString.contains("googlevideo.com") || uriString.contains("youtube.com")
                if ((isGoogleVideoStream || isSaavnStream) && (scheme == "https" || scheme == "http") &&
                    cleanId != null && YOUTUBE_ID_REGEX.matches(cleanId) &&
                    (isDeadStreamUrl(uriString) || !isResolvedUriFresh(uriString, uri))
                ) {
                    refreshStreamOnLoadThread(cleanId)?.let { fresh ->
                        return dataSpec.buildUpon()
                            .setUri(fresh)
                            .setKey(streamCacheKey(cleanId, fresh.toString()))
                            .setHttpRequestHeaders(headersForStream(fresh.toString()))
                            .build()
                    }
                }

                // Inject request headers (User-Agent, Origin, Referer) on pre-resolved HTTPS Google Video streams
                if (isGoogleVideoStream && (scheme == "https" || scheme == "http")) {
                    val profile = StreamClientUtils.resolveRequestProfile(uriString)
                    val headers = buildMap {
                        putAll(dataSpec.httpRequestHeaders)
                        put("User-Agent", profile.userAgent)
                        profile.origin?.let { put("Origin", it) }
                        profile.referer?.let { put("Referer", it) }
                    }
                    if (cleanId != null) {
                        StreamChoice.remember(cleanId, uriString, headers)
                    }
                    return dataSpec.buildUpon()
                        .apply { if (cleanId != null) setKey(streamCacheKey(cleanId, uriString)) }
                        .setHttpRequestHeaders(headers)
                        .build()
                }

                if (cleanId != null && dataSpec.key == null) {
                    return dataSpec.buildUpon().setKey(streamCacheKey(cleanId, uriString)).build()
                }

                return dataSpec
            }
        }

        // Explicit HTTP timeouts for low-connectivity resilience.
        // Perf: OkHttp with one connection pool shared by both players (and across rebuilds),
        // HTTP/2 multiplexing and TLS session reuse. YouTube range chunks and the several range
        // probes a FLAC seek makes now reuse a warm connection instead of paying a new
        // TCP+TLS handshake each time â€” the main cost of seeking in a lossless stream and of
        // mid-song chunk fetches.
        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(playbackHttpClient)
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            )

        val chunkedHttpDataSourceFactory = ChunkedDataSource.Factory(
            upstream = httpDataSourceFactory,
            chunkBytes = ChunkedDataSource.DEFAULT_MAX_RANGE_BYTES
        )

        val baseDataSourceFactory = DefaultDataSource.Factory(context, chunkedHttpDataSourceFactory)
        val directHttpDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        // Lossless sources (ArchiveTune port): route `deezer://` through the on-the-fly Blowfish
        // decryptor and `tidal-dash://` through the progressive DASH stitcher; everything else
        // (http/https CDN links, flattened Apple Music files, local files) uses the direct source.
        // Lossless streams completely bypass chunking and CacheDataSource to prevent partial-span
        // cache seam failures on servers that do not support arbitrary mid-stream HTTP Range requests.
        val losslessRoutingFactory = LosslessSchemeRoutingDataSource.create(
            defaultFactory = directHttpDataSourceFactory,
            httpUpstream = httpDataSourceFactory,
            httpClient = LosslessStreamResolver.httpClient,
        )
        // Complete-file caching layer: streams unhindered on first play while sequentially recording to disk;
        // subsequent replays read 100% complete local FLAC files with 0 MB network data and instant seeking.
        val losslessCachingFactory = LosslessCachingDataSource.Factory(context, losslessRoutingFactory)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(exoCache.cache)
            .setUpstreamDataSourceFactory(baseDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val selectiveDataSourceFactory = androidx.media3.datasource.DataSource.Factory {
            object : androidx.media3.datasource.DataSource {
                private var delegate: androidx.media3.datasource.DataSource? = null
                private val transferListeners = mutableListOf<androidx.media3.datasource.TransferListener>()

                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                    transferListeners.add(transferListener)
                    delegate?.addTransferListener(transferListener)
                }

                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    val uriStr = dataSpec.uri.toString()
                    val bypassCompleteFile = uriStr.startsWith("data:") ||
                        uriStr.contains("itunes.apple.com") ||
                        uriStr.contains("apple.com") ||
                        com.unshoo.pixelmusic.data.addons.AddonStreamRegistry.shouldBypassCompleteFileCache(uriStr)
                    val isLossless = LosslessStreamResolver.isLosslessUri(uriStr) ||
                        LosslessSchemeRoutingDataSource.isCustomLosslessScheme(dataSpec.uri) ||
                        uriStr.contains("monochrome.st/track/") ||
                        dataSpec.uri.path?.endsWith(".flac", ignoreCase = true) == true ||
                        dataSpec.uri.scheme == "monochrome" ||
                        dataSpec.uri.scheme == "apple-pending" ||
                        bypassCompleteFile

                    val factory = when {
                        dataSpec.uri.scheme == "file" || dataSpec.uri.scheme == "content" -> directHttpDataSourceFactory
                        bypassCompleteFile -> losslessRoutingFactory
                        isLossless -> losslessCachingFactory
                        else -> cacheDataSourceFactory
                    }
                    val source = factory.createDataSource()
                    transferListeners.forEach(source::addTransferListener)
                    delegate = source
                    return source.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

                override fun getUri(): Uri? = delegate?.uri

                override fun getResponseHeaders(): Map<String, List<String>> =
                    delegate?.responseHeaders ?: emptyMap()

                override fun close() {
                    delegate?.close()
                    delegate = null
                }
            }
        }

        val resolvingFactory = ResolvingDataSource.Factory(selectiveDataSourceFactory, resolver)
        val extractorsFactory = DefaultExtractorsFactory()
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
            .setConstantBitrateSeekingEnabled(true)

        // BUGFIX (adaptive buffering): previously just metered-vs-unmetered, which treats a fast
        // 5G connection the same as a barely-there one just because both are "mobile data". Now
        // tiered by the OS's real link-speed estimate, with an additional "recently unstable"
        // override driven by actual rebuffer events reported by this player (see
        // reportPlaybackStall() below) - live ground truth beats a link-speed guess. A fast link
        // gets a short startup buffer for a quick start; a weak or currently-unstable one gets a
        // longer one, trading a bit of startup time for fewer mid-song stalls, matching "prefer
        // uninstalled playback over saving a few hundred ms at startup".
        val bandwidthKbps = connectivityStateHolder.linkDownstreamBandwidthKbps.value
        val isRecentlyUnstable = connectivityStateHolder.isNetworkRecentlyUnstable.value
        data class BufferProfile(val minMs: Int, val maxMs: Int, val forPlaybackMs: Int, val afterRebufferMs: Int)
        // Industry-standard audio streaming buffer window (ViMusic/InnerTune/ArchiveTune parity):
        // Previous maxMs of 25sâ€“40s caused ExoPlayer to halt downloads after 25s, so playback ran
        // completely out of buffer at ~30s on weak links or during concurrent downloads.
        // Expanding maxMs to 90sâ€“120s lets the player continuously bank up to 2 minutes of audio,
        // while minMs (20sâ€“30s) starts fetching the next chunk long before the buffer is endangered.
        val profile = when {
            // afterRebuffer = how much audio must be re-banked before resuming after a stall.
            // 2â€“4 s made every hiccup feel like a long freeze; 1.5â€“3 s resumes sooner while still
            // leaving enough cushion (minMs keeps refilling in the background) to avoid a stall loop.
            isRecentlyUnstable -> BufferProfile(45_000, 150_000, 500, 3_000)
            bandwidthKbps < 1_000 -> BufferProfile(40_000, 150_000, 400, 2_500)
            bandwidthKbps < 5_000 -> BufferProfile(30_000, 150_000, 350, 2_000)
            else -> BufferProfile(25_000, 150_000, 250, 1_500)
        }

        // BUGFIX: the buffer profile used to be chosen from the lossless *toggle* at player-build
        // time. Players are only rebuilt on Hi-Fi/offload changes, so after flipping the toggle the
        // old profile stuck: YouTube AAC was capped at 45s (more rebuffering) with lossless on, and
        // FLAC ran on the uncapped 150s time-based profile with lossless off (heap spikes on
        // 24-bit streams). It also ran a blocking DataStore read on the calling thread.
        // A byte-capped, time-prioritised profile adapts to the actual bitrate of whatever plays.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minMs,
                profile.maxMs,
                profile.forPlaybackMs,
                profile.afterRebufferMs
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(20_000, /* retainBackBufferFromKeyframe = */ true)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(resolvingFactory, extractorsFactory)
                    .setLoadErrorHandlingPolicy(streamRecoveryLoadErrorPolicy)
                    // Apple Music (lossless sources) streams are Widevine-L3 protected fMP4; the
                    // provider answers DRM_UNSUPPORTED for everything else (see AppleMusicDrm).
                    .setDrmSessionManagerProvider(AppleMusicDrm.drmSessionManagerProvider)
            )
            .setLoadControl(loadControl)
            .build().apply {
                // BUGFIX (adaptive buffering): feed real rebuffer events back into
                // ConnectivityStateHolder so the *next* buffer-profile decision above (next
                // track, or next time this player is rebuilt) can react to it. A true mid-stream
                // LoadControl swap would need a custom LoadControl implementation - a larger,
                // separate change - so this closes the loop at track/rebuild boundaries instead,
                // which is where DefaultLoadControl's buffer targets can actually be changed.
                var hasStartedThisItem = false
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                if (hasStartedThisItem) {
                                    connectivityStateHolder.reportStablePlayback()
                                }
                                hasStartedThisItem = true
                            }
                            Player.STATE_BUFFERING -> {
                                // Only a genuine rebuffer (mid-playback stall), not the initial
                                // buffer-up before a track's first STATE_READY.
                                if (hasStartedThisItem) {
                                    connectivityStateHolder.reportPlaybackStall()
                                }
                            }
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        hasStartedThisItem = false
                        // Media3 manages each deck's CPU and Wi-Fi locks through buffering,
                        // playback and offload sleep, including before a standby deck is promoted.
                        setWakeMode(if (isLikelyLocalMedia(mediaItem)) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
                    }
                })
            applyAudioOffload(this)
            setAudioAttributes(audioAttributes, false)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            playWhenReady = false
        }
    }

    private fun attachAuxiliaryErrorListener(aux: ExoPlayer) {
        auxiliaryErrorListener?.let { runCatching { aux.removeListener(it) } }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (aux !== playerB || transitionRunning || isReleased) return
                val failedItem = aux.currentMediaItem ?: return
                val failedUri = failedItem.localConfiguration?.uri?.toString()
                val http = findHttpResponseException(error)
                val failedStreamUrl = http?.dataSpec?.uri?.toString()
                    ?: failedUri?.let { peekResolvedPlaybackUri(it) }
                    ?: failedUri
                val stepDownOverride = if (http != null && http.responseCode in REFUSED_STREAM_CODES) {
                    recordRefusedStreamFailure(http.dataSpec.uri.toString(), http.responseCode, youtubeIdOf(failedItem))
                } else {
                    null
                }
                if (LosslessStreamResolver.isLosslessUri(failedUri) || youtubeIdOf(failedItem) != null) {
                    Timber.tag("TransitionDebug").w(
                        error,
                        "Auxiliary preload error for %s; stepping down fallback",
                        failedItem.mediaId,
                    )
                    failoverLosslessToYoutube(
                        mediaItem = failedItem,
                        targetPlayer = aux,
                        reason = error.message ?: "Auxiliary preload error (${error.errorCodeName})",
                        failingUri = failedStreamUrl,
                        stepDownOverride = stepDownOverride,
                    )
                }
            }
        }
        auxiliaryErrorListener = listener
        aux.addListener(listener)
    }

    private fun resetPlaybackSpeedToNormal(player: Player?) {
        if (player != null && player.playbackParameters != PlaybackParameters.DEFAULT) {
            player.playbackParameters = PlaybackParameters.DEFAULT
        }
    }

    private fun getOrCreateAuxiliaryPlayer(): ExoPlayer {
        playerB?.let { existing ->
            val unusable = (existing.playbackState == Player.STATE_IDLE && existing.playerError != null) ||
                !existing.applicationLooper.thread.isAlive
            if (!unusable) {
                resetPlaybackSpeedToNormal(existing)
                attachAuxiliaryErrorListener(existing)
                return existing
            }
            auxiliaryErrorListener?.let { runCatching { existing.removeListener(it) } }
            try { existing.release() } catch (_: Exception) {}
            playerB = null
        }
        return buildPlayer().also { aux ->
            attachAuxiliaryErrorListener(aux)
            playerB = aux
        }
    }

    fun setPauseAtEndOfMediaItems(shouldPause: Boolean) {
        if (::playerA.isInitialized) {
            playerA.pauseAtEndOfMediaItems = shouldPause
        }
    }

    internal fun resumeOrAdvanceIfPausedAtEnd(player: Player) {
        if (!::playerA.isInitialized || player !== playerA) return
        val dur = player.duration
        val nearEnd = dur != C.TIME_UNSET && dur > 0L && player.currentPosition >= dur - 1_000L
        val stalledAtEnd = player.playbackState == Player.STATE_ENDED ||
            (!player.isPlaying && !player.playWhenReady && nearEnd)
        if (stalledAtEnd && player.hasNextMediaItem()) {
            player.seekToNext()
            player.prepare()
            player.play()
        }
    }

    fun getNextTransitionTarget(currentMediaItem: MediaItem, repeatMode: Int): TransitionTarget? {
        val snapshot = ensureQueueSnapshot()
        if (snapshot.isEmpty()) return null

        val currentAbsoluteIndex = resolveCurrentAbsoluteIndex(currentMediaItem, snapshot)
        if (currentAbsoluteIndex == C.INDEX_UNSET) return null

        val targetIndex = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> currentAbsoluteIndex
            else -> {
                val playerNext = if (::playerA.isInitialized &&
                    playerA.currentMediaItem?.mediaId == currentMediaItem.mediaId
                ) {
                    val idx = playerA.nextMediaItemIndex
                    if (idx != C.INDEX_UNSET) {
                        val abs = if (activePlayerUsesWindowedQueue) activeWindowStartIndex + idx else idx
                        abs.takeIf { it in snapshot.indices }
                    } else null
                } else null
                playerNext ?: when {
                    currentAbsoluteIndex + 1 in snapshot.indices -> currentAbsoluteIndex + 1
                    repeatMode == Player.REPEAT_MODE_ALL && snapshot.isNotEmpty() -> 0
                    else -> -1
                }
            }
        }

        val targetItem = snapshot.getOrNull(targetIndex) ?: return null
        return TransitionTarget(
            mediaItem = targetItem,
            absoluteIndex = targetIndex,
            queueSize = snapshot.size
        )
    }

    /**
     * Checks if the currently loaded item in the master player has an expired or stale stream URL
     * (e.g. after a long pause or app reopening) and proactively re-resolves it in-place.
     */
    suspend fun ensureFreshStreamForResume() {
        val isActivelyPlaying = withContext(Dispatchers.Main.immediate) {
            if (::playerA.isInitialized) {
                playerA.isPlaying || (playerA.playWhenReady && playerA.playbackState == Player.STATE_READY)
            } else false
        }
        if (isActivelyPlaying) {
            // Active stream is playing cleanly; never tear down the buffer or seek during live playback
            return
        }
        val (currentItem, currentUri) = withContext(Dispatchers.Main.immediate) {
            val item = if (::playerA.isInitialized) playerA.currentMediaItem else null
            val uri = item?.localConfiguration?.uri
            Pair(item, uri)
        }
        if (currentItem == null || currentUri == null) return

        val currentUriStr = currentUri.toString()
        val originalUriStr = currentItem.mediaMetadata.extras?.getString("com.unshoo.pixelmusic.external.CONTENT_URI") ?: currentUriStr

        // Apple HLS streams use data: URIs that embed the entire playlist as base64.
        // The CDN segment URLs inside expire after ~30 min, so after a long pause the
        // player buffers forever. Detect stale Apple HLS and force a full re-resolve.
        val isAppleHls = currentUriStr.startsWith("data:application/vnd.apple.mpegurl") ||
            currentUriStr.startsWith("data:application/x-mpegurl")
        if (isAppleHls) {
            if (LosslessStreamResolver.isFreshUri(currentUriStr)) {
                // Still within the freshness window; just make sure the player is prepared.
                withContext(Dispatchers.Main.immediate) {
                    if (::playerA.isInitialized) {
                        if (playerA.playbackState == Player.STATE_IDLE || playerA.playbackState == Player.STATE_ENDED) {
                            playerA.prepare()
                        }
                        playerA.playWhenReady = true
                    }
                }
                return
            }
            Timber.tag("DualPlayerEngine").i("Apple HLS stale on resume for %s, re-resolving...", currentItem.mediaId)
            LosslessStreamResolver.markUriFresh(currentUriStr, 0L)
            val sourceUri = Uri.parse(originalUriStr.takeIf { it != currentUriStr } ?: "youtube://${currentItem.mediaId.removePrefix("youtube_")}")
            val freshItem = withContext(Dispatchers.IO) {
                val sourceScheme = sourceUri.scheme
                if (sourceScheme != null && sourceScheme in REMOTE_MEDIA_SCHEMES) {
                    resolveMediaItem(currentItem.buildUpon().setUri(sourceUri).build())
                } else {
                    // Reconstruct a youtube:// URI to re-enter the lossless chain
                    val ytUri = Uri.parse("youtube://${currentItem.mediaId.removePrefix("youtube_")}")
                    resolveMediaItem(currentItem.buildUpon().setUri(ytUri).build())
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (::playerA.isInitialized && playerA.currentMediaItem?.mediaId == currentItem.mediaId) {
                    val pos = playerA.currentPosition
                    val idx = playerA.currentMediaItemIndex
                    val playWhenReady = playerA.playWhenReady
                    playerA.replaceMediaItem(idx, freshItem)
                    playerA.seekTo(idx, pos)
                    playerA.playWhenReady = playWhenReady
                    if (playerA.playbackState == Player.STATE_IDLE || playerA.playbackState == Player.STATE_ENDED) {
                        playerA.prepare()
                    }
                }
            }
            return
        }

        val isHttp = currentUri.scheme == "http" || currentUri.scheme == "https"
        val isRemoteScheme = currentUri.scheme in REMOTE_MEDIA_SCHEMES

        if (!isHttp && isRemoteScheme) {
            // BUGFIX (repeated loading on every resume): items queued as youtube:// are resolved by
            // the data source on demand. This branch used to re-resolve and replaceMediaItem() on
            // EVERY play press, which threw away the whole forward buffer even on a fast network,
            // so each resume showed the loader again. Only rebuild the item when the player has no
            // live source (idle/ended) or the URL it is streaming from is actually stale.
            val locked = activePlaybackResolvedUris[originalUriStr] ?: resolvedUriCache.get(originalUriStr)
            val playerHasSource = withContext(Dispatchers.Main.immediate) {
                ::playerA.isInitialized &&
                    playerA.playbackState != Player.STATE_IDLE &&
                    playerA.playbackState != Player.STATE_ENDED
            }
            val pausedForMs = if (lastPausedAtMs > 0L) SystemClock.elapsedRealtime() - lastPausedAtMs else 0L
            val lockIsFresh = locked == null ||
                (isResolvedUriFresh(originalUriStr, locked) && !isDeadStreamUrl(locked.toString()))
            if (playerHasSource && lockIsFresh && pausedForMs < 20 * 60 * 1000L) {
                return
            }
            Timber.tag("DualPlayerEngine").i("Stream URL needs initial resolution on resume for %s", originalUriStr)
            val sourceUri = Uri.parse(originalUriStr)
            val freshItem = withContext(Dispatchers.IO) {
                val resolved = resolveMediaItem(currentItem.buildUpon().setUri(sourceUri).build())
                resolved.localConfiguration?.uri?.toString()?.let { u ->
                    if (!LosslessStreamResolver.isLosslessUri(u) && !u.contains("monochrome.st/track/")) {
                        val key = currentItem.mediaId.removePrefix("youtube_").takeIf { it.isNotBlank() }
                        if (u.startsWith("http")) preCacheFirstChunk(u, key)
                    }
                }
                resolved
            }
            withContext(Dispatchers.Main.immediate) {
                if (::playerA.isInitialized && playerA.currentMediaItem?.mediaId == currentItem.mediaId) {
                    val pos = playerA.currentPosition
                    val idx = playerA.currentMediaItemIndex
                    val playWhenReady = playerA.playWhenReady
                    playerA.replaceMediaItem(idx, freshItem)
                    playerA.seekTo(idx, pos)
                    playerA.playWhenReady = playWhenReady
                    if (playerA.playbackState == Player.STATE_IDLE || playerA.playbackState == Player.STATE_ENDED) {
                        playerA.prepare()
                    }
                }
            }
            return
        }

        if (isHttp) {
            val activeError = withContext(Dispatchers.Main.immediate) {
                if (::playerA.isInitialized) playerA.playerError else null
            }
            if (activeError == null && isResolvedUriFresh(originalUriStr, currentUri)) {
                return // Fast path: already fresh
            }
            if (activeError != null) {
                val http = findHttpResponseException(activeError)
                if (http != null && http.responseCode in REFUSED_STREAM_CODES) {
                    recordRefusedStreamFailure(http.dataSpec.uri.toString(), http.responseCode, youtubeIdOf(currentItem))
                }
            }
            Timber.tag("DualPlayerEngine").i("Stream URL stale on resume for %s, re-resolving...", originalUriStr)
            invalidateResolvedUri(originalUriStr)
            val effectiveSourceUri = if (originalUriStr.startsWith("http")) {
                val cleanId = when {
                    currentItem.mediaId.startsWith("youtube_") -> currentItem.mediaId.removePrefix("youtube_")
                    currentItem.mediaId.length == 11 -> currentItem.mediaId
                    else -> null
                }
                if (cleanId != null) Uri.parse("youtube://$cleanId") else Uri.parse(originalUriStr)
            } else {
                Uri.parse(originalUriStr)
            }
            if (effectiveSourceUri.scheme in REMOTE_MEDIA_SCHEMES) {
                val freshItem = withContext(Dispatchers.IO) {
                    val resolved = resolveMediaItem(currentItem.buildUpon().setUri(effectiveSourceUri).build())
                    resolved.localConfiguration?.uri?.toString()?.let { u ->
                        if (!LosslessStreamResolver.isLosslessUri(u) && !u.contains("monochrome.st/track/")) {
                            val key = currentItem.mediaId.removePrefix("youtube_").takeIf { it.isNotBlank() }
                            if (u.startsWith("http")) preCacheFirstChunk(u, key)
                        }
                    }
                    resolved
                }
                withContext(Dispatchers.Main.immediate) {
                    if (::playerA.isInitialized && playerA.currentMediaItem?.mediaId == currentItem.mediaId) {
                        val pos = playerA.currentPosition
                        val idx = playerA.currentMediaItemIndex
                        val playWhenReady = playerA.playWhenReady
                        playerA.replaceMediaItem(idx, freshItem)
                        playerA.seekTo(idx, pos)
                        playerA.playWhenReady = playWhenReady
                        // BUGFIX: a prior error leaves the player STATE_IDLE; setting
                        // playWhenReady alone does nothing without prepare().
                        if (playerA.playbackState == Player.STATE_IDLE || playerA.playbackState == Player.STATE_ENDED) {
                            playerA.prepare()
                        }
                    }
                }
            }
        }
    }

    fun setHiFiMode(enabled: Boolean) {
        if (hiFiModeEnabled == enabled) return
        if (enabled && !HiFiCapabilityChecker.isSupported()) {
            Timber.tag("DualPlayerEngine").w("Hi-Fi mode requested but device does not support PCM_FLOAT")
            return
        }
        hiFiModeEnabled = enabled
        _hiFiModeEnabledState.value = enabled
        rebuildPlayersPreservingMasterState("Hi-Fi mode set to $enabled")
    }

    internal fun cachePreloadedStreamUri(youtubeUriString: String, resolved: Uri) {
        val existing = resolvedUriCache.get(youtubeUriString)
        if (existing != null &&
            isResolvedUriFresh(youtubeUriString, existing) &&
            LosslessStreamResolver.isLosslessUri(existing.toString()) &&
            !LosslessStreamResolver.isLosslessUri(resolved.toString())
        ) {
            return
        }
        resolvedUriCache.put(youtubeUriString, resolved)
    }

    suspend fun resolveCloudUri(
        uri: Uri,
        isNextInQueue: Boolean = false,
        lockForActivePlayback: Boolean = true,
    ): Uri = withContext(Dispatchers.IO) {
        val uriString = uri.toString()

        // Downloaded since the stream URL was cached? Play the file, drop the stale stream.
        if (uri.scheme == "youtube") {
            resolveLocalDiskFile(uriString)?.let { diskUri ->
                // Not cached: if the download is deleted later we must fall back to streaming.
                activePlaybackResolvedUris.remove(uriString)
                resolvedUriCache.remove(uriString)
                return@withContext diskUri
            }
        }
        
        // Return active playback locked URI to prevent ExoPlayer from stuttering or re-loading due to mid-stream URL changes.
        // YouTube googlevideo URLs expire; after a long pause using a stale locked URL causes endless buffering.
        activePlaybackResolvedUris[uriString]?.let { lockedUri ->
            if (isResolvedUriFresh(uriString, lockedUri)) return@withContext lockedUri
            activePlaybackResolvedUris.remove(uriString)
            resolvedUriCache.remove(uriString)
        }
        
        resolvedUriCache.get(uriString)?.let { cachedUri ->
            val youtubeId = if (uri.scheme == "youtube") uriString.removePrefix("youtube://") else null
            val needsUpgrade = isNextInQueue && youtubeId != null &&
                com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.shouldUpgradeCachedStream(
                    context = context,
                    videoId = youtubeId,
                    cachedUrl = cachedUri.toString(),
                    isNextInQueue = true,
                )
            if (isResolvedUriFresh(uriString, cachedUri) && !needsUpgrade) {
                if (lockForActivePlayback) activePlaybackResolvedUris[uriString] = cachedUri
                return@withContext cachedUri
            }
            if (!needsUpgrade) {
                resolvedUriCache.remove(uriString)
            }
        }

        val resolutionKey = if (isNextInQueue) "$uriString|next" else uriString
        val deferred = activeResolutions.computeIfAbsent(resolutionKey) {
            scope.async(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val losslessExtraMs = if (isNextInQueue && uri.scheme == "youtube") 9_500L else LOSSLESS_RESOLVE_EXTRA_MS
                val timeoutMs = losslessExtraMs + if (connectivityStateHolder.isMeteredNetwork.value) {
                    STREAM_RESOLVE_TIMEOUT_LOW_CONNECTIVITY_MS + 4_000L
                } else {
                    STREAM_RESOLVE_TIMEOUT_MS + 4_000L
                }
                val resolved = withTimeoutOrNull(timeoutMs) {
                    when (uri.scheme) {
                        "telegram" -> resolveTelegramUriAsync(uri, uriString)
                        "gdrive" -> resolveGDriveUriAsync(uriString)
                        "youtube" -> resolveYoutubeUriAsync(uriString, isNextInQueue = isNextInQueue)
                        "monochrome" -> {
                            val trackId = uriString.substringAfter("monochrome://")
                            Uri.parse("https://tracks.monochrome.st/track/$trackId")
                        }
                        else -> null
                    }
                }
                if (resolved != null) {
                    if (resolved.scheme != "file" && resolved.scheme != "content") {
                        resolvedUriCache.put(uriString, resolved)
                        if (lockForActivePlayback) activePlaybackResolvedUris[uriString] = resolved
                    }
                    resolved
                } else {
                    Timber.tag("DualPlayerEngine").w(
                        "resolveCloudUri timed out/null for %s (metered=%s, next=%s)",
                        uriString,
                        connectivityStateHolder.isMeteredNetwork.value,
                        isNextInQueue,
                    )
                    uri
                }
            }
        }
        deferred.invokeOnCompletion { activeResolutions.remove(resolutionKey, deferred) }

        try {
            deferred.await()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.tag("DualPlayerEngine").e(e, "Error awaiting resolution for %s", uriString)
            uri
        }
    }

    private fun isResolvedUriFresh(originalUriString: String, resolvedUri: Uri): Boolean {
        val resolved = resolvedUri.toString()
        if (isDeadStreamUrl(resolved)) return false
        // Lossless-source URIs carry their own freshness window (Tidal/Qobuz/Deezer links expire,
        // flattened Apple files can be evicted); the resolver tracks it, never the googlevideo
        // `expire=` heuristic below.
        if (LosslessStreamResolver.isLosslessUri(resolved) ||
            resolved.contains("monochrome.st/track/") ||
            originalUriString.startsWith("monochrome://")
        ) {
            return LosslessStreamResolver.isFreshUri(resolved)
        }
        if (resolvedUri.scheme == "file" || resolvedUri.scheme == "content") {
            return true
        }

        // JioSaavn streams:
        val isSaavn = resolved.contains("saavncdn.com", ignoreCase = true) ||
            resolved.contains("jiosaavn.com", ignoreCase = true)
        if (isSaavn) {
            if (LosslessStreamResolver.isFreshUri(resolved)) return true
            val expireParam = resolvedUri.getQueryParameter("expire")
                ?: resolvedUri.getQueryParameter("expires")
                ?: resolvedUri.getQueryParameter("exp")
            if (expireParam != null) {
                val expireSeconds = expireParam.toLongOrNull() ?: return true
                val nowSeconds = System.currentTimeMillis() / 1000L
                return expireSeconds > (nowSeconds + 900L)
            }
            val pausedForMs = if (lastPausedAtMs > 0L) SystemClock.elapsedRealtime() - lastPausedAtMs else 0L
            return pausedForMs <= 20 * 60 * 1000L
        }

        val isYoutubeResolution = (originalUriString.startsWith("youtube://") && (resolved.contains("googlevideo.com", ignoreCase = true) || resolved.contains("youtube.com", ignoreCase = true))) ||
            resolved.contains("googlevideo.com", ignoreCase = true) ||
            resolved.contains("youtube.com", ignoreCase = true)
        if (!isYoutubeResolution) {
            val expireParam = resolvedUri.getQueryParameter("expire") ?: resolvedUri.getQueryParameter("expires")
            if (expireParam != null) {
                val expireSeconds = expireParam.toLongOrNull() ?: return true
                val nowSeconds = System.currentTimeMillis() / 1000L
                return expireSeconds > (nowSeconds + 900L)
            }
            return true
        }

        val expireSeconds = resolvedUri.getQueryParameter("expire")?.toLongOrNull()
            ?: return false
        val nowSeconds = System.currentTimeMillis() / 1000L
        // Require at least 15 minutes (900s) remaining validity.
        // YouTube URLs expire in 6 hours. If the app hasn't been used in 5.5+ hours,
        // force a silent refresh in the background BEFORE playback starts to guarantee zero lag/retries.
        return expireSeconds > (nowSeconds + 900L)
    }

    private suspend fun resolveTelegramUriAsync(uri: Uri, uriString: String): Uri? = withContext(Dispatchers.IO) {
        val pathSegments = uri.pathSegments
        val fileId = if (pathSegments.isNotEmpty()) {
            telegramRepository.resolveTelegramUri(uriString)?.first
        } else {
            uri.host?.toIntOrNull()
        } ?: return@withContext null

        val fileInfo = telegramRepository.getFile(fileId)
        if (fileInfo?.local?.isDownloadingCompleted == true && fileInfo.local.path.isNotEmpty()) {
            return@withContext Uri.fromFile(File(fileInfo.local.path))
        }

        if (!connectivityStateHolder.isOnline.value) {
            connectivityStateHolder.triggerOfflineBlockedEvent()
            return@withContext null
        }

        if (!telegramStreamProxy.ensureReady(5_000L)) return@withContext null
        val proxyUrl = telegramStreamProxy.getProxyUrl(fileId, 0L)
        if (proxyUrl.isNotEmpty()) Uri.parse(proxyUrl) else null
    }



    private suspend fun resolveGDriveUriAsync(uriString: String): Uri? = withContext(Dispatchers.IO) {
        if (!connectivityStateHolder.isOnline.value) {
            connectivityStateHolder.triggerOfflineBlockedEvent()
            return@withContext null
        }
        if (!gdriveStreamProxy.ensureReady(5_000L)) return@withContext null
        gdriveStreamProxy.resolveGDriveUri(uriString)?.let { Uri.parse(it) }
    }

    private suspend fun preResolveNeighborYoutube(uri: Uri) = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        if (resolveLocalDiskFile(uriString) != null) return@withContext
        val youtubeId = uriString.removePrefix("youtube://")
        activePlaybackResolvedUris[uriString]?.let { if (isResolvedUriFresh(uriString, it)) return@withContext }
        resolvedUriCache.get(uriString)?.let { cached ->
            val needsUpgrade = com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.shouldUpgradeCachedStream(
                context = context,
                videoId = youtubeId,
                cachedUrl = cached.toString(),
                isNextInQueue = true,
            )
            if (isResolvedUriFresh(uriString, cached) && !needsUpgrade) return@withContext
        }
        val outerTimeoutMs = if (connectivityStateHolder.isMeteredNetwork.value) 22_000L else 18_000L
        val resolved = withTimeoutOrNull(outerTimeoutMs) {
            resolveCloudUri(uri, isNextInQueue = true, lockForActivePlayback = false)
        }
        if (resolved != null && resolved != uri && resolved.scheme != "file" && resolved.scheme != "content") {
            resolvedUriCache.put(uriString, resolved)
        }
    }

    private suspend fun resolveYoutubeUriAsync(
        uriString: String,
        isNextInQueue: Boolean = false,
    ): Uri? = withContext(Dispatchers.IO) {
        val youtubeId = uriString.removePrefix("youtube://")
        val skipLocalAudio = failedLocalYoutubeSources.get(uriString) == true
        try {
            val isLosslessEnabled = LosslessStreamResolver.isEnabled(context)

            val matchingItem = queueSnapshot.firstOrNull {
                it.mediaId == youtubeId ||
                    it.mediaId == "youtube_$youtubeId" ||
                    it.requestMetadata.mediaUri?.toString() == uriString ||
                    it.localConfiguration?.uri?.toString() == uriString
            } ?: withContext(Dispatchers.Main.immediate) {
                fun matches(item: androidx.media3.common.MediaItem?): Boolean =
                    item != null && (
                        item.mediaId == youtubeId ||
                            item.mediaId == "youtube_$youtubeId" ||
                            item.requestMetadata.mediaUri?.toString() == uriString ||
                            item.localConfiguration?.uri?.toString() == uriString
                    )
                val itemA = if (::playerA.isInitialized) playerA.currentMediaItem else null
                val itemB = playerB?.currentMediaItem
                when {
                    matches(itemA) -> itemA
                    matches(itemB) -> itemB
                    ::playerA.isInitialized -> (0 until playerA.mediaItemCount)
                        .asSequence()
                        .map { playerA.getMediaItemAt(it) }
                        .firstOrNull(::matches)
                    else -> null
                }
            }

            val rawDurMs = matchingItem?.mediaMetadata?.extras
                ?.getLong(com.unshoo.pixelmusic.utils.MediaItemBuilder.EXTERNAL_EXTRA_DURATION, 0L) ?: 0L
            val youtubeSong = com.unshoo.pixelmusic.data.model.youtube.Song(
                youtubeId = youtubeId,
                title = matchingItem?.mediaMetadata?.title?.toString().orEmpty(),
                artist = matchingItem?.mediaMetadata?.artist?.toString().orEmpty(),
                album = matchingItem?.mediaMetadata?.albumTitle?.toString().orEmpty(),
                duration = if (rawDurMs > 0L) (rawDurMs / 1_000L).toString() else ""
            )

            // Never retry the full quality-aware resolver without an outer timeout.
            // When lossless is on, YoutubeHelper spends up to LOSSLESS_INLINE_BUDGET_MS on the
            // lossless chain before the YouTube step; grant that time so the YouTube step is not
            // starved into the lowest-quality emergency path.
            val losslessExtraMs = if (isLosslessEnabled) {
                if (isNextInQueue) 9_500L else LOSSLESS_RESOLVE_EXTRA_MS
            } else 0L
            val timeoutMs = losslessExtraMs + if (connectivityStateHolder.isMeteredNetwork.value) {
                STREAM_RESOLVE_TIMEOUT_LOW_CONNECTIVITY_MS
            } else {
                STREAM_RESOLVE_TIMEOUT_MS
            }
            // Reserve time for one emergency fallback; never launch a second unbounded full resolve.
            val fallbackBudgetMs = 3_000L
            val primaryBudgetMs = (timeoutMs - fallbackBudgetMs).coerceAtLeast(1_000L)
            var path = withTimeoutOrNull(primaryBudgetMs) {
                if (skipLocalAudio) {
                    com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper
                        .getLowestQualityStreamUrl(context, youtubeSong, skipLocalAudio = true)
                } else {
                    com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.getSongPlayerUrl(
                        context,
                        youtubeSong,
                        isNextInQueue = isNextInQueue,
                    )
                }
            }

            if (path.isNullOrBlank() && !skipLocalAudio) {
                path = withTimeoutOrNull(fallbackBudgetMs) {
                    com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper
                        .getLowestQualityStreamUrl(context, youtubeSong, skipLocalAudio = skipLocalAudio)
                }
            }

            val resolvedPath = path?.takeIf { it.isNotBlank() } ?: return@withContext null
            when {
                LosslessStreamResolver.isLosslessUri(resolvedPath) ||
                    resolvedPath.startsWith("data:") ||
                    resolvedPath.contains("monochrome.st/track/") ||
                    LosslessSchemeRoutingDataSource.isCustomLosslessScheme(resolvedPath) ||
                    resolvedPath.startsWith("file:") ||
                    resolvedPath.startsWith("apple-pending:") -> {
                    if (resolvedPath.contains("monochrome.st/track/")) {
                        LosslessStreamResolver.markUriFresh(resolvedPath)
                    }
                    Uri.parse(resolvedPath)
                }
                resolvedPath.startsWith("content://") -> {
                    // Download saved in the custom (SAF) folder: was wrapped as a bogus file:// URI.
                    localFilePathCache.put(uriString, resolvedPath)
                    Uri.parse(resolvedPath)
                }
                !resolvedPath.startsWith("http") -> {
                    com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper
                        .registerLocalFilePath(youtubeId, resolvedPath)
                    localFilePathCache.put(uriString, resolvedPath)
                    Uri.fromFile(java.io.File(resolvedPath))
                }
                else -> {
                    // Cache warming is for NEIGHBOUR tracks only. For the track the player is about
                    // to open, a parallel 1MB pre-cache competes with the player's own request for
                    // the same bytes (double bandwidth, locked cache spans) and slows the start.
                    if (youtubeId != currentMasterYoutubeId) {
                        preCacheFirstChunk(resolvedPath, youtubeId)
                    }
                    Uri.parse(resolvedPath)
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Timber.tag("DualPlayerEngine").e(e, "resolveYoutubeUriAsync failed for %s", uriString)
            val localDiskUri = resolveLocalDiskFile(uriString)
            if (localDiskUri != null) return@withContext localDiskUri

            val low = withTimeoutOrNull(3_000L) {
                runCatching {
                    com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper
                        .getLowestQualityStreamUrl(
                            context,
                            com.unshoo.pixelmusic.data.model.youtube.Song(youtubeId = youtubeId),
                            skipLocalAudio = skipLocalAudio,
                        )
                }.getOrNull()
            }
            when {
                low.isNullOrBlank() -> null
                low.startsWith("http") -> {
                    preCacheFirstChunk(low, youtubeId)
                    Uri.parse(low)
                }
                java.io.File(low).isFile -> Uri.fromFile(java.io.File(low))
                else -> null
            }
        }
    }

    suspend fun resolveMediaItem(mediaItem: MediaItem, isNextInQueue: Boolean = false): MediaItem {
        val uri = mediaItem.localConfiguration?.uri ?: return mediaItem
        val scheme = uri.scheme
        if (scheme !in REMOTE_MEDIA_SCHEMES) return mediaItem
        if (scheme == "youtube" && failedLocalYoutubeSources.get(uri.toString()) != true) {
            val path = mediaItem.mediaMetadata.extras
                ?.getString(com.unshoo.pixelmusic.utils.MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH)
                ?.takeIf { it.isNotBlank() }
            if (path != null) {
                val localPath = withContext(Dispatchers.IO) {
                    com.unshoo.pixelmusic.utils.OfflineAudioResolver.readableAudioPath(context, path)
                }
                if (localPath != null) {
                    val localUri = if (localPath.startsWith("content://")) Uri.parse(localPath) else Uri.fromFile(File(localPath))
                    registerLocalPath(uri.toString(), localPath)
                    return mediaItem.buildUpon().setUri(localUri).setMimeType(null).setCustomCacheKey(null).build()
                }
            }
        }
        val resolvedUri = resolveCloudUri(uri, isNextInQueue = isNextInQueue, lockForActivePlayback = !isNextInQueue)
        if (resolvedUri == uri) return mediaItem
        if (resolvedUri.scheme == "file" || resolvedUri.scheme == "content") {
            return mediaItem.buildUpon().setUri(resolvedUri).setMimeType(null).setCustomCacheKey(null).build()
        }
        
        val builder = mediaItem.buildUpon().setUri(resolvedUri)
        val isLossless = LosslessStreamResolver.isLosslessUri(resolvedUri.toString()) ||
            LosslessStreamResolver.isLosslessUri(uri.toString()) ||
            resolvedUri.path?.endsWith(".flac", true) == true ||
            scheme == "monochrome" ||
            resolvedUri.toString().contains("monochrome.st/track/") ||
            LosslessSchemeRoutingDataSource.isCustomLosslessScheme(resolvedUri)

        val cleanId = when {
            scheme == "youtube" -> uri.toString().removePrefix("youtube://")
            scheme == "monochrome" -> "monochrome_" + (uri.host?.takeIf { it.isNotBlank() } ?: uri.toString().removePrefix("monochrome://"))
            uri.toString().startsWith("monochrome://") -> "monochrome_" + uri.toString().removePrefix("monochrome://")
            uri.toString().contains("monochrome.st/track/") -> "monochrome_" + uri.toString().substringAfterLast("/")
            resolvedUri.toString().contains("monochrome.st/track/") -> "monochrome_" + resolvedUri.toString().substringAfterLast("/")
            else -> mediaItem.mediaId.removePrefix("youtube_").takeIf { it.isNotBlank() }
        }
        // If this resolved lossless track is already 100% complete in the lossless disk cache
        // (and not overridden to a lossy/non-matching source), serve directly from file.
        if (isLossless) {
            val cachedComplete = cleanId?.let { LosslessFileCache.getCompleteFile(context, it) }
            if (cachedComplete != null) {
                return mediaItem.buildUpon()
                    .setUri(Uri.fromFile(cachedComplete))
                    .setMimeType(androidx.media3.common.MimeTypes.AUDIO_FLAC)
                    .build()
            }
        }

        // ONLY attach custom cache key to YouTube streams; NEVER attach to Lossless streams
        if (!isLossless && cleanId != null) {
            builder.setCustomCacheKey(streamCacheKey(cleanId, resolvedUri.toString()))
        }

        val resolvedStr = resolvedUri.toString()
        val containerMime = cleanId?.let { LosslessStreamResolver.resultFor(it)?.containerMimeType }
            ?: com.unshoo.pixelmusic.data.addons.AddonStreamRegistry.metaFor(resolvedStr)
                ?.let { com.unshoo.pixelmusic.data.addons.AddonStreamRegistry.containerMimeOf(it.transport) }
            ?: when {
                resolvedStr.lowercase(java.util.Locale.US).contains(".m3u8") ||
                    resolvedStr.startsWith("data:application/vnd.apple.mpegurl") ||
                    resolvedStr.startsWith("data:application/x-mpegurl") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                resolvedStr.lowercase(java.util.Locale.US).contains(".mpd") -> androidx.media3.common.MimeTypes.APPLICATION_MPD
                else -> null
            }

        if (containerMime != null) {
            builder.setMimeType(containerMime)
        } else if (resolvedUri.path?.endsWith(".flac", true) == true ||
            scheme == "monochrome" ||
            resolvedStr.contains("monochrome.st/track/")
        ) {
            builder.setMimeType(androidx.media3.common.MimeTypes.AUDIO_FLAC)
        } else if (scheme == "youtube" && !isLossless) {
            val videoId = uri.toString().removePrefix("youtube://")
            val cachedMime = com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.streamMimeTypeLruCache.let { cache ->
                cache.get("${videoId}_high")
                    ?: cache.get("${videoId}_low")
                    ?: cache.snapshot().keys.find { it.startsWith("${videoId}_") }?.let { cache.get(it) }
            }
            if (cachedMime != null) {
                builder.setMimeType(cachedMime)
            }
        }
        if (AppleMusicDrm.infoFor(cleanId) != null || AppleMusicDrm.infoFor(mediaItem.mediaId) != null) {
            builder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setMultiSession(true).build()
            )
        }
        return builder.build()
    }

    suspend fun prepareNext(target: TransitionTarget, startPositionMs: Long = 0L) {
        prepareNext(target.mediaItem, target.absoluteIndex, startPositionMs)
    }

    suspend fun prepareNext(mediaItem: MediaItem, startPositionMs: Long = 0L) {
        val preferredIndex = findMediaItemIndex(
            items = ensureQueueSnapshot(),
            mediaId = mediaItem.mediaId,
            preferAfterExclusive = resolveCurrentAbsoluteIndex(playerA.currentMediaItem ?: mediaItem, queueSnapshot)
        )
        prepareNext(mediaItem, preferredIndex, startPositionMs)
    }

    private suspend fun prepareNext(mediaItem: MediaItem, preferredAbsoluteIndex: Int, startPositionMs: Long = 0L) {
        if (transitionRunning || isReleased) return
        try {
            val snapshot = ensureQueueSnapshot()
            val currentAbsoluteIndex = resolveCurrentAbsoluteIndex(playerA.currentMediaItem ?: mediaItem, snapshot)
            val targetIndex = when {
                preferredAbsoluteIndex in snapshot.indices &&
                    snapshot[preferredAbsoluteIndex].mediaId == mediaItem.mediaId -> preferredAbsoluteIndex
                else -> findMediaItemIndex(snapshot, mediaItem.mediaId, currentAbsoluteIndex)
            }
            val resolvedItem = resolveMediaItem(mediaItem, isNextInQueue = true)
            currentCoroutineContext().ensureActive()
            if (transitionRunning || isReleased) return

            val auxiliaryPlayer = getOrCreateAuxiliaryPlayer()
            auxiliaryPlayer.stop()
            auxiliaryPlayer.clearMediaItems()

            if (targetIndex != C.INDEX_UNSET && snapshot.isNotEmpty()) {
                val count = snapshot.size
                val (start, end) = auxiliaryWindowBounds(targetIndex, count)
                val windowItems = ArrayList<MediaItem>(end - start)
                for (i in start until end) {
                    val item = snapshot[i]
                    windowItems.add(if (i == targetIndex) resolvedItem else item)
                }
                preparedWindowStartIndex = start
                preparedPlayerUsesWindowedQueue = count > MAX_AUXILIARY_TIMELINE_ITEMS
                auxiliaryPlayer.setMediaItems(windowItems, targetIndex - start, startPositionMs.coerceAtLeast(0L))
            } else {
                // Fallback for single item if not found in current timeline
                resetPreparedWindowState()
                auxiliaryPlayer.setMediaItem(resolvedItem)
                auxiliaryPlayer.seekTo(startPositionMs.coerceAtLeast(0L))
            }

            auxiliaryPlayer.repeatMode = playerA.repeatMode
            auxiliaryPlayer.shuffleModeEnabled = playerA.shuffleModeEnabled
            resetPlaybackSpeedToNormal(auxiliaryPlayer)
            auxiliaryPlayer.prepare()
            auxiliaryPlayer.volume = 0f
            auxiliaryPlayer.pause()
            onNextPlayerPreparedListeners.forEach { it(auxiliaryPlayer) }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Exception) {
            resetPreparedWindowState()
            Timber.tag("TransitionDebug").e(e, "Failed to prepare next player")
        }
    }

    fun getPreparedNextMediaId(): String? {
        return playerB?.takeIf { it.mediaItemCount > 0 }?.currentMediaItem?.mediaId
    }

    fun cancelNext() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { cancelNext() }
            return
        }
        val shouldPublishMasterPlayer = transitionRunning
        transitionJob?.cancel()
        transitionJob = null
        transitionRunning = false
        resetPreparedWindowState()
        playerB?.let { auxiliaryPlayer ->
            try {
                resetPlaybackSpeedToNormal(auxiliaryPlayer)
                if (auxiliaryPlayer.mediaItemCount > 0) {
                    auxiliaryPlayer.stop()
                    auxiliaryPlayer.clearMediaItems()
                    auxiliaryPlayer.volume = 0f
                }
            } catch (e: Exception) { /* Ignore */ }
        }
        if (::playerA.isInitialized) {
            if (!skipFadeActive) {
                playerA.volume = if (isDucked) preDuckVolumeA * DUCK_FACTOR else 1f
            }
            if (shouldPublishMasterPlayer) {
                onPlayerSwappedListeners.forEach { it(playerA) }
            }
        }
        incomingTrackReplayGainVolume = null
        setPauseAtEndOfMediaItems(false)
        if (shouldPublishMasterPlayer) onTransitionFinishedListeners.forEach { it() }
    }

    fun performTransition(settings: TransitionSettings) {
        transitionJob?.cancel()
        transitionRunning = true
        transitionJob = scope.launch {
            try {
                performOverlapTransition(settings)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Timber.tag("TransitionDebug").e(e, "Error performing transition")
                    if (::playerA.isInitialized && !skipFadeActive) {
                        playerA.volume = if (isDucked) preDuckVolumeA * DUCK_FACTOR else 1f
                    }
                    setPauseAtEndOfMediaItems(false)
                    playerB?.stop()
                }
            } finally {
                if (transitionJob === currentCoroutineContext()[Job]) {
                    transitionJob = null
                    transitionRunning = false
                    incomingTrackReplayGainVolume = null
                    onTransitionFinishedListeners.forEach { it() }
                }
            }
        }
    }

    private suspend fun performOverlapTransition(settings: TransitionSettings) {
        val auxiliaryPlayer = playerB
        if (auxiliaryPlayer == null || auxiliaryPlayer.mediaItemCount == 0) {
            if (::playerA.isInitialized && !skipFadeActive) {
                playerA.volume = if (isDucked) preDuckVolumeA * DUCK_FACTOR else 1f
            }
            setPauseAtEndOfMediaItems(false)
            return
        }

        // Ensure auxiliaryPlayer's stream URI is still fresh before crossfading (e.g. if paused/idle for >15 min)
        auxiliaryPlayer.currentMediaItem?.let { bItem ->
            val bUri = bItem.localConfiguration?.uri
            if (bUri != null && (bUri.scheme == "http" || bUri.scheme == "https")) {
                val origUriStr = bItem.mediaMetadata.extras?.getString("com.unshoo.pixelmusic.external.CONTENT_URI") ?: bUri.toString()
                if (!isResolvedUriFresh(origUriStr, bUri)) {
                    Timber.tag("TransitionDebug").i("playerB stream URL stale before crossfade for %s, re-resolving...", origUriStr)
                    invalidateResolvedUri(origUriStr)
                    val sourceUri = Uri.parse(origUriStr)
                    if (sourceUri.scheme in REMOTE_MEDIA_SCHEMES) {
                        try {
                            val freshItem = withContext(Dispatchers.IO) {
                                val resolved = resolveMediaItem(bItem.buildUpon().setUri(sourceUri).build())
                                resolved.localConfiguration?.uri?.toString()?.let { u ->
                                    if (!LosslessStreamResolver.isLosslessUri(u) && !u.contains("monochrome.st/track/")) {
                                        val key = bItem.mediaId.removePrefix("youtube_").takeIf { it.isNotBlank() }
                                        if (u.startsWith("http")) preCacheFirstChunk(u, key)
                                    }
                                }
                                resolved
                            }
                            val bIdx = auxiliaryPlayer.currentMediaItemIndex
                            val bPos = auxiliaryPlayer.currentPosition
                            auxiliaryPlayer.replaceMediaItem(bIdx, freshItem)
                            auxiliaryPlayer.seekTo(bIdx, bPos)
                            auxiliaryPlayer.prepare()
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (e: Exception) {
                            Timber.tag("TransitionDebug").w(e, "Failed to refresh playerB stale stream before crossfade")
                        }
                    }
                }
            }
        }

        if (auxiliaryPlayer.playbackState == Player.STATE_IDLE) auxiliaryPlayer.prepare()
        if (auxiliaryPlayer.playbackState != Player.STATE_READY) {
            val isReady = if (auxiliaryPlayer.playbackState == Player.STATE_BUFFERING) {
                awaitPlayerReady(auxiliaryPlayer, 3000L)
            } else {
                false
            }
            if (!isReady) {
                Timber.tag("TransitionDebug").w("playerB not ready for transition (state=%d). Aborting and falling back to playerA.", auxiliaryPlayer.playbackState)
                if (!skipFadeActive) {
                    playerA.volume = if (isDucked) preDuckVolumeA * DUCK_FACTOR else 1f
                }
                setPauseAtEndOfMediaItems(false)

                val isOutgoingStalled = playerA.playbackState == Player.STATE_ENDED ||
                    playerA.playbackState == Player.STATE_BUFFERING ||
                    (!playerA.isPlaying && playerA.duration != C.TIME_UNSET && playerA.currentPosition >= playerA.duration - 40000L)
                if (isOutgoingStalled && playerA.hasNextMediaItem()) {
                    playerA.seekToNext()
                    playerA.prepare()
                    playerA.play()
                }
                return
            }
        }

        val outgoingStartVolume = (if (isDucked) preDuckVolumeA else playerA.volume).coerceIn(0f, 1f)
        auxiliaryPlayer.volume = 0f
        if (!playerA.isPlaying && playerA.playbackState == Player.STATE_READY) playerA.play()
        // Captured before play(): the fade-in is measured from here in the incoming player's own
        // media time, so it only advances once its audio is actually being rendered.
        val incomingStartPosMs = auxiliaryPlayer.currentPosition
        auxiliaryPlayer.playWhenReady = true
        auxiliaryPlayer.play()

        val outgoingPlayer = playerA
        val incomingPlayer = auxiliaryPlayer

        incomingPlayer.repeatMode = outgoingPlayer.repeatMode
        incomingPlayer.shuffleModeEnabled = outgoingPlayer.shuffleModeEnabled
        resetPlaybackSpeedToNormal(incomingPlayer)
        resetPlaybackSpeedToNormal(outgoingPlayer)
        outgoingPlayer.pauseAtEndOfMediaItems = true
        incomingPlayer.pauseAtEndOfMediaItems = false
        onTransitionDisplayPlayerListeners.forEach { listener ->
            runCatching { listener(incomingPlayer) }
                .onFailure { Timber.tag("TransitionDebug").e(it, "Could not publish incoming track") }
        }

        // Both envelopes are driven by each player's own playback position rather than a wall
        // clock started here. A wall clock drifts from what is audible: the incoming deck only
        // becomes audible some hundreds of ms after play() (AudioTrack start + buffer), and any
        // delay before this point (late trigger, stream refresh, waiting for READY) made the
        // outgoing track hit its end - and pause - while its fade was still running, i.e. a cut.
        // Position-driven: the fade-out ends exactly at the outgoing track's end, the fade-in
        // follows the incoming audio as it plays, and a pause freezes both.
        val requestedDuration = settings.durationMs.toLong().coerceAtLeast(500L)
        val outgoingStartPosMs = outgoingPlayer.currentPosition
        val outgoingDurationMs = outgoingPlayer.duration
        val outgoingEndPosMs = if (outgoingDurationMs != C.TIME_UNSET && outgoingDurationMs > outgoingStartPosMs) {
            outgoingDurationMs
        } else {
            outgoingStartPosMs + requestedDuration
        }
        val fadeMs = requestedDuration
            .coerceAtMost(outgoingEndPosMs - outgoingStartPosMs)
            .coerceAtLeast(200L)
        // The fade-out spans the last fadeMs of the outgoing track.
        val fadeOutStartPosMs = outgoingEndPosMs - fadeMs
        val stepMs = 32L
        // Safety net if a deck stalls (e.g. network buffering) so the swap still happens. Only
        // time while something is playing counts, so a user pause mid-fade doesn't trip it.
        val hardTimeoutMs = fadeMs + 5_000L
        var playingMs = 0L
        var lastTickMs = SystemClock.elapsedRealtime()
        Timber.tag("TransitionDebug").d(
            "Crossfade: fade=%dms, outgoing %d->%d ms, incoming starts at %d ms",
            fadeMs, outgoingStartPosMs, outgoingEndPosMs, incomingStartPosMs
        )

        while (true) {
            currentCoroutineContext().ensureActive()
            val outPos = outgoingPlayer.currentPosition
            val outgoingDone = outgoingPlayer.playbackState == Player.STATE_ENDED ||
                outgoingPlayer.playbackState == Player.STATE_IDLE ||
                outPos >= outgoingEndPosMs - 20L
            val progressOut = if (outgoingDone) 1f
                else ((outPos - fadeOutStartPosMs).toFloat() / fadeMs).coerceIn(0f, 1f)
            val progressIn = ((incomingPlayer.currentPosition - incomingStartPosMs).toFloat() / fadeMs)
                .coerceIn(0f, 1f)

            val volIn = envelope(progressIn, settings.curveIn)
            val volOut = 1f - envelope(progressOut, settings.curveOut)
            val incomingTarget = (incomingTrackReplayGainVolume ?: 1f).coerceIn(0f, 1f)
            val duck = if (isDucked) DUCK_FACTOR else 1f
            incomingPlayer.volume = (volIn * incomingTarget * duck).coerceIn(0f, 1f)
            outgoingPlayer.volume = (volOut * outgoingStartVolume * duck).coerceIn(0f, 1f)

            if (progressIn >= 1f && progressOut >= 1f) break
            val nowMs = SystemClock.elapsedRealtime()
            if (incomingPlayer.playWhenReady || outgoingPlayer.isPlaying) playingMs += nowMs - lastTickMs
            lastTickMs = nowMs
            if (playingMs >= hardTimeoutMs) {
                Timber.tag("TransitionDebug").w(
                    "Crossfade timed out (in=%.2f, out=%.2f); finishing swap", progressIn, progressOut
                )
                break
            }
            delay(stepMs)
        }

        val finalTarget = (incomingTrackReplayGainVolume ?: 1f).coerceIn(0f, 1f)
        if (isDucked) {
            preDuckVolumeA = finalTarget
        }
        outgoingPlayer.volume = 0f
        incomingPlayer.volume = if (isDucked) finalTarget * DUCK_FACTOR else finalTarget
        incomingTrackReplayGainVolume = null

        outgoingPlayer.removeListener(masterPlayerListener)
        outgoingPlayer.removeAnalyticsListener(masterPlayerListener)
        auxiliaryErrorListener?.let { runCatching { incomingPlayer.removeListener(it) } }
        auxiliaryErrorListener = null

        playerA = incomingPlayer
        playerB = outgoingPlayer
        activeWindowStartIndex = preparedWindowStartIndex
        activePlayerUsesWindowedQueue = preparedPlayerUsesWindowedQueue
        resetPreparedWindowState()
        currentMasterYoutubeId = playerA.currentMediaItem?.let(::youtubeIdOf)
        _isPlaybackBufferingState.value = playerA.playbackState == Player.STATE_BUFFERING

        playerA.pauseAtEndOfMediaItems = false
        playerB?.pauseAtEndOfMediaItems = false
        playerA.addListener(masterPlayerListener)
        playerA.addAnalyticsListener(masterPlayerListener)
        if (playerA.playWhenReady) requestAudioFocus()

        onPlayerSwappedListeners.forEach { it(playerA) }
        _activeAudioSessionId.value = currentAudioSessionId()

        playerB?.pause()
        playerB?.stop()
        playerB?.clearMediaItems()
        playerB?.volume = 0f
        resetPlaybackSpeedToNormal(playerB)
        playerB?.let { attachAuxiliaryErrorListener(it) }

        setPauseAtEndOfMediaItems(false)
    }

    private fun ensureQueueSnapshot(): List<MediaItem> {
        if (!activePlayerUsesWindowedQueue && queueSnapshot.size != playerA.mediaItemCount) {
            refreshQueueSnapshotFromMaster(windowStartIndex = 0, usesWindowedQueue = false)
        }
        if (queueSnapshot.isEmpty()) {
            refreshQueueSnapshotFromMaster(windowStartIndex = 0, usesWindowedQueue = false)
        }
        return queueSnapshot
    }

    private fun refreshQueueSnapshotFromMaster(windowStartIndex: Int, usesWindowedQueue: Boolean) {
        if (!::playerA.isInitialized) return

        val count = playerA.mediaItemCount
        if (count <= 0) {
            queueSnapshot = emptyList()
            activeWindowStartIndex = 0
            activePlayerUsesWindowedQueue = false
            return
        }

        val items = ArrayList<MediaItem>(count)
        for (i in 0 until count) {
            items.add(playerA.getMediaItemAt(i))
        }

        queueSnapshot = items
        activeWindowStartIndex = windowStartIndex
        activePlayerUsesWindowedQueue = usesWindowedQueue
    }

    private fun resolveCurrentAbsoluteIndex(mediaItem: MediaItem, snapshot: List<MediaItem>): Int {
        if (snapshot.isEmpty()) return C.INDEX_UNSET

        val playerIndex = playerA.currentMediaItemIndex
        if (activePlayerUsesWindowedQueue) {
            val absoluteIndex = activeWindowStartIndex + playerIndex
            if (absoluteIndex in snapshot.indices &&
                snapshot[absoluteIndex].mediaId == mediaItem.mediaId
            ) {
                return absoluteIndex
            }
        } else if (playerIndex in snapshot.indices &&
            snapshot[playerIndex].mediaId == mediaItem.mediaId
        ) {
            return playerIndex
        }

        return findMediaItemIndex(snapshot, mediaItem.mediaId, preferAfterExclusive = C.INDEX_UNSET)
    }

    private fun findMediaItemIndex(
        items: List<MediaItem>,
        mediaId: String,
        preferAfterExclusive: Int
    ): Int {
        var fallback = C.INDEX_UNSET
        for (i in items.indices) {
            if (items[i].mediaId == mediaId) {
                if (preferAfterExclusive != C.INDEX_UNSET && i > preferAfterExclusive) return i
                if (fallback == C.INDEX_UNSET) fallback = i
            }
        }
        return fallback
    }

    private fun auxiliaryWindowBounds(targetIndex: Int, count: Int): Pair<Int, Int> {
        if (count <= MAX_AUXILIARY_TIMELINE_ITEMS) return 0 to count

        val halfWindow = MAX_AUXILIARY_TIMELINE_ITEMS / 2
        var start = (targetIndex - halfWindow).coerceAtLeast(0)
        var end = (start + MAX_AUXILIARY_TIMELINE_ITEMS).coerceAtMost(count)
        start = (end - MAX_AUXILIARY_TIMELINE_ITEMS).coerceAtLeast(0)
        return start to end
    }

    private fun resetPreparedWindowState() {
        preparedWindowStartIndex = 0
        preparedPlayerUsesWindowedQueue = false
    }

    private suspend fun awaitPlayerReady(player: ExoPlayer, timeoutMs: Long): Boolean {
        if (player.playbackState == Player.STATE_READY) return true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState != Player.STATE_BUFFERING) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(playbackState == Player.STATE_READY)
                        }
                    }
                }
                player.addListener(listener)
                cont.invokeOnCancellation { player.removeListener(listener) }
            }
        } ?: false
    }

    private suspend fun awaitPlayerPlaying(player: ExoPlayer, timeoutMs: Long): Boolean {
        if (player.isPlaying) return true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(true)
                        }
                    }
                }
                player.addListener(listener)
                cont.invokeOnCancellation { player.removeListener(listener) }
            }
        } ?: false
    }

    fun release() {
        cancelSkipFade()
        transitionJob?.cancel()
        transitionJob = null
        transitionRunning = false
        incomingTrackReplayGainVolume = null
        preResolutionJob?.cancel()
        cancelAudioOffloadFallback()
        cancelPreCacheJobs()
        scope.coroutineContext[Job]?.cancel()
        abandonAudioFocus()
        isDucked = false
        isFocusLossPause = false
        if (::playerA.isInitialized) {
            playerA.removeListener(masterPlayerListener)
            playerA.removeAnalyticsListener(masterPlayerListener)
            onPlayerAboutToBeReleasedListener?.invoke(playerA)
            playerA.release()
        }
        playerB?.release()
        playerB = null
        resolvedUriCache.evictAll()
        activePlaybackResolvedUris.clear()
        localFilePathCache.evictAll()
        activeResolutions.clear()
        isReleased = true
    }
}

/** No playable stream could be resolved for a source (as opposed to a transient network error). */
internal class UnresolvableStreamException(message: String) : java.io.IOException(message)
