package com.unshoo.pixelmusic.data.remote.youtube

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.unshoo.pixelmusic.data.remote.youtube.queue.AutoplayRadio
import com.unshoo.pixelmusic.data.remote.youtube.queue.ResilientRadioQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.absoluteValue
import com.unshoo.pixelmusic.utils.YouTubeIdUtils

import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.WatchEndpoint
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.utils.ContentFilterUtils
import com.unshoo.pixelmusic.data.lossless.audiosource.TitleMatch
import com.unshoo.pixelmusic.utils.MediaItemBuilder

object AutoQueueManager {
    private const val TAG = "AutoQueueMgr"
    /** Auto-queue keeps at least this many songs ahead of the playing one (it asks for more below it). */
    private const val AUTOPLAY_AHEAD = 10
    /** Songs auto-queue hands the player at a time. */
    private const val AUTOPLAY_BATCH = 20
    /** Answers that add nothing before auto-queue stops asking for one song. */
    private const val AUTOPLAY_RETRIES = 3
    private const val AUTOPLAY_RETRY_DELAY_MS = 1_500L
    private const val STARTUP_SAFETY_CHECK_DELAY_MS = 2_500L
    private const val SETTINGS_READ_TIMEOUT_MS = 2_000L

    /** A radio song: its video id and the item the player gets. */
    private data class Candidate(val videoId: String, val item: MediaItem)

    /** A radio the user started (its songs already fill the queue): auto-queue carries it on. */
    private data class Resume(val endpoint: WatchEndpoint, val continuation: String?, val videoId: String)

    /** The running radio (as in PixelMusicDesktop); only touched while [refillMutex] is held. */
    private val radio = AutoplayRadio<Candidate>(
        pages = AutoplayRadio.RadioPages { endpoint, continuation -> fetchRadioPage(endpoint, continuation) },
        idOf = { it.videoId },
    )
    private var localQueue: ResilientRadioQueue? = null
    private var radioGeneration = -1L

    /** Bumped by [reset]/[seed]: a refill started before drops its answer and the radio starts over. */
    @Volatile private var generation = 0L
    @Volatile private var pendingResume: Resume? = null
    /** The song auto-queue was last asked for (once per song). */
    @Volatile private var askedFor: String? = null
    /** The song asked for and how many of its answers added nothing (each is asked again, up to AUTOPLAY_RETRIES). */
    @Volatile private var emptyFor: Pair<String, Int>? = null

    private val refillMutex = Mutex()

    private val internalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scope: CoroutineScope? = null
    private var contextRef: Context? = null
    private var datastoreRepository: DatastoreRepository? = null
    private var userPreferencesRepository: UserPreferencesRepository? = null

    // playerRef is kept ONLY for listener add/remove bookkeeping (you must call
    // addListener/removeListener on the exact instance that has the listener).
    // It is NOT used for reading state anymore — see livePlayer() below.
    private var playerRef: Player? = null

    // The dual-player crossfade engine swaps which underlying ExoPlayer instance is
    // "master" on every single transition (see DualPlayerEngine: playerA becomes
    // playerB and vice-versa), and notifies listeners of that swap through several
    // conditional layers (cast-session checks, a pre-crossfade "display player"
    // publish, a post-crossfade publish). If ANY of those layers is delayed or
    // skipped for a given transition, a cached player reference goes stale — and the
    // old instance gets stop()+clearMediaItems()'d moments later. Reading state from
    // a stale reference silently "succeeds" while operating on a dead player nobody
    // is listening to, which looks exactly like "the queue randomly stops
    // populating." playerProvider() sidesteps this entirely: every state read and
    // every addMediaItems() call asks "what is the live player RIGHT NOW", so it is
    // correct even if the swap-notification pipeline is late, skipped, or racy.
    private var playerProvider: (() -> Player)? = null
    private fun livePlayer(): Player? = playerProvider?.invoke() ?: playerRef

    private var musicDaoRef: MusicDao? = null
    private var engagementDaoRef: com.unshoo.pixelmusic.data.database.EngagementDao? = null
    private var onQueueItemsAddedCallback: (() -> Unit)? = null

    @Volatile private var lastReseedRequestAtMs = 0L
    @Volatile private var lastTrimRequestAtMs = 0L
    private const val DUPLICATE_CALL_DEBOUNCE_MS = 400L

    private fun getActiveScope(): CoroutineScope {
        val s = scope
        return if (s != null && s.isActive) s else internalScope
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            Timber.tag(TAG).d("onMediaItemTransition reason=%d mediaId=%s", reason, mediaItem.mediaId)
            checkAndRefill()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                Timber.tag(TAG).d("onTimelineChanged(PLAYLIST_CHANGED), windowCount=%d", timeline.windowCount)
                checkAndRefill()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                Timber.tag(TAG).d("STATE_ENDED — forcing immediate refill")
                checkAndRefill(forceImmediate = true)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) checkAndRefill()
        }
    }

    /**
     * @param playerProvider Returns whichever player is CURRENTLY the live/master
     *   player at the moment it's called. For a simple single-player setup this can
     *   just be `{ player }`; for a crossfade dual-player engine it should be
     *   `{ engine.masterPlayer }` so every read reflects reality even between swaps.
     */
    fun attach(
        playerProvider: () -> Player,
        context: Context,
        datastoreRepo: DatastoreRepository,
        coroutineScope: CoroutineScope,
        musicDao: MusicDao,
        engagementDao: com.unshoo.pixelmusic.data.database.EngagementDao,
        onQueueItemsAdded: (() -> Unit)? = null,
        userPreferencesRepo: UserPreferencesRepository? = null
    ) {
        runOnMain {
            scope = coroutineScope
            contextRef = context.applicationContext
            datastoreRepository = datastoreRepo
            userPreferencesRepository = userPreferencesRepo
            this.playerProvider = playerProvider
            val initialPlayer = playerProvider()
            playerRef = initialPlayer
            musicDaoRef = musicDao
            engagementDaoRef = engagementDao
            onQueueItemsAddedCallback = onQueueItemsAdded
            initialPlayer.addListener(playerListener)
            Timber.tag(TAG).d("Attached to player")

            checkAndRefill()

            getActiveScope().launch(Dispatchers.IO) {
                delay(STARTUP_SAFETY_CHECK_DELAY_MS)
                checkAndRefill()
            }
        }
    }

    /**
     * Legacy attach overload for single Player reference.
     */
    fun attach(
        player: Player,
        context: Context,
        datastoreRepo: DatastoreRepository,
        coroutineScope: CoroutineScope,
        musicDao: MusicDao,
        engagementDao: com.unshoo.pixelmusic.data.database.EngagementDao,
        onQueueItemsAdded: (() -> Unit)? = null
    ) {
        attach(
            playerProvider = { player },
            context = context,
            datastoreRepo = datastoreRepo,
            coroutineScope = coroutineScope,
            musicDao = musicDao,
            engagementDao = engagementDao,
            onQueueItemsAdded = onQueueItemsAdded
        )
    }

    /**
     * Re-points the listener after a player swap (crossfade transition, cast
     * connect/disconnect, etc). Only affects listener bookkeeping — state reads
     * already go through [livePlayer] and don't depend on this having fired yet,
     * so a late or occasionally-skipped swap notification can no longer cause a
     * refill to silently operate on a dead player.
     */
    fun updatePlayer(newPlayer: Player) {
        runOnMain {
            val oldPlayer = playerRef
            if (oldPlayer !== newPlayer) {
                oldPlayer?.removeListener(playerListener)
                playerRef = newPlayer
                newPlayer.addListener(playerListener)
                Timber.tag(TAG).d("Player updated")
            }
        }
    }

    fun detach(player: Player?) {
        runOnMain {
            playerRef?.removeListener(playerListener)
            player?.removeListener(playerListener)
            playerRef = null
            playerProvider = null
            scope = null
            contextRef = null
            datastoreRepository = null
            musicDaoRef = null
            engagementDaoRef = null
            onQueueItemsAddedCallback = null
            Timber.tag(TAG).d("Detached")
        }
    }

    fun reset() {
        Timber.tag(TAG).d("reset() called")
        pendingResume = null
        askedFor = null
        emptyFor = null
        generation++
    }

    fun disableAndTrimQueue() {
        val now = System.currentTimeMillis()
        if (now - lastTrimRequestAtMs < DUPLICATE_CALL_DEBOUNCE_MS) {
            Timber.tag(TAG).d("disableAndTrimQueue() ignored — duplicate call within %d ms", DUPLICATE_CALL_DEBOUNCE_MS)
            return
        }
        lastTrimRequestAtMs = now
        reset()
        getActiveScope().launch(Dispatchers.Main) {
            val player = livePlayer() ?: return@launch
            val totalCount = player.mediaItemCount
            val currentIndex = player.currentMediaItemIndex
            if (totalCount <= currentIndex + 1) return@launch
            player.removeMediaItems(currentIndex + 1, totalCount)
            Timber.tag(TAG).d("disableAndTrimQueue removed %d upcoming tracks", totalCount - currentIndex - 1)
        }
    }

    fun resetAndReseedFromCurrentSong() {
        val now = System.currentTimeMillis()
        if (now - lastReseedRequestAtMs < DUPLICATE_CALL_DEBOUNCE_MS) {
            Timber.tag(TAG).d("resetAndReseedFromCurrentSong() ignored — duplicate call within %d ms", DUPLICATE_CALL_DEBOUNCE_MS)
            return
        }
        lastReseedRequestAtMs = now
        Timber.tag(TAG).d("resetAndReseedFromCurrentSong()")
        reset()
        checkAndRefill()
    }

    /**
     * A radio the user started: its first songs are in the queue and [continuation] is the page after them.
     * Auto-queue carries that radio on instead of starting a new one from the playing song.
     */
    fun seed(endpoint: WatchEndpoint, continuation: String?, videoId: String) {
        Timber.tag(TAG).d("seed() videoId=%s hasContinuation=%b", videoId, continuation != null)
        pendingResume = Resume(endpoint, continuation, videoId)
        askedFor = null
        emptyFor = null
        generation++
    }

    /**
     * Ask again for the playing song (after a new queue was built). [forceRefresh] drops the running radio so
     * the playing song's own radio follows.
     */
    fun scheduleAdaptiveRefill(delayMs: Long? = null, forceRefresh: Boolean = false) {
        getActiveScope().launch(Dispatchers.IO) {
            if (delayMs != null && delayMs > 0L) delay(delayMs)
            if (forceRefresh) reset() else askedFor = null
            checkAndRefill()
        }
    }

    fun scheduleRefill(delayMs: Long = 0L, forceRefresh: Boolean = false) {
        scheduleAdaptiveRefill(delayMs = if (delayMs > 0L) delayMs else null, forceRefresh = forceRefresh)
    }

    fun forceRefill(forceRefresh: Boolean) {
        scheduleAdaptiveRefill(delayMs = null, forceRefresh = forceRefresh)
    }

    fun registerSkip(songId: String, artistName: String? = null) {
        checkAndRefill()
    }

    suspend fun buildMixQueue(seedSong: Song, onlineRelated: List<Song>): List<Song> {
        if (onlineRelated.isEmpty()) return listOf(seedSong)

        val notInterestedIds = runCatching { userPreferencesRepository?.notInterestedSongIdsFlow?.first() }.getOrNull() ?: emptySet()
        val seedNormTitle = TitleMatch.normalize(seedSong.title)
        val seedNormArtist = TitleMatch.normalize(seedSong.artist)

        val seenIds = HashSet<String>().apply { addAll(seedSong.idVariants()) }
        val seenSignatures = HashSet<String>().apply {
            if (seedNormTitle.isNotBlank() && seedNormArtist.isNotBlank()) add("$seedNormTitle|$seedNormArtist")
        }

        val filtered = onlineRelated.filter { s ->
            val ids = s.idVariants()
            if (ids.any { it in notInterestedIds || it in seenIds }) return@filter false

            val sNormTitle = TitleMatch.normalize(s.title)
            val sNormArtist = TitleMatch.normalize(s.artist)

            if (sNormTitle.isNotBlank() && sNormTitle == seedNormTitle) {
                val artistMatch = sNormArtist.isBlank() || seedNormArtist.isBlank() ||
                    sNormArtist == seedNormArtist || sNormArtist.contains(seedNormArtist) ||
                    seedNormArtist.contains(sNormArtist) || TitleMatch.ratio(seedSong.artist, s.artist) >= 0.75
                if (artistMatch) return@filter false
            }

            val sig = if (sNormTitle.isNotBlank() && sNormArtist.isNotBlank()) "$sNormTitle|$sNormArtist" else null
            if (sig != null && !seenSignatures.add(sig)) return@filter false

            seenIds.addAll(ids)
            true
        }

        return listOf(seedSong) + filtered
    }

    private fun Song.idVariants(): List<String> {
        val clean = id.removePrefix("youtube_").removePrefix("monochrome_")
        val yt = youtubeId ?: if (contentUriString.startsWith("youtube://")) contentUriString.removePrefix("youtube://") else null
        return listOfNotNull(id, clean, yt, yt?.let { "youtube_$it" })
    }

    fun pruneNotInterestedFromQueue(songId: String, videoId: String? = null) {
        getActiveScope().launch(Dispatchers.Main) {
            val player = livePlayer() ?: return@launch
            val totalCount = player.mediaItemCount
            val currentIndex = player.currentMediaItemIndex
            if (currentIndex < 0 || totalCount <= currentIndex + 1) return@launch
            for (i in (totalCount - 1) downTo (currentIndex + 1)) {
                val item = player.getMediaItemAt(i)
                val itemVideoId = YouTubeIdUtils.extractVideoId(item.mediaId)
                if (item.mediaId == songId || (videoId != null && (item.mediaId == videoId || itemVideoId == videoId))) {
                    player.removeMediaItem(i)
                    Timber.tag(TAG).d("Pruned not interested song from upcoming queue at index %d: %s", i, item.mediaId)
                }
            }
        }
    }

    /**
     * The queue is about to run out: ask once per song for the radio that keeps it going (PixelMusicDesktop's
     * autoplay) — while fewer than [AUTOPLAY_AHEAD] songs are left after the playing one and repeat is off.
     */
    private fun checkAndRefill(forceImmediate: Boolean = false) {
        getActiveScope().launch(Dispatchers.IO) {
            try {
                if (!isAutoQueueEnabled()) return@launch
                val seedMediaId = withContext(Dispatchers.Main) {
                    val p = livePlayer() ?: return@withContext null
                    val current = p.currentMediaItem?.mediaId ?: return@withContext null
                    if (p.repeatMode != Player.REPEAT_MODE_OFF) return@withContext null
                    if (computeRemainingUpcoming(p) >= AUTOPLAY_AHEAD) return@withContext null
                    if (!forceImmediate && askedFor == current) return@withContext null
                    askedFor = current
                    current
                } ?: return@launch
                refillMutex.withLock { refillLocked(seedMediaId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "checkAndRefill error")
            }
        }
    }

    private suspend fun refillLocked(seedMediaId: String) {
        val gen = generation
        if (radioGeneration != gen) {
            radio.clear()
            localQueue = null
            radioGeneration = gen
        }
        val snap = withContext(Dispatchers.Main) { livePlayer()?.let(::snapshotOf) } ?: return
        // another song plays by now (it asks for itself), or the queue filled meanwhile
        if (snap.currentMediaId != seedMediaId || snap.remaining >= AUTOPLAY_AHEAD) return

        pendingResume?.let { resume ->
            pendingResume = null
            radio.resume(resume.endpoint, resume.continuation, snap.queueVideoIds.toList() + resume.videoId)
        }

        val filters = loadFilters()
        val videoId = resolveVideoId(seedMediaId)
        val items: List<MediaItem> = if (videoId != null) {
            radio.nextBatch(videoId, AUTOPLAY_BATCH) { passes(it.item, snap, filters) }.map { it.item }
        } else {
            // an on-device song with no YouTube id: songs related to it from the library
            localFallback(seedMediaId).filter { passes(it, snap, filters) }
        }
        if (gen != generation) {
            Timber.tag(TAG).d("refill for %s dropped: the queue changed meanwhile", seedMediaId)
            // the new queue (or seeded radio) asks for itself once this refill lets go of the lock
            checkAndRefill()
            return
        }

        if (items.isNotEmpty()) {
            emptyFor = null
            withContext(Dispatchers.Main) {
                if (gen != generation) return@withContext
                livePlayer()?.addMediaItems(items)
                onQueueItemsAddedCallback?.invoke()
            }
            Timber.tag(TAG).d("auto-queue after %s: %d songs", seedMediaId, items.size)
            return
        }

        // nothing new (all already listed, filtered, or an empty page) while that song still plays: ask again
        val tries = emptyFor?.takeIf { it.first == seedMediaId }?.second ?: 0
        Timber.tag(TAG).w("auto-queue after %s added nothing (try %d)", seedMediaId, tries + 1)
        if (tries < AUTOPLAY_RETRIES) {
            emptyFor = seedMediaId to tries + 1
            if (askedFor == seedMediaId) askedFor = null
            getActiveScope().launch(Dispatchers.IO) {
                delay(AUTOPLAY_RETRY_DELAY_MS)
                checkAndRefill()
            }
        }
    }

    /** One page of a song's YouTube Music radio. */
    private suspend fun fetchRadioPage(endpoint: WatchEndpoint, continuation: String?): AutoplayRadio.Page<Candidate> =
        withContext(Dispatchers.IO) {
            var result = YouTube.next(endpoint, continuation).getOrThrow()
            // a song radio holding only the song itself: that song's plain watch queue instead
            val videoId = endpoint.videoId
            if (continuation == null && result.items.size <= 1 && videoId != null &&
                endpoint.playlistId?.startsWith("RDAMVM") == true
            ) {
                YouTube.next(WatchEndpoint(videoId = videoId), null).getOrNull()
                    ?.takeIf { it.items.size > 1 }
                    ?.let { result = it }
            }
            AutoplayRadio.Page(
                items = result.items.map { Candidate(it.id, MediaItemBuilder.build(it.toNativeSong())) },
                continuation = result.continuation,
                endpoint = result.endpoint,
            )
        }

    private suspend fun localFallback(seedMediaId: String): List<MediaItem> {
        val dao = musicDaoRef ?: return emptyList()
        val queue = localQueue ?: ResilientRadioQueue.radio(videoId = null, musicDao = dao, isOnline = ::hasInternet).also {
            localQueue = it
        }
        val entity = withContext(Dispatchers.IO) {
            val longId = seedMediaId.toLongOrNull() ?: getDatabaseIdForYoutubeId(normalizeId(seedMediaId))
            runCatching { dao.getSongByIdOnce(longId) }.getOrNull()
        }
        queue.updateLocalSeed(entity)
        return runCatching { queue.nextPage() }.getOrElse {
            if (it is CancellationException) throw it
            emptyList()
        }
    }

    private class Filters(
        val coverLofi: Boolean,
        val keywords: List<String>,
        val pureYtMusic: Boolean,
        val dislikedYtIds: Set<String>,
        val dislikedSongIds: Set<String>,
        val notInterestedIds: Set<String>,
    )

    private suspend fun loadFilters(): Filters {
        val prefs = userPreferencesRepository
        return Filters(
            coverLofi = runCatching { prefs?.filterCoverAndLofiFlow?.first() }.getOrNull() ?: true,
            keywords = runCatching { prefs?.filterKeywordsFlow?.first() }.getOrNull() ?: ContentFilterUtils.DEFAULT_FILTER_KEYWORDS,
            pureYtMusic = runCatching { prefs?.pureYtMusicOnlyFlow?.first() }.getOrNull() ?: true,
            dislikedYtIds = runCatching { musicDaoRef?.getDislikedYoutubeIds()?.toSet() }.getOrNull() ?: emptySet(),
            dislikedSongIds = runCatching { musicDaoRef?.getDislikedSongIds()?.map { it.toString() }?.toSet() }.getOrNull() ?: emptySet(),
            notInterestedIds = runCatching { prefs?.notInterestedSongIdsFlow?.first() }.getOrNull() ?: emptySet(),
        )
    }

    /** Not already in the queue, not disliked / not interested, and allowed by the content filters. */
    private fun passes(item: MediaItem, snap: QueueSnapshot, filters: Filters): Boolean {
        val ytId = YouTubeIdUtils.extractVideoId(item.mediaId)
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val artist = item.mediaMetadata.artist?.toString().orEmpty()
        val sig = signature(title, artist)
        val ids = listOf(item.mediaId, item.mediaId.removePrefix("youtube_"))
        if (ids.any { it in snap.mediaIds || it in filters.dislikedSongIds || it in filters.notInterestedIds }) return false
        if (ytId.isNotBlank() &&
            (ytId in snap.queueVideoIds || ytId in filters.dislikedYtIds || ytId in filters.notInterestedIds)
        ) return false
        if (sig != null && sig in snap.signatures) return false
        if (filters.coverLofi &&
            ContentFilterUtils.isCoverOrLofi(title, artist, item.mediaMetadata.albumTitle?.toString().orEmpty(), filterKeywords = filters.keywords)
        ) return false
        if (filters.pureYtMusic && ContentFilterUtils.isVideoTitle(title)) return false
        return true
    }

    private fun signature(title: String, artist: String): String? {
        val t = title.trim().lowercase()
        val a = artist.trim().lowercase()
        return if (t.isNotBlank() && a.isNotBlank()) "$t|$a" else null
    }

    /** What the refill needs of the player, read on the main thread. */
    private fun snapshotOf(p: Player): QueueSnapshot? {
        val current = p.currentMediaItem?.mediaId ?: return null
        val mediaIds = HashSet<String>(p.mediaItemCount * 2)
        val videoIds = LinkedHashSet<String>(p.mediaItemCount)
        val signatures = HashSet<String>(p.mediaItemCount)
        for (i in 0 until p.mediaItemCount) {
            val mi = p.getMediaItemAt(i)
            mediaIds += mi.mediaId
            mediaIds += mi.mediaId.removePrefix("youtube_")
            val fromId = YouTubeIdUtils.extractVideoId(mi.mediaId)
            val fromUri = mi.localConfiguration?.uri?.toString()?.let(YouTubeIdUtils::extractVideoId).orEmpty()
            when {
                fromId.length == 11 -> videoIds += fromId
                fromUri.length == 11 -> videoIds += fromUri
            }
            signature(mi.mediaMetadata.title?.toString().orEmpty(), mi.mediaMetadata.artist?.toString().orEmpty())
                ?.let { signatures += it }
        }
        return QueueSnapshot(current, computeRemainingUpcoming(p), mediaIds, videoIds, signatures)
    }

    private suspend fun resolveVideoId(mediaId: String): String? {
        val extractedDirect = YouTubeIdUtils.extractVideoId(mediaId)
        if (extractedDirect.length == 11 && extractedDirect.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return extractedDirect
        }
        val clean = normalizeId(mediaId)
        if (clean.length == 11 && clean.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return clean

        // Check if live player holds this item with a content URI in extras or local configuration
        val playerUri = withContext(Dispatchers.Main) {
            val p = livePlayer() ?: return@withContext null
            val cur = p.currentMediaItem
            if (cur != null && (cur.mediaId == mediaId || cur.mediaId == clean)) {
                cur.mediaMetadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)
                    ?: cur.localConfiguration?.uri?.toString()
            } else {
                for (i in 0 until p.mediaItemCount) {
                    val item = p.getMediaItemAt(i)
                    if (item.mediaId == mediaId || item.mediaId == clean) {
                        return@withContext item.mediaMetadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)
                            ?: item.localConfiguration?.uri?.toString()
                    }
                }
                null
            }
        }
        if (!playerUri.isNullOrBlank()) {
            val fromUri = YouTubeIdUtils.extractVideoId(playerUri)
            if (fromUri.length == 11 && fromUri.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
                return fromUri
            }
        }

        val dao = musicDaoRef ?: return null
        val longId = mediaId.toLongOrNull() ?: return null
        val entity = runCatching { dao.getSongByIdOnce(longId) }.getOrNull() ?: return null
        val uri = entity.contentUriString
        val fromEntity = YouTubeIdUtils.extractVideoId(uri)
        if (fromEntity.length == 11 && fromEntity.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return fromEntity
        }
        return if (uri.startsWith("youtube://")) uri.removePrefix("youtube://") else null
    }

    private fun normalizeId(id: String): String {
        var clean = id.trim()
        if (clean.startsWith("song:")) {
            clean = clean.removePrefix("song:")
        }
        return when {
            clean.startsWith("youtube_") -> clean.substringAfter("youtube_")
            clean.startsWith("youtube://") -> clean.substringAfter("youtube://")
            clean.startsWith("monochrome_") -> clean.substringAfter("monochrome_")
            else -> clean
        }
    }

    /**
     * True while the user is playing on-device music (local files / downloaded playlist) or
     * Local Mode is on. Online radio songs have no audio offline, so they must never be
     * appended to such a queue.
     */
    @Volatile
    var offlineSessionActive: Boolean = false

    private suspend fun isAutoQueueEnabled(): Boolean {
        if (offlineSessionActive) return false
        val localMode = runCatching {
            withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) { userPreferencesRepository?.localModeEnabledFlow?.first() }
        }.getOrNull() ?: false
        if (localMode) return false
        val repo = datastoreRepository ?: return true
        return withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) {
            repo.settings.first().autoQueueEnabled
        } ?: true
    }

    private fun hasInternet(): Boolean {
        val ctx = contextRef ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) {
            false
        }
    }

    fun computeRemainingUpcoming(player: Player): Int {
        val timeline = player.currentTimeline
        val count = timeline.windowCount
        if (count == 0) return 0
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET) return 0
        if (!player.shuffleModeEnabled) {
            return (count - currentIndex - 1).coerceAtLeast(0)
        }
        var remaining = 0
        var windowIndex = timeline.getNextWindowIndex(currentIndex, Player.REPEAT_MODE_OFF, true)
        while (windowIndex != C.INDEX_UNSET && remaining < count) {
            remaining++
            windowIndex = timeline.getNextWindowIndex(windowIndex, Player.REPEAT_MODE_OFF, true)
        }
        return remaining
    }

    fun getDatabaseIdForYoutubeId(youtubeId: String): Long {
        return YouTubeIdUtils.toUnifiedYoutubeSongId(youtubeId)
    }
}

private data class QueueSnapshot(
    val currentMediaId: String,
    val remaining: Int,
    val mediaIds: Set<String>,
    /** video ids of the queue, in queue order */
    val queueVideoIds: Set<String>,
    val signatures: Set<String>,
)
