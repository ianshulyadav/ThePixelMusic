package com.unshoo.pixelmusic.data.feed

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Real artist photos for "Artists for you", the spotlight card and local top artists.
 *
 * BUGFIX: artists were given the cover art of a song they appear on. Every artist credited on the
 * same single (e.g. "Tu Mil Jaaye" from KING) showed that single's poster, so several different
 * artists rendered with the identical image. Photos now come only from the artist's own
 * YouTube Music channel (by channel id, or by an exact-name artist search) and are cached for the
 * process. Unresolved artists keep a null artwork so the UI shows its placeholder rather than a
 * wrong picture.
 */
object ArtistImageResolver {
    private val photoByKey = ConcurrentHashMap<String, String>()
    private val browseIdByName = ConcurrentHashMap<String, String>()
    private val missUntil = ConcurrentHashMap<String, Long>()
    private val permits = Semaphore(3)
    private const val MISS_TTL_MS = 30 * 60 * 1000L

    /** Normalised name for matching ("Sachin–Jigar", "sachin jigar" -> "sachinjigar"). */
    fun normalizeName(name: String): String =
        name.lowercase(Locale.ROOT)
            .replace(Regex("""\s*-\s*topic$"""), "")
            .replace(Regex("""[\s\-–—_.'’]"""), "")

    private fun keyOf(artist: FeedArtist): String =
        artist.browseId?.takeIf { it.startsWith("UC") } ?: ("name:" + normalizeName(artist.name))

    /** Cache-only lookup, safe to call on any thread. */
    fun cached(artist: FeedArtist): FeedArtist {
        val photo = photoByKey[keyOf(artist)]
            ?: photoByKey["name:" + normalizeName(artist.name)]
            ?: return artist
        return artist.copy(
            artworkUrl = photo,
            browseId = artist.browseId ?: browseIdByName[normalizeName(artist.name)],
        )
    }

    suspend fun resolve(
        artist: FeedArtist,
        searchArtists: suspend (String, Int) -> List<FeedArtist>,
    ): FeedArtist {
        // Callers only pass artwork that is already the artist's own photo (e.g. from an ArtistItem).
        if (artist.artworkUrl != null) return artist
        val hit = cached(artist)
        if (hit.artworkUrl != null) return hit
        val key = keyOf(artist)
        val now = System.currentTimeMillis()
        if ((missUntil[key] ?: 0L) > now) return artist.copy(artworkUrl = null)

        return permits.withPermit {
            val nameKey = normalizeName(artist.name)
            var browseId = artist.browseId?.takeIf { it.startsWith("UC") } ?: browseIdByName[nameKey]
            var photo: String? = null
            if (browseId == null) {
                val match = runCatching { searchArtists(artist.name, 5) }.getOrDefault(emptyList())
                    .firstOrNull { normalizeName(it.name) == nameKey }
                browseId = match?.browseId
                photo = match?.artworkUrl?.takeIf(ArtworkNormalizer::isRealImage)
            }
            if (photo == null && browseId != null) {
                photo = YouTube.artist(browseId).getOrNull()?.artist?.thumbnail
                    ?.takeIf(ArtworkNormalizer::isRealImage)
            }
            if (photo != null) {
                photoByKey[key] = photo
                photoByKey["name:$nameKey"] = photo
                browseId?.let { browseIdByName[nameKey] = it; photoByKey[it] = photo }
                artist.copy(artworkUrl = photo, browseId = artist.browseId ?: browseId)
            } else {
                missUntil[key] = now + MISS_TTL_MS
                artist.copy(artworkUrl = null, browseId = artist.browseId ?: browseId)
            }
        }
    }

    suspend fun resolveAll(
        artists: List<FeedArtist>,
        searchArtists: suspend (String, Int) -> List<FeedArtist>,
    ): List<FeedArtist> = coroutineScope {
        artists.map { async { resolve(it, searchArtists) } }.awaitAll()
    }
}
