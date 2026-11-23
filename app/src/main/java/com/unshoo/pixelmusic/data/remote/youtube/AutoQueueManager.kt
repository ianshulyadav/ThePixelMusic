package com.unshoo.pixelmusic.data.remote.youtube

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.unshoo.pixelmusic.data.remote.youtube.queue.EmptyQueue
import com.unshoo.pixelmusic.data.remote.youtube.queue.Queue
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.absoluteValue
import com.unshoo.pixelmusic.utils.YouTubeIdUtils
import com.unshoo.pixelmusic.data.remote.youtube.queue.SmartQueue
import com.unshoo.pixelmusic.data.remote.youtube.queue.SongLanguage
import com.unshoo.pixelmusic.data.database.toSong

import unshoo.ianshulyadav.pixelmusic.innertube.models.WatchEndpoint
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.utils.ContentFilterUtils
import com.unshoo.pixelmusic.data.lossless.audiosource.TitleMatch
import com.unshoo.pixelmusic.utils.MediaItemBuilder

object AutoQueueManager {
    private const val TAG = "AutoQueueMgr"
    private const val REFILL_THRESHOLD = 5
    private const val TARGET_UPCOMING_COUNT = 25
    private const val MAX_REFILL_BATCH_ATTEMPTS = 5
    private const val STARTUP_SAFETY_CHECK_DELAY_MS = 2_500L
    private const val SETTINGS_READ_TIMEOUT_MS = 2_000L

    @Volatile private var currentQueue: Queue = EmptyQueue
    private val sessionQueuedIds = LinkedHashSet<String>()

    private val refillMutex = Mutex()
    @Volatile private var refillRequestedAgain = false

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
        currentQueue = EmptyQueue
        if (sessionQueuedIds.size > 100) {
            val keep = sessionQueuedIds.toList().takeLast(30)
            sessionQueuedIds.clear()
            sessionQueuedIds.addAll(keep)
        }
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
        getActiveScope().launch(Dispatchers.IO) {
            try {
                if (!isAutoQueueEnabled()) {
                    Timber.tag(TAG).d("AutoQueue disabled, skipping reseed")
                    return@launch
                }
                val currentId = withContext(Dispatchers.Main) { livePlayer()?.currentMediaItem?.mediaId } ?: return@launch
                seedFromMediaId(currentId)
                checkAndRefill(forceImmediate = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "resetAndReseedFromCurrentSong failed")
            }
        }
    }

    fun seed(endpoint: WatchEndpoint, continuation: String?, videoId: String) {
        val dao = musicDaoRef ?: return
        currentQueue = ResilientRadioQueue.resumed(
            endpoint = endpoint,
            continuation = continuation,
            musicDao = dao,
            isOnline = ::hasInternet,
        )
        Timber.tag(TAG).d("seed() videoId=%s hasContinuation=%b", videoId, continuation != null)
        getActiveScope().launch(Dispatchers.IO) { ensureLocalSeed(videoId) }
    }

    fun scheduleAdaptiveRefill(delayMs: Long? = null, forceRefresh: Boolean = false) {
        getActiveScope().launch(Dispatchers.IO) {
            if (delayMs != null && delayMs > 0L) delay(delayMs)
            if (forceRefresh) {
                val currentId = withContext(Dispatchers.Main) { livePlayer()?.currentMediaItem?.mediaId }
                if (currentId != null) seedFromMediaId(currentId)
            }
            doRefill()
        }
    }

    fun scheduleRefill(delayMs: Long = 0L, forceRefresh: Boolean = false) {
        scheduleAdaptiveRefill(delayMs = if (delayMs > 0L) delayMs else null, forceRefresh = forceRefresh)
    }

    fun forceRefill(forceRefresh: Boolean) {
        scheduleAdaptiveRefill(delayMs = null, forceRefresh = forceRefresh)
    }

    /**
     * Quick skip (< 15 s). Spotify-style learning: saved per-song skip history, an artist
     * skipped twice this session drops out of the upcoming auto-queue, and three skips in a
     * row rebuild the radio from the last song the user actually finished.
     */
    fun registerSkip(songId: String, artistName: String? = null) {
        val ctx = contextRef
        getActiveScope().launch(Dispatchers.IO) {
            runCatching {
                ctx?.let { SmartQueue.recordSkip(it, keyOfSongId(songId)) }
                val action = SmartQueue.onSkipped(artistName)
                android.util.Log.w("SmartQueue", "skip: ${artistName.orEmpty()} -> $action")
                when (action) {
                    is SmartQueue.SkipAction.DropArtist -> withContext(Dispatchers.Main) {
                        removeUpcomingAutoQueued { SmartQueue.primaryArtist(it.mediaMetadata.artist?.toString()) == action.artist }
                    }
                    is SmartQueue.SkipAction.Resteer -> {
                        Timber.tag(TAG).d("3 skips in a row: re-steering radio from %s", action.seedVideoId)
                        withContext(Dispatchers.Main) { removeUpcomingAutoQueued { true } }
                        seedRadioFromVideoId(action.seedVideoId)
                    }
                    SmartQueue.SkipAction.None -> Unit
                }
            }.onFailure { Timber.tag(TAG).w(it, "registerSkip failed") }
            checkAndRefill()
        }
    }

    /** A song listened to properly: positive signal for the session and the skip history. */
    fun registerCompletion(songId: String, artistName: String?) {
        val ctx = contextRef
        getActiveScope().launch(Dispatchers.IO) {
            val key = keyOfSongId(songId)
            SmartQueue.onCompleted(key.takeIf(SmartQueue::isVideoId), artistName)
            android.util.Log.w("SmartQueue", "listened: ${artistName.orEmpty()}")
            ctx?.let { SmartQueue.recordCompletion(it, key) }
        }
    }

    private fun keyOfSongId(songId: String): String =
        YouTubeIdUtils.extractVideoId(songId).takeIf(SmartQueue::isVideoId) ?: songId

    /** Removes upcoming songs that the auto-queue added (never the user's own queue). */
    private fun removeUpcomingAutoQueued(match: (MediaItem) -> Boolean) {
        val p = livePlayer() ?: return
        val autoIds = synchronized(sessionQueuedIds) { HashSet(sessionQueuedIds) }
        var removed = 0
        for (i in p.mediaItemCount - 1 downTo p.currentMediaItemIndex + 1) {
            val item = p.getMediaItemAt(i)
            val isAuto = item.mediaId in autoIds || SmartQueue.keyOf(item) in autoIds
            if (isAuto && match(item)) {
                p.removeMediaItem(i)
                removed++
            }
        }
        if (removed > 0) Timber.tag(TAG).d("Removed %d upcoming auto-queued items", removed)
    }

    private suspend fun seedRadioFromVideoId(videoId: String) {
        val dao = musicDaoRef ?: return
        currentQueue = ResilientRadioQueue.radio(videoId = videoId, musicDao = dao, isOnline = ::hasInternet)
            .also { it.anchorProvider = SmartQueue::nextAnchor }
        ensureLocalSeed(videoId)
    }

    /**
     * Starts a SmartQueue session for a new listening context. If the user was playing a
     * playlist or album (several songs queued), a few of its songs become extra radio seeds so
     * autoplay continues the playlist's vibe, not just its last song's.
     */
    private suspend fun startSmartSession(currentMediaId: String) {
        data class Start(val seed: String?, val lang: String, val playlistSeeds: List<String>)
        val start = withContext(Dispatchers.Main) {
            val p = livePlayer() ?: return@withContext Start(null, "", emptyList())
            val cur = p.currentMediaItem
            val seed = cur?.let(SmartQueue::keyOf)?.takeIf(SmartQueue::isVideoId)
            val seedLang = cur?.let(SmartQueue::languageOf).orEmpty()
            val count = p.mediaItemCount
            // Playlist/album seeds: only songs in the seed's language, so a mixed list
            // (e.g. Quick Picks) can't pull autoplay into other languages. When the seed's
            // language is unknown, only songs whose language is also unknown qualify.
            val others = if (count >= 3) {
                val step = maxOf(1, count / 4)
                (0 until count step step).map { p.getMediaItemAt(it) }
                    .filter { item ->
                        val l = SmartQueue.languageOf(item)
                        if (seedLang.isEmpty()) l.isEmpty() else SongLanguage.compatible(seedLang, l)
                    }
                    .map(SmartQueue::keyOf)
                    .filter(SmartQueue::isVideoId)
            } else emptyList()
            Start(seed, seedLang, others)
        }
        SmartQueue.startSession(
            start.seed ?: keyOfSongId(currentMediaId).takeIf(SmartQueue::isVideoId),
            start.playlistSeeds,
            start.lang,
        )
        android.util.Log.w("SmartQueue", "session: lang=${start.lang.ifEmpty { "?" }} playlistSeeds=${start.playlistSeeds.size}")
    }

    // Favourites pool for the 1-in-4 mix; rebuilt at most every 10 minutes (one bounded query each).
    @Volatile private var favoritesCache: Pair<Long, Pair<List<MediaItem>, Set<String>>>? = null

    private suspend fun favoritesAndLikes(): Pair<List<MediaItem>, Set<String>> {
        val now = System.currentTimeMillis()
        favoritesCache?.let { (at, value) -> if (now - at < 10 * 60_000L) return value }
        val dao = musicDaoRef ?: return emptyList<MediaItem>() to emptySet<String>()
        val value = runCatching {
            val liked = dao.getFavoriteSongsSample(80)
            val likedItems = liked.map { MediaItemBuilder.build(it.toSong()) }
            // Most-played songs not heard for 3h+ also count as favourites.
            val top = engagementDaoRef?.getTopPlayedSongs(60).orEmpty()
                .filter { it.playCount >= 3 && now - it.lastPlayedTimestamp > 3 * 60 * 60_000L }
            val topIds = top.mapNotNull { e ->
                e.songId.toLongOrNull() ?: YouTubeIdUtils.extractVideoId(e.songId)
                    .takeIf(SmartQueue::isVideoId)?.let(YouTubeIdUtils::toUnifiedYoutubeSongId)
            }
            val topItems = if (topIds.isEmpty()) emptyList()
            else dao.getSongsByIdsListSimple(topIds).map { MediaItemBuilder.build(it.toSong()) }
            val all = (likedItems + topItems).distinctBy(SmartQueue::keyOf).shuffled()
            all to likedItems.mapTo(HashSet<String>(), SmartQueue::keyOf)
        }.getOrElse { emptyList<MediaItem>() to emptySet<String>() }
        favoritesCache = now to value
        return value
    }

    /** Ranks + mixes + spaces one refill batch. Cheap: a few dozen items, no network. */
    private suspend fun arrangeBatch(items: List<MediaItem>, excludeKeys: Set<String>): List<MediaItem> {
        val ctx = contextRef ?: return items
        if (items.isEmpty()) return items
        return runCatching {
            val (favorites, likedKeys) = favoritesAndLikes()
            val recentArtists = withContext(Dispatchers.Main) {
                val p = livePlayer() ?: return@withContext emptyList<String>()
                val from = maxOf(0, p.mediaItemCount - 9)
                (from until p.mediaItemCount).map { p.getMediaItemAt(it).mediaMetadata.artist?.toString().orEmpty() }
            }
            val freshFavorites = favorites.filter { SmartQueue.keyOf(it) !in excludeKeys && it.mediaId !in excludeKeys }
            SmartQueue.arrange(ctx, items, SmartQueue.Signals(likedKeys, freshFavorites, recentArtists)).also { out ->
                android.util.Log.i(
                    "SmartQueue",
                    "refill: ${items.size} from radio, ${SmartQueue.lastDroppedForLanguage} off-language dropped " +
                        "(lang=${SmartQueue.currentLanguage().ifEmpty { "?" }}) -> ${out.size} queued " +
                        "(${out.size - items.count { it in out }} favourites mixed in)"
                )
            }
        }.getOrElse {
            Timber.tag(TAG).w(it, "arrangeBatch failed; keeping YouTube order")
            items
        }
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

    private fun checkAndRefill(forceImmediate: Boolean = false) {
        getActiveScope().launch(Dispatchers.IO) {
            try {
                if (!isAutoQueueEnabled()) return@launch

                val (remaining, isPlaying) = withContext(Dispatchers.Main) {
                    val p = livePlayer() ?: return@withContext Pair(-1, false)
                    Pair(computeRemainingUpcoming(p), p.isPlaying || p.playWhenReady)
                }
                if (remaining < 0 || !isPlaying) return@launch

                if (forceImmediate || remaining <= REFILL_THRESHOLD) {
                    doRefill()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "checkAndRefill error")
            }
        }
    }

    private suspend fun doRefill() {
        if (!refillMutex.tryLock()) {
            refillRequestedAgain = true
            return
        }
        try {
            do {
                refillRequestedAgain = false
                doRefillLocked()
            } while (refillRequestedAgain)
        } finally {
            refillMutex.unlock()
        }
    }

    private suspend fun doRefillLocked() {
        if (livePlayer() == null) return
        if (!isAutoQueueEnabled()) return

        var (remaining, isPlaying) = withContext(Dispatchers.Main) {
            val p = livePlayer() ?: return@withContext Pair(-1, false)
            Pair(computeRemainingUpcoming(p), p.isPlaying || p.playWhenReady)
        }
        if (remaining < 0 || !isPlaying || remaining > REFILL_THRESHOLD) return

        if (currentQueue === EmptyQueue) {
            val currentId = withContext(Dispatchers.Main) { livePlayer()?.currentMediaItem?.mediaId }
            if (currentId != null) {
                startSmartSession(currentId)
                seedFromMediaId(currentId)
            }
        }

        var batchAttempts = 0
        var totalAdded = 0
        var consecutiveEmptyFetches = 0
        val batchedItems = mutableListOf<MediaItem>()
        var firstBatchAdded = false

        while (remaining < TARGET_UPCOMING_COUNT && batchAttempts < MAX_REFILL_BATCH_ATTEMPTS) {
            batchAttempts++
            val activeQueue = currentQueue
            if (activeQueue === EmptyQueue) {
                val seedMediaId = withContext(Dispatchers.Main) {
                    val p = livePlayer() ?: return@withContext null
                    p.currentMediaItem?.mediaId
                        ?: if (p.mediaItemCount > 0) p.getMediaItemAt(p.mediaItemCount - 1).mediaId else null
                }
                if (seedMediaId != null) {
                    seedFromMediaId(seedMediaId)
                } else {
                    break
                }
            }

            val (existingIds, existingVideoIds, currentTrackSignature, currentVideoId, existingSignatures) = withContext(Dispatchers.Main) {
                val p = livePlayer() ?: return@withContext QueueFilterSnapshot(emptySet(), emptySet(), null, null, emptySet())
                val ids = HashSet<String>(p.mediaItemCount * 2)
                val vIds = HashSet<String>(p.mediaItemCount * 2)
                val sigs = HashSet<String>(p.mediaItemCount)
                for (i in 0 until p.mediaItemCount) {
                    val mi = p.getMediaItemAt(i)
                    ids.add(mi.mediaId)
                    ids.add(mi.mediaId.removePrefix("youtube_"))
                    val extracted = YouTubeIdUtils.extractVideoId(mi.mediaId)
                    if (extracted.isNotBlank()) vIds.add(extracted)
                    val uriId = mi.localConfiguration?.uri?.toString()?.let(YouTubeIdUtils::extractVideoId).orEmpty()
                    if (uriId.isNotBlank()) vIds.add(uriId)
                    val t = mi.mediaMetadata.title?.toString()?.trim()?.lowercase().orEmpty()
                    val a = mi.mediaMetadata.artist?.toString()?.trim()?.lowercase().orEmpty()
                    if (t.isNotBlank() && a.isNotBlank()) {
                        sigs.add("$t|$a")
                    }
                }
                val cur = p.currentMediaItem
                val curTitle = cur?.mediaMetadata?.title?.toString()?.trim()?.lowercase()
                val curArtist = cur?.mediaMetadata?.artist?.toString()?.trim()?.lowercase()
                val sig = if (!curTitle.isNullOrBlank() && !curArtist.isNullOrBlank()) "$curTitle|$curArtist" else null
                val curVid = cur?.let {
                    val idFromMedia = YouTubeIdUtils.extractVideoId(it.mediaId)
                    val idFromUri = it.localConfiguration?.uri?.toString()?.let(YouTubeIdUtils::extractVideoId).orEmpty()
                    if (idFromMedia.length == 11) idFromMedia else if (idFromUri.length == 11) idFromUri else null
                }
                QueueFilterSnapshot(ids, vIds, sig, curVid, sigs)
            }

            val fetched = try {
                currentQueue.nextPage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "nextPage() failed on batch %d", batchAttempts)
                emptyList()
            }

            val filterCoverLofi = runCatching { userPreferencesRepository?.filterCoverAndLofiFlow?.first() }.getOrNull() ?: true
            val filterKeywords = runCatching { userPreferencesRepository?.filterKeywordsFlow?.first() }.getOrNull() ?: ContentFilterUtils.DEFAULT_FILTER_KEYWORDS
            val pureYtMusic = runCatching { userPreferencesRepository?.pureYtMusicOnlyFlow?.first() }.getOrNull() ?: true
            val dislikedYtIds = runCatching { musicDaoRef?.getDislikedYoutubeIds()?.toSet() }.getOrNull() ?: emptySet()
            val dislikedSongIds = runCatching { musicDaoRef?.getDislikedSongIds()?.map { it.toString() }?.toSet() }.getOrNull() ?: emptySet()
            val notInterestedIds = runCatching { userPreferencesRepository?.notInterestedSongIdsFlow?.first() }.getOrNull() ?: emptySet()

            // Recently played songs are skipped to avoid repeats. Most-played songs are no longer
            // banned: SmartQueue mixes favourites back in deliberately, Spotify-style.
            val recentPlayedSongIds = HashSet<String>()
            val highlyRotatedSongIds = HashSet<String>()
            runCatching {
                val recentEngagements = engagementDaoRef?.getRecentlyPlayedSongs(40).orEmpty()
                recentEngagements.forEach {
                    recentPlayedSongIds.add(it.songId)
                    recentPlayedSongIds.add(it.songId.removePrefix("youtube_"))
                    val ext = YouTubeIdUtils.extractVideoId(it.songId)
                    if (ext.isNotBlank()) recentPlayedSongIds.add(ext)
                }
            }

            val newItems = fetched.filterNot { item ->
                val ytId = YouTubeIdUtils.extractVideoId(item.mediaId)
                val itemTitle = item.mediaMetadata.title?.toString()?.trim()?.lowercase()
                val itemArtist = item.mediaMetadata.artist?.toString()?.trim()?.lowercase()
                val itemSig = if (!itemTitle.isNullOrBlank() && !itemArtist.isNullOrBlank()) "$itemTitle|$itemArtist" else null

                item.mediaId in existingIds ||
                item.mediaId.removePrefix("youtube_") in existingIds ||
                (ytId.isNotBlank() && (ytId in existingVideoIds || ytId == currentVideoId)) ||
                (itemSig != null && (itemSig == currentTrackSignature || itemSig in existingSignatures)) ||
                item.mediaId in dislikedSongIds ||
                (ytId.isNotBlank() && ytId in dislikedYtIds) ||
                item.mediaId in notInterestedIds ||
                (ytId.isNotBlank() && ytId in notInterestedIds) ||
                (ytId.isNotBlank() && ytId in sessionQueuedIds) ||
                (ytId.isNotBlank() && ytId in recentPlayedSongIds) ||
                (ytId.isNotBlank() && ytId in highlyRotatedSongIds) ||
                item.mediaId in sessionQueuedIds ||
                item.mediaId in recentPlayedSongIds ||
                item.mediaId in highlyRotatedSongIds ||
                SmartQueue.isArtistBlocked(item.mediaMetadata.artist?.toString())
            }.let { items ->
                var filtered = items
                if (filterCoverLofi) {
                    filtered = filtered.filterNot { item ->
                        val title = item.mediaMetadata.title?.toString().orEmpty()
                        val artist = item.mediaMetadata.artist?.toString().orEmpty()
                        val album = item.mediaMetadata.albumTitle?.toString().orEmpty()
                        ContentFilterUtils.isCoverOrLofi(title, artist, album, filterKeywords = filterKeywords)
                    }
                }
                if (pureYtMusic) {
                    filtered = filtered.filterNot { item ->
                        val title = item.mediaMetadata.title?.toString().orEmpty()
                        ContentFilterUtils.isVideoTitle(title)
                    }
                }
                filtered
            }

            if (newItems.isNotEmpty()) {
                consecutiveEmptyFetches = 0
                for (item in newItems) {
                    val yt = YouTubeIdUtils.extractVideoId(item.mediaId)
                    if (yt.isNotBlank()) sessionQueuedIds.add(yt)
                    sessionQueuedIds.add(item.mediaId)
                }
                if (!firstBatchAdded && remaining <= 1) {
                    val arranged = arrangeBatch(newItems, existingIds + recentPlayedSongIds)
                    withContext(Dispatchers.Main) {
                        livePlayer()?.addMediaItems(arranged)
                        onQueueItemsAddedCallback?.invoke()
                    }
                    firstBatchAdded = true
                } else {
                    batchedItems.addAll(newItems)
                }
                totalAdded += newItems.size
                remaining += newItems.size
                Timber.tag(TAG).d("Batch %d: buffered %d items -> remaining target is now %d", batchAttempts, newItems.size, remaining)
            } else {
                consecutiveEmptyFetches++
                Timber.tag(TAG).w("Batch %d yielded no new items (fetched=%d, existing=%d). Consecutive empty=%d",
                    batchAttempts, fetched.size, existingIds.size, consecutiveEmptyFetches)

                if (consecutiveEmptyFetches >= 2) {
                    // Re-seed from the session anchor (original seed / songs the user finished);
                    // fall back to the current or last queued track.
                    val anchor = SmartQueue.nextAnchor()
                    if (anchor != null) {
                        android.util.Log.w("SmartQueue", "radio re-anchored to $anchor")
                        seedRadioFromVideoId(anchor)
                        continue
                    }
                    val reseedId = withContext(Dispatchers.Main) {
                        val p = livePlayer() ?: return@withContext null
                        p.currentMediaItem?.mediaId
                            ?: if (p.mediaItemCount > 0) p.getMediaItemAt(p.mediaItemCount - 1).mediaId else null
                    }
                    if (reseedId != null) {
                        Timber.tag(TAG).d("Reseeding AutoQueue from song: %s", reseedId)
                        seedFromMediaId(reseedId)
                    } else {
                        break
                    }
                }
                if (consecutiveEmptyFetches >= 4) {
                    break
                }
            }
        }

        if (batchedItems.isNotEmpty()) {
            val excludeKeys = withContext(Dispatchers.Main) {
                val p = livePlayer() ?: return@withContext emptySet<String>()
                (0 until p.mediaItemCount).flatMapTo(HashSet()) { i ->
                    val mi = p.getMediaItemAt(i)
                    listOf(mi.mediaId, SmartQueue.keyOf(mi))
                }
            }
            val arranged = arrangeBatch(batchedItems, excludeKeys)
            batchedItems.clear()
            batchedItems.addAll(arranged)
            synchronized(sessionQueuedIds) {
                arranged.forEach { sessionQueuedIds.add(it.mediaId); sessionQueuedIds.add(SmartQueue.keyOf(it)) }
            }
            withContext(Dispatchers.Main) {
                livePlayer()?.addMediaItems(batchedItems)
                onQueueItemsAddedCallback?.invoke()
            }
            Timber.tag(TAG).d("Committed %d batched items to livePlayer in a single call", batchedItems.size)
        }

        if (totalAdded > 0) {
            val finalRemaining = withContext(Dispatchers.Main) {
                val p = livePlayer() ?: return@withContext -1
                computeRemainingUpcoming(p)
            }
            Timber.tag(TAG).d("doRefillLocked finished: total added %d items, upcoming remaining is now %d", totalAdded, finalRemaining)
        }
    }

    private suspend fun seedFromMediaId(mediaId: String) {
        val dao = musicDaoRef ?: return
        val videoId = resolveVideoId(mediaId)
        if (videoId == null) {
            currentQueue = ResilientRadioQueue.radio(videoId = null, musicDao = dao, isOnline = ::hasInternet)
            ensureLocalSeed(mediaId)
            return
        }
        currentQueue = ResilientRadioQueue.radio(videoId = videoId, musicDao = dao, isOnline = ::hasInternet)
            .also { it.anchorProvider = SmartQueue::nextAnchor }
        ensureLocalSeed(videoId)
    }

    private suspend fun ensureLocalSeed(idOrMediaId: String) {
        val dao = musicDaoRef ?: return
        val queue = currentQueue
        if (queue !is ResilientRadioQueue) return
        val entity = withContext(Dispatchers.IO) {
            val longId = idOrMediaId.toLongOrNull() ?: getDatabaseIdForYoutubeId(normalizeId(idOrMediaId))
            runCatching { dao.getSongByIdOnce(longId) }.getOrNull()
        }
        queue.updateLocalSeed(entity)
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

private data class QueueFilterSnapshot(
    val existingIds: Set<String>,
    val existingVideoIds: Set<String>,
    val currentTrackSignature: String?,
    val currentVideoId: String?,
    val existingSignatures: Set<String>,
)
