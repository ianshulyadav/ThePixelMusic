/*
 * Ported from ArchiveTune (2026) — © Rukamori, GPL-3.0.
 * Origin: playback/MusicService.kt (resolveMultiSourceDataSpec, sourceResolutionChain,
 * resolveTidalStream / ensureValidTidalToken / refreshTidalToken, resolveQobuzStream,
 * resolveDeezerStream, resolveAppleStream / appleStreamFile, applyDirectStream,
 * persistDirectStreamFormat, measuredBitrate, sourceCacheKey) and
 * playback/LosslessStreamResolver.kt.
 *
 * PixelMusic entry point for the ArchiveTune multi-source ("lossless") chain. It is called from
 * YoutubeHelper.getSongPlayerUrl* before the JioSaavn/YouTube paths and returns a playable URI
 * plus the stream's format description, or null when no source cleared the metadata-match gate.
 *
 * Resolution order (per user preference, see AudioSourceConfig.DEFAULT_ORDER):
 *   TIDAL  → personal account token (auto-refresh, 401 → force refresh + retry)
 *           → Source Pool Tidal accounts (401 → report "dead", cooldown on failure)
 *           → public Tidal instances (user list ∪ pool discovery, health-ranked)
 *   QOBUZ  → user tokens (JSON) ∪ pool Qobuz accounts, user instances ∪ pool discovery
 *   DEEZER → manual ARL ∪ pool Deezer accounts (Blowfish-decrypted through deezer:// scheme)
 *   APPLE  → Media-User-Token (pasted or pool) + developer token; Widevine L3 via AppleMusicDrm
 *   AMAZON → no resolver (CENC, no decryption step — same as ArchiveTune)
 *   JIOSAAVN / YOUTUBE → handled by PixelMusic's existing paths after this resolver returns null
 */

package com.unshoo.pixelmusic.data.lossless

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import com.unshoo.pixelmusic.data.addons.AddonHealthTracker
import com.unshoo.pixelmusic.data.addons.AddonManager
import com.unshoo.pixelmusic.data.addons.AddonResolvedStream
import com.unshoo.pixelmusic.data.addons.AddonStreamRegistry
import com.unshoo.pixelmusic.data.addons.AddonTransport
import com.unshoo.pixelmusic.data.addons.InstalledAddon
import com.unshoo.pixelmusic.data.addons.MonochromeInstancePool
import com.unshoo.pixelmusic.data.addons.UnifiedSongSourceOverride
import com.unshoo.pixelmusic.data.addons.UnifiedSourceId
import com.unshoo.pixelmusic.data.lossless.applemusic.AppleMusicAudioProvider
import com.unshoo.pixelmusic.data.lossless.applemusic.AppleMusicDrm
import com.unshoo.pixelmusic.data.lossless.applemusic.AppleTrackDrmInfo
import com.unshoo.pixelmusic.data.lossless.audiosource.AudioSourceConfig
import com.unshoo.pixelmusic.data.lossless.audiosource.DirectStream
import com.unshoo.pixelmusic.data.lossless.audiosource.SongSourceOverride
import com.unshoo.pixelmusic.data.lossless.audiosource.TitleMatch
import com.unshoo.pixelmusic.data.lossless.audiosource.pcmBitrateOrNull
import com.unshoo.pixelmusic.data.lossless.cache.LosslessFileCache
import com.unshoo.pixelmusic.data.lossless.constants.AddonsEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.AddonsFastConcurrentKey
import com.unshoo.pixelmusic.data.lossless.constants.AddonsTimeoutMsKey
import com.unshoo.pixelmusic.data.lossless.constants.AppleMusicQuality
import com.unshoo.pixelmusic.data.lossless.constants.AppleMusicQualityKey
import com.unshoo.pixelmusic.data.lossless.constants.AppleMusicMediaUserTokenKey
import com.unshoo.pixelmusic.data.lossless.constants.AppleMusicSourceEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceOrderKey
import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceType
import com.unshoo.pixelmusic.data.lossless.constants.MonochromeEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.MonochromeInstancesKey
import com.unshoo.pixelmusic.data.lossless.constants.DeezerArlKey
import com.unshoo.pixelmusic.data.lossless.constants.PoolApiKeyKey
import com.unshoo.pixelmusic.data.lossless.constants.DeezerAudioQuality
import com.unshoo.pixelmusic.data.lossless.constants.DeezerAudioQualityKey
import com.unshoo.pixelmusic.data.lossless.constants.DeezerEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.LosslessStreamingEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.QobuzAudioQuality
import com.unshoo.pixelmusic.data.lossless.constants.QobuzAudioQualityKey
import com.unshoo.pixelmusic.data.lossless.constants.QobuzEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.QobuzInstancesKey
import com.unshoo.pixelmusic.data.lossless.constants.QobuzLastProbeTrackKey
import com.unshoo.pixelmusic.data.lossless.constants.QobuzTokensKey
import com.unshoo.pixelmusic.data.lossless.constants.SongSourceOverrideKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalAccessTokenKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalAccountFirstKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalAudioQuality
import com.unshoo.pixelmusic.data.lossless.constants.TidalAudioQualityKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalAuthFlowKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalCountryCodeKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalInstancesKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalLastProbeTrackKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalNeedsReloginKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalRefreshTokenKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalTokenExpiryKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalUserIdKey
import com.unshoo.pixelmusic.data.lossless.constants.getAsync
import com.unshoo.pixelmusic.data.lossless.constants.toFormatId
import com.unshoo.pixelmusic.data.lossless.constants.toFormatName
import com.unshoo.pixelmusic.data.lossless.deezer.DeezerAudioProvider
import com.unshoo.pixelmusic.data.lossless.playback.LosslessSchemeRoutingDataSource
import com.unshoo.pixelmusic.data.lossless.pool.PoolAccountManager
import com.unshoo.pixelmusic.data.lossless.qobuz.QobuzAudioProvider
import com.unshoo.pixelmusic.data.lossless.qobuz.QobuzToken
import com.unshoo.pixelmusic.data.lossless.tidal.TidalAccountManager
import com.unshoo.pixelmusic.data.lossless.tidal.TidalAudioProvider
import com.unshoo.pixelmusic.data.lossless.tidal.TidalInstanceHealthManager
import com.unshoo.pixelmusic.data.preferences.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import com.unshoo.pixelmusic.utils.trimTo
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object LosslessStreamResolver {
    private const val TAG = "LosslessResolver"

    /** Same TTL ArchiveTune uses for its `directStreamCache` (DIRECT_STREAM_CACHE_TTL_MS). */
    private const val DIRECT_STREAM_CACHE_TTL_MS = 5 * 60 * 1000L

    /** How long a resolved URI is considered playable by the player-side freshness checks. */
    private const val URI_FRESHNESS_MS = 60 * 60 * 1000L

    /**
     * Apple CDN segment URLs inside HLS playlists expire faster than other sources (~30 min).
     * Use a shorter freshness window so stale Apple streams get re-resolved on resume instead
     * of buffering forever against expired segment URLs.
     */
    private const val APPLE_HLS_FRESHNESS_MS = 25 * 60 * 1000L


    /**
     * Cap for the per-track resolver maps. They used to grow by one entry per distinct track played
     * for the life of the process. 256 is far above the queue look-ahead plus recent history, so
     * only tracks that finished long ago are dropped (they simply re-resolve if replayed).
     */
    private const val MAX_RESOLVED_ENTRIES = 256

    /** What the player receives: a URI plus enough format info for caches, badges and ExoPlayer. */
    data class Result(
        val mediaId: String,
        val uri: String,
        /** Bare audio mime type, e.g. `audio/flac`, `audio/mp4`, `audio/eac3-joc`. */
        val mimeType: String,
        val codecs: String,
        /** Bits per second (measured from size/duration when possible, otherwise a label heuristic). */
        val bitrate: Int,
        val sampleRate: Int?,
        val bitDepth: Int?,
        val contentLength: Long?,
        /** Human readable label, e.g. "Tidal HI_RES_LOSSLESS", "Addon FLAC 24-bit / 96 kHz". */
        val label: String,
        val source: AudioSourceType,
        val resolvedAtMs: Long = System.currentTimeMillis(),
        val sourceId: String = UnifiedSourceId.forBuiltIn(source),
        val providerDisplayName: String? = null,
        /** Optional ExoPlayer container MediaItem MIME (`application/x-mpegURL` or `application/dash+xml`). */
        val containerMimeType: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val allowDownloads: Boolean = true,
        val isAtmos: Boolean = false,
    ) {
        val isDolbyAtmos: Boolean
            get() = isAtmos ||
                mimeType.contains("eac3-joc", ignoreCase = true) ||
                codecs.contains("ec+3", ignoreCase = true) ||
                codecs.contains("ac-4", ignoreCase = true) ||
                label.contains("ATMOS", ignoreCase = true)

        val isLossless: Boolean
            get() =
                codecs.contains("flac", ignoreCase = true) ||
                    codecs.contains("alac", ignoreCase = true) ||
                    mimeType.contains("flac", ignoreCase = true) ||
                    label.uppercase(Locale.US).let { it.contains("LOSSLESS") || it.contains("FLAC") || it.contains("HI_RES") || it.contains("HI-RES") }

        val isHiRes: Boolean
            get() = (sampleRate ?: 0) > 48_000 ||
                (bitDepth ?: 0) > 16 ||
                label.uppercase(Locale.US).let { it.contains("HI_RES") || it.contains("HI-RES") || it.contains("HIRES") || it.contains("24-BIT") || it.contains("24/") }
    }

    /** Everything the chain needs to know about the wanted recording. */
    data class Request(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        val isrc: String? = null,
    )

    private class CachedDirectStream(
        val stream: DirectStream,
        val expiresAtMs: Long,
    )

    private class CachedAddonResult(
        val resolved: AddonResolvedStream,
        val expiresAtMs: Long,
    )

    private val directStreamCache = ConcurrentHashMap<String, CachedDirectStream>()
    private val addonResultCache = ConcurrentHashMap<String, CachedAddonResult>()

    /** resolved URI → time after which the player must re-resolve. */
    private val uriFreshness = ConcurrentHashMap<String, Long>()

    /** mediaId → last accepted result (drives the Now Playing quality badge). */
    private val resolvedByMediaId = ConcurrentHashMap<String, Result>()

    private val _lastResolved = MutableStateFlow<Result?>(null)

    /** Emits every accepted lossless resolution (latest wins); UI observes this for the badge. */
    val lastResolved: StateFlow<Result?> = _lastResolved.asStateFlow()

    /** mediaId → sources that resolved *and* cleared the match gate at least once this session. */
    private val resolvedSourcesByMediaId = ConcurrentHashMap<String, MutableSet<AudioSourceType>>()

    private val inFlight = ConcurrentHashMap<String, Mutex>()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val tidalTokenRefreshLock = Any()

    /** Shared client for Apple playlist/fMP4 downloads and HEAD length back-fill. */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }


    // ── Public helpers used by the player / YoutubeHelper ─────────────────────────────────────

    private fun hasActiveInstalledAddons(context: Context?, p: androidx.datastore.preferences.core.Preferences): Boolean {
        val addonsMaster = p[AddonsEnabledKey] ?: true
        if (!addonsMaster) return false
        val list = if (context != null) AddonManager.ensureLoadedBlocking(context) else AddonManager.installedAddons.value
        return list.any { it.enabled }
    }

    /**
     * Master switch: true when either Lossless Streaming is enabled OR the user has installed and
     * enabled at least one Addon/JS module in Developer Options -> Addons.
     */
    suspend fun isEnabled(context: Context): Boolean {
        val p = prefs(context)
        if (p[LosslessStreamingEnabledKey] ?: defaultEnabled()) return true
        return hasActiveInstalledAddons(context, p)
    }

    fun isEnabledBlocking(context: Context): Boolean =
        runCatching { runBlocking(Dispatchers.IO) { isEnabled(context) } }.getOrDefault(false)

    /** Default for the master switch when the user never touched it: off by default. */
    fun defaultEnabled(): Boolean = false

    @Volatile
    private var monochromeDownUntilMs: Long = 0L

    fun isMonochromeUp(): Boolean =
        System.currentTimeMillis() >= monochromeDownUntilMs

    fun markMonochromeDown(backoffMs: Long = 5 * 60 * 1000L) {
        monochromeDownUntilMs = System.currentTimeMillis() + backoffMs
    }

    fun markMonochromeUp() {
        monochromeDownUntilMs = 0L
    }

    fun hasAnyActiveLosslessSource(p: androidx.datastore.preferences.core.Preferences): Boolean {
        if ((p[AddonsEnabledKey] ?: true) && AddonManager.installedAddons.value.any { it.enabled }) {
            return true
        }
        val masterEnabled = p[LosslessStreamingEnabledKey] ?: defaultEnabled()
        if (!masterEnabled) return false

        val monochromeEnabled = p[MonochromeEnabledKey] ?: true
        val hasMonochrome = monochromeEnabled && isMonochromeUp()

        val hasBuildKey = com.unshoo.pixelmusic.BuildConfig.SOURCE_PROVIDER_KEY.isNotBlank()
        val hasPoolKey = !p[PoolApiKeyKey].isNullOrBlank() || PoolAccountManager.hasReadKey || PoolAccountManager.hasAccounts()
        val hasTidal = !p[TidalAccessTokenKey].isNullOrBlank()
        val hasDeezer = !p[DeezerArlKey].isNullOrBlank()
        val hasApple = !p[AppleMusicMediaUserTokenKey].isNullOrBlank()
        val hasQobuz = !p[QobuzTokensKey].isNullOrBlank()

        return hasMonochrome || hasBuildKey || hasPoolKey || hasTidal || hasDeezer || hasApple || hasQobuz
    }

    suspend fun hasAnyActiveLosslessSource(context: Context): Boolean {
        AddonManager.ensureLoadedBlocking(context)
        return hasAnyActiveLosslessSource(prefs(context))
    }

    /**
     * Emits true when lossless streaming or at least one installed addon is usable.
     */
    fun isLosslessConfiguredOrSignedInFlow(context: Context): Flow<Boolean> =
        kotlinx.coroutines.flow.combine(
            context.dataStore.data,
            AddonManager.installedAddons,
        ) { p, addons ->
            val addonsActive = (p[AddonsEnabledKey] ?: true) && addons.any { it.enabled }
            addonsActive || hasAnyActiveLosslessSource(p)
        }

    /** True when [uri] came out of this resolver (any source, any scheme). */
    fun isLosslessUri(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        if (uriFreshness.containsKey(uri)) return true
        return LosslessSchemeRoutingDataSource.isCustomLosslessScheme(uri) ||
            AddonStreamRegistry.isInlineManifestUri(uri) ||
            uri.startsWith("apple-pending:") ||
            uri.startsWith("data:application/vnd.apple.mpegurl") ||
            uri.startsWith("data:application/x-mpegurl") ||
            uri.startsWith("data:") ||
            uri.startsWith("monochrome://") ||
            uri.contains("monochrome.st/track/") ||
            resolvedByMediaId.values.any { it.uri == uri }
    }

    /** Whether a URI produced by [resolve] may still be handed to the player without re-resolving. */
    fun isFreshUri(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        if (AddonStreamRegistry.isInlineManifestUri(uri) && AddonStreamRegistry.manifestFor(uri) == null) {
            uriFreshness.remove(uri)
            return false
        }
        val until = uriFreshness[uri]
        if (until != null) {
            if (until <= System.currentTimeMillis()) {
                // Keep the expired entry in the map so subsequent calls don't fall through
                // to heuristic re-granting (e.g. Apple data: URIs or monochrome links).
                return false
            }
        } else if (uri.startsWith("monochrome://") || uri.contains("monochrome.st/track/")) {
            uriFreshness[uri] = System.currentTimeMillis() + URI_FRESHNESS_MS
            return true
        } else if (uri.startsWith("data:application/vnd.apple.mpegurl") || uri.startsWith("data:application/x-mpegurl")) {
            uriFreshness[uri] = System.currentTimeMillis() + APPLE_HLS_FRESHNESS_MS
            return true
        } else {
            return false
        }
        if (uri.startsWith("file:")) {
            val exists = runCatching { File(Uri.parse(uri).path.orEmpty()).let { it.isFile && it.length() > 0L } }.getOrDefault(false)
            if (!exists) {
                uriFreshness.remove(uri)
                return false
            }
        }
        return true
    }

    fun markUriFresh(uri: String, ttlMs: Long = URI_FRESHNESS_MS) {
        if (uri.isNotBlank()) {
            uriFreshness[uri] = System.currentTimeMillis() + ttlMs
        }
    }

    fun resultFor(mediaId: String?): Result? {
        if (mediaId == null) return null
        resolvedByMediaId[mediaId]?.let { return it }
        val clean = mediaId.removePrefix("youtube_").removePrefix("monochrome_")
        return resolvedByMediaId[clean]
    }

    fun availableSourcesForSong(mediaId: String): List<AudioSourceType> =
        resolvedSourcesByMediaId[mediaId]?.toList().orEmpty()

    /** Drop every cached decision for [mediaId] (used after a per-song source override changes). */
    fun clearResolved(mediaId: String) {
        val clean = mediaId.removePrefix("youtube_").removePrefix("monochrome_")
        AudioSourceType.entries.forEach {
            directStreamCache.remove(sourceCacheKey(it, mediaId))
            directStreamCache.remove(sourceCacheKey(it, clean))
        }
        addonResultCache.keys.removeIf { it.endsWith(":$mediaId") || it.endsWith(":$clean") }
        resolvedByMediaId.remove(mediaId)?.let { uriFreshness.remove(it.uri) }
        if (clean != mediaId) {
            resolvedByMediaId.remove(clean)?.let { uriFreshness.remove(it.uri) }
        }
        resolvedSourcesByMediaId.remove(mediaId)
        AppleMusicDrm.clear(mediaId)
    }

    private val failedMediaIds = ConcurrentHashMap<String, Long>()
    /** Per-track failed unified source IDs so playback errors step down to the next addon/source first! */
    private val failedSourceIdsByTrack = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Marks ONLY the currently active source (`addon:<id>` or `builtin:<TYPE>`) as failed for
     * [mediaId] so an immediate re-resolve steps down to the NEXT addon/source in the unified chain!
     * Returns `true` if a specific source was excluded and another attempt should run before
     * abandoning lossless/addons completely.
     */
    fun markCurrentSourceFailedForTrack(mediaId: String, reason: String = "Playback stream error"): Boolean {
        val clean = mediaId.removePrefix("youtube_").removePrefix("monochrome_")
        val active = resolvedByMediaId[mediaId] ?: resolvedByMediaId[clean]
        if (active == null) {
            markFailedForTrack(mediaId)
            return false
        }
        AddonHealthTracker.recordFailure(active.sourceId, reason)
        val set = failedSourceIdsByTrack.getOrPut(clean) { ConcurrentHashMap.newKeySet() }
        val newlyAdded = set.add(active.sourceId)
        directStreamCache.remove(sourceCacheKey(active.source, mediaId))
        directStreamCache.remove(sourceCacheKey(active.source, clean))
        addonResultCache.remove("${active.sourceId}:$mediaId")
        addonResultCache.remove("${active.sourceId}:$clean")
        resolvedByMediaId.remove(mediaId)?.let { uriFreshness.remove(it.uri) }
        resolvedByMediaId.remove(clean)?.let { uriFreshness.remove(it.uri) }
        if (!newlyAdded || set.size >= 6) {
            markFailedForTrack(mediaId)
            return false
        }
        return true
    }

    /** Mark a track as failed for lossless streaming so it falls back to YouTube immediately without loops. */
    fun markFailedForTrack(mediaId: String) {
        clearResolved(mediaId)
        val clean = mediaId.removePrefix("youtube_").removePrefix("monochrome_")
        val expiry = System.currentTimeMillis() + 3 * 60 * 1000L
        failedMediaIds[mediaId] = expiry
        if (clean != mediaId) failedMediaIds[clean] = expiry
    }

    fun clearFailedForTrack(mediaId: String) {
        val clean = mediaId.removePrefix("youtube_").removePrefix("monochrome_")
        failedMediaIds.remove(mediaId)
        failedMediaIds.remove(clean)
        failedSourceIdsByTrack.remove(mediaId)
        failedSourceIdsByTrack.remove(clean)
    }

    fun isFailedForTrack(mediaId: String?): Boolean {
        if (mediaId == null) return false
        val clean = mediaId.removePrefix("youtube_").removePrefix("monochrome_")
        val until = failedMediaIds[mediaId] ?: failedMediaIds[clean] ?: return false
        if (System.currentTimeMillis() > until) {
            failedMediaIds.remove(mediaId)
            failedMediaIds.remove(clean)
            failedSourceIdsByTrack.remove(clean)
            return false
        }
        return true
    }

    fun clearAll() {
        directStreamCache.clear()
        addonResultCache.clear()
        resolvedByMediaId.clear()
        uriFreshness.clear()
        resolvedSourcesByMediaId.clear()
        failedMediaIds.clear()
        failedSourceIdsByTrack.clear()
    }

    suspend fun setSongSourceOverride(
        context: Context,
        mediaId: String,
        source: AudioSourceType?,
    ) {
        val unified = source?.let(UnifiedSourceId::forBuiltIn)
        setUnifiedSongSourceOverride(context, mediaId, unified)
    }

    suspend fun setUnifiedSongSourceOverride(
        context: Context,
        mediaId: String,
        unifiedSourceId: String?,
    ) {
        context.dataStore.edit { prefs ->
            prefs[SongSourceOverrideKey] =
                UnifiedSongSourceOverride.withOverride(prefs[SongSourceOverrideKey], mediaId, unifiedSourceId)
        }
        clearFailedForTrack(mediaId)
        clearResolved(mediaId)
    }

    suspend fun getUnifiedSongSourceOverride(context: Context, mediaId: String): String? {
        val raw = prefs(context)[SongSourceOverrideKey]
        return UnifiedSongSourceOverride.get(raw, mediaId)
    }

    // ── Preference readers ────────────────────────────────────────────────────────────────────

    private suspend fun prefs(context: Context) = context.dataStore.data.first()

    private fun parseTidalAudioQuality(stored: String?): TidalAudioQuality =
        runCatching { TidalAudioQuality.valueOf(stored ?: TidalAudioQuality.FLAC.name) }.getOrDefault(TidalAudioQuality.FLAC)

    private fun parseAppleMusicQuality(stored: String?): AppleMusicQuality =
        runCatching { AppleMusicQuality.valueOf(stored ?: AppleMusicQuality.LOSSLESS.name) }.getOrDefault(AppleMusicQuality.LOSSLESS)

    private fun parseQobuzAudioQuality(stored: String?): QobuzAudioQuality =
        runCatching { QobuzAudioQuality.valueOf(stored ?: QobuzAudioQuality.FLAC.name) }.getOrDefault(QobuzAudioQuality.FLAC)

    private fun parseDeezerAudioQuality(stored: String?): DeezerAudioQuality =
        runCatching { DeezerAudioQuality.valueOf(stored ?: DeezerAudioQuality.FLAC.name) }.getOrDefault(DeezerAudioQuality.FLAC)

    private fun parseMultiline(raw: String?): List<String> =
        raw.orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private val SaavnEnabledPrefKey = androidx.datastore.preferences.core.booleanPreferencesKey("enable_saavn_streaming")

    class SourceSettings(
        val losslessMasterEnabled: Boolean,
        val addonsMasterEnabled: Boolean,
        val addonsFastConcurrent: Boolean,
        val addonTimeoutMs: Long,
        val installedAddons: List<InstalledAddon>,
        val unifiedOrder: List<String>,
        val saavnEnabled: Boolean,
        val monochromeEnabled: Boolean,
        val monochromeInstances: List<String>,
        val tidalEnabled: Boolean,
        val qobuzEnabled: Boolean,
        val deezerEnabled: Boolean,
        val appleEnabled: Boolean,
        val rawOrder: String?,
        val overrideRaw: String?,
        val tidalQuality: TidalAudioQuality,
        val tidalAccountFirst: Boolean,
        val tidalInstances: List<String>,
        val qobuzQuality: QobuzAudioQuality,
        val qobuzInstances: List<String>,
        val qobuzTokensJson: String,
        val deezerQuality: DeezerAudioQuality,
        val appleQuality: AppleMusicQuality,
    )

    suspend fun readSettings(context: Context): SourceSettings {
        val addons = AddonManager.ensureLoadedBlocking(context)
        val p = prefs(context)
        return SourceSettings(
            losslessMasterEnabled = p[LosslessStreamingEnabledKey] ?: defaultEnabled(),
            addonsMasterEnabled = p[AddonsEnabledKey] ?: true,
            addonsFastConcurrent = p[AddonsFastConcurrentKey] ?: true,
            addonTimeoutMs = (p[AddonsTimeoutMsKey] ?: AddonManager.DEFAULT_PER_ADDON_TIMEOUT_MS).coerceIn(2_000L, 20_000L),
            installedAddons = addons,
            unifiedOrder = AddonManager.readUnifiedOrder(p, addons),
            saavnEnabled = p[SaavnEnabledPrefKey] ?: true,
            monochromeEnabled = p[MonochromeEnabledKey] ?: true,
            monochromeInstances = parseMultiline(p[MonochromeInstancesKey]),
            tidalEnabled = p[TidalEnabledKey] ?: true,
            qobuzEnabled = p[QobuzEnabledKey] ?: false,
            deezerEnabled = p[DeezerEnabledKey] ?: false,
            appleEnabled = p[AppleMusicSourceEnabledKey] ?: true,
            rawOrder = p[AudioSourceOrderKey]?.ifBlank { null },
            overrideRaw = p[SongSourceOverrideKey],
            tidalQuality = parseTidalAudioQuality(p[TidalAudioQualityKey]),
            tidalAccountFirst = p[TidalAccountFirstKey] ?: true,
            tidalInstances = parseMultiline(p[TidalInstancesKey]),
            qobuzQuality = parseQobuzAudioQuality(p[QobuzAudioQualityKey]),
            qobuzInstances = parseMultiline(p[QobuzInstancesKey]),
            qobuzTokensJson = p[QobuzTokensKey].orEmpty(),
            deezerQuality = parseDeezerAudioQuality(p[DeezerAudioQualityKey]),
            appleQuality = parseAppleMusicQuality(p[AppleMusicQualityKey]),
        )
    }

    /**
     * Builds the unified candidate chain (`addon:<id>` + `builtin:<TYPE>`) up to the first enabled
     * non-lossless built-in fallback (`builtin:JIOSAAVN` or `builtin:YOUTUBE`), respecting both the
     * user's unified drag order and per-provider enable switches.
     */
    private fun unifiedResolutionChain(s: SourceSettings, forDownload: Boolean = false): List<String> {
        val addonById = s.installedAddons.associateBy { it.id }
        val result = ArrayList<String>()
        for (sourceId in s.unifiedOrder) {
            if (UnifiedSourceId.isAddon(sourceId)) {
                if (!s.addonsMasterEnabled) continue
                val addonId = UnifiedSourceId.addonIdOrNull(sourceId) ?: continue
                val addon = addonById[addonId] ?: continue
                if (!addon.enabled) continue
                if (forDownload && !addon.allowDownloads) continue
                result += sourceId
            } else {
                val type = UnifiedSourceId.builtInTypeOrNull(sourceId) ?: continue
                when (type) {
                    AudioSourceType.YOUTUBE -> break
                    AudioSourceType.JIOSAAVN -> if (s.saavnEnabled && !forDownload) break
                    AudioSourceType.MONOCHROME,
                    AudioSourceType.TIDAL,
                    AudioSourceType.QOBUZ,
                    AudioSourceType.DEEZER,
                    AudioSourceType.APPLE -> {
                        if (s.losslessMasterEnabled && isSourceEnabled(type, s)) {
                            result += sourceId
                        }
                    }
                    else -> {}
                }
            }
        }
        return result
    }

    private fun isUnifiedEntryUsableForOverride(sourceId: String, s: SourceSettings, forDownload: Boolean): Boolean {
        if (UnifiedSourceId.isAddon(sourceId)) {
            val addonId = UnifiedSourceId.addonIdOrNull(sourceId) ?: return false
            val addon = s.installedAddons.firstOrNull { it.id == addonId } ?: return false
            if (!addon.enabled) return false
            if (forDownload && !addon.allowDownloads) return false
            return true
        }
        val type = UnifiedSourceId.builtInTypeOrNull(sourceId) ?: return false
        return when (type) {
            AudioSourceType.YOUTUBE, AudioSourceType.JIOSAAVN -> true
            AudioSourceType.MONOCHROME,
            AudioSourceType.TIDAL,
            AudioSourceType.QOBUZ,
            AudioSourceType.DEEZER,
            AudioSourceType.APPLE -> isSourceEnabled(type, s)
            else -> false
        }
    }

    private fun isSourceEnabled(source: AudioSourceType, s: SourceSettings): Boolean =
        when (source) {
            AudioSourceType.ADDON -> s.addonsMasterEnabled && s.installedAddons.any { it.enabled }
            AudioSourceType.YOUTUBE -> true
            AudioSourceType.MONOCHROME -> s.monochromeEnabled
            AudioSourceType.TIDAL -> s.tidalEnabled
            AudioSourceType.QOBUZ -> s.qobuzEnabled
            AudioSourceType.DEEZER -> s.deezerEnabled
            AudioSourceType.APPLE -> s.appleEnabled
            AudioSourceType.QOBUZ_BACKUP, AudioSourceType.AMAZON, AudioSourceType.JIOSAAVN -> false
        }

    /** True when at least one addon or lossless source is switched on (cheap pre-check for callers). */
    suspend fun anySourceEnabled(context: Context): Boolean {
        if (!isEnabled(context)) return false
        val s = readSettings(context)
        return unifiedResolutionChain(s).isNotEmpty()
    }

    // ── Main entry point ──────────────────────────────────────────────────────────────────────

    /**
     * Resolves a song across the Unified Fallback Chain (Installed Addons / JS Modules + Built-in
     * Lossless Sources), using Fast Hedged Concurrent Waves when enabled. Never throws.
     */
    suspend fun resolve(
        context: Context,
        request: Request,
        lowDataMode: Boolean = false,
        forDownload: Boolean = false,
    ): Result? =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            if (!isEnabled(appContext)) return@withContext null
            val mediaId = request.mediaId
            if (mediaId.isBlank() || request.title.isBlank()) return@withContext null

            val mutex = inFlight.getOrPut(mediaId) { Mutex() }
            try {
                mutex.withLock {
                    runCatching { resolveLocked(appContext, request, lowDataMode, forDownload) }
                        .onFailure { Timber.tag(TAG).w(it, "Unified stream resolve crashed for %s", mediaId) }
                        .getOrNull()
                }
            } finally {
                inFlight.remove(mediaId, mutex)
            }
        }

    private sealed interface CandidateOutcome {
        data class BuiltInWin(val source: AudioSourceType, val stream: DirectStream) : CandidateOutcome
        data class AddonWin(val resolved: AddonResolvedStream) : CandidateOutcome
    }

    private suspend fun resolveLocked(
        context: Context,
        request: Request,
        lowDataMode: Boolean,
        forDownload: Boolean,
    ): Result? {
        val mediaId = request.mediaId
        val cleanMediaId = mediaId.removePrefix("youtube_").removePrefix("monochrome_")
        val settings = readSettings(context)
        val unifiedOverride = UnifiedSongSourceOverride.get(settings.overrideRaw, mediaId)

        // 1. Instant offline cache check (only when no explicit source override is active)
        if (!forDownload && unifiedOverride == null) {
            val cachedLossless = LosslessFileCache.getCompleteFile(context, mediaId)
            if (cachedLossless != null) {
                val fileUri = Uri.fromFile(cachedLossless).toString()
                markUriFresh(fileUri)
                val result = Result(
                    mediaId = mediaId,
                    uri = fileUri,
                    mimeType = "audio/flac",
                    codecs = "flac",
                    bitrate = 1411200,
                    sampleRate = 44100,
                    bitDepth = 16,
                    contentLength = cachedLossless.length(),
                    label = "FLAC (Cached)",
                    source = AudioSourceType.MONOCHROME,
                )
                resolvedByMediaId[mediaId] = result
                return result
            }
        }

        if (isFailedForTrack(mediaId) && unifiedOverride == null) {
            Timber.tag(TAG).d("Unified resolve skipped for %s (all sources failed recently)", mediaId)
            return null
        }

        if (lowDataMode && unifiedOverride == null) {
            Timber.tag(TAG).i("Low-data mode active; skipping lossless/addon chain for %s", mediaId)
            return null
        }

        // Handle explicit per-song source pin ("Play from...")
        if (unifiedOverride == "builtin:YOUTUBE" || unifiedOverride == "builtin:JIOSAAVN") {
            Timber.tag(TAG).d("Per-song override: %s pinned to %s; yielding to YoutubeHelper", mediaId, unifiedOverride)
            return null
        }

        val failedSourcesForSong = failedSourceIdsByTrack[cleanMediaId].orEmpty()
        val isTrustedOverride = unifiedOverride != null && isUnifiedEntryUsableForOverride(unifiedOverride, settings, forDownload)

        val candidateChain: List<String> = if (isTrustedOverride && unifiedOverride != null) {
            listOf(unifiedOverride)
        } else {
            unifiedResolutionChain(settings, forDownload).filterNot { it in failedSourcesForSong }
        }

        if (candidateChain.isEmpty()) {
            Timber.tag(TAG).d("Unified resolve skip for %s: candidate chain empty", mediaId)
            return null
        }

        // 2. Fast in-memory stream cache check in priority order
        val now = System.currentTimeMillis()
        if (!forDownload) {
            for (sourceId in candidateChain) {
                if (UnifiedSourceId.isAddon(sourceId)) {
                    val key = "$sourceId:$mediaId"
                    val cached = addonResultCache[key] ?: continue
                    if (cached.expiresAtMs <= now) {
                        addonResultCache.remove(key, cached)
                        continue
                    }
                    if (!AddonStreamRegistry.isInlineManifestUri(cached.resolved.uri) || AddonStreamRegistry.manifestFor(cached.resolved.uri) != null) {
                        recordResolvedSource(mediaId, AudioSourceType.ADDON)
                        return publishAddonStream(mediaId, cached.resolved)
                    }
                } else {
                    val builtIn = UnifiedSourceId.builtInTypeOrNull(sourceId) ?: continue
                    val cacheKey = sourceCacheKey(builtIn, mediaId)
                    val cached = directStreamCache[cacheKey] ?: continue
                    if (cached.expiresAtMs <= now) {
                        directStreamCache.remove(cacheKey, cached)
                        continue
                    }
                    if (streamStillUsable(cached.stream)) {
                        recordResolvedSource(mediaId, builtIn)
                        return publish(mediaId, cached.stream, fromCache = true)
                    }
                }
            }
        }

        // Load pool accounts only when at least one built-in pool-backed provider is in the chain
        val p = prefs(context)
        val needsPool = candidateChain.any {
            it in setOf("builtin:TIDAL", "builtin:QOBUZ", "builtin:DEEZER", "builtin:APPLE")
        }
        if (needsPool && (PoolAccountManager.hasReadKey || !p[PoolApiKeyKey].isNullOrBlank() || com.unshoo.pixelmusic.BuildConfig.SOURCE_PROVIDER_KEY.isNotBlank())) {
            runCatching { PoolAccountManager.loadCached(context) }
            runCatching { PoolAccountManager.refresh(context) }
        }

        val addonMap = settings.installedAddons.associateBy { it.id }
        Timber.tag(TAG).d(
            "Unified resolve for \"%s\" (%s) | chain=%s | fastConcurrent=%s",
            request.title,
            mediaId,
            candidateChain.joinToString(","),
            settings.addonsFastConcurrent,
        )

        val winner = AddonManager.executeOrderedCandidates(
            candidates = candidateChain,
            fastConcurrent = settings.addonsFastConcurrent,
            perSourceTimeoutMs = settings.addonTimeoutMs,
        ) { sourceId ->
            if (UnifiedSourceId.isAddon(sourceId)) {
                val addonId = UnifiedSourceId.addonIdOrNull(sourceId) ?: return@executeOrderedCandidates null
                val addon = addonMap[addonId] ?: return@executeOrderedCandidates null
                val resolved = AddonManager.resolveSingleAddon(
                    context = context,
                    addon = addon,
                    title = request.title,
                    artists = request.artists,
                    album = request.album,
                    durationMs = request.durationMs,
                    isrc = request.isrc,
                    preferLossless = !lowDataMode,
                    forDownload = forDownload,
                    trustedOverride = isTrustedOverride && unifiedOverride == sourceId,
                    timeoutMs = settings.addonTimeoutMs,
                ) ?: return@executeOrderedCandidates null
                CandidateOutcome.AddonWin(resolved)
            } else {
                val builtIn = UnifiedSourceId.builtInTypeOrNull(sourceId) ?: return@executeOrderedCandidates null
                val trusted = isTrustedOverride && unifiedOverride == sourceId
                val stream: DirectStream? = when (builtIn) {
                    AudioSourceType.MONOCHROME -> resolveMonochromeStream(request, settings, trusted)
                    AudioSourceType.TIDAL -> resolveTidalStream(context, request, settings)
                    AudioSourceType.QOBUZ -> resolveQobuzStream(request, settings)
                    AudioSourceType.DEEZER -> resolveDeezerStream(request, settings)
                    AudioSourceType.APPLE -> resolveAppleStream(context, request, settings, trusted = trusted)
                    AudioSourceType.ADDON,
                    AudioSourceType.AMAZON,
                    AudioSourceType.QOBUZ_BACKUP,
                    AudioSourceType.JIOSAAVN,
                    AudioSourceType.YOUTUBE -> null
                }
                if (stream == null) return@executeOrderedCandidates null
                val match = if (trusted) {
                    TitleMatch.Result(true, 1.0, 1.0, 1.0, 1.0, "per-song override bypass")
                } else {
                    TitleMatch.evaluate(
                        wantedTitle = request.title,
                        wantedArtists = request.artists,
                        wantedAlbum = request.album,
                        wantedDurationMs = request.durationMs,
                        stream = stream,
                    )
                }
                if (!match.accepted) {
                    Timber.tag(TAG).i(
                        "Source %s rejected for \"%s\": %s score=%.1f%% matched=\"%s\"",
                        builtIn.name,
                        request.title,
                        match.reason,
                        match.score * 100,
                        stream.matchedTitle ?: "?",
                    )
                    return@executeOrderedCandidates null
                }
                CandidateOutcome.BuiltInWin(builtIn, stream)
            }
        }

        if (winner == null) {
            Timber.tag(TAG).w("Unified chain returned no matching stream for \"%s\"; yielding to fallback", request.title)
            return null
        }

        return when (val outcome = winner.second) {
            is CandidateOutcome.AddonWin -> {
                val r = outcome.resolved
                Timber.tag(TAG).i(
                    "Addon WIN: %s (%s) resolved \"%s\" [%s] transport=%s",
                    r.addonName,
                    r.addonId,
                    request.title,
                    r.qualityLabel,
                    r.transport.name,
                )
                recordResolvedSource(mediaId, AudioSourceType.ADDON)
                val cacheKey = "${UnifiedSourceId.forAddon(r.addonId)}:$mediaId"
                addonResultCache[cacheKey] = CachedAddonResult(
                    resolved = r,
                    expiresAtMs = System.currentTimeMillis() + DIRECT_STREAM_CACHE_TTL_MS,
                )
                addonResultCache.trimTo(MAX_RESOLVED_ENTRIES) { it.expiresAtMs }
                publishAddonStream(mediaId, r)
            }
            is CandidateOutcome.BuiltInWin -> {
                val winningSource = outcome.source
                val winningStream = outcome.stream
                Timber.tag(TAG).i(
                    "Built-in WIN: %s resolved \"%s\" [%s] (%s)",
                    winningSource.name,
                    request.title,
                    winningStream.label,
                    winningStream.uri.take(80),
                )
                recordResolvedSource(mediaId, winningSource)
                if (winningSource == AudioSourceType.TIDAL) {
                    TidalAudioProvider.lastResolvedTrackId?.takeIf { it.isNotBlank() }?.let { probe ->
                        runCatching { context.dataStore.edit { prefs -> prefs[TidalLastProbeTrackKey] = probe } }
                    }
                } else if (winningSource == AudioSourceType.QOBUZ) {
                    QobuzAudioProvider.lastResolvedTrackId?.takeIf { it.isNotBlank() }?.let { probe ->
                        runCatching { context.dataStore.edit { prefs -> prefs[QobuzLastProbeTrackKey] = probe } }
                    }
                }
                applyDirectStream(mediaId, winningStream)
            }
        }
    }

    private fun publishAddonStream(mediaId: String, resolved: AddonResolvedStream): Result {
        val effectiveUri = AddonStreamRegistry.register(resolved)
        val containerMime = AddonStreamRegistry.containerMimeOf(resolved.transport)
        val bitrate = resolved.bitrateBps
            ?: run {
                val sr = resolved.sampleRate ?: 44_100
                val bd = resolved.bitDepth ?: 16
                when {
                    resolved.isAtmos -> 768_000
                    resolved.codecs.contains("flac", true) || resolved.codecs.contains("alac", true) ->
                        (sr * bd * 2 * 65) / 100
                    else -> 320_000
                }
            }
        val label = "${resolved.addonName} · ${resolved.qualityLabel}"
        val result = Result(
            mediaId = mediaId,
            uri = effectiveUri,
            mimeType = resolved.audioMimeType,
            codecs = resolved.codecs,
            bitrate = bitrate,
            sampleRate = resolved.sampleRate,
            bitDepth = resolved.bitDepth,
            contentLength = null,
            label = label,
            source = AudioSourceType.ADDON,
            sourceId = UnifiedSourceId.forAddon(resolved.addonId),
            providerDisplayName = resolved.addonName,
            containerMimeType = containerMime,
            headers = resolved.headers,
            allowDownloads = resolved.allowDownloads,
            isAtmos = resolved.isAtmos,
        )
        resolvedByMediaId[mediaId] = result
        uriFreshness[effectiveUri] = System.currentTimeMillis() + URI_FRESHNESS_MS
        resolvedByMediaId.trimTo(MAX_RESOLVED_ENTRIES) { it.resolvedAtMs }
        uriFreshness.trimTo(MAX_RESOLVED_ENTRIES) { it }
        _lastResolved.value = result
        return result
    }

    private fun streamStillUsable(stream: DirectStream): Boolean {
        if (stream.uri.startsWith("file:")) {
            return runCatching { File(Uri.parse(stream.uri).path.orEmpty()).let { it.isFile && it.length() > 0L } }.getOrDefault(false)
        }
        return true
    }

    private fun recordResolvedSource(mediaId: String, source: AudioSourceType) {
        resolvedSourcesByMediaId.getOrPut(mediaId) { ConcurrentHashMap.newKeySet() }.add(source)
    }

    // ── Tidal ─────────────────────────────────────────────────────────────────────────────────

    private fun markTidalNeedsRelogin(context: Context) {
        runCatching { runBlocking { context.dataStore.edit { prefs -> prefs[TidalNeedsReloginKey] = true } } }
    }

    private fun persistRefreshedTidalToken(context: Context, refreshed: TidalAccountManager.TokenResult) {
        runBlocking {
            context.dataStore.edit { prefs ->
                prefs[TidalAccessTokenKey] = refreshed.accessToken
                prefs[TidalTokenExpiryKey] = refreshed.expiresAtMillis
                refreshed.refreshToken?.let { prefs[TidalRefreshTokenKey] = it }
                refreshed.userId?.let { prefs[TidalUserIdKey] = it }
                refreshed.countryCode?.let { prefs[TidalCountryCodeKey] = it }
                prefs[TidalNeedsReloginKey] = false
            }
        }
    }

    private fun readTidalTokenState(context: Context): Triple<String, Long, Pair<String, String>> {
        val p = runBlocking { context.dataStore.data.first() }
        return Triple(
            p[TidalAccessTokenKey].orEmpty(),
            p[TidalTokenExpiryKey] ?: 0L,
            p[TidalRefreshTokenKey].orEmpty() to (p[TidalAuthFlowKey] ?: TidalAccountManager.FLOW_OAUTH),
        )
    }

    /** Port of `ensureValidTidalToken`: returns a usable personal token or null (public-instance fallback). */
    private fun ensureValidTidalToken(context: Context): String? {
        val (token, expiry, refreshAndFlow) = readTidalTokenState(context)
        val (refresh, flow) = refreshAndFlow
        if (token.isNotBlank() && expiry > System.currentTimeMillis() + 60_000L) return token
        if (refresh.isBlank()) {
            if (token.isBlank()) {
                // No account at all — nothing to flag.
                return null
            }
            Timber.tag(TAG).d("Tidal token expired/absent and no refresh token; account path unavailable")
            return token.ifBlank { null }
        }
        return synchronized(tidalTokenRefreshLock) {
            val (currentToken, currentExpiry, _) = readTidalTokenState(context)
            if (currentToken.isNotBlank() && currentExpiry > System.currentTimeMillis() + 60_000L) {
                Timber.tag(TAG).d("Tidal token already refreshed by another thread; reusing")
                return@synchronized currentToken
            }
            Timber.tag(TAG).d("Tidal access token expired; refreshing via stored refresh token (flow=%s)", flow)
            val refreshed =
                runCatching { runBlocking(Dispatchers.IO) { TidalAccountManager.refreshAccessToken(refresh, flow) } }
                    .onFailure { Timber.tag(TAG).w(it, "Tidal token refresh threw") }
                    .getOrNull()
            if (refreshed == null) {
                Timber.tag(TAG).w("Tidal token refresh failed; flagging re-login and falling back to public instances")
                markTidalNeedsRelogin(context)
                return@synchronized null
            }
            persistRefreshedTidalToken(context, refreshed)
            Timber.tag(TAG).i("Tidal token refreshed; valid for ~%ds", (refreshed.expiresAtMillis - System.currentTimeMillis()) / 1000)
            refreshed.accessToken
        }
    }

    /** Port of `refreshTidalToken` (force refresh after a 401). */
    private fun refreshTidalToken(context: Context, rejectedToken: String?): String? =
        synchronized(tidalTokenRefreshLock) {
            val (current, _, refreshAndFlow) = readTidalTokenState(context)
            val (refresh, flow) = refreshAndFlow
            if (current.isNotBlank() && current != rejectedToken) {
                Timber.tag(TAG).d("Tidal token already refreshed by another thread; reusing")
                return@synchronized current
            }
            if (refresh.isBlank()) {
                Timber.tag(TAG).w("401 from Tidal but no refresh token; account needs re-login")
                markTidalNeedsRelogin(context)
                return@synchronized null
            }
            Timber.tag(TAG).d("Force-refreshing Tidal token after 401 (flow=%s)", flow)
            val refreshed =
                runCatching { runBlocking(Dispatchers.IO) { TidalAccountManager.refreshAccessToken(refresh, flow) } }
                    .onFailure { Timber.tag(TAG).w(it, "Force refresh threw") }
                    .getOrNull()
            if (refreshed == null) {
                Timber.tag(TAG).w("Force refresh failed; account needs re-login")
                markTidalNeedsRelogin(context)
                return@synchronized null
            }
            persistRefreshedTidalToken(context, refreshed)
            Timber.tag(TAG).i("Tidal token force-refreshed after 401")
            refreshed.accessToken
        }

    private suspend fun resolveTidalStream(
        context: Context,
        request: Request,
        settings: SourceSettings,
    ): DirectStream? {
        val quality = settings.tidalQuality
        val cacheDir = context.cacheDir
        Timber.tag(TAG).d("Tidal resolve start | quality=%s accountFirst=%s", quality.name, settings.tidalAccountFirst)

        if (settings.tidalAccountFirst) {
            val apiQuality =
                when (quality) {
                    TidalAudioQuality.HI_RES_LOSSLESS -> "HI_RES_LOSSLESS"
                    TidalAudioQuality.FLAC -> "LOSSLESS"
                    TidalAudioQuality.AAC_320 -> "HIGH"
                }

            suspend fun attempt(accessToken: String, countryCode: String): DirectStream? =
                TidalAccountManager.resolveDirectStream(
                    accessToken = accessToken,
                    title = request.title,
                    artists = request.artists,
                    durationMs = request.durationMs,
                    audioQuality = apiQuality,
                    cacheDir = cacheDir,
                    countryCode = countryCode,
                )

            var token = ensureValidTidalToken(context)
            Timber.tag(TAG).d("Tidal account token available=%s", token != null)
            if (token != null) {
                val accountCountry = (prefs(context)[TidalCountryCodeKey].orEmpty()).ifBlank { "US" }
                val accountStream =
                    try {
                        attempt(token, accountCountry)
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        if (TidalAccountManager.isUnauthorized(e)) {
                            Timber.tag(TAG).w("Tidal account 401 (possibly wrapped); refreshing token + retrying")
                            Thread.interrupted()
                            val refreshed = refreshTidalToken(context, rejectedToken = token)
                            if (refreshed != null && refreshed != token) {
                                token = refreshed
                                runCatching { attempt(refreshed, accountCountry) }
                                    .onFailure { Timber.tag(TAG).w(it, "Tidal account retry failed for %s", request.mediaId) }
                                    .getOrNull()
                            } else {
                                null
                            }
                        } else {
                            Timber.tag(TAG).w(e, "Tidal account resolve failed for %s", request.mediaId)
                            null
                        }
                    }
                if (accountStream != null) return accountStream
            }

            for (poolAccount in PoolAccountManager.tidalAccounts()) {
                val poolCountry = poolAccount.countryCode?.trim()?.ifBlank { null } ?: "US"
                val poolStream =
                    runCatching { attempt(poolAccount.token, poolCountry) }
                        .onFailure {
                            if (it is kotlinx.coroutines.CancellationException) throw it
                            if (TidalAccountManager.isUnauthorized(it)) {
                                PoolAccountManager.report("tidal", "account", poolAccount.id, "dead")
                            } else {
                                Timber.tag(TAG).w(it, "Tidal pool account resolve failed for %s", request.mediaId)
                            }
                        }.getOrNull()
                if (poolStream != null) {
                    Timber.tag(TAG).d("Tidal resolved via pool account (premium=%s)", poolAccount.premium)
                    PoolAccountManager.noteAccountSuccess("tidal", poolAccount.id)
                    return poolStream
                }
                PoolAccountManager.noteAccountFailure("tidal", poolAccount.id)
            }
        }

        val configuredInstances = settings.tidalInstances
        val discoveredInstances = TidalInstanceHealthManager.healthyUrls(context)
        val mergedInstances =
            LinkedHashSet<String>().apply {
                addAll(configuredInstances)
                addAll(discoveredInstances)
            }.toList()
        Timber.tag(TAG).d(
            "Tidal public-instance fallback | configured=%d discovered=%d merged=%d",
            configuredInstances.size,
            discoveredInstances.size,
            mergedInstances.size,
        )
        if (mergedInstances.isEmpty()) {
            Timber.tag(TAG).d("Tidal skip: no instances configured (account path disabled or exhausted)")
            return null
        }
        TidalAudioProvider.setInstances(mergedInstances)
        val resolved =
            runCatching {
                TidalAudioProvider.resolve(
                    query =
                        TidalAudioProvider.Query(
                            mediaId = request.mediaId,
                            title = request.title,
                            artists = request.artists,
                            album = request.album,
                            isrc = request.isrc,
                            durationMs = request.durationMs,
                        ),
                    cacheDir = cacheDir,
                    preferAtmos = false,
                    preferLiveDash = true,
                    audioQuality = quality,
                )
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Timber.tag(TAG).w(error, "TIDAL stream resolution failed for %s", request.mediaId)
            }.getOrNull() ?: return null

        return DirectStream(
            uri = resolved.mediaUri,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            contentLength = resolved.contentLength,
            label = "Tidal ${resolved.label}",
            source = AudioSourceType.TIDAL,
            matchedTitle = resolved.matchedTitle,
            matchedArtist = resolved.matchedArtist,
            matchedAlbum = resolved.matchedAlbum,
            matchedDurationMs = resolved.matchedDurationMs,
            sampleRate = resolved.sampleRate,
        )
    }

    // ── Qobuz ─────────────────────────────────────────────────────────────────────────────────

    private fun resolveQobuzStream(
        request: Request,
        settings: SourceSettings,
    ): DirectStream? {
        val userInstances = settings.qobuzInstances
        val discoveredInstances = runCatching { QobuzAudioProvider.discoverInstances() }.getOrDefault(emptyList())
        val configuredInstances =
            LinkedHashSet<String>().apply {
                addAll(userInstances)
                addAll(discoveredInstances)
            }.toList()

        val poolTokens =
            PoolAccountManager.qobuzAccounts().map {
                QobuzToken(
                    token = it.token,
                    appId = it.appId,
                    appSecret = it.appSecret,
                    label = "Source Pool",
                    subscription = if (it.premium) "premium" else "",
                    poolId = it.id,
                )
            }
        val configuredTokens =
            (QobuzToken.listFromJson(settings.qobuzTokensJson) + poolTokens)
                .distinctBy { it.token }
        if (configuredInstances.isEmpty() && configuredTokens.isEmpty()) {
            Timber.tag(TAG).d("Qobuz skip: no tokens or instances configured")
            return null
        }
        val formatId = settings.qobuzQuality.toFormatId()
        Timber.tag(TAG).d(
            "Qobuz resolve start | formatId=%d tokens=%d instances=%d",
            formatId,
            configuredTokens.size,
            configuredInstances.size,
        )
        QobuzAudioProvider.setTokens(configuredTokens)
        QobuzAudioProvider.setInstances(configuredInstances)
        return runCatching {
            QobuzAudioProvider.resolve(
                query =
                    QobuzAudioProvider.Query(
                        mediaId = request.mediaId,
                        title = request.title,
                        artists = request.artists,
                        album = request.album,
                        durationMs = request.durationMs,
                        directTrackId = null,
                    ),
                formatId = formatId,
            )
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "QOBUZ stream resolution failed for %s", request.mediaId)
        }.getOrNull()
    }

    // ── Deezer ────────────────────────────────────────────────────────────────────────────────

    private fun resolveDeezerStream(
        request: Request,
        settings: SourceSettings,
    ): DirectStream? {
        if (!DeezerAudioProvider.hasAccounts()) {
            Timber.tag(TAG).d("Deezer skip: no manual or pooled accounts available")
            return null
        }
        val quality = settings.deezerQuality
        Timber.tag(TAG).d("Deezer resolve start | quality=%s", quality.name)
        return runCatching {
            DeezerAudioProvider
                .resolve(
                    query =
                        DeezerAudioProvider.Query(
                            mediaId = request.mediaId,
                            title = request.title,
                            artists = request.artists,
                            album = request.album,
                            durationMs = request.durationMs,
                            isrc = request.isrc,
                        ),
                    format = quality.toFormatName(),
                )?.let { resolved ->
                    DirectStream(
                        uri = resolved.uri,
                        mimeType = resolved.mimeType,
                        codecs = resolved.codecs,
                        contentLength = resolved.contentLength,
                        label = resolved.label,
                        source = AudioSourceType.DEEZER,
                        matchedTitle = resolved.matchedTitle,
                        matchedArtist = resolved.matchedArtist,
                        matchedAlbum = resolved.matchedAlbum,
                        matchedDurationMs = resolved.matchedDurationMs,
                        sampleRate = resolved.sampleRate,
                        bitDepth = resolved.bitDepth,
                    )
                }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "DEEZER stream resolution failed for %s", request.mediaId)
        }.getOrNull()
    }

    // ── Apple Music ───────────────────────────────────────────────────────────────────────────

    private suspend fun resolveAppleStream(
        context: Context,
        request: Request,
        settings: SourceSettings,
        trusted: Boolean,
    ): DirectStream? {
        if (AppleMusicAudioProvider.mediaUserToken() == null || AppleMusicAudioProvider.devToken() == null) {
            Timber.tag(TAG).d("Apple Music source: missing tokens (sign in via Settings -> Lossless Sources -> Apple Music)")
            return null
        }
        val appleQuality = settings.appleQuality
        val candidates =
            runCatching {
                AppleMusicAudioProvider.resolveCandidates(
                    title = request.title,
                    artists = request.artists,
                    album = request.album,
                    durationMs = request.durationMs,
                    quality = appleQuality,
                )
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Timber.tag(TAG).w(it, "Apple Music candidate search failed for %s", request.mediaId)
            }.getOrDefault(emptyList())
        if (candidates.isEmpty()) return null

        var winner: Pair<AppleMusicAudioProvider.AppleMusicStream, DirectStream>? = null
        var bestScore = -1.0
        for (candidate in candidates) {
            val stream =
                DirectStream(
                    uri = candidate.hlsDataUri,
                    mimeType = "application/vnd.apple.mpegurl",
                    codecs = if (candidate.flavor.endsWith("ctrp64")) "mp4a.40.5" else "mp4a.40.2",
                    contentLength = null,
                    label = "Apple Music ${candidate.flavor}",
                    source = AudioSourceType.APPLE,
                    matchedTitle = candidate.matchedTitle,
                    matchedArtist = candidate.matchedArtist,
                    matchedAlbum = candidate.matchedAlbum,
                    matchedDurationMs = candidate.matchedDurationMs,
                )
            val match =
                if (trusted) {
                    TitleMatch.Result(true, 1.0, 1.0, 1.0, 1.0, "per-song override bypass")
                } else {
                    TitleMatch.evaluate(
                        wantedTitle = request.title,
                        wantedArtists = request.artists,
                        wantedAlbum = request.album,
                        wantedDurationMs = request.durationMs,
                        stream = stream,
                    )
                }
            if (match.accepted && match.score > bestScore) {
                winner = candidate to stream
                bestScore = match.score
            }
        }
        val (candidate, stream) = winner ?: return null

        AppleMusicDrm.register(
            request.mediaId,
            AppleTrackDrmInfo(
                adamId = candidate.songId,
                drmUri = candidate.drmUri,
                mediaUserToken = candidate.mediaUserToken,
                developerToken = candidate.developerToken,
            ),
        )
        Timber.tag(TAG).i("Apple Music resolved [%s] for \"%s\" (HLS)", stream.label, request.title)
        return stream
    }

    // ── Result publication (port of applyDirectStream + persistDirectStreamFormat) ────────────

    private fun applyDirectStream(mediaId: String, stream: DirectStream): Result {
        Timber.tag(TAG).i("Using %s stream for %s: %s", stream.source, mediaId, stream.label)
        directStreamCache[sourceCacheKey(stream.source, mediaId)] =
            CachedDirectStream(stream = stream, expiresAtMs = System.currentTimeMillis() + DIRECT_STREAM_CACHE_TTL_MS)
        directStreamCache.trimTo(MAX_RESOLVED_ENTRIES) { it.expiresAtMs }
        return publish(mediaId, stream, fromCache = false)
    }

    private fun publish(mediaId: String, stream: DirectStream, fromCache: Boolean): Result {
        val label = stream.label.uppercase(Locale.US)
        val mime = stream.mimeType.substringBefore(";").trim().ifBlank { "audio/flac" }
        val codecs =
            stream.codecs.ifBlank {
                stream.mimeType.substringAfter("codecs=", "").removeSurrounding("\"").ifBlank { "flac" }
            }
        val sampleRate =
            stream.sampleRate?.takeIf { it > 0 }
                ?: when {
                    label.contains("HI_RES") || label.contains("MASTER") || label.contains("MQA") -> 96_000
                    label.contains("LOSSLESS") || codecs.contains("flac", true) || codecs.contains("alac", true) -> 44_100
                    else -> null
                }
        val knownContentLength = stream.contentLength?.takeIf { it > 0L }
        val bitrate =
            measuredBitrate(knownContentLength, stream.matchedDurationMs)
                ?: stream.pcmBitrateOrNull()
                ?: when {
                    label.contains("HI_RES") || label.contains("MASTER") || label.contains("MQA") -> 2_304_000
                    label.contains("LOSSLESS") || codecs.contains("flac", true) || codecs.contains("alac", true) -> 1_411_000
                    label.contains("HIGH") || label.contains("256") -> 256_000
                    label.contains("64") -> 64_000
                    else -> 0
                }
        val detectedContainerMime = AddonStreamRegistry.metaFor(stream.uri)?.let { AddonStreamRegistry.containerMimeOf(it.transport) }
            ?: when {
                stream.uri.startsWith("data:application/vnd.apple.mpegurl", ignoreCase = true) ||
                    stream.uri.startsWith("data:application/x-mpegurl", ignoreCase = true) ||
                    stream.mimeType.contains("mpegurl", ignoreCase = true) ||
                    stream.uri.lowercase(Locale.US).contains(".m3u8") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                stream.uri.lowercase(Locale.US).contains(".mpd") -> androidx.media3.common.MimeTypes.APPLICATION_MPD
                else -> null
            }
        val result =
            Result(
                mediaId = mediaId,
                uri = stream.uri,
                mimeType = mime,
                codecs = codecs,
                bitrate = bitrate,
                sampleRate = sampleRate,
                bitDepth = stream.bitDepth,
                contentLength = knownContentLength,
                label = stream.label,
                source = stream.source,
                containerMimeType = detectedContainerMime,
            )
        resolvedByMediaId[mediaId] = result
        val freshnessTtl = if (stream.source == AudioSourceType.APPLE) APPLE_HLS_FRESHNESS_MS else URI_FRESHNESS_MS
        uriFreshness[stream.uri] = System.currentTimeMillis() + freshnessTtl
        // Oldest-resolved first; uriFreshness expires in the same order, so the two stay consistent.
        resolvedByMediaId.trimTo(MAX_RESOLVED_ENTRIES) { it.resolvedAtMs }
        uriFreshness.trimTo(MAX_RESOLVED_ENTRIES) { it }
        _lastResolved.value = result

        // ArchiveTune back-fills the byte length with a HEAD request so the bitrate shown in the
        // player is measured rather than guessed. Only meaningful for plain progressive HTTP(S) streams.
        if (!fromCache && detectedContainerMime == null && knownContentLength == null && stream.uri.startsWith("http", ignoreCase = true)) {
            ioScope.launch {
                runCatching {
                    val headRequest = okhttp3.Request.Builder().url(stream.uri).head().build()
                    httpClient.newCall(headRequest).execute().use { response ->
                        val len = response.header("Content-Length")?.toLongOrNull() ?: -1L
                        if (len > 0L) {
                            val backfilled = measuredBitrate(len, stream.matchedDurationMs)
                            val current = resolvedByMediaId[mediaId]
                            if (current != null && current.uri == stream.uri) {
                                val updated = current.copy(contentLength = len, bitrate = backfilled ?: current.bitrate)
                                resolvedByMediaId[mediaId] = updated
                                if (_lastResolved.value?.mediaId == mediaId) _lastResolved.value = updated
                            }
                        }
                    }
                }.onFailure { err -> Timber.tag(TAG).d(err, "HEAD request for content length failed: %s", stream.uri) }
            }
        }
        return result
    }

    private fun measuredBitrate(
        contentLength: Long?,
        durationMs: Long?,
    ): Int? {
        val bytes = contentLength?.takeIf { it > 0L } ?: return null
        val millis = durationMs?.takeIf { it > 0L } ?: return null
        val bitsPerSecond = bytes * 8_000L / millis
        return bitsPerSecond.takeIf { it in 1L..50_000_000L }?.toInt()
    }

    private fun resolveMonochromeStream(
        request: Request,
        settings: SourceSettings,
        trusted: Boolean = false,
    ): DirectStream? {
        if (!settings.monochromeEnabled && !trusted) return null
        return try {
            val stream = MonochromeInstancePool.resolve(
                httpClient = httpClient,
                title = request.title,
                artists = request.artists,
                album = request.album,
                durationMs = request.durationMs,
                customInstancesRaw = settings.monochromeInstances.joinToString("\n"),
                trustedOverride = trusted,
            )
            if (stream != null) {
                markMonochromeUp()
                // Detect DASH MPD vs HLS M3U8 returned by HiFi API v2 instances so ExoPlayer uses the right MediaSource
                val lowerUri = stream.uri.lowercase(Locale.US)
                val transport = when {
                    lowerUri.contains(".mpd") -> AddonTransport.DASH
                    lowerUri.contains(".m3u8") -> AddonTransport.HLS
                    else -> AddonTransport.PROGRESSIVE
                }
                if (transport != AddonTransport.PROGRESSIVE) {
                    AddonStreamRegistry.registerStreamMetadata(
                        uri = stream.uri,
                        headers = emptyMap(),
                        sourceId = "builtin:MONOCHROME",
                        addonName = "Monochrome",
                        transport = transport,
                        audioMimeType = stream.mimeType,
                    )
                }
            }
            stream
        } catch (e: Exception) {
            if (e is java.io.IOException) markMonochromeDown()
            Timber.tag(TAG).w(e, "Monochrome pool resolve failed for %s", request.title)
            null
        }
    }

    private fun sourceCacheKey(
        source: AudioSourceType,
        mediaId: String,
    ): String = "${source.name.lowercase(Locale.US)}:$mediaId"
}
