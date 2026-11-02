package com.unshoo.pixelmusic.data.addons

import android.net.Uri
import androidx.media3.common.MimeTypes
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped registry for active resolved addon/module streams.
 *
 * Bridges the gap between addon/JS module stream resolution and Media3's DataSource + MediaItem
 * pipeline without leaking addon internals into ExoPlayer:
 *  1. Remembers explicit transport declarations (`HLS` / `DASH`) even when the stream URL has no
 *     `.m3u8` / `.mpd` extension (e.g., `/dash/{id}` serving an HLS playlist).
 *  2. Stores per-URL and per-origin HTTP headers (`Authorization`, `Referer`, `Cookie`, custom UA)
 *     so both the root manifest/file request AND relative HLS/DASH segment requests carry the
 *     exact headers required by the module.
 *  3. Serves inline/data-URI manifests (`addon-manifest://{token}.mpd` / `.m3u8`) directly from
 *     memory via `ByteArrayDataSource` with zero temp-file race conditions.
 *  4. Signals `LosslessCachingDataSource` when a stream MUST bypass single-file `.flac` capture
 *     (HLS playlists, DASH manifests, `.m4s`/`.ts` segments, lossy streams, or `noStreamCache`).
 */
object AddonStreamRegistry {
    const val INLINE_SCHEME = "addon-manifest"
    const val INLINE_MANIFEST_SCHEME = INLINE_SCHEME
    private const val MAX_ENTRIES = 128
    private const val DEFAULT_BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    data class InlineManifest(
        val bytes: ByteArray,
        val transport: AddonTransport,
    )

    data class RegisteredStream(
        val url: String,
        val addonId: String,
        val addonName: String,
        val transport: AddonTransport,
        val audioMimeType: String,
        val headers: Map<String, String>,
        val isLossless: Boolean,
        val noStreamCache: Boolean,
        val inlineManifestBytes: ByteArray? = null,
        val baseOriginUrl: String? = null,
        val registeredAtMs: Long = System.currentTimeMillis(),
    )

    private val byUrl = ConcurrentHashMap<String, RegisteredStream>()
    private val byOriginHost = ConcurrentHashMap<String, RegisteredStream>()

    fun register(stream: AddonResolvedStream): String {
        trimIfNeeded()
        val effectiveUrl = if (!stream.inlineManifest.isNullOrBlank()) {
            val ext = if (stream.transport == AddonTransport.HLS) "m3u8" else "mpd"
            "$INLINE_SCHEME://${UUID.randomUUID()}.$ext"
        } else {
            stream.url
        }

        val entry = RegisteredStream(
            url = effectiveUrl,
            addonId = stream.addonId,
            addonName = stream.addonName,
            transport = stream.transport,
            audioMimeType = stream.audioMimeType,
            headers = stream.headers,
            isLossless = stream.isLossless,
            noStreamCache = stream.noStreamCache,
            inlineManifestBytes = stream.inlineManifest?.toByteArray(Charsets.UTF_8),
            baseOriginUrl = stream.url.takeIf { it.startsWith("http", ignoreCase = true) },
        )
        byUrl[effectiveUrl] = entry
        if (stream.url.isNotBlank() && stream.url != effectiveUrl) {
            byUrl[stream.url] = entry
        }
        extractHostKey(stream.url)?.let { hostKey ->
            byOriginHost[hostKey] = entry
        }
        return effectiveUrl
    }

    fun get(url: String?): RegisteredStream? {
        if (url.isNullOrBlank()) return null
        return byUrl[url]
    }

    fun metaFor(url: String?): RegisteredStream? = get(url)

    fun isInlineManifestUri(url: String?): Boolean =
        url?.startsWith("$INLINE_SCHEME://", ignoreCase = true) == true

    fun manifestFor(url: String?): InlineManifest? {
        val reg = get(url) ?: return null
        val bytes = reg.inlineManifestBytes ?: return null
        return InlineManifest(bytes = bytes, transport = reg.transport)
    }

    fun registerStreamMetadata(
        uri: String,
        headers: Map<String, String>,
        sourceId: String,
        addonName: String,
        transport: AddonTransport,
        audioMimeType: String,
        isLossless: Boolean = true,
        noStreamCache: Boolean = false,
    ) {
        if (uri.isBlank()) return
        trimIfNeeded()
        val existing = byUrl[uri]
        val entry = RegisteredStream(
            url = uri,
            addonId = sourceId.removePrefix(UnifiedSourceId.ADDON_PREFIX),
            addonName = addonName,
            transport = transport,
            audioMimeType = audioMimeType,
            headers = headers.ifEmpty { existing?.headers.orEmpty() },
            isLossless = isLossless,
            noStreamCache = noStreamCache || (existing?.noStreamCache == true),
            inlineManifestBytes = existing?.inlineManifestBytes,
            baseOriginUrl = existing?.baseOriginUrl ?: uri.takeIf { it.startsWith("http", ignoreCase = true) },
        )
        byUrl[uri] = entry
        extractHostKey(uri)?.let { byOriginHost[it] = entry }
    }

    fun containerMimeOf(transport: AddonTransport): String? = when (transport) {
        AddonTransport.HLS -> MimeTypes.APPLICATION_M3U8
        AddonTransport.DASH -> MimeTypes.APPLICATION_MPD
        AddonTransport.PROGRESSIVE -> null
    }

    fun isAddonStreamUri(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith("$INLINE_SCHEME://", ignoreCase = true)) return true
        if (byUrl.containsKey(url)) return true
        val hostKey = extractHostKey(url) ?: return false
        return byOriginHost.containsKey(hostKey)
    }

    /**
     * Returns the container MIME type Media3's `DefaultMediaSourceFactory` needs (`APPLICATION_M3U8`
     * for HLS, `APPLICATION_MPD` for DASH), or `null` when progressive extraction applies.
     */
    fun containerMimeOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        byUrl[url]?.let { reg ->
            return when (reg.transport) {
                AddonTransport.HLS -> MimeTypes.APPLICATION_M3U8
                AddonTransport.DASH -> MimeTypes.APPLICATION_MPD
                AddonTransport.PROGRESSIVE -> null
            }
        }
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            cleanPath.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            cleanPath.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    fun isSegmentedTransport(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (containerMimeOf(url) != null) return true
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        if (cleanPath.endsWith(".m4s") || cleanPath.endsWith(".ts") || cleanPath.endsWith(".cmfa")) {
            return true
        }
        val hostEntry = extractHostKey(url)?.let { byOriginHost[it] }
        return hostEntry?.transport == AddonTransport.HLS || hostEntry?.transport == AddonTransport.DASH
    }

    /**
     * True when `LosslessCachingDataSource` MUST NOT record this request into a single `.flac` file
     * (because it is an HLS/DASH playlist or media segment, a lossy stream, or `noStreamCache`).
     */
    fun shouldBypassCompleteFileCache(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith("$INLINE_SCHEME://", ignoreCase = true)) return true
        if (isSegmentedTransport(url)) return true
        val direct = byUrl[url] ?: extractHostKey(url)?.let { byOriginHost[it] } ?: return false
        if (direct.noStreamCache) return true
        if (!direct.isLossless) return true
        val audioMime = direct.audioMimeType.lowercase(Locale.ROOT)
        return !audioMime.contains("flac")
    }

    fun inlineManifestBytes(uri: Uri): ByteArray? = byUrl[uri.toString()]?.inlineManifestBytes

    /**
     * Resolves HTTP headers for [url]. Matches exact registered URLs first, then falls back to
     * same-origin segment inheritance (stripping sensitive `Authorization`/`Cookie` if the host
     * does not match the registered stream's host).
     */
    fun headersFor(url: String?): Map<String, String> {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return emptyMap()
        val exact = byUrl[url]
        if (exact != null) {
            return withDefaultUserAgent(exact.headers)
        }
        val hostKey = extractHostKey(url)
        val byHost = hostKey?.let { byOriginHost[it] }
        if (byHost != null) {
            return withDefaultUserAgent(byHost.headers)
        }
        return emptyMap()
    }

    private fun withDefaultUserAgent(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return mapOf("User-Agent" to DEFAULT_BROWSER_UA)
        val hasUa = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
        return if (hasUa) headers else LinkedHashMap<String, String>(headers.size + 1).apply {
            put("User-Agent", DEFAULT_BROWSER_UA)
            putAll(headers)
        }
    }

    private fun extractHostKey(url: String): String? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        return afterScheme.substringBefore('/').substringBefore('?').lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }

    private fun trimIfNeeded() {
        if (byUrl.size >= MAX_ENTRIES) {
            val cutoff = System.currentTimeMillis() - 30 * 60_000L
            byUrl.entries.removeIf { it.value.registeredAtMs < cutoff }
            byOriginHost.entries.removeIf { it.value.registeredAtMs < cutoff }
            if (byUrl.size >= MAX_ENTRIES) {
                byUrl.minByOrNull { it.value.registeredAtMs }?.key?.let { byUrl.remove(it) }
            }
        }
    }

    fun clear() {
        byUrl.clear()
        byOriginHost.clear()
    }
}
