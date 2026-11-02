package com.unshoo.pixelmusic.data.addons

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * ES2020+ JavaScript module runtime powered by QuickJS (`com.dokar.quickjs.QuickJs`).
 *
 * Executes both **8SPINE JS modules** (`8spine.js`, Convx `:spine`, BitChord `SpineModule`) and
 * **ArchiveTune `.arch` provider scripts** (`provider.js` / CommonJS `module.exports`).
 *
 * Host capabilities injected into every sandbox:
 *  - `console` (`log`, `info`, `warn`, `error`, `debug`)
 *  - `Headers`, `URLSearchParams`, `URL` (resolved via JVM `URI`/`HttpUrl`)
 *  - `atob`, `btoa`, `TextEncoder`, `TextDecoder`
 *  - `AbortController`, `AbortSignal.timeout(ms)`, `setTimeout`, `clearTimeout`
 *  - `Promise.any` / `Promise.allSettled` polyfills if needed
 *  - Persistent per-module `localStorage` backed by disk JSON (so modules like `8spine.js` cache
 *    minted tokens across invocations)
 *  - Async `fetch(input, init)` backed by `OkHttpClient` with relative URL resolution against the
 *    module's `sourceUrl` origin!
 */
class QuickJsAddonRuntime(
    private val httpClient: OkHttpClient,
    private val storageDirProvider: () -> File,
) {
    data class InspectedModule(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val author: String,
        val homepage: String,
        val iconUrl: String,
        val iconBase64: String,
        val labels: List<String>,
        val noStreamCache: Boolean,
        val allowDownloads: Boolean,
        val settingsSchema: List<AddonSettingSpec>,
        val capabilities: List<String>,
    )

    private class ModuleSession(
        val addonId: String,
        val scriptHash: Int,
        val originUrl: String,
        val mutex: Mutex = Mutex(),
        var quickJs: QuickJs? = null,
        val activeTimers: ConcurrentHashMap<String, CompletableDeferred<Unit>> = ConcurrentHashMap(),
    ) {
        fun close() {
            activeTimers.values.forEach { it.complete(Unit) }
            activeTimers.clear()
            runCatching { quickJs?.close() }
            quickJs = null
        }
    }

    private val sessions = ConcurrentHashMap<String, ModuleSession>()

    suspend fun inspectScript(
        scriptSource: String,
        originUrl: String = "",
        timeoutMs: Long = 10_000L,
    ): InspectedModule = withContext(Dispatchers.Default) {
        withTimeout(timeoutMs) {
            val tempId = "inspect-${System.nanoTime()}"
            val timers = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            val qjs = QuickJs.create(jobDispatcher = Dispatchers.IO)
            try {
                installHostBindings(qjs, tempId, originUrl, timers)
                loadModuleIntoGlobal(qjs, scriptSource)
                val metaJsonStr = qjs.evaluate<String>(INSPECT_MODULE_JS)
                parseInspectedMetadata(JSONObject(metaJsonStr), originUrl)
            } finally {
                timers.values.forEach { it.complete(Unit) }
                timers.clear()
                runCatching { qjs.close() }
            }
        }
    }

    suspend fun searchTracks(
        addon: InstalledAddon,
        scriptSource: String,
        query: String,
        limit: Int = 15,
        isMeteredNetwork: Boolean = false,
        nextTrackIds: List<String> = emptyList(),
        timeoutMs: Long = 12_000L,
    ): List<AddonTrackCandidate> = withContext(Dispatchers.Default) {
        val ctxJson = buildContextJson(addon, isMeteredNetwork, nextTrackIds)
        val argsJson = JSONArray()
            .put(query)
            .put(limit)
            .put(ctxJson)
            .toString()

        val rawResult = invokeProviderFunction(
            addon = addon,
            scriptSource = scriptSource,
            functionName = "searchTracks",
            argsJson = argsJson,
            timeoutMs = timeoutMs,
        ) ?: return@withContext emptyList()

        HttpAddonClient(httpClient).parseTracksPayload(rawResult)
    }

    suspend fun resolveStream(
        addon: InstalledAddon,
        scriptSource: String,
        candidate: AddonTrackCandidate,
        isMeteredNetwork: Boolean = false,
        preferLossless: Boolean = true,
        forDownload: Boolean = false,
        nextTrackIds: List<String> = emptyList(),
        timeoutMs: Long = 12_000L,
    ): AddonResolvedStream? = withContext(Dispatchers.Default) {
        if (forDownload && !addon.allowDownloads) return@withContext null

        val ctxJson = buildContextJson(addon, isMeteredNetwork, nextTrackIds, candidate)
        val qualityValue = resolveQualityArg(addon, isMeteredNetwork, preferLossless)
        val argsJson = JSONArray()
            .put(candidate.id)
            .put(qualityValue)
            .put(ctxJson)
            .toString()

        val rawStream = if (forDownload) {
            invokeProviderFunction(
                addon = addon,
                scriptSource = scriptSource,
                functionName = "getTrackDownloadUrl",
                argsJson = argsJson,
                timeoutMs = timeoutMs,
                optionalFunction = true,
            ) ?: invokeProviderFunction(
                addon = addon,
                scriptSource = scriptSource,
                functionName = "getTrackStreamUrl",
                argsJson = argsJson,
                timeoutMs = timeoutMs,
            )
        } else {
            invokeProviderFunction(
                addon = addon,
                scriptSource = scriptSource,
                functionName = "getTrackStreamUrl",
                argsJson = argsJson,
                timeoutMs = timeoutMs,
            )
        } ?: return@withContext null

        parseJsStreamResponse(addon, candidate, rawStream)
    }

    suspend fun resolveForSong(
        addon: InstalledAddon,
        scriptSource: String,
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        isrc: String?,
        isMeteredNetwork: Boolean = false,
        preferLossless: Boolean = true,
        forDownload: Boolean = false,
        trustedOverride: Boolean = false,
        nextTrackIds: List<String> = emptyList(),
        timeoutMs: Long = 12_000L,
    ): AddonResolvedStream? = withContext(Dispatchers.Default) {
        if (forDownload && !addon.allowDownloads) return@withContext null
        val startedAt = System.currentTimeMillis()
        val query = AddonTrackMatcher.buildSearchQuery(title, artists)

        val candidates = runCatching {
            searchTracks(
                addon = addon,
                scriptSource = scriptSource,
                query = query,
                limit = 12,
                isMeteredNetwork = isMeteredNetwork,
                nextTrackIds = nextTrackIds,
                timeoutMs = timeoutMs,
            )
        }.onFailure { err ->
            if (err is CancellationException) throw err
            AddonHealthTracker.recordFailure(addon.unifiedSourceId, err.message ?: "JS search failed")
            Timber.tag(TAG).w(err, "JS module %s search failed for '%s'", addon.name, query)
        }.getOrNull().orEmpty()

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

        for (entry in ranked.take(2)) {
            val stream = runCatching {
                resolveStream(
                    addon = addon,
                    scriptSource = scriptSource,
                    candidate = entry.candidate,
                    isMeteredNetwork = isMeteredNetwork,
                    preferLossless = preferLossless,
                    forDownload = forDownload,
                    nextTrackIds = nextTrackIds,
                    timeoutMs = timeoutMs,
                )
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Timber.tag(TAG).w(err, "JS module %s stream resolve failed for %s", addon.name, entry.candidate.id)
            }.getOrNull()

            if (stream != null) {
                AddonHealthTracker.recordSuccess(addon.unifiedSourceId, System.currentTimeMillis() - startedAt)
                return@withContext stream
            }
        }
        null
    }

    fun evictAddon(addonId: String) {
        sessions.remove(addonId)?.close()
    }

    fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private suspend fun invokeProviderFunction(
        addon: InstalledAddon,
        scriptSource: String,
        functionName: String,
        argsJson: String,
        timeoutMs: Long,
        optionalFunction: Boolean = false,
    ): String? {
        val hash = scriptSource.hashCode()
        val session = sessions.compute(addon.id) { _, existing ->
            if (existing != null && existing.scriptHash == hash && existing.originUrl == addon.sourceUrl) {
                existing
            } else {
                existing?.close()
                ModuleSession(addonId = addon.id, scriptHash = hash, originUrl = addon.sourceUrl)
            }
        }!!

        return session.mutex.withLock {
            try {
                withTimeout(timeoutMs) {
                    val qjs = ensureEngine(session, scriptSource)
                    val callExpr = """
                        (async function() {
                            const p = globalThis.__pixelProvider;
                            if (!p || typeof p[${JSONObject.quote(functionName)}] !== 'function') {
                                return ${if (optionalFunction) "null" else "(() => { throw new Error('Missing function ' + ${JSONObject.quote(functionName)}); })()"};
                            }
                            const args = JSON.parse(${JSONObject.quote(argsJson)});
                            const out = await p[${JSONObject.quote(functionName)}].apply(p, args);
                            return out === undefined || out === null ? null : JSON.stringify(out);
                        })()
                    """.trimIndent()
                    qjs.evaluate<String?>(callExpr)
                }
            } catch (e: CancellationException) {
                // Reset engine if timed out mid-promise so next call starts clean
                session.close()
                throw e
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "QuickJS invoke %s.%s failed", addon.name, functionName)
                session.close()
                if (optionalFunction) null else throw e
            }
        }
    }

    private suspend fun ensureEngine(session: ModuleSession, scriptSource: String): QuickJs {
        session.quickJs?.let { return it }
        val qjs = QuickJs.create(jobDispatcher = Dispatchers.IO)
        installHostBindings(qjs, session.addonId, session.originUrl, session.activeTimers)
        loadModuleIntoGlobal(qjs, scriptSource)
        session.quickJs = qjs
        return qjs
    }

    private suspend fun installHostBindings(
        qjs: QuickJs,
        addonId: String,
        originUrl: String,
        timers: ConcurrentHashMap<String, CompletableDeferred<Unit>>,
    ) {
        qjs.function("__hostLog") { args ->
            val level = args.getOrNull(0)?.toString() ?: "d"
            val msg = args.drop(1).joinToString(" ") { it?.toString().orEmpty() }
            if (level == "e" || level == "w") {
                Timber.tag("AddonJS:$addonId").w("%s", msg)
            } else {
                Timber.tag("AddonJS:$addonId").d("%s", msg)
            }
            null
        }

        qjs.function("__hostResolveUrl") { args ->
            val raw = args.getOrNull(0)?.toString().orEmpty()
            val base = args.getOrNull(1)?.toString()?.takeIf { it.isNotBlank() && it != "undefined" && it != "null" }
                ?: originUrl
            resolveUrlJson(raw, base)
        }

        qjs.function("__hostStorageGet") { args ->
            val key = args.getOrNull(0)?.toString().orEmpty()
            readModuleStorage(addonId).opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()
        }

        qjs.function("__hostStorageSet") { args ->
            val key = args.getOrNull(0)?.toString().orEmpty()
            val value = args.getOrNull(1)?.toString()
            if (key.isNotEmpty()) {
                val obj = readModuleStorage(addonId)
                if (value == null) obj.remove(key) else obj.put(key, value)
                writeModuleStorage(addonId, obj)
            }
            null
        }

        qjs.function("__hostTimerCancel") { args ->
            val id = args.getOrNull(0)?.toString().orEmpty()
            timers.remove(id)?.complete(Unit)
            null
        }

        qjs.asyncFunction("__hostDelay") { args ->
            val id = args.getOrNull(0)?.toString().orEmpty()
            val ms = (args.getOrNull(1) as? Number)?.toLong()?.coerceIn(0L, 20_000L) ?: 0L
            val gate = CompletableDeferred<Unit>()
            timers[id] = gate
            try {
                withTimeout(ms.coerceAtLeast(1L)) {
                    gate.await()
                }
            } catch (_: Throwable) {
                // Delay elapsed normally
            } finally {
                timers.remove(id)
            }
            null
        }

        qjs.asyncFunction("__hostFetch") { args ->
            val reqJson = JSONObject(args.getOrNull(0)?.toString() ?: "{}")
            executeHostFetch(reqJson, originUrl)
        }

        qjs.evaluate<Any?>(HOST_BOOTSTRAP_JS)
    }

    private suspend fun loadModuleIntoGlobal(qjs: QuickJs, rawScript: String) {
        val transformed = rewriteEsModuleSyntax(rawScript.removePrefix("\uFEFF"))
        val wrapper = """
            (async function() {
                const module = { exports: {} };
                const exports = module.exports;
                globalThis.module = module;
                globalThis.exports = exports;
                let __iifeResult = undefined;
                __iifeResult = await (async function() {
            $transformed
                })();
                const candidate =
                    (module.exports && Object.keys(module.exports).length > 0 ? (module.exports.default || module.exports) : null) ||
                    (__iifeResult && typeof __iifeResult === 'object' ? (__iifeResult.default || __iifeResult) : null) ||
                    globalThis.__exportedDefault ||
                    (Object.keys(globalThis.__namedExports || {}).length > 0 ? globalThis.__namedExports : null) ||
                    globalThis.plugin ||
                    globalThis.provider ||
                    globalThis.addon;
                if (!candidate || typeof candidate !== 'object') {
                    throw new Error('JS module did not export a provider object');
                }
                globalThis.__pixelProvider = candidate;
                return true;
            })()
        """.trimIndent()
        qjs.evaluate<Any?>(wrapper)
    }

    private fun executeHostFetch(reqJson: JSONObject, originUrl: String): String {
        val rawUrl = reqJson.optString("url").trim()
        val resolvedUrl = resolveAgainstOrigin(rawUrl, originUrl)
            ?: throw IllegalArgumentException("Invalid fetch URL: $rawUrl")

        val method = reqJson.optString("method", "GET").uppercase(Locale.ROOT)
        val headersObj = reqJson.optJSONObject("headers")
        val bodyStr = if (reqJson.has("body") && !reqJson.isNull("body")) reqJson.optString("body") else null
        val bodyBase64 = if (reqJson.has("bodyBase64") && !reqJson.isNull("bodyBase64")) reqJson.optString("bodyBase64") else null

        val reqBuilder = Request.Builder().url(resolvedUrl)
        var hasUa = false
        var contentType: String? = null
        if (headersObj != null) {
            val keys = headersObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = headersObj.optString(k)
                if (k.equals("user-agent", ignoreCase = true)) hasUa = true
                if (k.equals("content-type", ignoreCase = true)) contentType = v
                reqBuilder.header(k, v)
            }
        }
        if (!hasUa) {
            reqBuilder.header("User-Agent", "PixelMusic/2.0 (QuickJS; SpineHost)")
        }

        val requestBody = when {
            bodyBase64 != null -> Base64.getDecoder().decode(bodyBase64).toRequestBody(contentType?.toMediaTypeOrNull())
            bodyStr != null -> bodyStr.toRequestBody(contentType?.toMediaTypeOrNull())
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull())
            else -> null
        }
        reqBuilder.method(method, requestBody)

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val respHeaders = JSONObject()
            for (name in resp.headers.names()) {
                respHeaders.put(name.lowercase(Locale.ROOT), resp.headers.values(name).joinToString(", "))
            }
            return JSONObject().apply {
                put("status", resp.code)
                put("statusText", resp.message)
                put("ok", resp.isSuccessful)
                put("url", resp.request.url.toString())
                put("headers", respHeaders)
                put("bodyBase64", Base64.getEncoder().encodeToString(bytes))
            }.toString()
        }
    }

    private fun buildContextJson(
        addon: InstalledAddon,
        isMeteredNetwork: Boolean,
        nextTrackIds: List<String>,
        candidate: AddonTrackCandidate? = null,
    ): JSONObject {
        val resolved = addon.resolvedSettings(isMeteredNetwork)
        val settingsObj = JSONObject()
        for ((k, v) in resolved) {
            val typedVal: Any = when (v.lowercase(Locale.ROOT)) {
                "true" -> true
                "false" -> false
                else -> v.toLongOrNull() ?: v.toDoubleOrNull() ?: v
            }
            settingsObj.put(k, JSONObject().apply {
                put("value", typedVal)
            })
        }
        return JSONObject().apply {
            put("settings", settingsObj)
            put("values", JSONObject().apply { resolved.forEach { (k, v) -> put(k, v) } })
            put("network", if (isMeteredNetwork) "cellular" else "wifi")
            if (nextTrackIds.isNotEmpty()) {
                put("nextTrackId", nextTrackIds.first())
                put("nextTrackIds", JSONArray(nextTrackIds))
            }
            if (candidate != null) {
                put("track", JSONObject().apply {
                    put("id", candidate.id)
                    put("title", candidate.title)
                    put("artists", JSONArray(candidate.artists))
                    candidate.album?.let { put("album", it) }
                    candidate.durationMs?.let { put("durationMs", it) }
                    candidate.isrc?.let { put("isrc", it) }
                })
            }
        }
    }

    private fun resolveQualityArg(
        addon: InstalledAddon,
        isMeteredNetwork: Boolean,
        preferLossless: Boolean,
    ): String {
        val resolved = addon.resolvedSettings(isMeteredNetwork)
        val qualitySpec = addon.qualitySettingSpec()
        if (qualitySpec != null) {
            val configured = resolved[qualitySpec.key]
            if (!configured.isNullOrBlank()) return configured
        }
        return when {
            !preferLossless -> "HIGH"
            isMeteredNetwork -> "LOSSLESS"
            else -> "HI_RES_LOSSLESS"
        }
    }

    private fun parseJsStreamResponse(
        addon: InstalledAddon,
        candidate: AddonTrackCandidate,
        rawResult: String,
    ): AddonResolvedStream? {
        val trimmed = rawResult.trim()
        val obj = when {
            trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            trimmed.startsWith("\"") -> {
                val urlStr = runCatching { JSONArray("[$trimmed]").optString(0) }.getOrNull().orEmpty()
                JSONObject().put("url", urlStr)
            }
            trimmed.startsWith("http") -> JSONObject().put("url", trimmed)
            else -> return null
        }

        // Merge nested `track` metadata (8SPINE + ArchiveTune shape) onto a normalized stream JSONObject
        val trackObj = obj.optJSONObject("track")
        val normalized = JSONObject(obj.toString())
        if (normalized.optString("url").isBlank() && normalized.optString("streamUrl").isNotBlank()) {
            normalized.put("url", normalized.optString("streamUrl"))
        }
        if (normalized.optString("url").isBlank() && normalized.optString("downloadUrl").isNotBlank()) {
            normalized.put("url", normalized.optString("downloadUrl"))
        }
        if (trackObj != null) {
            for (field in listOf("codec", "codecs", "format", "audioQuality", "quality", "sampleRate", "bitDepth", "bitrate", "isHiRes", "isAtmos", "audioModes", "mimeType")) {
                if (!normalized.has(field) && trackObj.has(field) && !trackObj.isNull(field)) {
                    normalized.put(field, trackObj.opt(field))
                }
            }
        }
        return HttpAddonClient(httpClient).parseStreamObject(addon, candidate, normalized)
    }

    private fun parseInspectedMetadata(meta: JSONObject, originUrl: String): InspectedModule {
        val name = meta.optString("displayName")
            .ifBlank { meta.optString("name") }
            .ifBlank { originUrl.substringAfterLast('/').substringBefore('?').removeSuffix(".js") }
            .ifBlank { "JS Module" }
        val id = InstalledAddon.sanitizeId(meta.optString("id").ifBlank { name })
        val labels = (InstalledAddon.jsonStringList(meta.optJSONArray("labels")) +
            InstalledAddon.jsonStringList(meta.optJSONArray("tags"))).distinct()
        val settingsSchema = AddonSettingSpec.parseList(meta.opt("settings"))
        val rawIcon = meta.optString("icon").ifBlank { meta.optString("logo") }
        val resolvedIcon = if (rawIcon.isNotBlank() && !rawIcon.endsWith(".png") && rawIcon.startsWith("http")) {
            rawIcon
        } else if (rawIcon.isNotBlank() && originUrl.isNotBlank() && !rawIcon.equals("icon.png", true)) {
            resolveAgainstOrigin(rawIcon, originUrl).orEmpty()
        } else {
            ""
        }

        return InspectedModule(
            id = id,
            name = name,
            version = meta.optString("version", "1.0.0").ifBlank { "1.0.0" },
            description = meta.optString("description"),
            author = meta.optString("author"),
            homepage = meta.optString("homepage").ifBlank { meta.optString("repository") },
            iconUrl = resolvedIcon,
            iconBase64 = meta.optString("iconBase64"),
            labels = labels,
            noStreamCache = meta.optBoolean("noStreamCache", false),
            allowDownloads = meta.optBoolean("allowDownloads", true),
            settingsSchema = settingsSchema,
            capabilities = InstalledAddon.jsonStringList(meta.optJSONArray("capabilities")),
        )
    }

    private fun readModuleStorage(addonId: String): JSONObject {
        return runCatching {
            val dir = File(storageDirProvider(), "js_storage").apply { mkdirs() }
            val f = File(dir, "${InstalledAddon.sanitizeId(addonId)}.json")
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        }.getOrDefault(JSONObject())
    }

    private fun writeModuleStorage(addonId: String, obj: JSONObject) {
        runCatching {
            val dir = File(storageDirProvider(), "js_storage").apply { mkdirs() }
            val f = File(dir, "${InstalledAddon.sanitizeId(addonId)}.json")
            f.writeText(obj.toString())
        }
    }

    companion object {
        private const val TAG = "QuickJsAddonRuntime"

        /**
         * Rewrites top-level ES module `export` declarations into CommonJS/global assignments so
         * scripts work identically whether authored as CommonJS (`module.exports = ...`), ES modules
         * (`export default { ... }` / `export async function searchTracks`), or IIFEs (`8spine.js`).
         */
        internal fun rewriteEsModuleSyntax(script: String): String {
            var out = script
            out = out.replace(Regex("""(?m)^\s*export\s+default\s+"""), "globalThis.__exportedDefault = ")
            out = out.replace(
                Regex("""(?m)^\s*export\s+async\s+function\s+([A-Za-z0-9_$]+)\s*\("""),
                "globalThis.__namedExports = globalThis.__namedExports || {}; globalThis.__namedExports.$1 = async function $1(",
            )
            out = out.replace(
                Regex("""(?m)^\s*export\s+function\s+([A-Za-z0-9_$]+)\s*\("""),
                "globalThis.__namedExports = globalThis.__namedExports || {}; globalThis.__namedExports.$1 = function $1(",
            )
            out = out.replace(
                Regex("""(?m)^\s*export\s+(?:const|let|var)\s+([A-Za-z0-9_$]+)\s*="""),
                "globalThis.__namedExports = globalThis.__namedExports || {}; const $1 = globalThis.__namedExports.$1 =",
            )
            return out
        }

        internal fun resolveAgainstOrigin(rawUrl: String, originUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                return trimmed
            }
            if (originUrl.isNotBlank()) {
                val base = originUrl.toHttpUrlOrNull()
                if (base != null) {
                    return base.resolve(trimmed)?.toString()
                }
            }
            return null
        }

        internal fun resolveUrlJson(value: String, base: String?): String {
            val resolvedStr = if (!base.isNullOrBlank()) {
                base.toHttpUrlOrNull()?.resolve(value)?.toString()
                    ?: runCatching { URI(base).resolve(value).toString() }.getOrNull()
                    ?: value
            } else {
                value
            }
            val uri = URI(resolvedStr)
            val scheme = (uri.scheme ?: "https").lowercase(Locale.ROOT)
            val host = uri.host ?: ""
            val port = uri.port
            val hostWithPort = if (port > 0 && !((scheme == "http" && port == 80) || (scheme == "https" && port == 443))) {
                "$host:$port"
            } else {
                host
            }
            val origin = "$scheme://$hostWithPort"
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            val search = uri.rawQuery?.let { "?$it" } ?: ""
            val hash = uri.rawFragment?.let { "#$it" } ?: ""
            return JSONObject().apply {
                put("origin", origin)
                put("protocol", "$scheme:")
                put("hostname", host)
                put("host", hostWithPort)
                put("pathname", path)
                put("search", search)
                put("hash", hash)
            }.toString()
        }

        private val INSPECT_MODULE_JS = """
            (function() {
                const p = globalThis.__pixelProvider || {};
                const fnNames = ['searchTracks', 'getTrackStreamUrl', 'getTrackDownloadUrl', 'getAlbum', 'getArtist', 'getPlaylist']
                    .filter(k => typeof p[k] === 'function');
                return JSON.stringify({
                    id: p.id || '',
                    name: p.name || '',
                    displayName: p.displayName || '',
                    version: p.version || '1.0.0',
                    description: p.description || '',
                    author: p.author || '',
                    homepage: p.homepage || p.repository || '',
                    icon: p.icon || p.logo || '',
                    iconBase64: p.iconBase64 || '',
                    labels: Array.isArray(p.labels) ? p.labels : [],
                    tags: Array.isArray(p.tags) ? p.tags : [],
                    noStreamCache: Boolean(p.noStreamCache),
                    allowDownloads: p.allowDownloads === undefined ? true : Boolean(p.allowDownloads),
                    settings: p.settings || {},
                    capabilities: fnNames
                });
            })()
        """.trimIndent()

        private val HOST_BOOTSTRAP_JS = """
            globalThis.__namedExports = {};
            globalThis.console = {
                log: (...a) => __hostLog('d', ...a),
                info: (...a) => __hostLog('i', ...a),
                debug: (...a) => __hostLog('d', ...a),
                warn: (...a) => __hostLog('w', ...a),
                error: (...a) => __hostLog('e', ...a),
            };
            globalThis.localStorage = {
                getItem: (k) => __hostStorageGet(String(k)),
                setItem: (k, v) => __hostStorageSet(String(k), String(v)),
                removeItem: (k) => __hostStorageSet(String(k), null),
            };
            globalThis.atob = function(value) {
                const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
                const text = String(value).replace(/[\s=]+/g, '').replace(/-/g, '+').replace(/_/g, '/');
                let bits = 0, count = 0, out = '';
                for (let i = 0; i < text.length; i++) {
                    const v = alphabet.indexOf(text.charAt(i));
                    if (v < 0) continue;
                    bits = (bits << 6) | v;
                    count += 6;
                    if (count >= 8) {
                        count -= 8;
                        out += String.fromCharCode((bits >>> count) & 0xff);
                    }
                }
                return out;
            };
            globalThis.btoa = function(value) {
                const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
                const str = String(value);
                let out = '', bits = 0, count = 0;
                for (let i = 0; i < str.length; i++) {
                    bits = (bits << 8) | (str.charCodeAt(i) & 0xff);
                    count += 8;
                    while (count >= 6) {
                        count -= 6;
                        out += alphabet[(bits >>> count) & 63];
                    }
                }
                if (count > 0) out += alphabet[(bits << (6 - count)) & 63];
                while (out.length % 4 !== 0) out += '=';
                return out;
            };
            globalThis.TextEncoder = class TextEncoder {
                encode(str = '') {
                    const utf8 = unescape(encodeURIComponent(String(str)));
                    const arr = new Uint8Array(utf8.length);
                    for (let i = 0; i < utf8.length; i++) arr[i] = utf8.charCodeAt(i);
                    return arr;
                }
            };
            globalThis.TextDecoder = class TextDecoder {
                decode(input = new Uint8Array()) {
                    const bytes = input instanceof Uint8Array ? input : new Uint8Array(input.buffer || input);
                    let bin = '';
                    for (let i = 0; i < bytes.length; i++) {
                        bin += '%' + bytes[i].toString(16).padStart(2, '0');
                    }
                    try { return decodeURIComponent(bin); } catch (_) {
                        let raw = '';
                        for (let i = 0; i < bytes.length; i++) raw += String.fromCharCode(bytes[i]);
                        return raw;
                    }
                }
            };
            globalThis.Headers = class Headers {
                constructor(init = {}) {
                    this._map = Object.create(null);
                    if (init instanceof Headers) init = init._map;
                    if (Array.isArray(init)) init.forEach(([k, v]) => this.set(k, v));
                    else if (init && typeof init === 'object') Object.keys(init).forEach(k => this.set(k, init[k]));
                }
                set(k, v) { this._map[String(k).toLowerCase()] = String(v); }
                append(k, v) { const lk = String(k).toLowerCase(); this._map[lk] = this._map[lk] ? this._map[lk] + ', ' + v : String(v); }
                get(k) { return this._map[String(k).toLowerCase()] ?? null; }
                has(k) { return Object.prototype.hasOwnProperty.call(this._map, String(k).toLowerCase()); }
                delete(k) { delete this._map[String(k).toLowerCase()]; }
                forEach(fn) { Object.keys(this._map).forEach(k => fn(this._map[k], k, this)); }
                toObject() { return Object.assign({}, this._map); }
            };
            globalThis.URLSearchParams = class URLSearchParams {
                constructor(init = '') {
                    this._pairs = [];
                    if (typeof init === 'string') {
                        init.replace(/^\?/, '').split('&').filter(Boolean).forEach(part => {
                            const idx = part.indexOf('=');
                            const k = idx < 0 ? part : part.slice(0, idx);
                            const v = idx < 0 ? '' : part.slice(idx + 1);
                            this.append(decodeURIComponent(k.replace(/\+/g, ' ')), decodeURIComponent(v.replace(/\+/g, ' ')));
                        });
                    } else if (Array.isArray(init)) {
                        init.forEach(([k, v]) => this.append(k, v));
                    } else if (init && typeof init === 'object') {
                        Object.keys(init).forEach(k => this.append(k, init[k]));
                    }
                }
                append(k, v) { this._pairs.push([String(k), String(v)]); }
                set(k, v) { this.delete(k); this.append(k, v); }
                get(k) { const f = this._pairs.find(p => p[0] === String(k)); return f ? f[1] : null; }
                getAll(k) { return this._pairs.filter(p => p[0] === String(k)).map(p => p[1]); }
                has(k) { return this._pairs.some(p => p[0] === String(k)); }
                delete(k) { this._pairs = this._pairs.filter(p => p[0] !== String(k)); }
                toString() { return this._pairs.map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&'); }
            };
            globalThis.URL = class URL {
                constructor(url, base) {
                    const p = JSON.parse(__hostResolveUrl(String(url), base === undefined ? '' : String(base)));
                    this.origin = p.origin;
                    this.protocol = p.protocol;
                    this.hostname = p.hostname;
                    this.host = p.host;
                    this.pathname = p.pathname;
                    this.hash = p.hash;
                    this.searchParams = new URLSearchParams(p.search);
                }
                get search() { const q = this.searchParams.toString(); return q ? '?' + q : ''; }
                get href() { return this.toString(); }
                toString() { return this.origin + this.pathname + this.search + (this.hash || ''); }
            };
            globalThis.AbortSignal = class AbortSignal {
                constructor() { this.aborted = false; this._listeners = []; }
                addEventListener(type, fn) { if (type === 'abort') this._listeners.push(fn); }
                removeEventListener(type, fn) { this._listeners = this._listeners.filter(f => f !== fn); }
                static timeout(ms) {
                    const sig = new AbortSignal();
                    setTimeout(() => { sig.aborted = true; sig._listeners.forEach(fn => fn()); }, ms);
                    return sig;
                }
            };
            globalThis.AbortController = class AbortController {
                constructor() { this.signal = new AbortSignal(); }
                abort() {
                    if (!this.signal.aborted) {
                        this.signal.aborted = true;
                        this.signal._listeners.forEach(fn => fn());
                    }
                }
            };
            let __timerSeq = 0;
            const __activeTimers = new Set();
            globalThis.setTimeout = function(fn, ms = 0, ...args) {
                const id = String(++__timerSeq);
                __activeTimers.add(id);
                __hostDelay(id, Number(ms) || 0).then(() => {
                    if (__activeTimers.delete(id) && typeof fn === 'function') fn(...args);
                });
                return id;
            };
            globalThis.clearTimeout = function(id) {
                const key = String(id);
                __activeTimers.delete(key);
                __hostTimerCancel(key);
            };
            globalThis.fetch = async function(input, options = {}) {
                const url = input instanceof URL ? input.toString() : String(input);
                const hdrs = new Headers(options.headers || {});
                let bodyStr = null;
                if (options.body !== undefined && options.body !== null) {
                    if (options.body instanceof URLSearchParams) {
                        bodyStr = options.body.toString();
                        if (!hdrs.has('content-type')) hdrs.set('content-type', 'application/x-www-form-urlencoded;charset=UTF-8');
                    } else if (typeof options.body === 'object' && !(options.body instanceof Uint8Array)) {
                        bodyStr = JSON.stringify(options.body);
                        if (!hdrs.has('content-type')) hdrs.set('content-type', 'application/json');
                    } else {
                        bodyStr = String(options.body);
                    }
                }
                const rawResp = await __hostFetch(JSON.stringify({
                    url: url,
                    method: options.method || 'GET',
                    headers: hdrs.toObject(),
                    body: bodyStr
                }));
                const parsed = JSON.parse(rawResp);
                const binStr = globalThis.atob(parsed.bodyBase64 || '');
                const bytes = new Uint8Array(binStr.length);
                for (let i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i);
                const responseHeaders = new Headers(parsed.headers || {});
                return {
                    status: parsed.status,
                    statusText: parsed.statusText || '',
                    ok: Boolean(parsed.ok),
                    url: parsed.url || url,
                    headers: responseHeaders,
                    async arrayBuffer() { return bytes.buffer; },
                    async text() { return new TextDecoder().decode(bytes); },
                    async json() { return JSON.parse(new TextDecoder().decode(bytes)); }
                };
            };
        """.trimIndent()
    }
}
