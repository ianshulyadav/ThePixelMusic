package com.unshoo.pixelmusic.data.playlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class SpotifyParsedTrack(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val spotifyUrl: String,
    val album: String = "",
    val coverUrl: String? = null
)

data class SpotifyParsedPlaylist(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val tracks: List<SpotifyParsedTrack>
)

object SpotifyPlaylistParser {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val PLAYLIST_URL_PATTERN = Pattern.compile(
        """(?:https?://open\.spotify\.com/(?:intl-[a-z]{2}/)?playlist/|spotify:playlist:)([a-zA-Z0-9]+)"""
    )
    private val NEXT_DATA_PATTERN = Pattern.compile(
        """<script\s+id="__NEXT_DATA__"\s+type="application/json">([^<]+)</script>"""
    )

    fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        val matcher = PLAYLIST_URL_PATTERN.matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1)
        }
        // Direct alphanumeric ID check (typical Spotify ID is 22 chars)
        if (trimmed.matches(Regex("^[a-zA-Z0-9]{15,30}$"))) {
            return trimmed
        }
        return null
    }

    suspend fun parse(input: String): Result<SpotifyParsedPlaylist> = withContext(Dispatchers.IO) {
        val playlistId = extractPlaylistId(input)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Spotify playlist URL or ID"))

        try {
            val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
            val request = Request.Builder()
                .url(embedUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                )
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to fetch Spotify playlist (HTTP ${response.code})"))
            }

            val html = response.body?.string().orEmpty()
            val matcher = NEXT_DATA_PATTERN.matcher(html)
            if (!matcher.find()) {
                return@withContext Result.failure(Exception("Unable to extract playlist data from Spotify"))
            }

            val jsonContent = matcher.group(1) ?: return@withContext Result.failure(Exception("Empty playlist payload"))
            val root = JSONObject(jsonContent)
            val pageProps = root.optJSONObject("props")?.optJSONObject("pageProps")
                ?: return@withContext Result.failure(Exception("Invalid Spotify data structure"))

            val state = pageProps.optJSONObject("state")
            val entity = state?.optJSONObject("data")?.optJSONObject("entity")
                ?: return@withContext Result.failure(Exception("Playlist entity not found in Spotify data"))

            val title = entity.optString("name").ifBlank { entity.optString("title", "Imported Spotify Playlist") }
            val coverUrl: String? = run {
                val viImages = entity.optJSONObject("visualIdentity")?.optJSONArray("image")
                if (viImages != null && viImages.length() > 0) {
                    val url = viImages.optJSONObject(viImages.length() - 1)?.optString("url")
                    if (!url.isNullOrBlank()) return@run url
                }
                val caSources = entity.optJSONObject("coverArt")?.optJSONArray("sources")
                if (caSources != null && caSources.length() > 0) {
                    val url = caSources.optJSONObject(0)?.optString("url")
                    if (!url.isNullOrBlank()) return@run url
                }
                val images = entity.optJSONArray("images")
                if (images != null && images.length() > 0) {
                    val url = images.optJSONObject(0)?.optString("url")
                    if (!url.isNullOrBlank()) return@run url
                }
                entity.optString("coverArt").takeIf { it.startsWith("http") }
            }

            val tracks = mutableListOf<SpotifyParsedTrack>()
            val trackList = entity.optJSONArray("trackList")
            if (trackList != null) {
                for (i in 0 until trackList.length()) {
                    val item = trackList.optJSONObject(i) ?: continue
                    val trackTitle = item.optString("title").trim()
                    val artist = item.optString("subtitle").trim()
                    val durationMs = item.optLong("duration", 0L)
                    val uri = item.optString("uri")
                    val trackId = uri.substringAfterLast(":", "")
                    val spotifyUrl = if (trackId.isNotBlank()) "https://open.spotify.com/track/$trackId" else ""

                    if (trackTitle.isNotBlank()) {
                        tracks.add(
                            SpotifyParsedTrack(
                                title = trackTitle,
                                artist = artist.ifBlank { "Unknown Artist" },
                                durationMs = durationMs,
                                spotifyUrl = spotifyUrl,
                                coverUrl = coverUrl
                            )
                        )
                    }
                }
            }

            // BUGFIX (imports stopped at 100 songs): the embed page only ever carries the first
            // 100 tracks, and "trackList" is a JSON *array*, so reading a "total" from it as an
            // object always gave tracks.size and pagination never ran. The embed has no total,
            // so whenever it is full (>= 100) page the Web API with an anonymous token.
            if (tracks.size >= EMBED_TRACK_CAP) {
                val accessToken = state.optJSONObject("settings")
                    ?.optJSONObject("session")
                    ?.optString("accessToken")
                    ?.takeIf { it.isNotBlank() }
                    ?: fetchAnonymousToken()
                if (!accessToken.isNullOrBlank()) {
                    fetchRemainingTracks(playlistId, accessToken, tracks.size, tracks)
                } else {
                    Timber.w("SpotifyPlaylistParser: no access token; only the first %d tracks are available", tracks.size)
                }
            }

            Result.success(
                SpotifyParsedPlaylist(
                    id = playlistId,
                    title = title,
                    coverUrl = coverUrl.takeIf { !it.isNullOrBlank() },
                    tracks = tracks
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "SpotifyPlaylistParser: Error parsing playlist $playlistId")
            Result.failure(e)
        }
    }

    private const val EMBED_TRACK_CAP = 100

    /** Anonymous web-player token (fallback when the embed page carries none). */
    private fun fetchAnonymousToken(): String? = runCatching {
        val req = Request.Builder()
            .url("https://open.spotify.com/get_access_token?reason=transport&productType=embed")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .build()
        httpClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@use null
            JSONObject(res.body?.string().orEmpty()).optString("accessToken").takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun fetchRemainingTracks(
        playlistId: String,
        accessToken: String,
        startOffset: Int,
        outTracks: MutableList<SpotifyParsedTrack>
    ) {
        var offset = startOffset
        val limit = 100
        var hasMore = true
        var rateLimitRetries = 0

        while (hasMore) {
            try {
                val apiUrl = "https://api.spotify.com/v1/playlists/$playlistId/tracks?offset=$offset&limit=$limit"
                val apiReq = Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Bearer $accessToken")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                val apiRes = httpClient.newCall(apiReq).execute()
                if (apiRes.code == 429 && rateLimitRetries < 4) {
                    // Rate limited: wait as told (or back off) and retry the same page.
                    val waitSec = apiRes.header("Retry-After")?.toLongOrNull() ?: (2L shl rateLimitRetries)
                    apiRes.close()
                    rateLimitRetries++
                    Thread.sleep(waitSec.coerceIn(1L, 30L) * 1000L)
                    continue
                }
                if (!apiRes.isSuccessful) {
                    Timber.w("SpotifyPlaylistParser: page at offset %d failed (HTTP %d)", offset, apiRes.code)
                    apiRes.close()
                    break
                }
                rateLimitRetries = 0

                val bodyStr = apiRes.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val items = json.optJSONArray("items") ?: break

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    // Local files / removed tracks come back as null "track": skip, keep paging.
                    val track = item.optJSONObject("track") ?: continue
                    val trackTitle = track.optString("name").trim()
                    if (trackTitle.isBlank()) continue

                    val artistsArr = track.optJSONArray("artists")
                    val artistsList = mutableListOf<String>()
                    if (artistsArr != null) {
                        for (a in 0 until artistsArr.length()) {
                            val aObj = artistsArr.optJSONObject(a)
                            val name = aObj?.optString("name")
                            if (!name.isNullOrBlank()) artistsList.add(name)
                        }
                    }
                    val artistStr = artistsList.joinToString(", ").ifBlank { "Unknown Artist" }
                    val durationMs = track.optLong("duration_ms", 0L)
                    val trackId = track.optString("id")
                    val spotifyUrl = if (trackId.isNotBlank()) "https://open.spotify.com/track/$trackId" else ""
                    val albumObj = track.optJSONObject("album")
                    val albumName = albumObj?.optString("name").orEmpty()
                    val cover = albumObj?.optJSONArray("images")?.optJSONObject(0)?.optString("url")

                    outTracks.add(
                        SpotifyParsedTrack(
                            title = trackTitle,
                            artist = artistStr,
                            durationMs = durationMs,
                            spotifyUrl = spotifyUrl,
                            album = albumName,
                            coverUrl = cover
                        )
                    )
                }

                val total = json.optInt("total", Int.MAX_VALUE)
                offset += items.length()
                hasMore = items.length() > 0 && json.optString("next").let { it.isNotBlank() && it != "null" } && offset < total
            } catch (e: Exception) {
                Timber.w(e, "SpotifyPlaylistParser: Error fetching page at offset $offset")
                break
            }
        }
    }
}
