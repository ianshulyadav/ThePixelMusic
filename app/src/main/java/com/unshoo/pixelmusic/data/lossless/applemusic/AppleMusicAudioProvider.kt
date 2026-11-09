/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.unshoo.pixelmusic.data.lossless.applemusic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.unshoo.pixelmusic.data.lossless.applemusic.AppleMusicProvider
import com.unshoo.pixelmusic.data.lossless.constants.AppleMusicQuality
import com.unshoo.pixelmusic.data.lossless.pool.PoolAccountManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Apple Music full-track playback source. */
object AppleMusicAudioProvider {
    private const val TAG = "AppleMusicSource"

    private const val AMP_BASE = "https://amp-api.music.apple.com"
    private const val WEB_PLAYBACK_URL =
        "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/145.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Storefront resolved from the Media-User-Token (`/v1/me/storefront`), cached 24 h. */
    private const val STOREFRONT_TTL_MS = 24 * 60 * 60 * 1000L

    /**
     * KEYFORMAT of the Widevine #EXT-X-KEY line — the standard Widevine system id as a URN. The
     * same playlist also lists FairPlay and PlayReady keys, which are not usable on Android here.
     */
    private const val WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    @Volatile private var cachedStorefront: String? = null
    @Volatile private var cachedStorefrontAtMs = 0L
    private val storefrontMutex = Mutex()

    /** Resolved storefront for the token (es/jp/…); "us" when it cannot be determined. */
    suspend fun resolveStorefront(): String {
        val now = System.currentTimeMillis()
        cachedStorefront?.let { if (now - cachedStorefrontAtMs < STOREFRONT_TTL_MS) return it }
        return storefrontMutex.withLock {
            val stillFresh = cachedStorefront?.takeIf { now - cachedStorefrontAtMs < STOREFRONT_TTL_MS }
            if (stillFresh != null) return@withLock stillFresh
            val media = mediaUserToken()?.takeIf { it.isNotBlank() } ?: return@withLock cachedStorefront ?: "us"
            val dev = devToken() ?: return@withLock cachedStorefront ?: "us"
            fetchedStorefront(media, dev)?.let { fetched ->
                cachedStorefront = fetched
                cachedStorefrontAtMs = System.currentTimeMillis()
                return@withLock fetched
            }
            cachedStorefront ?: "us"
        }
    }

    private suspend fun fetchedStorefront(mediaToken: String, devToken: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request.Builder()
                        .url("$AMP_BASE/v1/me/storefront")
                        .header("Authorization", "Bearer $devToken")
                        .header("Media-User-Token", mediaToken)
                        .header("Origin", "https://music.apple.com")
                        .header("Referer", "https://music.apple.com/")
                        .header("User-Agent", UA)
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "storefront fetch failed: %d".format(response.code))
                        return@use null
                    }
                    val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    root["data"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()
        }

    /** Drops the cached storefront — call when the Media-User-Token changes (a new account may live elsewhere). */
    fun clearStorefrontCache() {
        cachedStorefront = null
        cachedStorefrontAtMs = 0L
    }

    fun devToken(): String? {
        val custom = AppleMusicProvider.devTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() && !AppleMusicProvider.isJwtExpired(it) }
        if (custom != null) return custom
        return AppleMusicProvider.getFreshDevToken().takeIf { it.isNotBlank() }
    }

    fun mediaUserToken(): String? = AppleMusicProvider.mediaUserTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }

    /** True when both tokens are present — the source cannot resolve anything otherwise. */
    fun isAvailable(): Boolean = devToken() != null && mediaUserToken() != null

    /**
     * Verifies a captured (media-user-token, developer-token) pair against the
     * storefront endpoint before the login screen persists them — a token that
     * cannot read /v1/me/storefront is not worth saving.
     */
    suspend fun verifyTokens(
        mediaToken: String,
        devToken: String,
    ): Boolean = fetchedStorefront(mediaToken.trim(), devToken.trim()) != null

    /** Thrown by [searchSongIds]/[webPlayback] on 401/403 — the media-user-token is dead. */
    private class AuthException : Exception("apple media-user-token rejected (401/403)")

    /** One entry of the account ring: a media-user-token plus its pool id (null = personal). */
    private data class RingEntry(val token: String, val poolId: Long?)

    @Volatile
    private var ring: List<RingEntry> = emptyList()

    @Volatile
    private var ringBuiltAt = 0L

    /** Sticky index of the last account that resolved successfully; rotation starts here. */
    @Volatile
    private var ringIndex = 0

    /**
     * The account ring: the personal media-user-token first (when signed in), then the shared
     * pool accounts premium-first — mirroring how the Qobuz/Deezer resolvers walk their token
     * lists. Cached for a minute so per-call rebuilds don't hammer the preference stores.
     */
    private fun accountRing(): List<RingEntry> {
        val now = System.currentTimeMillis()
        val cached = ring
        if (cached.isNotEmpty() && now - ringBuiltAt < 60_000L) return cached
        val personal = mediaUserToken()
        val pool = PoolAccountManager.appleMusicAccounts().map { RingEntry(it.mediaUserToken, it.id) }
        val built = ((personal?.let { listOf(RingEntry(it, null)) } ?: emptyList()) + pool)
            .distinctBy { it.token }
        ring = built
        ringBuiltAt = now
        if (ringIndex >= built.size) ringIndex = 0
        return built
    }

    /**
     * One plain catalog search hit — the metadata the source-search popup row
     * renders (no stream resolution; tapping a row resolves via the shared
     * title/artist text-search chain like every other source's popup results).
     */
    data class AppleMusicCandidate(
        val songId: String,
        val title: String,
        val artist: String?,
        val thumbnailUrl: String?,
        val durationMs: Long?,
    )

    /**
     * Text search over the Apple Music catalog for the source-search popup.
     * The catalog search needs a developer JWT but NOT necessarily a signed-in
     * user: falls back to the auto-scraped web-player token when no
     * user-pasted developer token exists, and to an anonymous storefront
     * lookup when no personal/pool media-user token exists (catalog data is
     * public; a 401/403 simply yields no results, same as before).
     */
    suspend fun searchCandidates(
        query: String,
        limit: Int = 8,
    ): List<AppleMusicCandidate> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val devToken =
                devToken() ?: AppleMusicProvider.currentDevToken() ?: return@withContext emptyList()
            var ringEntries = accountRing()
            if (ringEntries.isEmpty()) {
                ringEntries = listOf(RingEntry("", null))
            }

            for (attempt in ringEntries.indices) {
                val index = (ringIndex + attempt) % ringEntries.size
                val entry = ringEntries[index]
                val rows =
                    runCatching {
                        searchCatalogRows(query, limit, entry.token, devToken)
                    }.getOrElse { error ->
                        if (error !is AuthException) {
                            Log.w(TAG, "search failed: ${error.message}")
                            return@withContext emptyList()
                        }
                        Log.w(TAG, "media-user-token rejected — rotating account ${index + 1}/${ringEntries.size}")
                        if (entry.poolId != null) {
                            PoolAccountManager.report(
                                service = "apple-music",
                                kind = "account",
                                id = entry.poolId,
                                reportType = "dead",
                            )
                        }
                        null
                    }
                if (rows != null) {
                    ringIndex = index
                    return@withContext rows
                }
            }
            emptyList()
        }

    /** One catalog search pass with a specific media-user-token (blank = anonymous). */
    private suspend fun searchCatalogRows(
        query: String,
        limit: Int,
        mediaToken: String,
        devToken: String,
    ): List<AppleMusicCandidate> =
        withContext(Dispatchers.IO) {
            val storefront = resolveStorefront()
            val url =
                "$AMP_BASE/v1/catalog/$storefront/search".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("term", query)
                    .addQueryParameter("types", "songs")
                    .addQueryParameter("limit", limit.coerceAtMost(25).toString())
                    .build()
            // An empty media-user token means an anonymous catalog lookup —
            // sending a blank header would be rejected outright, so the header
            // is simply omitted (catalog search does not require a user).
            val request =
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $devToken")
                    .apply {
                        if (mediaToken.isNotBlank()) header("Media-User-Token", mediaToken)
                    }
                    .header("Origin", "https://music.apple.com")
                    .header("Referer", "https://music.apple.com/")
                    .header("User-Agent", UA)
                    .get()
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403) throw AuthException()
                    Log.w(TAG, "search failed: %d".format(response.code))
                    return@withContext emptyList()
                }
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val songs =
                    root["results"]?.jsonObject?.get("songs")?.jsonObject?.get("data")?.jsonArray
                        ?: return@withContext emptyList()
                songs.mapNotNull { element ->
                    val song = element.jsonObject
                    val attributes = song["attributes"]?.jsonObject ?: return@mapNotNull null
                    val songId = song["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val name = attributes["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    AppleMusicCandidate(
                        songId = songId,
                        title = name,
                        artist = attributes["artistName"]?.jsonPrimitive?.contentOrNull,
                        thumbnailUrl =
                            attributes["artwork"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                                ?.replace("{w}", "300")
                                ?.replace("{h}", "300"),
                        durationMs =
                            attributes["durationInMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    )
                }
            }
        }

    /**
     * One playable Apple Music candidate. [playlistUrl] serves the HLS playlist whose
     * segments are byteranges of the single encrypted fMP4 at [mediaUrl].
     */
    data class AppleMusicStream(
        val songId: String,
        val hlsDataUri: String,
        val playlistUrl: String,
        val drmUri: String,
        val flavor: String,
        val matchedTitle: String,
        val matchedArtist: String?,
        val matchedAlbum: String?,
        val matchedDurationMs: Long?,
        val mediaUserToken: String,
        val developerToken: String,
    )

    /**
     * Search the catalog and resolve every plausible candidate to a playable stream. The
     * caller applies the shared metadata-match gate ([com.unshoo.pixelmusic.data.lossless.audiosource
     * .TitleMatch]) to pick the winner, so candidates are returned in search-rank order.
     * Empty when the tokens are missing/unauthorized or nothing resolved.
     */
    suspend fun resolveCandidates(
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        quality: AppleMusicQuality = AppleMusicQuality.LOSSLESS,
    ): List<AppleMusicStream> =
        withContext(Dispatchers.IO) {
            val devToken = devToken() ?: return@withContext emptyList()
            val ringEntries = accountRing()
            if (ringEntries.isEmpty()) return@withContext emptyList()

            // Walk the ring starting at the sticky winner; a 401/403 advances to the next
            // account (reporting dead pool entries) until one resolves or all fail.
            for (attempt in ringEntries.indices) {
                val index = (ringIndex + attempt) % ringEntries.size
                val entry = ringEntries[index]
                val streams =
                    runCatching {
                        resolveWithToken(entry.token, devToken, title, artists, quality)
                    }.getOrElse { error ->
                        if (error !is AuthException) {
                            Log.w(TAG, "resolve failed: ${error.message}")
                            return@withContext emptyList()
                        }
                        Log.w(TAG, "media-user-token rejected — rotating account ${index + 1}/${ringEntries.size}")
                        if (entry.poolId != null) {
                            PoolAccountManager.report(
                                service = "apple-music",
                                kind = "account",
                                id = entry.poolId,
                                reportType = "dead",
                            )
                        }
                        null
                    }
                if (streams != null) {
                    ringIndex = index // sticky winner — next playback starts here
                    return@withContext streams
                }
            }
            emptyList()
        }

    /** One resolve pass with a specific media-user-token. Throws [AuthException] on 401/403. */
    private suspend fun resolveWithToken(
        mediaToken: String,
        devToken: String,
        title: String,
        artists: List<String>,
        quality: AppleMusicQuality,
    ): List<AppleMusicStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val storefront = resolveStorefront()

                val query = if (title.contains(artists.firstOrNull().orEmpty(), ignoreCase = true)) {
                    title
                } else {
                    "${artists.firstOrNull().orEmpty()} $title".trim()
                }
                val candidates = searchCatalogRows(query, 5, mediaToken, devToken)
                if (candidates.isEmpty()) return@runCatching emptyList()

                // Resolve up to the first few candidates; stop early once we have three
                // playable ones (webPlayback is the expensive call).
                val out = mutableListOf<AppleMusicStream>()
                for (cand in candidates.take(5)) {
                    if (out.size >= 3) break
                    webPlayback(cand.songId, devToken, mediaToken, storefront, quality, cand)?.let { out += it }
                }
                out
            }.getOrElse { error ->
                // Auth failures must propagate to the rotation loop — swallowing them here
                // would pin the ring to a dead account.
                if (error is AuthException) throw error
                Log.w(TAG, "resolve failed: ${error.message}")
                emptyList()
            }
        }

    /** Resolve one catalog id through the web-playback endpoint to a playable playlist. */
    private fun webPlayback(
        songId: String,
        devToken: String,
        mediaToken: String,
        storefront: String,
        quality: AppleMusicQuality,
        candidateMeta: AppleMusicCandidate? = null,
    ): AppleMusicStream? {
        val body = """{"salableAdamId":"$songId","language":"en-US"}""".toRequestBody(JSON_MEDIA)
        val request =
            Request.Builder()
                .url(WEB_PLAYBACK_URL)
                .post(body)
                .header("Authorization", "Bearer $devToken")
                .header("Media-User-Token", mediaToken)
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
                .header("User-Agent", UA)
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) throw AuthException()
                Log.w(TAG, "webPlayback failed for %s: %d".format(songId, response.code))
                return null
            }
            val root = runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }
                .getOrNull() ?: return null
            val song = root["songList"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val assets = song["assets"]?.jsonArray ?: return null

            data class Asset(val flavor: String, val url: String, val kbps: Int)

            val candidates =
                assets.mapNotNull { element ->
                    val asset = element.jsonObject
                    val flavor = asset["flavor"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val url = asset["URL"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!flavor.contains("ctrp")) return@mapNotNull null
                    val kbps = Regex("(\\d+)$").find(flavor)?.groupValues?.last()?.toIntOrNull() ?: 0
                    Asset(flavor, url, kbps)
                }.sortedByDescending { it.kbps }

            val wantedFlavor = if (quality == AppleMusicQuality.AAC) "ctrp64" else "ctrp256"
            val asset = candidates.firstOrNull { it.flavor.endsWith(wantedFlavor) }
                ?: candidates.firstOrNull { it.flavor.endsWith("ctrp256") }
                ?: candidates.firstOrNull() ?: return null

            val playlistUrl = asset.url
            val playlistRequest = Request.Builder()
                .url(playlistUrl)
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
                .header("User-Agent", UA)
                .build()
            val playlistText = client.newCall(playlistRequest).execute().use { res ->
                if (!res.isSuccessful) return null
                res.body?.string().orEmpty()
            }
            if (playlistText.isBlank()) return null

            val (keyUri, keyId) = AppleHls.legacyKey(playlistText) ?: run {
                Log.w(TAG, "Apple Music HLS playlist has no Widevine key for $songId")
                return null
            }
            val pssh = AppleHls.psshFor(keyId)
            val rewritten = AppleHls.rewriteLegacy(playlistText, playlistUrl, keyUri, pssh)
            val hlsDataUri = "data:application/vnd.apple.mpegurl;base64," +
                android.util.Base64.encodeToString(rewritten.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

            val metadata = song["metadata"] as? JsonObject
            val resolvedTitle = metadata?.title()?.takeIf { it.isNotBlank() } ?: candidateMeta?.title ?: songId
            val resolvedArtist = metadata?.artist()?.takeIf { it.isNotBlank() } ?: candidateMeta?.artist
            val resolvedAlbum = metadata?.album()?.takeIf { it.isNotBlank() }
            val resolvedDuration = metadata?.durationMs() ?: candidateMeta?.durationMs
            return AppleMusicStream(
                songId = songId,
                hlsDataUri = hlsDataUri,
                playlistUrl = playlistUrl,
                drmUri = keyUri,
                flavor = asset.flavor,
                matchedTitle = resolvedTitle,
                matchedArtist = resolvedArtist,
                matchedAlbum = resolvedAlbum,
                matchedDurationMs = resolvedDuration,
                mediaUserToken = mediaToken,
                developerToken = devToken,
            )
        }
    }

    private fun JsonObject.title(): String? = this["title"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.artist(): String? =
        this["artistName"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.album(): String? =
        this["albumName"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.durationMs(): Long? =
        this["trackDuration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
}
