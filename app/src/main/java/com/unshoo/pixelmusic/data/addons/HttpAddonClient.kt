package com.unshoo.pixelmusic.data.addons

import android.media.MediaCodecList
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.Base64
import java.util.Locale

/**
 * HTTP JSON Addon protocol client compatible with BitChord Hi-Res Addons, Convx Eclipse Addons,
 * Stremio-style music manifests (`tidomeow`, `kat`), and Mochy WebModules.
 *
 * Endpoints:
 *  - `GET {base}/manifest.json`
 *  - `GET {base}/isrc/{isrc}?{settings}` (when `"isrc"` is declared in `resources`)
 *  - `GET {base}/search?q={query}&{settings}`
 *  - `GET {base}/stream/{trackId}?{settings}` (with fallback to search row's `streamURL`)
 */
class HttpAddonClient(
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "HttpAddonClient"
        private const val USER_AGENT = "PixelMusic/2.0 (Android; AddonClient)"

        private val AUDIO_CODECS = setOf(
            "flac", "alac", "wav", "aiff", "mp3", "aac", "he-aac", "m4a", "mp4",
            "ogg", "opus", "vorbis", "webm", "eac3", "eac3-joc", "ec3-joc", "ac3", "ac4",
        )

        @Volatile
        private var cachedEac3Supported: Boolean? = null

        fun deviceSupportsAtmos(): Boolean {
            cachedEac3Supported?.let { return it }
            val supported = runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return@runCatching false
                val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                codecs.any { info ->
                    !info.isEncoder && info.supportedTypes.any { mime ->
                        mime.equals("audio/eac3-joc", ignoreCase = true) ||
                            mime.equals("audio/eac3", ignoreCase = true)
                    }
                }
            }.getOrDefault(false)
            cachedEac3Supported = supported
            return supported
        }

        fun isMalformedStreamUrl(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return true
            if (trimmed.startsWith("data:", ignoreCase = true)) return false
            val parsed = trimmed.toHttpUrlOrNull() ?: return true
            // Guard against doubled-origin bug (`https://host/https://other/...`) seen in broken proxies
            val path = parsed.encodedPath
            if (path.contains("/http://", ignoreCase = true) || path.contains("/https://", ignoreCase = true)) {
                return true
            }
            return false
        }

        internal fun parseArtists(item: JSONObject): List<String> {
            val raw = item.opt("artists")?.takeUnless { it == JSONObject.NULL }
                ?: item.opt("artist")?.takeUnless { it == JSONObject.NULL }
                ?: item.opt("artistNames")?.takeUnless { it == JSONObject.NULL }
            return when (raw) {
                is JSONArray -> buildList {
                    for (i in 0 until raw.length()) {
                        when (val entry = raw.opt(i)) {
                            is JSONObject -> entry.optString("name").trim().takeIf { it.isNotEmpty() }?.let(::add)
                            is String -> entry.trim().takeIf { it.isNotEmpty() }?.let(::add)
                        }
                    }
                }
                is JSONObject -> listOfNotNull(raw.optString("name").trim().takeIf { it.isNotEmpty() })
                is String -> raw.split(Regex("\\s*(?:,|;|/|(?:\\s+&\\s+))\\s*"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { listOfNotNull(raw.trim().takeIf { it.isNotEmpty() }) }
                else -> emptyList()
            }
        }

        internal fun decodeDataUriManifest(dataUri: String): Pair<String, AddonTransport>? {
            val header = dataUri.substringBefore(',', "")
            val payload = dataUri.substringAfter(',', "")
            if (payload.isEmpty()) return null
            val text = if (header.contains(";base64", ignoreCase = true)) {
                runCatching { String(Base64.getDecoder().decode(payload.trim()), Charsets.UTF_8) }.getOrNull()
            } else {
                runCatching { java.net.URLDecoder.decode(payload, "UTF-8") }.getOrNull()
            } ?: return null
            val transport = when {
                header.contains("mpegurl", ignoreCase = true) || text.trimStart().startsWith("#EXTM3U") -> AddonTransport.HLS
                else -> AddonTransport.DASH
            }
            return text to transport
        }

        internal fun parseSampleRateHz(json: JSONObject, qualityText: String): Int? {
            if (json.has("sampleRate") && !json.isNull("sampleRate")) {
                val raw = json.optDouble("sampleRate", 0.0)
                if (raw > 0) return if (raw < 1000.0) (raw * 1000.0).toInt() else raw.toInt()
            }
            if (json.has("sampleRateHz") && !json.isNull("sampleRateHz")) {
                val raw = json.optInt("sampleRateHz", 0)
                if (raw > 0) return raw
            }
            val khzMatch = Regex("""(\d+(?:\.\d+)?)\s*kHz""", RegexOption.IGNORE_CASE).find(qualityText)
            if (khzMatch != null) {
                val khz = khzMatch.groupValues[1].toDoubleOrNull()
                if (khz != null && khz > 0) return (khz * 1000.0).toInt()
            }
            return null
        }

        internal fun parseBitDepth(json: JSONObject, qualityText: String): Int? {
            for (k in listOf("bitDepth", "bits", "bitsPerSample")) {
                if (json.has(k) && !json.isNull(k)) {
                    val v = json.optInt(k, 0)
                    if (v in 8..64) return v
                }
            }
            val bitMatch = Regex("""(\d{2})\s*-?\s*bit""", RegexOption.IGNORE_CASE).find(qualityText)
            if (bitMatch != null) {
                val b = bitMatch.groupValues[1].toIntOrNull()
                if (b in 16..32) return b
            }
            return null
        }

        internal fun parseBitrateBps(
            json: JSONObject,
            qualityText: String,
            codec: String,
            sampleRate: Int?,
            bitDepth: Int?,
        ): Int {
            for (k in listOf("bitrate", "kbps")) {
                if (json.has(k) && !json.isNull(k)) {
                    val v = json.optInt(k, 0)
                    if (v > 0) return if (v < 10_000) v * 1_000 else v
                }
            }
            val kbpsMatch = Regex("""(\d{2,4})\s*kbps""", RegexOption.IGNORE_CASE).find(qualityText)
            if (kbpsMatch != null) {
                val kbps = kbpsMatch.groupValues[1].toIntOrNull()
                if (kbps != null && kbps > 0) return kbps * 1_000
            }
            return when {
                codec in setOf("flac", "alac", "wav", "aiff") -> {
                    val sr = sampleRate ?: 44_100
                    val bd = bitDepth ?: 16
                    (sr * bd * 2 * 0.65).toInt().coerceAtLeast(900_000)
                }
                codec.contains("eac3") -> 768_000
                else -> 320_000
            }
        }

        internal fun buildDisplayQualityLabel(
            rawQuality: String,
            codec: String,
            bitDepth: Int?,
            sampleRate: Int?,
            bitrateBps: Int,
            isAtmos: Boolean,
            isHiRes: Boolean,
            isLossless: Boolean,
        ): String {
            if (isAtmos) return "Dolby Atmos"
            if (rawQuality.isNotBlank() && !rawQuality.equals("HIRES", true) && !rawQuality.equals("LOSSLESS", true) && !rawQuality.equals("HIGH", true)) {
                return rawQuality
            }
            if (isLossless) {
                val parts = ArrayList<String>(3)
                if (bitDepth != null) parts.add("${bitDepth}-bit")
                if (sampleRate != null) {
                    val khz = if (sampleRate % 1000 == 0) "${sampleRate / 1000}kHz" else String.format(Locale.US, "%.1fkHz", sampleRate / 1000.0)
                    parts.add(khz)
                }
                parts.add(codec.uppercase(Locale.ROOT))
                return if (parts.size > 1) parts.joinToString(" / ") else if (isHiRes) "Hi-Res FLAC" else "FLAC"
            }
            val kbps = if (bitrateBps > 0) "${bitrateBps / 1000}kbps " else ""
            return "$kbps${codec.uppercase(Locale.ROOT)}".trim()
        }
    }

    suspend fun searchTracks(
        addon: InstalledAddon,
        query: String,
        isMeteredNetwork: Boolean = false,
        preferLossless: Boolean = true,
    ): List<AddonTrackCandidate> = withContext(Dispatchers.IO) {
        val base = addon.sourceUrl.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext emptyList()
        if (AddonHealthTracker.isOriginRateLimited(base)) return@withContext emptyList()

        val baseUrl = base.toHttpUrlOrNull() ?: return@withContext emptyList()
        val urlBuilder = baseUrl.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
        appendNegotiatedSettings(urlBuilder, addon, isMeteredNetwork, preferLossless)

        val body = executeJsonGet(addon.unifiedSourceId, urlBuilder.build().toString()) ?: return@withContext emptyList()
        parseTracksPayload(body)
    }

    suspend fun lookupByIsrc(
        addon: InstalledAddon,
        isrc: String,
        isMeteredNetwork: Boolean = false,
        preferLossless: Boolean = true,
    ): List<AddonTrackCandidate> = withContext(Dispatchers.IO) {
        if (isrc.isBlank()) return@withContext emptyList()
        val supportsIsrc = addon.resources.any { it.equals("isrc", ignoreCase = true) }
        if (!supportsIsrc) return@withContext emptyList()
        val base = addon.sourceUrl.trim().trimEnd('/')
        val baseUrl = base.toHttpUrlOrNull() ?: return@withContext emptyList()
        if (AddonHealthTracker.isOriginRateLimited(base)) return@withContext emptyList()

        val urlBuilder = baseUrl.newBuilder()
            .addPathSegment("isrc")
            .addPathSegment(isrc.trim())
        appendNegotiatedSettings(urlBuilder, addon, isMeteredNetwork, preferLossless)

        val body = executeJsonGet(addon.unifiedSourceId, urlBuilder.build().toString()) ?: return@withContext emptyList()
        parseTracksPayload(body)
    }

    suspend fun resolveStream(
        addon: InstalledAddon,
        candidate: AddonTrackCandidate,
        isMeteredNetwork: Boolean = false,
        preferLossless: Boolean = true,
        forDownload: Boolean = false,
    ): AddonResolvedStream? = withContext(Dispatchers.IO) {
        if (forDownload && !addon.allowDownloads) {
            Timber.tag(TAG).d("%s: skipping download resolve (allowDownloads=false)", addon.name)
            return@withContext null
        }

        val base = addon.sourceUrl.trim().trimEnd('/')
        val baseUrl = base.toHttpUrlOrNull() ?: return@withContext null
        if (AddonHealthTracker.isOriginRateLimited(base)) return@withContext null

        val streamUrlBuilder = baseUrl.newBuilder()
            .addPathSegment("stream")
            .addPathSegment(candidate.id)
        appendNegotiatedSettings(streamUrlBuilder, addon, isMeteredNetwork, preferLossless)

        val startedAt = System.currentTimeMillis()
        val rawBody = executeJsonGet(
            sourceId = addon.unifiedSourceId,
            url = streamUrlBuilder.build().toString(),
            treat404AsSoftMiss = true,
        )

        val streamJson = rawBody?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (streamJson != null) {
            val errMsg = streamJson.optString("error").trim()
            if (errMsg.isNotEmpty() && streamJson.optString("url").isBlank() && streamJson.optString("streamUrl").isBlank()) {
                Timber.tag(TAG).w("%s: stream error for %s: %s", addon.name, candidate.id, errMsg)
            } else {
                parseStreamObject(addon, candidate, streamJson)?.let { resolved ->
                    AddonHealthTracker.recordSuccess(addon.unifiedSourceId, System.currentTimeMillis() - startedAt)
                    return@withContext resolved
                }
            }
        }

        // Fallback: use the search row's own direct `streamURL` when `/stream/{id}` is absent/404s
        val fallbackDirect = candidate.directStreamUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (fallbackDirect != null && !isMalformedStreamUrl(fallbackDirect)) {
            val fallbackObj = JSONObject().apply {
                put("url", fallbackDirect)
                candidate.formatHint?.let { put("format", it) }
                candidate.qualityLabel?.let { put("quality", it) }
            }
            return@withContext parseStreamObject(addon, candidate, fallbackObj)
        }

        null
    }

    suspend fun resolveForSong(
        addon: InstalledAddon,
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        isrc: String?,
        isMeteredNetwork: Boolean = false,
        preferLossless: Boolean = true,
        forDownload: Boolean = false,
        trustedOverride: Boolean = false,
    ): AddonResolvedStream? = withContext(Dispatchers.IO) {
        if (forDownload && !addon.allowDownloads) return@withContext null

        val startedAt = System.currentTimeMillis()
        val isrcCandidates = if (!isrc.isNullOrBlank()) {
            lookupByIsrc(addon, isrc, isMeteredNetwork, preferLossless)
        } else {
            emptyList()
        }

        val candidates = isrcCandidates.ifEmpty {
            val query = AddonTrackMatcher.buildSearchQuery(title, artists)
            searchTracks(addon, query, isMeteredNetwork, preferLossless)
        }

        if (candidates.isEmpty()) return@withContext null

        val ranked = AddonTrackMatcher.rankCandidates(
            wantedTitle = title,
            wantedArtists = artists,
            wantedAlbum = album,
            wantedDurationMs = durationMs,
            wantedIsrc = isrc,
            candidates = candidates,
            trustedOverride = trustedOverride,
        )
        if (ranked.isEmpty()) return@withContext null

        // Try up to top 2 ranked matches in case the #1 track ID is geo-restricted or unavailable
        for (entry in ranked.take(2)) {
            val stream = resolveStream(
                addon = addon,
                candidate = entry.candidate,
                isMeteredNetwork = isMeteredNetwork,
                preferLossless = preferLossless,
                forDownload = forDownload,
            )
            if (stream != null) {
                AddonHealthTracker.recordSuccess(addon.unifiedSourceId, System.currentTimeMillis() - startedAt)
                return@withContext stream
            }
        }
        null
    }

    private fun appendNegotiatedSettings(
        urlBuilder: okhttp3.HttpUrl.Builder,
        addon: InstalledAddon,
        isMeteredNetwork: Boolean,
        preferLossless: Boolean,
    ) {
        val resolved = LinkedHashMap(addon.resolvedSettings(isMeteredNetwork))
        val qualitySpec = addon.qualitySettingSpec()

        if (qualitySpec != null) {
            val userExplicitlySet = if (qualitySpec.perNetwork && isMeteredNetwork) {
                addon.mobileSettingsValues.containsKey(qualitySpec.key) || addon.settingsValues.containsKey(qualitySpec.key)
            } else {
                addon.settingsValues.containsKey(qualitySpec.key)
            }
            if (!userExplicitlySet) {
                negotiateQualityOption(qualitySpec.options, preferLossless, isMeteredNetwork)?.let { picked ->
                    resolved[qualitySpec.key] = picked
                }
            }
        } else if ("quality" !in resolved) {
            resolved["quality"] = when {
                !preferLossless -> "HIGH"
                isMeteredNetwork -> "LOSSLESS"
                else -> "HIRES"
            }
        }

        // Atmos negotiation (BitChord / Eclipse spec): only request Atmos if supported on device
        val atmosKey = resolved.keys.firstOrNull { it.equals("atmos", ignoreCase = true) }
        if (atmosKey != null) {
            val valLower = resolved[atmosKey]?.lowercase(Locale.ROOT).orEmpty()
            if ((valLower == "true" || valLower == "1" || valLower == "auto") && !deviceSupportsAtmos()) {
                resolved.remove(atmosKey)
            }
        }

        for ((k, v) in resolved) {
            if (k.isNotBlank() && v.isNotBlank()) {
                urlBuilder.setQueryParameter(k, v)
            }
        }
    }

    private fun negotiateQualityOption(
        options: List<AddonSettingOption>,
        preferLossless: Boolean,
        isMeteredNetwork: Boolean,
    ): String? {
        if (options.isEmpty()) return null
        fun matchOption(vararg tokens: String): String? =
            options.firstOrNull { opt ->
                val hay = "${opt.value} ${opt.label}".lowercase(Locale.ROOT)
                tokens.any { hay.contains(it) }
            }?.value

        return when {
            !preferLossless -> matchOption("high", "320", "aac", "mp3") ?: options.last().value
            isMeteredNetwork -> matchOption("lossless", "16", "cd", "flac") ?: options.first().value
            else -> matchOption("hires", "hi-res", "hi_res", "max", "24", "master", "lossless", "flac")
                ?: options.first().value
        }
    }

    private fun executeJsonGet(
        sourceId: String,
        url: String,
        treat404AsSoftMiss: Boolean = false,
    ): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .get()
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 429) {
                    AddonHealthTracker.noteOriginRateLimit(url, resp.header("Retry-After"))
                    AddonHealthTracker.recordFailure(sourceId, "Rate limited (HTTP 429)")
                    return null
                }
                if (resp.code == 402) {
                    AddonHealthTracker.recordFailure(sourceId, "Deployment disabled / payment required (HTTP 402)", permanentHint = true)
                    return null
                }
                if (resp.code == 404 && treat404AsSoftMiss) {
                    return null
                }
                if (!resp.isSuccessful) {
                    if (resp.code >= 500 || resp.code == 403 || resp.code == 401) {
                        AddonHealthTracker.recordFailure(sourceId, "HTTP ${resp.code}")
                    }
                    return null
                }
                val text = resp.body?.string().orEmpty()
                if (text.contains("DEPLOYMENT_DISABLED", ignoreCase = true)) {
                    AddonHealthTracker.recordFailure(sourceId, "Deployment disabled", permanentHint = true)
                    return null
                }
                text.takeIf { it.isNotBlank() }
            }
        } catch (e: IOException) {
            AddonHealthTracker.recordFailure(sourceId, e.message ?: "Network error")
            Timber.tag(TAG).d(e, "HTTP addon request failed: %s", url)
            null
        }
    }

    internal fun parseTracksPayload(rawBody: String): List<AddonTrackCandidate> {
        val trimmed = rawBody.trim()
        val tracksArray: JSONArray? = when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            trimmed.startsWith("{") -> {
                val root = runCatching { JSONObject(trimmed) }.getOrNull()
                root?.optJSONArray("tracks")
                    ?: root?.optJSONArray("results")
                    ?: root?.optJSONArray("items")
                    ?: root?.optJSONObject("data")?.optJSONArray("items")
                    ?: root?.optJSONObject("data")?.optJSONArray("tracks")
            }
            else -> null
        }
        if (tracksArray == null) return emptyList()

        return buildList {
            for (i in 0 until minOf(tracksArray.length(), 40)) {
                val item = tracksArray.optJSONObject(i) ?: continue
                val id = item.optString("id").ifBlank { item.optString("trackId") }.trim()
                val title = item.optString("title").ifBlank { item.optString("name") }.trim()
                if (id.isEmpty() || title.isEmpty()) continue

                val artists = parseArtists(item)
                val album = when (val alb = item.opt("album")) {
                    is JSONObject -> alb.optString("title").ifBlank { alb.optString("name") }
                    is String -> alb
                    else -> item.optString("albumTitle")
                }.trim().ifBlank { null }

                val rawDur = when {
                    item.has("durationMs") && !item.isNull("durationMs") -> item.optLong("durationMs", 0L)
                    item.has("duration") && !item.isNull("duration") -> {
                        val d = item.optDouble("duration", 0.0)
                        if (d > 10_000) d.toLong() else (d * 1000.0).toLong()
                    }
                    else -> 0L
                }.takeIf { it > 0L }

                val qualityText = item.optString("audioQuality")
                    .ifBlank { item.optString("quality") }
                    .ifBlank { item.optString("format") }
                    .trim()
                    .ifBlank { null }

                val modesArr = item.optJSONArray("audioModes")
                val hasAtmosMode = (0 until (modesArr?.length() ?: 0)).any { idx ->
                    modesArr?.optString(idx)?.contains("ATMOS", ignoreCase = true) == true
                }
                val isAtmos = item.optBoolean("isAtmos", false) ||
                    item.optBoolean("atmos", false) ||
                    item.optString("audioMode").contains("ATMOS", ignoreCase = true) ||
                    hasAtmosMode
                val isHiRes = item.optBoolean("isHiRes", false) ||
                    qualityText?.contains("HI_RES", ignoreCase = true) == true ||
                    qualityText?.contains("HIRES", ignoreCase = true) == true ||
                    qualityText?.contains("24-bit", ignoreCase = true) == true ||
                    qualityText?.contains("24bit", ignoreCase = true) == true

                add(
                    AddonTrackCandidate(
                        id = id,
                        title = title,
                        artists = artists,
                        album = album,
                        durationMs = rawDur,
                        isrc = item.optString("isrc").trim().ifBlank { null },
                        explicit = if (item.has("explicit") && !item.isNull("explicit")) item.optBoolean("explicit") else null,
                        artworkUrl = item.optString("artworkURL")
                            .ifBlank { item.optString("albumArtworkURL") }
                            .ifBlank { item.optString("cover") }
                            .trim()
                            .ifBlank { null },
                        formatHint = item.optString("format").trim().ifBlank { null },
                        qualityLabel = qualityText,
                        isHiRes = isHiRes,
                        isAtmos = isAtmos,
                        directStreamUrl = item.optString("streamURL").ifBlank { item.optString("streamUrl") }.trim().ifBlank { null },
                    )
                )
            }
        }
    }

    internal fun parseStreamObject(
        addon: InstalledAddon,
        candidate: AddonTrackCandidate,
        json: JSONObject,
    ): AddonResolvedStream? {
        if (json.optBoolean("encrypted", false)) {
            Timber.tag(TAG).w("%s: rejecting encrypted stream for %s", addon.name, candidate.id)
            return null
        }

        val rawUrl = json.optString("url")
            .ifBlank { json.optString("streamUrl") }
            .ifBlank { json.optString("streamURL") }
            .trim()

        var inlineManifest: String? = json.optString("inlineManifest").trim().ifBlank { null }
        var effectiveUrl = rawUrl
        var dataUriTransport: AddonTransport? = null

        if (rawUrl.startsWith("data:", ignoreCase = true)) {
            val decoded = decodeDataUriManifest(rawUrl) ?: return null
            inlineManifest = decoded.first
            dataUriTransport = decoded.second
            effectiveUrl = addon.sourceUrl.ifBlank { "https://addon.local" }
        } else if (inlineManifest == null && rawUrl.isEmpty()) {
            return null
        } else if (rawUrl.isNotEmpty() && isMalformedStreamUrl(rawUrl)) {
            Timber.tag(TAG).w("%s: rejecting malformed stream URL: %s", addon.name, rawUrl.take(120))
            return null
        }

        val statedTransport = json.optString("manifest")
            .ifBlank { json.optString("streamType") }
            .ifBlank { json.optString("transport") }
            .trim()
            .lowercase(Locale.ROOT)
        val rawMime = json.optString("mimeType").ifBlank { json.optString("mediaType") }.trim()
        val urlExt = effectiveUrl.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(Locale.ROOT)

        val transport = when {
            dataUriTransport != null -> dataUriTransport
            statedTransport == "hls" || statedTransport == "m3u8" -> AddonTransport.HLS
            statedTransport == "dash" || statedTransport == "mpd" -> AddonTransport.DASH
            rawMime.contains("mpegurl", ignoreCase = true) -> AddonTransport.HLS
            rawMime.contains("dash+xml", ignoreCase = true) -> AddonTransport.DASH
            urlExt == "m3u8" -> AddonTransport.HLS
            urlExt == "mpd" -> AddonTransport.DASH
            inlineManifest != null && inlineManifest.trimStart().startsWith("#EXTM3U") -> AddonTransport.HLS
            inlineManifest != null && inlineManifest.contains("<MPD", ignoreCase = true) -> AddonTransport.DASH
            else -> AddonTransport.PROGRESSIVE
        }

        // Guard against DRM-protected inline manifests
        if (inlineManifest != null) {
            if (inlineManifest.contains("<ContentProtection", ignoreCase = true) ||
                (inlineManifest.contains("#EXT-X-KEY:") && !inlineManifest.contains("METHOD=NONE"))
            ) {
                Timber.tag(TAG).w("%s: rejecting DRM-protected inline manifest for %s", addon.name, candidate.id)
                return null
            }
        }

        val qualityText = json.optString("quality")
            .ifBlank { json.optString("streamQuality") }
            .ifBlank { json.optString("audioQuality") }
            .ifBlank { candidate.qualityLabel.orEmpty() }
            .trim()

        val statedCodec = listOf(
            json.optString("codec"),
            json.optString("fileCodec"),
            json.optString("codecs"),
            json.optString("format"),
            json.optString("container"),
            json.optString("containerFormat"),
            candidate.formatHint.orEmpty(),
        ).map { it.trim().lowercase(Locale.ROOT) }
            .firstOrNull { it in AUDIO_CODECS }

        val isAtmos = json.optBoolean("isAtmos", false) ||
            json.optBoolean("atmos", false) ||
            json.optString("audioMode").contains("ATMOS", ignoreCase = true) ||
            qualityText.contains("ATMOS", ignoreCase = true) ||
            statedCodec == "eac3-joc" || statedCodec == "ec3-joc"

        if (isAtmos && !deviceSupportsAtmos()) {
            Timber.tag(TAG).w("%s: rejecting Atmos rendition on device without E-AC3 decoder", addon.name)
            return null
        }

        val codec = when {
            statedCodec != null -> statedCodec
            isAtmos -> "eac3-joc"
            qualityText.contains("FLAC", ignoreCase = true) ||
                qualityText.contains("LOSSLESS", ignoreCase = true) ||
                qualityText.contains("HI-RES", ignoreCase = true) ||
                qualityText.contains("HIRES", ignoreCase = true) -> "flac"
            qualityText.contains("ALAC", ignoreCase = true) -> "alac"
            urlExt in AUDIO_CODECS -> urlExt
            else -> "flac"
        }

        val audioMime = when (codec) {
            "flac" -> "audio/flac"
            "alac" -> "audio/alac"
            "wav" -> "audio/wav"
            "aiff" -> "audio/aiff"
            "mp3" -> "audio/mpeg"
            "aac", "he-aac", "m4a", "mp4" -> "audio/mp4"
            "opus", "webm" -> "audio/webm"
            "ogg", "vorbis" -> "audio/ogg"
            "eac3-joc", "ec3-joc", "eac3" -> "audio/eac3-joc"
            else -> if (rawMime.startsWith("audio/")) rawMime else "audio/flac"
        }

        val sampleRate = parseSampleRateHz(json, qualityText)
        val bitDepth = parseBitDepth(json, qualityText)
        val bitrateBps = parseBitrateBps(json, qualityText, codec, sampleRate, bitDepth)
        val isLossless = codec in setOf("flac", "alac", "wav", "aiff")
        val isHiRes = isLossless && (
            (sampleRate ?: 0) > 48_000 ||
                (bitDepth ?: 0) >= 24 ||
                qualityText.contains("24-bit", ignoreCase = true) ||
                qualityText.contains("HI-RES", ignoreCase = true) ||
                qualityText.contains("HIRES", ignoreCase = true) ||
                candidate.isHiRes
            )

        val headers = LinkedHashMap<String, String>()
        listOf(json.optJSONObject("headers"), json.optJSONObject("requestHeaders"), json.optJSONObject("streamHeaders"))
            .forEach { hdrObj ->
                if (hdrObj != null) {
                    val keys = hdrObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (!hdrObj.isNull(k)) headers[k] = hdrObj.optString(k)
                    }
                }
            }

        val now = System.currentTimeMillis()
        val rawExp = json.optLong("expiresAtMs", json.optLong("expiresAt", 0L))
        val expiresAtMs = when {
            rawExp <= 0L -> now + 20 * 60_000L
            rawExp < 10_000_000_000L -> rawExp * 1_000L
            else -> rawExp
        }

        val label = buildDisplayQualityLabel(qualityText, codec, bitDepth, sampleRate, bitrateBps, isAtmos, isHiRes, isLossless)

        return AddonResolvedStream(
            addonId = addon.id,
            addonName = addon.name,
            url = effectiveUrl,
            downloadUrl = json.optString("downloadUrl").trim().ifBlank { null },
            headers = headers,
            audioMimeType = audioMime,
            codecs = codec,
            transport = transport,
            inlineManifest = inlineManifest,
            bitrate = bitrateBps,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            contentLength = json.optLong("contentLength", 0L).takeIf { it > 0L },
            qualityLabel = label,
            isLossless = isLossless,
            isHiRes = isHiRes,
            isAtmos = isAtmos,
            expiresAtMs = expiresAtMs,
            noStreamCache = addon.noStreamCache || json.optBoolean("noStreamCache", false),
            allowDownloads = addon.allowDownloads,
            matchedTitle = candidate.title,
            matchedArtist = candidate.artists.firstOrNull(),
            matchedAlbum = candidate.album,
            matchedDurationMs = candidate.durationMs,
            matchedIsrc = json.optString("isrc").trim().ifBlank { candidate.isrc },
        )
    }
}
