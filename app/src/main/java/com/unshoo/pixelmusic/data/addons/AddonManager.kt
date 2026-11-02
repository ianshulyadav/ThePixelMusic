package com.unshoo.pixelmusic.data.addons

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.unshoo.pixelmusic.data.lossless.constants.AddonsEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.AddonsFastConcurrentKey
import com.unshoo.pixelmusic.data.lossless.constants.AddonsTimeoutMsKey
import com.unshoo.pixelmusic.data.lossless.constants.AppleMusicSourceEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceOrderKey
import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceType
import com.unshoo.pixelmusic.data.lossless.constants.DeezerEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.MonochromeEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.QobuzEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.SongSourceOverrideKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.UnifiedSourceOrderKey
import com.unshoo.pixelmusic.data.preferences.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central multi-addon registry, lifecycle coordinator, and hedged concurrent wave resolver.
 *
 * Manages:
 *  - Persistence of all installed HTTP Addons, 8SPINE JS modules, and ArchiveTune `.arch` packages
 *    under `<filesDir>/addons/`.
 *  - Bidirectional synchronization of the **Unified Fallback Order** across installed Addons and
 *    built-in providers (`Monochrome`, `Tidal`, `Qobuz`, `Deezer`, `Apple Music`, `JioSaavn`, `YouTube`).
 *  - Concurrent hedged wave racing so healthy fast addons return streams in ~200–600 ms while
 *    preserving strict priority ordering when higher-ranked sources answer within the grace window.
 */
object AddonManager {
    private const val TAG = "AddonManager"
    const val DEFAULT_PER_ADDON_TIMEOUT_MS = 6_500L
    private const val FAST_HEAD_START_MS = 120L
    private const val RANK_GRACE_WINDOW_MS = 950L
    private const val WAVE_CONCURRENCY = 3

    private val SaavnEnabledPrefKey = booleanPreferencesKey("enable_saavn_streaming")

    private val initialized = AtomicBoolean(false)
    private val diskMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appDirRoot: File? = null

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    val httpAddonClient: HttpAddonClient by lazy { HttpAddonClient(httpClient) }

    val jsRuntime: QuickJsAddonRuntime by lazy {
        QuickJsAddonRuntime(
            httpClient = httpClient,
            storageDirProvider = ::addonsRootDir,
        )
    }

    val installer: AddonInstaller by lazy {
        AddonInstaller(
            httpClient = httpClient,
            jsRuntime = jsRuntime,
            scriptsDirProvider = ::scriptsDir,
        )
    }

    private val _installedAddons = MutableStateFlow<List<InstalledAddon>>(emptyList())
    val installedAddons: StateFlow<List<InstalledAddon>> = _installedAddons.asStateFlow()

    private val _checkReports = MutableStateFlow<Map<String, AddonCheckReport>>(emptyMap())
    val checkReports: StateFlow<Map<String, AddonCheckReport>> = _checkReports.asStateFlow()

    fun initialize(context: Context, scope: CoroutineScope = ioScope) {
        val appCtx = context.applicationContext
        appDirRoot = appCtx.filesDir
        if (!initialized.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            runCatching { loadInstalledFromDisk(appCtx) }
                .onFailure { Timber.tag(TAG).w(it, "Failed to load installed addons") }
        }
    }

    fun ensureLoadedBlocking(context: Context): List<InstalledAddon> {
        val appCtx = context.applicationContext
        if (appDirRoot == null) appDirRoot = appCtx.filesDir
        if (initialized.compareAndSet(false, true)) {
            runCatching {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) { loadInstalledFromDisk(appCtx) }
            }
        }
        return _installedAddons.value
    }

    private fun addonsRootDir(): File {
        val base = appDirRoot ?: File(System.getProperty("java.io.tmpdir", "/tmp"), "pixelmusic_addons")
        return File(base, "addons").apply { mkdirs() }
    }

    private fun scriptsDir(): File = File(addonsRootDir(), "scripts").apply { mkdirs() }

    private fun registryFile(): File = File(addonsRootDir(), "installed_addons.json")

    private suspend fun loadInstalledFromDisk(context: Context) {
        diskMutex.withLock {
            val file = registryFile()
            if (!file.isFile) {
                _installedAddons.value = emptyList()
                return@withLock
            }
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
            if (text.isBlank()) {
                _installedAddons.value = emptyList()
                return@withLock
            }
            val arr = runCatching { JSONArray(text) }.getOrNull() ?: JSONArray()
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    InstalledAddon.fromJson(obj)?.let(::add)
                }
            }
            _installedAddons.value = list
            syncUnifiedOrderWithAddons(context, list)
        }
    }

    private suspend fun persistAddonsLocked(addons: List<InstalledAddon>) {
        val arr = JSONArray()
        addons.forEach { arr.put(it.toJson()) }
        val target = registryFile()
        val temp = File(target.parentFile, "installed_addons.json.tmp")
        temp.writeText(arr.toString(2), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        _installedAddons.value = addons
    }

    private suspend fun syncUnifiedOrderWithAddons(context: Context, addons: List<InstalledAddon>) {
        context.dataStore.edit { prefs ->
            val merged = UnifiedSourceId.buildUnifiedOrder(
                savedUnifiedCsv = prefs[UnifiedSourceOrderKey],
                legacyBuiltInCsv = prefs[AudioSourceOrderKey],
                addons = addons,
            )
            prefs[UnifiedSourceOrderKey] = merged.joinToString(",")
        }
    }

    fun addonById(addonId: String): InstalledAddon? {
        val clean = InstalledAddon.sanitizeId(addonId)
        return _installedAddons.value.firstOrNull { it.id == clean }
    }

    fun readUnifiedOrder(prefs: Preferences, addons: List<InstalledAddon> = _installedAddons.value): List<String> =
        UnifiedSourceId.buildUnifiedOrder(
            savedUnifiedCsv = prefs[UnifiedSourceOrderKey],
            legacyBuiltInCsv = prefs[AudioSourceOrderKey],
            addons = addons,
        )

    suspend fun readUnifiedOrder(context: Context): List<String> {
        val addons = ensureLoadedBlocking(context)
        val prefs = context.applicationContext.dataStore.data.first()
        return readUnifiedOrder(prefs, addons)
    }

    suspend fun saveUnifiedOrder(context: Context, newOrder: List<String>) {
        val addons = ensureLoadedBlocking(context)
        val normalized = (newOrder.mapNotNull(UnifiedSourceId::normalize) +
            UnifiedSourceId.buildUnifiedOrder(null, null, addons)).distinct()
        val builtInOnly = normalized
            .mapNotNull(UnifiedSourceId::builtInTypeOrNull)
            .map { it.name }
            .distinct()

        context.dataStore.edit { prefs ->
            prefs[UnifiedSourceOrderKey] = normalized.joinToString(",")
            if (builtInOnly.isNotEmpty()) {
                prefs[AudioSourceOrderKey] = builtInOnly.joinToString(",")
            }
        }
    }

    suspend fun syncFromLegacyBuiltInOrder(context: Context, builtInOrder: List<AudioSourceType>) {
        val addons = ensureLoadedBlocking(context)
        context.dataStore.edit { prefs ->
            val current = readUnifiedOrder(prefs, addons)
            val targetSet = builtInOrder.map(UnifiedSourceId::forBuiltIn).toSet()
            val queue = ArrayDeque(builtInOrder.map(UnifiedSourceId::forBuiltIn))
            val updated = current.map { entry ->
                if (entry in targetSet && queue.isNotEmpty()) queue.removeFirst() else entry
            }
            prefs[UnifiedSourceOrderKey] = updated.distinct().joinToString(",")
            prefs[AudioSourceOrderKey] = builtInOrder.joinToString(",") { it.name }
        }
    }

    fun isBuiltInEnabled(type: AudioSourceType, prefs: Preferences): Boolean = when (type) {
        AudioSourceType.MONOCHROME -> prefs[MonochromeEnabledKey] ?: true
        AudioSourceType.TIDAL -> prefs[TidalEnabledKey] ?: true
        AudioSourceType.QOBUZ -> prefs[QobuzEnabledKey] ?: false
        AudioSourceType.DEEZER -> prefs[DeezerEnabledKey] ?: false
        AudioSourceType.APPLE -> prefs[AppleMusicSourceEnabledKey] ?: true
        AudioSourceType.JIOSAAVN -> prefs[SaavnEnabledPrefKey] ?: true
        AudioSourceType.YOUTUBE -> true
        AudioSourceType.ADDON -> prefs[AddonsEnabledKey] ?: true
        AudioSourceType.QOBUZ_BACKUP, AudioSourceType.AMAZON -> false
    }

    suspend fun setBuiltInEnabled(context: Context, type: AudioSourceType, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            when (type) {
                AudioSourceType.MONOCHROME -> prefs[MonochromeEnabledKey] = enabled
                AudioSourceType.TIDAL -> prefs[TidalEnabledKey] = enabled
                AudioSourceType.QOBUZ -> prefs[QobuzEnabledKey] = enabled
                AudioSourceType.DEEZER -> prefs[DeezerEnabledKey] = enabled
                AudioSourceType.APPLE -> prefs[AppleMusicSourceEnabledKey] = enabled
                AudioSourceType.JIOSAAVN -> prefs[SaavnEnabledPrefKey] = enabled
                else -> {}
            }
        }
    }

    // ── Installation / Import / Configuration ─────────────────────────────────────────────────

    suspend fun discoverOrInstallUrl(context: Context, rawUrl: String): AddonDiscoverResult {
        ensureLoadedBlocking(context)
        val existingMap = _installedAddons.value.associateBy { it.id }
        val result = installer.discoverOrInstallFromUrl(rawUrl, existingMap)
        if (result is AddonDiscoverResult.Installed) {
            upsertInstalledAddon(context, result.addon)
        }
        return result
    }

    suspend fun installCatalogEntry(
        context: Context,
        entry: CatalogAddonEntry,
        indexUrl: String,
    ): InstalledAddon {
        ensureLoadedBlocking(context)
        val existingMap = _installedAddons.value.associateBy { it.id }
        val installed = installer.installCatalogEntry(entry, indexUrl, existingMap)
        upsertInstalledAddon(context, installed)
        return installed
    }

    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        fallbackFileName: String = "module.arch",
    ): AddonDiscoverResult = withContext(Dispatchers.IO) {
        ensureLoadedBlocking(context)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not open selected file")
        val existingMap = _installedAddons.value.associateBy { it.id }
        val result = installer.importFromBytes(bytes, fallbackFileName, "", existingMap)
        if (result is AddonDiscoverResult.Installed) {
            upsertInstalledAddon(context, result.addon)
        }
        result
    }

    suspend fun upsertInstalledAddon(context: Context, addon: InstalledAddon) {
        diskMutex.withLock {
            val current = _installedAddons.value.toMutableList()
            val idx = current.indexOfFirst { it.id == addon.id }
            if (idx >= 0) {
                current[idx] = addon
            } else {
                current.add(0, addon)
            }
            persistAddonsLocked(current)
            AddonHealthTracker.reset(addon.unifiedSourceId)
            syncUnifiedOrderWithAddons(context, current)
        }
    }

    suspend fun setAddonEnabled(context: Context, addonId: String, enabled: Boolean) {
        diskMutex.withLock {
            val current = _installedAddons.value.map {
                if (it.id == addonId) it.copy(enabled = enabled) else it
            }
            persistAddonsLocked(current)
            if (enabled) AddonHealthTracker.reset(UnifiedSourceId.forAddon(addonId))
        }
    }

    suspend fun updateAddonSetting(
        context: Context,
        addonId: String,
        key: String,
        value: String,
        forMobileNetwork: Boolean = false,
    ) {
        diskMutex.withLock {
            val current = _installedAddons.value.map { addon ->
                if (addon.id != addonId) {
                    addon
                } else if (forMobileNetwork) {
                    addon.copy(mobileSettingsValues = addon.mobileSettingsValues + (key to value))
                } else {
                    addon.copy(settingsValues = addon.settingsValues + (key to value))
                }
            }
            persistAddonsLocked(current)
            AddonHealthTracker.reset(UnifiedSourceId.forAddon(addonId))
        }
    }

    suspend fun removeAddon(context: Context, addonId: String) {
        val clean = InstalledAddon.sanitizeId(addonId)
        diskMutex.withLock {
            val removed = _installedAddons.value.firstOrNull { it.id == clean }
            val remaining = _installedAddons.value.filterNot { it.id == clean }
            persistAddonsLocked(remaining)
            removed?.scriptFileName?.takeIf { it.isNotBlank() }?.let { File(scriptsDir(), it).delete() }
            File(File(addonsRootDir(), "js_storage"), "$clean.json").delete()
            jsRuntime.evictAddon(clean)
            AddonHealthTracker.reset(UnifiedSourceId.forAddon(clean))
            _checkReports.update { it - clean }

            val targetUnified = UnifiedSourceId.forAddon(clean)
            context.dataStore.edit { prefs ->
                val order = readUnifiedOrder(prefs, remaining).filterNot { it == targetUnified }
                prefs[UnifiedSourceOrderKey] = order.joinToString(",")
                // Clean any song overrides pinned to the removed addon
                val overrides = UnifiedSongSourceOverride.parse(prefs[SongSourceOverrideKey])
                    .filterValues { it != targetUnified }
                prefs[SongSourceOverrideKey] = UnifiedSongSourceOverride.encode(overrides)
            }
        }
    }

    suspend fun refreshAddon(context: Context, addonId: String): InstalledAddon = withContext(Dispatchers.IO) {
        ensureLoadedBlocking(context)
        val current = addonById(addonId) ?: throw IllegalArgumentException("Addon not found: $addonId")
        val existingMap = _installedAddons.value.associateBy { it.id }

        if (current.indexUrl.isNotBlank()) {
            val cat = installer.discoverOrInstallFromUrl(current.indexUrl, existingMap)
            if (cat is AddonDiscoverResult.Catalog) {
                val match = cat.entries.firstOrNull { InstalledAddon.sanitizeId(it.id) == current.id || it.name.equals(current.name, true) }
                if (match != null) {
                    return@withContext installCatalogEntry(context, match, current.indexUrl)
                }
            }
        }

        val refreshUrl = current.manifestUrl.ifBlank { current.sourceUrl }
        if (refreshUrl.isBlank()) {
            AddonHealthTracker.reset(current.unifiedSourceId)
            return@withContext current
        }
        val discovered = installer.discoverOrInstallFromUrl(refreshUrl, existingMap)
        when (discovered) {
            is AddonDiscoverResult.Installed -> {
                upsertInstalledAddon(context, discovered.addon)
                discovered.addon
            }
            is AddonDiscoverResult.Catalog -> {
                val match = discovered.entries.firstOrNull { InstalledAddon.sanitizeId(it.id) == current.id }
                    ?: discovered.entries.firstOrNull()
                    ?: throw IllegalStateException("No matching module found in catalog")
                installCatalogEntry(context, match, refreshUrl)
            }
        }
    }

    suspend fun checkAddon(context: Context, addonId: String): AddonCheckReport = withContext(Dispatchers.IO) {
        ensureLoadedBlocking(context)
        val addon = addonById(addonId)
            ?: return@withContext AddonCheckReport(addonId, false, 0L, "Addon not installed")
        AddonHealthTracker.reset(addon.unifiedSourceId)
        val started = System.currentTimeMillis()
        val report = try {
            val resolved = resolveSingleAddon(
                context = context,
                addon = addon,
                title = "Get Lucky",
                artists = listOf("Daft Punk"),
                album = "Random Access Memories",
                durationMs = 369_000L,
                isrc = "USQX91300108",
                preferLossless = true,
                forDownload = false,
                trustedOverride = true,
                timeoutMs = 12_000L,
            )
            val elapsed = System.currentTimeMillis() - started
            if (resolved != null) {
                val transportTag = resolved.transport.name
                AddonCheckReport(
                    addonId = addon.id,
                    ok = true,
                    latencyMs = elapsed,
                    summary = "Ready (${elapsed} ms) · ${resolved.qualityLabel} [$transportTag]",
                    details = buildString {
                        appendLine("Matched: ${resolved.matchedTitle ?: "Get Lucky"} — ${resolved.matchedArtist ?: "Daft Punk"}")
                        appendLine("Format: ${resolved.qualityLabel} (${resolved.audioMimeType}, codecs=${resolved.codecs})")
                        appendLine("Transport: $transportTag")
                        resolved.sampleRate?.let { appendLine("Sample rate: $it Hz") }
                        resolved.bitDepth?.let { appendLine("Bit depth: $it-bit") }
                        appendLine("Latency: $elapsed ms")
                    }.trim(),
                )
            } else {
                val healthErr = AddonHealthTracker.stateOf(addon.unifiedSourceId).lastError
                AddonCheckReport(
                    addonId = addon.id,
                    ok = false,
                    latencyMs = elapsed,
                    summary = healthErr?.let { "Failed: $it" } ?: "No playable stream returned (${elapsed} ms)",
                    details = healthErr ?: "Probe query 'Get Lucky — Daft Punk' returned no playable stream.",
                )
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            val elapsed = System.currentTimeMillis() - started
            AddonCheckReport(
                addonId = addon.id,
                ok = false,
                latencyMs = elapsed,
                summary = "Error: ${e.message ?: e::class.java.simpleName}",
                details = e.stackTraceToString().take(800),
            )
        }
        _checkReports.update { it + (addon.id to report) }
        report
    }

    // ── Playback & Download Resolution ────────────────────────────────────────────────────────

    suspend fun resolveSingleAddon(
        context: Context,
        addon: InstalledAddon,
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        isrc: String?,
        preferLossless: Boolean = true,
        forDownload: Boolean = false,
        trustedOverride: Boolean = false,
        nextTrackIds: List<String> = emptyList(),
        timeoutMs: Long = DEFAULT_PER_ADDON_TIMEOUT_MS,
    ): AddonResolvedStream? {
        if (forDownload && !addon.allowDownloads) return null
        val metered = isMeteredConnection(context)
        return withTimeoutOrNull(timeoutMs.coerceIn(1_500L, 25_000L)) {
            when (addon.protocol) {
                AddonProtocol.HTTP_ADDON -> httpAddonClient.resolveForSong(
                    addon = addon,
                    title = title,
                    artists = artists,
                    album = album,
                    durationMs = durationMs,
                    isrc = isrc,
                    isMeteredNetwork = metered,
                    preferLossless = preferLossless,
                    forDownload = forDownload,
                    trustedOverride = trustedOverride,
                )
                AddonProtocol.SPINE_JS, AddonProtocol.ARCH_PACKAGE -> {
                    val script = readAddonScript(addon) ?: return@withTimeoutOrNull null
                    jsRuntime.resolveForSong(
                        addon = addon,
                        scriptSource = script,
                        title = title,
                        artists = artists,
                        album = album,
                        durationMs = durationMs,
                        isrc = isrc,
                        isMeteredNetwork = metered,
                        preferLossless = preferLossless,
                        forDownload = forDownload,
                        trustedOverride = trustedOverride,
                        nextTrackIds = nextTrackIds,
                        timeoutMs = timeoutMs,
                    )
                }
            }
        }
    }

    /**
     * Executes an ordered list of source resolvers using either **Fast Hedged Concurrent Waves**
     * (default) or strict sequential walking:
     *
     *  - Launches up to [WAVE_CONCURRENCY] (3) ordered candidates per wave with a small
     *    [FAST_HEAD_START_MS] (120 ms) stagger per rank.
     *  - If Rank #0 succeeds first, lower-ranked in-flight tasks are cancelled immediately.
     *  - If Rank #1 succeeds while Rank #0 is still pending, Rank #0 gets up to
     *    [RANK_GRACE_WINDOW_MS] (950 ms) to finish; if Rank #0 misses or exceeds the grace window,
     *    Rank #1 wins immediately without blocking playback on a slow/cold endpoint!
     */
    suspend fun <T : Any> executeOrderedCandidates(
        candidates: List<String>,
        fastConcurrent: Boolean,
        perSourceTimeoutMs: Long,
        block: suspend (sourceId: String) -> T?,
    ): Pair<String, T>? {
        if (candidates.isEmpty()) return null
        if (!fastConcurrent || candidates.size == 1) {
            for (sourceId in candidates) {
                val res = withTimeoutOrNull(perSourceTimeoutMs) { block(sourceId) }
                if (res != null) return sourceId to res
            }
            return null
        }

        for (wave in candidates.chunked(WAVE_CONCURRENCY)) {
            val waveWinner = runHedgedWave(wave, perSourceTimeoutMs, block)
            if (waveWinner != null) return waveWinner
        }
        return null
    }

    private suspend fun <T : Any> runHedgedWave(
        wave: List<String>,
        perSourceTimeoutMs: Long,
        block: suspend (String) -> T?,
    ): Pair<String, T>? = coroutineScope {
        val slots = Array<CompletableDeferred<T?>>(wave.size) { CompletableDeferred() }
        val jobs = wave.mapIndexed { idx, sourceId ->
            async(Dispatchers.IO) {
                try {
                    if (idx > 0) {
                        delay(idx * FAST_HEAD_START_MS)
                        // If a higher-ranked slot already produced a non-null stream, skip starting!
                        if ((0 until idx).any { higher -> slots[higher].isCompleted && runCatching { slots[higher].getCompleted() }.getOrNull() != null }) {
                            slots[idx].complete(null)
                            return@async
                        }
                    }
                    val value = withTimeoutOrNull(perSourceTimeoutMs) { block(sourceId) }
                    slots[idx].complete(value)
                } catch (c: CancellationException) {
                    slots[idx].complete(null)
                    throw c
                } catch (_: Throwable) {
                    slots[idx].complete(null)
                }
            }
        }

        try {
            for (idx in wave.indices) {
                // Wait for slot[idx], UNLESS a lower-ranked slot (j > idx) already succeeded and
                // slot[idx] takes longer than RANK_GRACE_WINDOW_MS after that!
                val currentSlot = slots[idx]
                if (!currentSlot.isCompleted) {
                    var waitedGraceMs = 0L
                    while (!currentSlot.isCompleted) {
                        val lowerReady = ((idx + 1) until wave.size).any { j ->
                            slots[j].isCompleted && runCatching { slots[j].getCompleted() }.getOrNull() != null
                        }
                        if (lowerReady) {
                            if (waitedGraceMs >= RANK_GRACE_WINDOW_MS) break
                            delay(50L)
                            waitedGraceMs += 50L
                        } else {
                            delay(35L)
                        }
                    }
                }
                if (currentSlot.isCompleted) {
                    val result = runCatching { currentSlot.getCompleted() }.getOrNull()
                    if (result != null) {
                        return@coroutineScope wave[idx] to result
                    }
                }
            }

            // Pick the highest-ranked completed winner if Rank #0 timed out past grace window
            for (idx in wave.indices) {
                if (slots[idx].isCompleted) {
                    val res = runCatching { slots[idx].getCompleted() }.getOrNull()
                    if (res != null) return@coroutineScope wave[idx] to res
                }
            }
            // Otherwise await remaining slots in rank order
            for (idx in wave.indices) {
                val res = slots[idx].await()
                if (res != null) return@coroutineScope wave[idx] to res
            }
            null
        } finally {
            jobs.forEach { it.cancel() }
            coroutineContext.cancelChildren()
        }
    }

    private fun readAddonScript(addon: InstalledAddon): String? {
        if (addon.scriptFileName.isBlank()) return null
        val file = File(scriptsDir(), addon.scriptFileName)
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    @Suppress("DEPRECATION")
    private fun isMeteredConnection(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.isActiveNetworkMetered ?: false
        }.getOrDefault(false)
    }
}
