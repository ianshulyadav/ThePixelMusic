package com.unshoo.pixelmusic.data.remote.youtube.queue

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import com.unshoo.pixelmusic.utils.YouTubeIdUtils
import java.util.Locale

/**
 * Spotify-style "what plays next" on top of YouTube Music's radio.
 *
 * YouTube supplies the candidates (already relevant and, when signed in, personalised).
 * This layer adds what Spotify does on top:
 *  1. learns from skips (this session + a small saved history),
 *  2. re-ranks each refill batch with the user's own signals,
 *  3. mixes in favourites (liked / most-played) that fit the current vibe,
 *  4. keeps the radio anchored to the original seed instead of drifting,
 *  5. spaces artists out,
 *  6. seeds playlist/album autoplay from several of its songs.
 *
 * Cost model (kept deliberately light): no network of its own, runs only at refill time
 * (~every 20 songs) over a few dozen items, O(n) scoring + a small greedy re-order.
 * State is a handful of small maps; skip history is a capped SharedPreferences file.
 */
object SmartQueue {

    // ------------------------------------------------------------------ session memory

    private val lock = Any()
    private var originalSeed: String? = null
    private val contextSeeds = ArrayList<String>(3)
    private val completed = ArrayDeque<Played>()           // newest last, capped
    private val sessionArtistPlays = HashMap<String, Int>() // finished songs per artist
    private val sessionArtistSkips = HashMap<String, Int>() // skips per artist
    private var consecutiveSkips = 0
    private var anchorCursor = 0
    /** Language/vibe of the song the session started with ("" = not known yet). */
    private var sessionLanguage = SongLanguage.UNKNOWN

    private data class Played(val videoId: String?, val artist: String)

    sealed interface SkipAction {
        data object None : SkipAction
        /** Two skips of this artist: drop their upcoming auto-queued songs. */
        data class DropArtist(val artist: String) : SkipAction
        /** Three skips in a row: the radio is off; rebuild it from a song they finished. */
        data class Resteer(val seedVideoId: String) : SkipAction
    }

    /** New listening context (new song/playlist started): forget the old session. */
    fun startSession(seedVideoId: String?, playlistSeeds: List<String>, seedLanguage: String) = synchronized(lock) {
        originalSeed = seedVideoId
        sessionLanguage = seedLanguage
        contextSeeds.clear()
        contextSeeds.addAll(playlistSeeds.filter { it != seedVideoId }.distinct().take(3))
        completed.clear()
        sessionArtistPlays.clear()
        sessionArtistSkips.clear()
        consecutiveSkips = 0
        anchorCursor = 0
    }

    fun onCompleted(videoId: String?, artistRaw: String?) = synchronized(lock) {
        consecutiveSkips = 0
        val artist = primaryArtist(artistRaw)
        completed.addLast(Played(videoId?.takeIf(::isVideoId), artist))
        while (completed.size > MAX_COMPLETED) completed.removeFirst()
        if (artist.isNotEmpty()) sessionArtistPlays[artist] = (sessionArtistPlays[artist] ?: 0) + 1
    }

    fun onSkipped(artistRaw: String?): SkipAction = synchronized(lock) {
        consecutiveSkips++
        val artist = primaryArtist(artistRaw)
        var artistSkips = 0
        if (artist.isNotEmpty()) {
            artistSkips = (sessionArtistSkips[artist] ?: 0) + 1
            sessionArtistSkips[artist] = artistSkips
        }
        when {
            consecutiveSkips >= RESTEER_AFTER_SKIPS -> {
                consecutiveSkips = 0
                val seed = completed.lastOrNull { it.videoId != null }?.videoId ?: originalSeed
                if (seed != null) SkipAction.Resteer(seed) else SkipAction.None
            }
            artistSkips == DROP_ARTIST_AFTER_SKIPS -> SkipAction.DropArtist(artist)
            else -> SkipAction.None
        }
    }

    /**
     * Seed for the next radio refill. Rotates between the original seed, the playlist's own
     * songs and the last songs the user actually finished, so the mix stays on the session's
     * vibe instead of chaining off whatever was auto-queued last (which drifts genres).
     */
    fun nextAnchor(): String? = synchronized(lock) {
        val pool = buildList {
            originalSeed?.let(::add)
            addAll(contextSeeds)
            completed.toList().asReversed().mapNotNull { it.videoId }.take(2).forEach(::add)
        }.distinct()
        if (pool.isEmpty()) return null
        pool[(anchorCursor++) % pool.size]
    }

    private fun snapshot(): Pair<Map<String, Int>, Map<String, Int>> = synchronized(lock) {
        HashMap(sessionArtistPlays) to HashMap(sessionArtistSkips)
    }

    fun isArtistBlocked(artistRaw: String?): Boolean = synchronized(lock) {
        (sessionArtistSkips[primaryArtist(artistRaw)] ?: 0) >= DROP_ARTIST_AFTER_SKIPS
    }

    // ------------------------------------------------------------------ saved skip history

    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext
            .getSharedPreferences("smart_queue_skips", Context.MODE_PRIVATE)
            .also { prefs = it }

    /** +1 skip for [key] (video id or media id). One small apply() write; file capped. */
    fun recordSkip(context: Context, key: String) {
        if (key.isBlank()) return
        val p = prefs(context)
        val editor = p.edit().putInt(key, p.getInt(key, 0) + 1)
        if (p.all.size > MAX_SKIP_ENTRIES) {
            // Keep it tiny: forget single skips first (the weakest signal).
            p.all.entries.filter { (it.value as? Int ?: 0) <= 1 }.take(MAX_SKIP_ENTRIES / 4)
                .forEach { editor.remove(it.key) }
        }
        editor.apply()
    }

    /** A finished listen cancels one old skip, so songs can recover. */
    fun recordCompletion(context: Context, key: String) {
        if (key.isBlank()) return
        val p = prefs(context)
        val n = p.getInt(key, 0)
        if (n > 0) p.edit().apply { if (n == 1) remove(key) else putInt(key, n - 1) }.apply()
    }

    private fun skipCounts(context: Context): Map<String, Int> =
        prefs(context).all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()

    // ------------------------------------------------------------------ ranking

    class Signals(
        /** Keys (video id / media id) of liked songs. */
        val likedKeys: Set<String>,
        /** Liked / most-played songs not played recently, ready to queue. */
        val favorites: List<MediaItem>,
        /** Artists of the last few songs already in the queue (for spacing). */
        val recentQueueArtists: List<String>,
    )

    /**
     * Ranks one refill batch: YouTube's order stays the base (it is already relevance-sorted),
     * adjusted by session likes/skips and saved skip history; favourites that fit the vibe are
     * mixed in (~1 in 4) and artists are spaced out.
     */
    fun arrange(context: Context, candidates: List<MediaItem>, signals: Signals): List<MediaItem> {
        if (candidates.isEmpty()) return candidates
        val (artistPlays, artistSkips) = snapshot()
        val savedSkips = skipCounts(context)

        // Language lock: keep autoplay in the language of the song the user started with.
        val langs = candidates.map(::languageOf)
        val lang = sessionLanguageFor(langs)
        val inLanguage = candidates.filterIndexed { i, _ -> SongLanguage.compatible(lang, langs[i]) }
        // Never leave the queue empty: if YouTube returned nothing in this language, keep its top few.
        val pool = inLanguage.ifEmpty { candidates.take(3) }
        lastDroppedForLanguage = candidates.size - inLanguage.size
        val n = pool.size

        val scored = pool.mapIndexedNotNull { i, item ->
            val artist = primaryArtist(item.mediaMetadata.artist?.toString())
            val key = keyOf(item)
            val skips = savedSkips[key] ?: 0
            val artistSkip = artistSkips[artist] ?: 0
            // Songs the user keeps skipping, and artists skipped twice this session, are out.
            if (skips >= DROP_SONG_AFTER_SKIPS || artistSkip >= DROP_ARTIST_AFTER_SKIPS) return@mapIndexedNotNull null
            var score = 1.0 - 0.5 * i / n                                  // YouTube relevance
            score += 0.6 * minOf(artistPlays[artist] ?: 0, 3) / 3.0        // finished this session
            if (key in signals.likedKeys) score += 0.4                     // liked
            score -= 0.5 * artistSkip                                      // skipped artist
            score -= 0.25 * skips                                          // skipped before
            Scored(item, artist, score)
        }.sortedByDescending { it.score }

        // Favourites that fit the vibe: same artist as a candidate or something finished now.
        val vibeArtists = HashSet<String>().apply {
            scored.forEach { add(it.artist) }
            addAll(artistPlays.keys)
        }
        val candidateKeys = candidates.mapTo(HashSet()) { keyOf(it) }
        val favs = signals.favorites.asSequence()
            .filter { keyOf(it) !in candidateKeys }
            .filter { SongLanguage.compatible(lang, languageOf(it)) }
            .map { Scored(it, primaryArtist(it.mediaMetadata.artist?.toString()), 0.0) }
            .filter { it.artist.isNotEmpty() && it.artist in vibeArtists && (artistSkips[it.artist] ?: 0) == 0 }
            .take(maxOf(1, scored.size / FAVORITE_EVERY))
            .toMutableList()

        val mixed = ArrayList<Scored>(scored.size + favs.size)
        scored.forEachIndexed { idx, s ->
            mixed += s
            if ((idx + 1) % (FAVORITE_EVERY - 1) == 0 && favs.isNotEmpty()) mixed += favs.removeAt(0)
        }
        return spaceArtists(mixed, signals.recentQueueArtists).map { it.item }
    }

    private class Scored(val item: MediaItem, val artist: String, val score: Double)

    /** For logs: how many radio songs the language lock removed in the last refill. */
    @Volatile var lastDroppedForLanguage = 0
        private set

    fun currentLanguage(): String = synchronized(lock) { sessionLanguage }

    fun languageOf(item: MediaItem): String = SongLanguage.guess(
        item.mediaMetadata.title?.toString(),
        item.mediaMetadata.artist?.toString(),
        item.mediaMetadata.albumTitle?.toString(),
    )

    /**
     * The session's language; if the seed's couldn't be told, adopt the clear majority
     * (>= 60% of >= 5 detected) of the seed's own radio, which YouTube already ranks by vibe.
     */
    private fun sessionLanguageFor(candidateLangs: List<String>): String = synchronized(lock) {
        if (sessionLanguage.isNotEmpty()) return sessionLanguage
        val detected = candidateLangs.filter { it.isNotEmpty() }
        if (detected.size < 5) return SongLanguage.UNKNOWN
        val (top, count) = detected.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return SongLanguage.UNKNOWN
        if (count * 10 >= detected.size * 6) sessionLanguage = top
        sessionLanguage
    }

    /** Greedy re-order: no artist twice in a row, at most 2 per artist in any 10. */
    private fun spaceArtists(items: List<Scored>, recent: List<String>): List<Scored> {
        val pending = items.toMutableList()
        val out = ArrayList<Scored>(items.size)
        val window = ArrayDeque(recent.map(::primaryArtist).takeLast(SPACING_WINDOW - 1))
        while (pending.isNotEmpty()) {
            val last = window.lastOrNull()
            val idx = pending.indexOfFirst { s ->
                s.artist.isEmpty() || (s.artist != last && window.count { it == s.artist } < MAX_PER_WINDOW)
            }.takeIf { it >= 0 } ?: 0 // nothing fits: take the best rather than stall
            val pick = pending.removeAt(idx)
            out += pick
            window.addLast(pick.artist)
            while (window.size > SPACING_WINDOW - 1) window.removeFirst()
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    fun isVideoId(id: String) = VIDEO_ID.matches(id)

    /** Stable key for a queue item: its YouTube video id when it has one, else its media id. */
    fun keyOf(item: MediaItem): String {
        val fromId = YouTubeIdUtils.extractVideoId(item.mediaId)
        if (isVideoId(fromId)) return fromId
        val fromUri = item.localConfiguration?.uri?.toString()?.let(YouTubeIdUtils::extractVideoId).orEmpty()
        return if (isVideoId(fromUri)) fromUri else item.mediaId
    }

    private val ARTIST_SPLIT = Regex("\\s*(,|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b|\\band\\b)\\s*", RegexOption.IGNORE_CASE)

    /** "Arijit Singh, Pritam" -> "arijit singh" (first credited artist, lower-case). */
    fun primaryArtist(raw: String?): String =
        raw?.split(ARTIST_SPLIT)?.firstOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()

    private const val MAX_COMPLETED = 10
    private const val RESTEER_AFTER_SKIPS = 3
    private const val DROP_ARTIST_AFTER_SKIPS = 2
    private const val DROP_SONG_AFTER_SKIPS = 3
    private const val FAVORITE_EVERY = 4
    private const val SPACING_WINDOW = 10
    private const val MAX_PER_WINDOW = 2
    private const val MAX_SKIP_ENTRIES = 500
}
