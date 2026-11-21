package com.unshoo.pixelmusic.data.remote.saavn

import com.unshoo.pixelmusic.data.lossless.audiosource.TitleMatch
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.remote.youtube.toNativeSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Maps a JioSaavn search result ("saavn_<id>") to the same song on YouTube Music, so it gets a
 * real catalog id and every feature that needs one works: download, like, add to playlist,
 * radio/auto-queue, lyrics sync, history. Playback still prefers JioSaavn's 320 kbps stream for
 * YouTube songs when the Saavn source is enabled, so quality is not lost by switching ids.
 *
 * A match must agree on title, artist and length (within 6 s); otherwise the Saavn song is kept.
 */
object SaavnYouTubeMatcher {
    private const val TAG = "SaavnYtMatcher"
    private const val MAX_DURATION_DIFF_MS = 6_000L
    private val cache = ConcurrentHashMap<String, Song>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    fun isSaavnSong(song: Song): Boolean = song.id.startsWith("saavn_")

    /** Returns the YouTube Music equivalent of [song], or null when no confident match exists. */
    suspend fun match(song: Song): Song? {
        if (!isSaavnSong(song)) return null
        cache[song.id]?.let { return it }
        if (song.id in misses) return null
        val result = withContext(Dispatchers.IO) {
            withTimeoutOrNull(6_000L) {
                val primaryArtist = song.artist.substringBefore(',').trim()
                val query = "${song.title} $primaryArtist".trim()
                val items = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    ?.items
                    ?.filterIsInstance<SongItem>()
                    ?.filterNot { it.isVideo }
                    .orEmpty()
                    .take(8)
                pickBest(song, items)
            }
        }
        if (result != null) {
            cache[song.id] = result
            Timber.tag(TAG).d("Matched \"%s\" -> %s", song.title, result.id)
        } else {
            misses += song.id
            Timber.tag(TAG).d("No confident YouTube Music match for \"%s\"", song.title)
        }
        return result
    }

    private fun pickBest(song: Song, items: List<SongItem>): Song? {
        val wantedArtists = song.artist.split(",", "&").map { it.trim() }.filter { it.isNotBlank() }
        var best: Pair<Double, SongItem>? = null
        for (item in items) {
            val candDurMs = item.duration?.takeIf { it > 0 }?.times(1000L)
            if (song.duration > 0 && candDurMs != null && abs(candDurMs - song.duration) > MAX_DURATION_DIFF_MS) continue
            val title = TitleMatch.ratio(song.title, item.title)
            if (title < 0.85) continue
            val itemArtists = item.artists.joinToString(", ") { it.name }
            val artistOk = wantedArtists.isEmpty() || itemArtists.isBlank() ||
                wantedArtists.any { w -> TitleMatch.ratio(w, itemArtists) >= 0.8 || itemArtists.contains(w, ignoreCase = true) }
            if (!artistOk) continue
            val durationBonus = if (candDurMs != null && song.duration > 0) {
                1.0 - (abs(candDurMs - song.duration).toDouble() / MAX_DURATION_DIFF_MS) * 0.5
            } else 0.5
            val score = title * 0.7 + durationBonus * 0.3
            if (best == null || score > best!!.first) best = score to item
        }
        val chosen = best?.second ?: return null
        // Keep JioSaavn's sharper artwork when YouTube only has a video thumbnail.
        return chosen.toNativeSong().let { yt ->
            if (yt.albumArtUriString.isNullOrBlank()) yt.copy(albumArtUriString = song.albumArtUriString) else yt
        }
    }
}
