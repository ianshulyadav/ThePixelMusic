package com.unshoo.pixelmusic.data.addons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Universal installer and format identifier for all supported addon & module ecosystems:
 *  1. **HTTP Addon Manifest URLs** (`.../manifest.json` or bare addon root URL, preserving token
 *     path prefixes such as `/7d8f5e42bea241d7b68ed8e0716fc29c`).
 *  2. **Token-Gated Root Endpoints** (e.g. `tidomeowspine` root URLs exposing `GET /generate` ->
 *     `{ "token": "...", "manifestUrl": "/{token}/manifest.json" }`).
 *  3. **Manifestless HTTP Addons** (root endpoints answering `/search?q=...` with `{ "tracks": [...] }`).
 *  4. **8SPINE Source Index & Mochy Catalog JSONs** (`8spine-source.json` with `category:music` /
 *     `category:*` excluding `category:artworks` & `category:testing`, or `{ "modules": [...] }`).
 *  5. **Remote / Local 8SPINE `.js` Scripts** (`8spine.js`, ES modules, CommonJS, IIFE).
 *  6. **ArchiveTune `.arch` & Mochy `.zip` Packages** (with SHA-256 verification and embedded `icon.png`).
 */
class AddonInstaller(
    private val httpClient: OkHttpClient,
    private val jsRuntime: QuickJsAddonRuntime,
    private val scriptsDirProvider: () -> File,
) {
    companion object {
        private const val MAX_PACKAGE_BYTES = 5 * 1024 * 1024
        private const val MAX_SCRIPT_BYTES = 3 * 1024 * 1024
        private const val USER_AGENT = "PixelMusic/2.0 (Android; AddonInstaller)"

        fun normalizeInputUrl(rawInput: String): String {
            var u = rawInput.trim()
            if (u.startsWith("stremio://", ignoreCase = true)) {
                u = "https://" + u.substring("stremio://".length)
            } else if (u.startsWith("addon://", ignoreCase = true)) {
                u = "https://" + u.substring("addon://".length)
            } else if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
                u = "https://$u"
            }
            val hashIdx = u.indexOf('#')
            if (hashIdx >= 0) u = u.substring(0, hashIdx)
            return u.trim()
        }

        fun stripManifestSuffix(manifestUrl: String): String {
            val clean = manifestUrl.trim()
            val qIdx = clean.indexOf('?')
            val pathPart = if (qIdx >= 0) clean.substring(0, qIdx) else clean
            return if (pathPart.endsWith("/manifest.json", ignoreCase = true)) {
                pathPart.substring(0, pathPart.length - "/manifest.json".length)
            } else {
                pathPart.trimEnd('/')
            }
        }
    }

    suspend fun discoverOrInstallFromUrl(
        rawUrl: String,
        existingById: Map<String, InstalledAddon> = emptyMap(),
    ): AddonDiscoverResult = withContext(Dispatchers.IO) {
        val url = normalizeInputUrl(rawUrl)
        val parsedUrl = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid addon URL: $rawUrl")

        val pathLower = parsedUrl.encodedPath.lowercase(Locale.ROOT)

        // 1. Explicit `.js` script URL -> inspect as 8SPINE / JS module
        if (pathLower.endsWith(".js")) {
            val script = fetchTextStrict(url)
            val addon = buildJsAddon(
                scriptSource = script,
                sourceUrl = url,
                indexUrl = "",
                protocol = AddonProtocol.SPINE_JS,
                existing = existingById,
            )
            return@withContext AddonDiscoverResult.Installed(addon, updatedExisting = addon.id in existingById)
        }

        // 2. Explicit `.arch` or `.zip` package URL
        if (pathLower.endsWith(".arch") || pathLower.endsWith(".zip")) {
            val bytes = fetchBytesStrict(url)
            return@withContext importFromBytes(
                bytes = bytes,
                fileName = pathLower.substringAfterLast('/'),
                originUrl = url,
                existingById = existingById,
            )
        }

        // 3. Probe the given URL directly (if `.json` or root) and `{base}/manifest.json`
        val directProbe = fetchTextOptional(url)
        if (directProbe != null) {
            classifyDocument(
                body = directProbe.body,
                fetchedUrl = directProbe.finalUrl,
                existingById = existingById,
            )?.let { return@withContext it }
        }

        if (!pathLower.endsWith(".json")) {
            val manifestProbeUrl = "${url.trimEnd('/')}/manifest.json"
            val manifestProbe = fetchTextOptional(manifestProbeUrl)
            if (manifestProbe != null) {
                classifyDocument(
                    body = manifestProbe.body,
                    fetchedUrl = manifestProbe.finalUrl,
                    existingById = existingById,
                )?.let { return@withContext it }
            }

            // 4. Token-minting root endpoint (e.g. Tidomeow `/generate` -> `manifestUrl`)
            val generateUrl = "${url.trimEnd('/')}/generate"
            val generated = fetchTextOptional(generateUrl)
            if (generated != null) {
                val genObj = runCatching { JSONObject(generated.body) }.getOrNull()
                if (genObj != null) {
                    val relManifest = genObj.optString("manifestUrl").trim()
                    val token = genObj.optString("token").trim()
                    val nextManifestUrl = when {
                        relManifest.startsWith("http", ignoreCase = true) -> relManifest
                        relManifest.isNotEmpty() -> parsedUrl.resolve(relManifest)?.toString()
                        token.isNotEmpty() -> "${url.trimEnd('/')}/$token/manifest.json"
                        else -> null
                    }
                    if (!nextManifestUrl.isNullOrBlank()) {
                        val mintedProbe = fetchTextOptional(nextManifestUrl)
                        if (mintedProbe != null) {
                            classifyDocument(
                                body = mintedProbe.body,
                                fetchedUrl = mintedProbe.finalUrl,
                                existingById = existingById,
                            )?.let { return@withContext it }
                        }
                    }
                }
            }

            // 5. Fallback probe for manifestless HTTP Addon (`/search?q=daft+punk`)
            val searchProbeUrl = "${url.trimEnd('/')}/search?q=daft%20punk"
            val searchProbe = fetchTextOptional(searchProbeUrl)
            if (searchProbe != null) {
                val searchJson = runCatching { JSONObject(searchProbe.body) }.getOrNull()
                if (searchJson != null && (searchJson.has("tracks") || searchJson.has("results"))) {
                    val hostLabel = parsedUrl.host.substringBefore('.').replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                    }
                    val id = InstalledAddon.sanitizeId("http-${parsedUrl.host}")
                    val prior = existingById[id]
                    val addon = InstalledAddon(
                        id = id,
                        protocol = AddonProtocol.HTTP_ADDON,
                        name = prior?.name ?: "$hostLabel Addon",
                        version = "1.0.0",
                        description = "Manifestless HTTP audio source (${parsedUrl.host})",
                        sourceUrl = url.trimEnd('/'),
                        manifestUrl = "",
                        enabled = prior?.enabled ?: true,
                        settingsValues = prior?.settingsValues.orEmpty(),
                        mobileSettingsValues = prior?.mobileSettingsValues.orEmpty(),
                        installedAtMs = prior?.installedAtMs ?: System.currentTimeMillis(),
                    )
                    return@withContext AddonDiscoverResult.Installed(addon, updatedExisting = prior != null)
                }
            }
        }

        if (directProbe?.body?.contains("DEPLOYMENT_DISABLED", ignoreCase = true) == true) {
            throw IOException("Remote addon deployment is currently disabled on its host (DEPLOYMENT_DISABLED).")
        }

        throw IOException("Could not find a valid HTTP Addon manifest, 8SPINE index, or JS module at $url")
    }

    suspend fun installCatalogEntry(
        entry: CatalogAddonEntry,
        indexUrl: String = "",
        existingById: Map<String, InstalledAddon> = emptyMap(),
    ): InstalledAddon = withContext(Dispatchers.IO) {
        val targetUrl = entry.downloadUrl.trim()
        val pathLower = targetUrl.substringBefore('?').lowercase(Locale.ROOT)
        if (pathLower.endsWith(".js") || entry.protocolHint == AddonProtocol.SPINE_JS) {
            val body = fetchTextStrict(targetUrl)
            val trimmed = body.trimStart()
            // Some catalog entries (like Mochy catalog) point to HTTP addon manifest URLs rather than `.js`
            if (trimmed.startsWith("{") && !pathLower.endsWith(".js")) {
                val json = runCatching { JSONObject(trimmed) }.getOrNull()
                if (json != null && isHttpAddonManifest(json)) {
                    return@withContext parseHttpManifestAddon(json, targetUrl, indexUrl, existingById)
                }
            }
            val built = buildJsAddon(
                scriptSource = body,
                sourceUrl = targetUrl,
                indexUrl = indexUrl,
                protocol = AddonProtocol.SPINE_JS,
                existing = existingById,
                fallbackEntry = entry,
            )
            return@withContext built
        }

        val discovered = discoverOrInstallFromUrl(targetUrl, existingById)
        when (discovered) {
            is AddonDiscoverResult.Installed -> discovered.addon.copy(
                indexUrl = indexUrl.ifBlank { discovered.addon.indexUrl },
                iconUrl = discovered.addon.iconUrl.ifBlank { entry.iconUrl },
            )
            is AddonDiscoverResult.Catalog -> {
                val first = discovered.entries.firstOrNull()
                    ?: throw IOException("Catalog at $targetUrl contained no installable modules")
                installCatalogEntry(first, targetUrl, existingById)
            }
        }
    }

    suspend fun importFromBytes(
        bytes: ByteArray,
        fileName: String,
        originUrl: String = "",
        existingById: Map<String, InstalledAddon> = emptyMap(),
    ): AddonDiscoverResult = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) throw IllegalArgumentException("Imported file is empty")
        if (bytes.size > MAX_PACKAGE_BYTES) throw IllegalArgumentException("Addon package exceeds 5 MB limit")

        if (isZipArchive(bytes)) {
            val addon = importZipOrArchPackage(bytes, originUrl, existingById)
            return@withContext AddonDiscoverResult.Installed(addon, updatedExisting = addon.id in existingById)
        }

        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        val lowerName = fileName.lowercase(Locale.ROOT)
        if (lowerName.endsWith(".json") || (text.startsWith("{") && !lowerName.endsWith(".js"))) {
            classifyDocument(text, originUrl, existingById)?.let { return@withContext it }
        }

        val addon = buildJsAddon(
            scriptSource = text,
            sourceUrl = originUrl,
            indexUrl = "",
            protocol = AddonProtocol.SPINE_JS,
            existing = existingById,
        )
        AddonDiscoverResult.Installed(addon, updatedExisting = addon.id in existingById)
    }

    private suspend fun classifyDocument(
        body: String,
        fetchedUrl: String,
        existingById: Map<String, InstalledAddon>,
    ): AddonDiscoverResult? {
        val trimmed = body.removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("DEPLOYMENT_DISABLED", ignoreCase = true)) return null

        if (trimmed.startsWith("{")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull()
            if (json != null) {
                parseCatalogEntries(json, fetchedUrl)?.takeIf { it.isNotEmpty() }?.let { entries ->
                    return AddonDiscoverResult.Catalog(indexUrl = fetchedUrl, entries = entries)
                }
                if (isHttpAddonManifest(json)) {
                    val addon = parseHttpManifestAddon(json, fetchedUrl, "", existingById)
                    return AddonDiscoverResult.Installed(addon, updatedExisting = addon.id in existingById)
                }
            }
        }

        // Check if document is a JS module (e.g. raw script served without `.js` suffix)
        if (trimmed.contains("searchTracks") && trimmed.contains("getTrackStreamUrl")) {
            val addon = runCatching {
                buildJsAddon(
                    scriptSource = trimmed,
                    sourceUrl = fetchedUrl,
                    indexUrl = "",
                    protocol = AddonProtocol.SPINE_JS,
                    existing = existingById,
                )
            }.getOrNull()
            if (addon != null) {
                return AddonDiscoverResult.Installed(addon, updatedExisting = addon.id in existingById)
            }
        }
        return null
    }

    internal fun isHttpAddonManifest(json: JSONObject): Boolean {
        val hasNameOrId = json.optString("name").isNotBlank() || json.optString("id").isNotBlank()
        if (!hasNameOrId) return false
        return json.has("resources") ||
            json.has("types") ||
            json.has("endpoint") ||
            json.has("settings") ||
            json.has("allowDownloads") ||
            json.has("version")
    }

    internal fun parseCatalogEntries(json: JSONObject, indexUrl: String): List<CatalogAddonEntry>? {
        val entries = ArrayList<CatalogAddonEntry>()

        // 1. 8SPINE Index format: `category:music` / `category:*` (excluding artworks & testing)
        val keys = json.keys()
        var sawCategoryKey = false
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.startsWith("category:", ignoreCase = true)) continue
            sawCategoryKey = true
            val category = key.substringAfter(':').lowercase(Locale.ROOT)
            if (category == "artworks" || category == "testing") continue
            val arr = json.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                parseSingleCatalogItem(item, category, indexUrl)?.let(entries::add)
            }
        }
        if (sawCategoryKey) return entries

        // 2. Mochy / Generic `{ "modules": [...] }` catalog format
        val modulesArr = json.optJSONArray("modules")
        if (modulesArr != null) {
            for (i in 0 until modulesArr.length()) {
                val item = modulesArr.optJSONObject(i) ?: continue
                parseSingleCatalogItem(item, "music", indexUrl)?.let(entries::add)
            }
            return entries
        }
        return null
    }

    private fun parseSingleCatalogItem(
        item: JSONObject,
        category: String,
        indexUrl: String,
    ): CatalogAddonEntry? {
        val rawUrl = item.optString("download")
            .ifBlank { item.optString("downloadUrl") }
            .ifBlank { item.optString("scriptUrl") }
            .ifBlank { item.optString("url") }
            .trim()
        if (rawUrl.isEmpty()) return null
        val resolvedDownload = QuickJsAddonRuntime.resolveAgainstOrigin(rawUrl, indexUrl) ?: rawUrl

        val rawIcon = item.optString("icon").ifBlank { item.optString("logo") }.trim()
        val resolvedIcon = if (rawIcon.isNotEmpty()) {
            QuickJsAddonRuntime.resolveAgainstOrigin(rawIcon, indexUrl) ?: rawIcon
        } else {
            ""
        }

        val name = item.optString("name").ifBlank { item.optString("title") }.ifBlank { rawUrl.substringAfterLast('/') }
        val id = InstalledAddon.sanitizeId(item.optString("id").ifBlank { name })
        val labels = (InstalledAddon.jsonStringList(item.optJSONArray("labels")) +
            InstalledAddon.jsonStringList(item.optJSONArray("tags")) +
            InstalledAddon.jsonStringList(item.optJSONArray("adds"))).distinct()

        val hint = if (resolvedDownload.substringBefore('?').endsWith(".js", ignoreCase = true)) {
            AddonProtocol.SPINE_JS
        } else {
            AddonProtocol.HTTP_ADDON
        }

        return CatalogAddonEntry(
            id = id,
            name = name,
            version = item.optString("version", "1.0.0").ifBlank { "1.0.0" },
            description = item.optString("description"),
            author = item.optString("author").ifBlank { item.optString("creator") },
            category = category,
            downloadUrl = resolvedDownload,
            iconUrl = resolvedIcon,
            labels = labels,
            protocolHint = hint,
        )
    }

    internal fun parseHttpManifestAddon(
        json: JSONObject,
        fetchedUrl: String,
        indexUrl: String,
        existingById: Map<String, InstalledAddon>,
    ): InstalledAddon {
        val explicitEndpoint = json.optString("endpoint").trim()
        val baseUrl = when {
            explicitEndpoint.startsWith("http", ignoreCase = true) -> explicitEndpoint.trimEnd('/')
            explicitEndpoint.isNotEmpty() && fetchedUrl.isNotBlank() ->
                (QuickJsAddonRuntime.resolveAgainstOrigin(explicitEndpoint, fetchedUrl) ?: stripManifestSuffix(fetchedUrl)).trimEnd('/')
            fetchedUrl.isNotBlank() -> stripManifestSuffix(fetchedUrl)
            else -> ""
        }
        val manifestUrl = if (fetchedUrl.contains("/manifest.json", ignoreCase = true)) {
            fetchedUrl
        } else if (baseUrl.isNotBlank()) {
            "$baseUrl/manifest.json"
        } else {
            ""
        }

        val name = json.optString("name").trim().ifBlank { "HTTP Addon" }
        val rawId = json.optString("id").trim().ifBlank {
            val host = baseUrl.toHttpUrlOrNull()?.host.orEmpty()
            "http-$host-$name"
        }
        val id = InstalledAddon.sanitizeId(rawId)
        val prior = existingById[id]

        val resources = buildList {
            val resArr = json.optJSONArray("resources")
            if (resArr != null) {
                for (i in 0 until resArr.length()) {
                    when (val r = resArr.opt(i)) {
                        is String -> if (r.isNotBlank()) add(r.trim())
                        is JSONObject -> r.optString("name").trim().takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }
        }.ifEmpty { listOf("search", "stream") }

        val allowDownloads = when (val ad = json.opt("allowDownloads")) {
            is Boolean -> ad
            is Number -> ad.toInt() != 0
            is String -> !ad.equals("false", true) && ad != "0"
            else -> true
        }
        val checkValidLossless = when (val cl = json.opt("checkValidLossless")) {
            is Boolean -> cl
            is Number -> cl.toInt() != 0
            is String -> cl.equals("true", true) || cl == "1"
            else -> false
        }

        val rawLogo = json.optString("logo").ifBlank { json.optString("icon") }.trim()
        val iconUrl = if (rawLogo.isNotEmpty() && fetchedUrl.isNotBlank()) {
            QuickJsAddonRuntime.resolveAgainstOrigin(rawLogo, fetchedUrl) ?: rawLogo
        } else {
            rawLogo
        }

        val settingsSchema = AddonSettingSpec.parseList(json.opt("settings"))
        val labels = (InstalledAddon.jsonStringList(json.optJSONArray("labels")) +
            InstalledAddon.jsonStringList(json.optJSONArray("tags")) +
            InstalledAddon.jsonStringList(json.optJSONArray("types"))).distinct()

        return InstalledAddon(
            id = id,
            protocol = AddonProtocol.HTTP_ADDON,
            name = name,
            version = json.optString("version", "1.0.0").ifBlank { "1.0.0" },
            description = json.optString("description"),
            author = json.optString("author").ifBlank { json.optString("contactEmail") },
            homepage = json.optString("homepage").ifBlank { json.optString("website") },
            sourceUrl = baseUrl,
            manifestUrl = manifestUrl,
            indexUrl = indexUrl.ifBlank { prior?.indexUrl.orEmpty() },
            iconUrl = iconUrl,
            labels = labels,
            resources = resources,
            enabled = prior?.enabled ?: true,
            allowDownloads = allowDownloads,
            checkValidLossless = checkValidLossless,
            noStreamCache = json.optBoolean("noStreamCache", false),
            settingsSchema = settingsSchema,
            settingsValues = prior?.settingsValues.orEmpty(),
            mobileSettingsValues = prior?.mobileSettingsValues.orEmpty(),
            installedAtMs = prior?.installedAtMs ?: System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private suspend fun importZipOrArchPackage(
        bytes: ByteArray,
        originUrl: String,
        existingById: Map<String, InstalledAddon>,
    ): InstalledAddon {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val cleanName = entry.name.replace('\\', '/').trimStart('/')
                if (cleanName.contains("..")) throw SecurityException("Unsafe archive path: $cleanName")
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = zis.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_SCRIPT_BYTES) throw IllegalArgumentException("Archive entry too large: $cleanName")
                    buf.write(chunk, 0, read)
                }
                entries[cleanName.substringAfterLast('/')] = buf.toByteArray()
                zis.closeEntry()
            }
        }

        val manifestBytes = entries["manifest.json"]
        val manifestJson = manifestBytes?.let { JSONObject(it.toString(Charsets.UTF_8).removePrefix("\uFEFF")) }

        // Case A: Mochy WebModule `.zip` containing `manifest.json` with `"url"` and no JS file
        val providerJsBytes = entries["provider.js"] ?: entries["index.js"]
            ?: entries.entries.firstOrNull { it.key.endsWith(".js", ignoreCase = true) }?.value

        if (providerJsBytes == null && manifestJson != null) {
            val webUrl = manifestJson.optString("url").ifBlank { manifestJson.optString("endpoint") }.trim()
            if (webUrl.isNotEmpty()) {
                val res = discoverOrInstallFromUrl(webUrl, existingById)
                if (res is AddonDiscoverResult.Installed) return res.addon
            }
            if (isHttpAddonManifest(manifestJson)) {
                return parseHttpManifestAddon(manifestJson, originUrl, "", existingById)
            }
            throw IllegalArgumentException("Archive contains neither a provider.js script nor an HTTP endpoint URL")
        }

        val scriptBytes = providerJsBytes
            ?: throw IllegalArgumentException("No JavaScript module entry found in archive")

        // Verify SHA-256 checksum if ArchiveTune `.arch` declares one
        val expectedSha = manifestJson?.optString("sha256")?.trim().orEmpty()
        if (expectedSha.length == 64) {
            val actualSha = MessageDigest.getInstance("SHA-256")
                .digest(scriptBytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                throw SecurityException("Package script SHA-256 checksum mismatch")
            }
        }

        val iconBytes = entries["icon.png"] ?: entries["icon.jpg"] ?: entries["icon.jpeg"]
        val iconBase64 = iconBytes?.let { Base64.getEncoder().encodeToString(it) }.orEmpty()

        val rawScript = scriptBytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val metadataObj = manifestJson?.optJSONObject("module") ?: manifestJson

        val wrappedScript = if (metadataObj != null) {
            """
                (function() {
                    const module = { exports: {} };
                    const exports = module.exports;
                    (function() {
                $rawScript
                    })();
                    const p = (module.exports && (module.exports.default || module.exports)) || {};
                    const meta = JSON.parse(${JSONObject.quote(metadataObj.toString())});
                    return Object.assign({}, meta, p, {
                        id: meta.id || p.id || '',
                        name: meta.displayName || meta.name || p.name || '',
                        version: meta.version || p.version || '1.0.0',
                        author: meta.author || p.author || '',
                        description: meta.description || p.description || '',
                        labels: meta.labels || p.labels || [],
                        tags: meta.tags || p.tags || [],
                        settings: p.settings && Object.keys(p.settings).length > 0 ? p.settings : (meta.settings || {}),
                        iconBase64: ${JSONObject.quote(iconBase64)}
                    });
                })()
            """.trimIndent()
        } else {
            rawScript
        }

        val effectiveOrigin = metadataObj?.optString("homepage")
            ?.ifBlank { metadataObj.optString("repository") }
            ?.takeIf { it.startsWith("http") }
            ?: originUrl

        return buildJsAddon(
            scriptSource = wrappedScript,
            sourceUrl = effectiveOrigin,
            indexUrl = "",
            protocol = AddonProtocol.ARCH_PACKAGE,
            existing = existingById,
            overrideIconBase64 = iconBase64,
        )
    }

    private suspend fun buildJsAddon(
        scriptSource: String,
        sourceUrl: String,
        indexUrl: String,
        protocol: AddonProtocol,
        existing: Map<String, InstalledAddon>,
        fallbackEntry: CatalogAddonEntry? = null,
        overrideIconBase64: String = "",
    ): InstalledAddon {
        val inspected = jsRuntime.inspectScript(scriptSource, originUrl = sourceUrl)
        val id = InstalledAddon.sanitizeId(
            inspected.id.ifBlank { fallbackEntry?.id.orEmpty() }.ifBlank { inspected.name }
        )
        val prior = existing[id]
        val scriptFileName = "$id.js"
        val scriptsDir = scriptsDirProvider().apply { mkdirs() }
        File(scriptsDir, scriptFileName).writeText(scriptSource, Charsets.UTF_8)
        jsRuntime.evictAddon(id)

        return InstalledAddon(
            id = id,
            protocol = protocol,
            name = inspected.name.ifBlank { fallbackEntry?.name ?: id },
            version = inspected.version.ifBlank { fallbackEntry?.version ?: "1.0.0" },
            description = inspected.description.ifBlank { fallbackEntry?.description.orEmpty() },
            author = inspected.author.ifBlank { fallbackEntry?.author.orEmpty() },
            homepage = inspected.homepage,
            sourceUrl = sourceUrl,
            manifestUrl = "",
            indexUrl = indexUrl.ifBlank { prior?.indexUrl.orEmpty() },
            iconUrl = inspected.iconUrl.ifBlank { fallbackEntry?.iconUrl.orEmpty() },
            iconBase64 = overrideIconBase64.ifBlank { inspected.iconBase64 },
            labels = (inspected.labels + fallbackEntry?.labels.orEmpty()).distinct(),
            resources = inspected.capabilities.ifEmpty { listOf("searchTracks", "getTrackStreamUrl") },
            enabled = prior?.enabled ?: true,
            allowDownloads = inspected.allowDownloads,
            checkValidLossless = false,
            noStreamCache = inspected.noStreamCache,
            settingsSchema = inspected.settingsSchema,
            settingsValues = prior?.settingsValues.orEmpty(),
            mobileSettingsValues = prior?.mobileSettingsValues.orEmpty(),
            scriptFileName = scriptFileName,
            installedAtMs = prior?.installedAtMs ?: System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private data class FetchedText(val body: String, val finalUrl: String)

    private fun fetchTextOptional(url: String): FetchedText? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, application/javascript, text/javascript, text/plain, */*")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return null
                FetchedText(body = body, finalUrl = resp.request.url.toString())
            }
        }.getOrNull()
    }

    private fun fetchTextStrict(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} fetching $url")
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) throw IOException("Empty response from $url")
            return text
        }
    }

    private fun fetchBytesStrict(url: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} fetching $url")
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) throw IOException("Empty archive response from $url")
            return bytes
        }
    }

    private fun isZipArchive(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4b.toByte() &&
            ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) ||
                (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()))
}
