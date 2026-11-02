package com.unshoo.pixelmusic.data.addons

import com.unshoo.pixelmusic.data.lossless.audiosource.DirectStream
import com.unshoo.pixelmusic.data.lossless.audiosource.TitleMatch
import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

/**
 * Multi-instance Monochrome FLAC / Hi-Res resolver with automatic mirror failover.
 *
 * Implements Monochrome's full upstream mirror list (`monochrome-music/monochrome` +
 * `Mochy-Music/MonochromeModule.kt`) across all three active server shapes:
 *  1. **HiFi API v2** (`/search/?s={q}` + `/track/?id={id}&quality=HI_RES_LOSSLESS` returning
 *     `OriginalTrackUrl` or base64 JSON/MPD `manifest`).
 *  2. **Monochrome Streamer** (`/search/tracks?q={q}&limit=6` + `/track/{id}`).
 *  3. **Monochrome Relay** (`/search?q={q}` + `/s/{id}?q=flac` with `X-Relay-Client: relay/2`).
 *
 * Unreachable or 5xx instances enter [AddonHealthTracker] cooldown so subsequent songs skip dead
 * mirrors in 0 ms and immediately hit the fastest healthy server.
 */
object MonochromeInstancePool {
    private const val TAG = "MonochromePool"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    val DEFAULT_INSTANCES: List<String> = listOf(
        "https://api.monochrome.tf",
        "https://eu-central.monochrome.tf",
        "https://us-west.monochrome.tf",
        "https://arran.monochrome.tf",
        "https://triton.squid.wtf",
        "https://wolf.qqdl.site",
        "https://maus.qqdl.site",
        "https://vogel.qqdl.site",
        "https://hund.qqdl.site",
        "https://katze.qqdl.site",
        "https://tracks.monochrome.st",
    )

    fun activeInstances(customRaw: String? = null): List<String> {
        val custom = customRaw
            ?.split('\n', ',', ';')
            ?.map { it.trim().trimEnd('/') }
            ?.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .orEmpty()
        val combined = (custom + DEFAULT_INSTANCES).distinct()
        val now = System.currentTimeMillis()
        val healthy = combined.filter { AddonHealthTracker.isAvailable("mono:$it", now) }
            .sortedBy { instance ->
                val st = AddonHealthTracker.stateOf("mono:$instance")
                if (st.avgLatencyMs > 0L) st.avgLatencyMs else 600L
            }
        return healthy.ifEmpty { combined.take(3) }
    }

    fun resolve(
        httpClient: OkHttpClient,
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        isrc: String? = null,
        customInstancesRaw: String? = null,
        trustedOverride: Boolean = false,
    ): DirectStream? {
        val query = AddonTrackMatcher.buildSearchQuery(title, artists)
        if (query.isBlank()) return null
        val encoded = URLEncoder.encode(query, "UTF-8")

        val instances = activeInstances(customInstancesRaw).take(4)
        for (base in instances) {
            val healthKey = "mono:$base"
            val startedAt = System.currentTimeMillis()
            val resolved = runCatching {
                if (base.contains("monochrome.st", ignoreCase = true)) {
                    tryStreamerProtocol(httpClient, base, encoded, title, artists, album, durationMs, isrc, trustedOverride)
                } else {
                    tryHifiApiProtocol(httpClient, base, encoded, title, artists, album, durationMs, isrc, trustedOverride)
                        ?: tryRelayProtocol(httpClient, base, encoded, title, artists, album, durationMs, isrc, trustedOverride)
                        ?: tryStreamerProtocol(httpClient, base, encoded, title, artists, album, durationMs, isrc, trustedOverride)
                }
            }.onFailure { err ->
                if (err is IOException) {
                    AddonHealthTracker.recordFailure(healthKey, err.message ?: "Unreachable")
                }
                Timber.tag(TAG).d(err, "Monochrome instance %s failed for '%s'", base, title)
            }.getOrNull()

            if (resolved != null) {
                AddonHealthTracker.recordSuccess(healthKey, System.currentTimeMillis() - startedAt)
                return resolved
            }
        }
        return null
    }

    private fun tryHifiApiProtocol(
        httpClient: OkHttpClient,
        base: String,
        encodedQuery: String,
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        isrc: String?,
        trustedOverride: Boolean,
    ): DirectStream? {
        val searchUrl = "$base/search/?s=$encodedQuery"
        val body = getStringOrThrowOn5xx(httpClient, searchUrl, "mono:$base") ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val items: JSONArray = root.optJSONObject("data")?.optJSONArray("items")
            ?: root.optJSONArray("items")
            ?: return null

        val candidates = ArrayList<AddonTrackCandidate>()
        for (i in 0 until minOf(items.length(), 15)) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val itemTitle = item.optString("title").trim()
            if (id.isEmpty() || itemTitle.isEmpty()) continue
            val itemArtists = HttpAddonClient.parseArtists(item)
            val itemAlbum = item.optJSONObject("album")?.optString("title")?.ifBlank { null }
            val durSec = item.optLong("duration", 0L)
            val durMs = if (durSec > 10_000L) durSec else if (durSec > 0L) durSec * 1_000L else null
            val quality = item.optString("audioQuality")
            candidates.add(
                AddonTrackCandidate(
                    id = id,
                    title = itemTitle,
                    artists = itemArtists,
                    album = itemAlbum,
                    durationMs = durMs,
                    isrc = item.optString("isrc").ifBlank { null },
                    qualityLabel = quality.ifBlank { "FLAC" },
                    isHiRes = quality.contains("HI_RES", true) || quality.contains("LOSSLESS", true),
                )
            )
        }

        val winner = AddonTrackMatcher.rankCandidates(
            wantedTitle = title,
            wantedArtists = artists,
            wantedAlbum = album,
            wantedDurationMs = durationMs,
            wantedIsrc = isrc,
            candidates = candidates,
            trustedOverride = trustedOverride,
        ).firstOrNull()?.candidate ?: return null

        for (qualityTier in listOf("HI_RES_LOSSLESS", "LOSSLESS")) {
            val trackBody = getStringOrThrowOn5xx(
                httpClient,
                "$base/track/?id=${winner.id}&quality=$qualityTier",
                "mono:$base",
            ) ?: continue
            val parsedStream = parseHifiTrackManifest(trackBody, winner)
            if (parsedStream != null) return parsedStream
        }
        return null
    }

    internal fun parseHifiTrackManifest(rawBody: String, winner: AddonTrackCandidate): DirectStream? {
        val root = runCatching { JSONObject(rawBody) }.getOrNull() ?: return null
        val dataObj = root.optJSONObject("data") ?: root

        // Direct OriginalTrackUrl shape
        val directOriginal = dataObj.optString("OriginalTrackUrl").trim()
        val bitDepth = dataObj.optInt("bitDepth", 16).takeIf { it in 16..32 } ?: 16
        val sampleRate = dataObj.optInt("sampleRate", 44_100).takeIf { it > 0 } ?: 44_100
        if (directOriginal.startsWith("http", ignoreCase = true)) {
            return buildMonochromeDirectStream(
                uri = directOriginal,
                winner = winner,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
            )
        }

        // Base64 `manifest` shape (`application/vnd.tidal.bts` JSON or `application/dash+xml` MPD)
        val manifestB64 = dataObj.optString("manifest").trim()
        if (manifestB64.isEmpty()) return null
        val manifestMime = dataObj.optString("manifestMimeType").lowercase(Locale.ROOT)
        val decoded = runCatching { String(Base64.getDecoder().decode(manifestB64), Charsets.UTF_8) }.getOrNull()
            ?: return null

        if (manifestMime.contains("dash+xml") || decoded.trimStart().startsWith("<")) {
            if (decoded.contains("<ContentProtection", ignoreCase = true)) return null
            val addonStream = AddonResolvedStream(
                addonId = "monochrome",
                addonName = "Monochrome (FLAC)",
                url = "https://api.monochrome.tf",
                audioMimeType = "audio/flac",
                codecs = "flac",
                transport = AddonTransport.DASH,
                inlineManifest = decoded,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                qualityLabel = if (bitDepth >= 24 || sampleRate > 48_000) "Monochrome Hi-Res FLAC" else "Monochrome FLAC",
                isLossless = true,
                isHiRes = bitDepth >= 24 || sampleRate > 48_000,
                matchedTitle = winner.title,
                matchedArtist = winner.artists.firstOrNull(),
                matchedAlbum = winner.album,
                matchedDurationMs = winner.durationMs,
            )
            val inlineUri = AddonStreamRegistry.register(addonStream)
            return buildMonochromeDirectStream(
                uri = inlineUri,
                winner = winner,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
            )
        }

        val btsJson = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        val urls = btsJson.optJSONArray("urls")
        val firstUrl = urls?.optString(0)?.trim().orEmpty()
        if (!firstUrl.startsWith("http", ignoreCase = true)) return null
        val codecs = btsJson.optString("codecs", "flac").lowercase(Locale.ROOT)
        val mime = btsJson.optString("mimeType", "audio/flac")
        return DirectStream(
            uri = firstUrl,
            mimeType = mime,
            codecs = codecs,
            contentLength = null,
            label = if (bitDepth >= 24 || sampleRate > 48_000) "Monochrome Hi-Res FLAC" else "Monochrome FLAC",
            source = AudioSourceType.MONOCHROME,
            matchedTitle = winner.title,
            matchedArtist = winner.artists.firstOrNull(),
            matchedAlbum = winner.album,
            matchedDurationMs = winner.durationMs,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
        )
    }

    private fun tryStreamerProtocol(
        httpClient: OkHttpClient,
        base: String,
        encodedQuery: String,
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        isrc: String?,
        trustedOverride: Boolean,
    ): DirectStream? {
        val body = getStringOrThrowOn5xx(httpClient, "$base/search/tracks?q=$encodedQuery&limit=6", "mono:$base")
            ?: return null
        val tracks = runCatching { JSONObject(body).optJSONArray("tracks") }.getOrNull() ?: return null
        val candidates = ArrayList<AddonTrackCandidate>()
        for (i in 0 until tracks.length()) {
            val item = tracks.optJSONObject(i) ?: continue
            if (!item.optBoolean("playable", true)) continue
            val trackId = item.optString("id").ifBlank { item.optString("trackId") }.trim()
            val itemTitle = item.optString("title").trim()
            if (trackId.isEmpty() || itemTitle.isEmpty()) continue
            val itemArtists = HttpAddonClient.parseArtists(item)
            val dur = item.optLong("duration", 0L)
            val effDur = if (dur > 10_000L) dur else if (dur > 0L) dur * 1_000L else null
            candidates.add(
                AddonTrackCandidate(
                    id = trackId,
                    title = itemTitle,
                    artists = itemArtists,
                    album = item.optString("albumTitle").ifBlank { null },
                    durationMs = effDur,
                    isrc = item.optString("isrc").ifBlank { null },
                )
            )
        }
        val winner = AddonTrackMatcher.rankCandidates(
            wantedTitle = title,
            wantedArtists = artists,
            wantedAlbum = album,
            wantedDurationMs = durationMs,
            wantedIsrc = isrc,
            candidates = candidates,
            trustedOverride = trustedOverride,
        ).firstOrNull()?.candidate ?: return null

        return buildMonochromeDirectStream(
            uri = "$base/track/${winner.id}",
            winner = winner,
            sampleRate = 44_100,
            bitDepth = 16,
        )
    }

    private fun tryRelayProtocol(
        httpClient: OkHttpClient,
        base: String,
        encodedQuery: String,
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        isrc: String?,
        trustedOverride: Boolean,
    ): DirectStream? {
        val req = Request.Builder()
            .url("$base/search?q=$encodedQuery")
            .header("User-Agent", BROWSER_UA)
            .header("X-Relay-Client", "relay/2")
            .build()
        val body = httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } ?: return null

        val candidates = HttpAddonClient(httpClient).parseTracksPayload(body)
        val winner = AddonTrackMatcher.rankCandidates(
            wantedTitle = title,
            wantedArtists = artists,
            wantedAlbum = album,
            wantedDurationMs = durationMs,
            wantedIsrc = isrc,
            candidates = candidates,
            trustedOverride = trustedOverride,
        ).firstOrNull()?.candidate ?: return null

        val streamUrl = "$base/s/${winner.id}?q=flac"
        AddonStreamRegistry.register(
            AddonResolvedStream(
                addonId = "monochrome",
                addonName = "Monochrome (FLAC)",
                url = streamUrl,
                headers = mapOf("X-Relay-Client" to "relay/2", "User-Agent" to BROWSER_UA),
                audioMimeType = "audio/flac",
                codecs = "flac",
                transport = AddonTransport.PROGRESSIVE,
                isLossless = true,
            )
        )
        return buildMonochromeDirectStream(
            uri = streamUrl,
            winner = winner,
            sampleRate = 44_100,
            bitDepth = 16,
        )
    }

    private fun buildMonochromeDirectStream(
        uri: String,
        winner: AddonTrackCandidate,
        sampleRate: Int,
        bitDepth: Int,
    ): DirectStream = DirectStream(
        uri = uri,
        mimeType = "audio/flac",
        codecs = "flac",
        contentLength = null,
        label = if (bitDepth >= 24 || sampleRate > 48_000) "Monochrome Hi-Res FLAC" else "Monochrome FLAC (Free)",
        source = AudioSourceType.MONOCHROME,
        matchedTitle = winner.title,
        matchedArtist = winner.artists.firstOrNull(),
        matchedAlbum = winner.album,
        matchedDurationMs = winner.durationMs,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
    )

    private fun getStringOrThrowOn5xx(httpClient: OkHttpClient, url: String, healthKey: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (resp.code == 429) {
                AddonHealthTracker.noteOriginRateLimit(url, resp.header("Retry-After"))
                AddonHealthTracker.recordFailure(healthKey, "HTTP 429")
                return null
            }
            if (resp.code >= 500) {
                AddonHealthTracker.recordFailure(healthKey, "HTTP ${resp.code}")
                return null
            }
            if (!resp.isSuccessful) return null
            return resp.body?.string()?.takeIf { it.isNotBlank() }
        }
    }
}
