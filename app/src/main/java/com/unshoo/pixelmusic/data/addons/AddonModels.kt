package com.unshoo.pixelmusic.data.addons

import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class AddonProtocol(val badge: String, val displayLabel: String) {
    HTTP_ADDON("HTTP", "HTTP Addon"),
    SPINE_JS("8SPINE", "8SPINE JS Module"),
    ARCH_PACKAGE("ARCH", "ArchiveTune Package"),
}

enum class AddonSettingType {
    SELECT,
    TOGGLE,
    TEXT,
    NUMBER,
}

data class AddonSettingOption(
    val value: String,
    val label: String,
    val description: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("value", value)
        put("label", label)
        if (description.isNotBlank()) put("description", description)
    }

    companion object {
        fun fromJson(obj: JSONObject): AddonSettingOption {
            val rawVal = obj.optString("value").ifBlank { obj.optString("id") }
            val rawLabel = obj.optString("label").ifBlank { obj.optString("name").ifBlank { rawVal } }
            return AddonSettingOption(
                value = rawVal,
                label = rawLabel,
                description = obj.optString("description").ifBlank { obj.optString("subtitle") },
            )
        }
    }
}

data class AddonSettingSpec(
    val key: String,
    val type: AddonSettingType,
    val label: String,
    val description: String = "",
    val defaultValue: String = "",
    val options: List<AddonSettingOption> = emptyList(),
    val perNetwork: Boolean = false,
    val secret: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
) {
    fun effectiveValue(
        wifiValues: Map<String, String>,
        mobileValues: Map<String, String>,
        isMeteredNetwork: Boolean,
    ): String {
        val raw = if (perNetwork && isMeteredNetwork) {
            mobileValues[key] ?: wifiValues[key] ?: defaultValue
        } else {
            wifiValues[key] ?: defaultValue
        }
        return when (type) {
            AddonSettingType.SELECT -> {
                if (options.isEmpty() || options.any { it.value == raw }) raw
                else options.firstOrNull { it.value == defaultValue }?.value ?: options.first().value
            }
            AddonSettingType.TOGGLE -> {
                when (raw.trim().lowercase(Locale.ROOT)) {
                    "true", "1", "yes", "on" -> "true"
                    "false", "0", "no", "off" -> "false"
                    else -> if (defaultValue.equals("true", true) || defaultValue == "1") "true" else "false"
                }
            }
            else -> raw
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("type", type.name)
        put("label", label)
        if (description.isNotBlank()) put("description", description)
        put("defaultValue", defaultValue)
        put("perNetwork", perNetwork)
        put("secret", secret)
        if (min != null) put("min", min)
        if (max != null) put("max", max)
        if (options.isNotEmpty()) {
            put("options", JSONArray().apply { options.forEach { put(it.toJson()) } })
        }
    }

    companion object {
        fun fromJson(obj: JSONObject, fallbackKey: String = ""): AddonSettingSpec? {
            val key = obj.optString("key").ifBlank { fallbackKey }.trim()
            if (key.isEmpty()) return null
            val rawType = obj.optString("type").trim().lowercase(Locale.ROOT)
            val optionsArr = obj.optJSONArray("options") ?: obj.optJSONArray("choices") ?: obj.optJSONArray("values")
            val parsedOptions = buildList {
                if (optionsArr != null) {
                    for (i in 0 until optionsArr.length()) {
                        when (val entry = optionsArr.opt(i)) {
                            is JSONObject -> {
                                val opt = AddonSettingOption.fromJson(entry)
                                if (opt.value.isNotEmpty()) add(opt)
                            }
                            is String -> if (entry.isNotBlank()) add(AddonSettingOption(entry, entry))
                            is Number, is Boolean -> add(AddonSettingOption(entry.toString(), entry.toString()))
                        }
                    }
                }
            }
            val type = when {
                rawType == "select" || rawType == "selector" || rawType == "dropdown" || rawType == "enum" || rawType == "choice" -> AddonSettingType.SELECT
                rawType == "toggle" || rawType == "boolean" || rawType == "bool" || rawType == "switch" || rawType == "checkbox" -> AddonSettingType.TOGGLE
                rawType == "number" || rawType == "int" || rawType == "integer" || rawType == "float" -> AddonSettingType.NUMBER
                rawType == "AddonSettingType.SELECT".lowercase(Locale.ROOT) -> AddonSettingType.SELECT
                rawType == "AddonSettingType.TOGGLE".lowercase(Locale.ROOT) -> AddonSettingType.TOGGLE
                rawType == "AddonSettingType.NUMBER".lowercase(Locale.ROOT) -> AddonSettingType.NUMBER
                rawType == "AddonSettingType.TEXT".lowercase(Locale.ROOT) -> AddonSettingType.TEXT
                runCatching { AddonSettingType.valueOf(obj.optString("type")) }.getOrNull() != null ->
                    AddonSettingType.valueOf(obj.optString("type"))
                parsedOptions.isNotEmpty() -> AddonSettingType.SELECT
                obj.opt("default") is Boolean || obj.opt("defaultValue") is Boolean -> AddonSettingType.TOGGLE
                else -> AddonSettingType.TEXT
            }
            val defaultObj = when {
                obj.has("defaultValue") && !obj.isNull("defaultValue") -> obj.opt("defaultValue")
                obj.has("default") && !obj.isNull("default") -> obj.opt("default")
                obj.has("value") && !obj.isNull("value") -> obj.opt("value")
                else -> null
            }
            val defaultStr = when (defaultObj) {
                null -> parsedOptions.firstOrNull()?.value.orEmpty()
                is Boolean -> if (defaultObj) "true" else "false"
                else -> defaultObj.toString()
            }
            val label = obj.optString("label")
                .ifBlank { obj.optString("title") }
                .ifBlank { obj.optString("name") }
                .ifBlank { key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }
            val desc = obj.optString("description")
                .ifBlank { obj.optString("help") }
                .ifBlank { obj.optString("subtitle") }
            val secret = obj.optBoolean("secret", false) ||
                rawType == "password" ||
                rawType == "secret" ||
                key.contains("secret", ignoreCase = true) ||
                key.contains("password", ignoreCase = true)
            return AddonSettingSpec(
                key = key,
                type = type,
                label = label,
                description = desc,
                defaultValue = defaultStr,
                options = parsedOptions,
                perNetwork = obj.optBoolean("perNetwork", false),
                secret = secret,
                min = if (obj.has("min") && !obj.isNull("min")) obj.optDouble("min").takeIf { it.isFinite() } else null,
                max = if (obj.has("max") && !obj.isNull("max")) obj.optDouble("max").takeIf { it.isFinite() } else null,
            )
        }

        fun parseList(rawSettings: Any?): List<AddonSettingSpec> {
            return when (rawSettings) {
                is JSONArray -> buildList {
                    for (i in 0 until rawSettings.length()) {
                        val item = rawSettings.optJSONObject(i) ?: continue
                        fromJson(item)?.let(::add)
                    }
                }
                is JSONObject -> buildList {
                    val keys = rawSettings.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val item = rawSettings.optJSONObject(k) ?: continue
                        fromJson(item, fallbackKey = k)?.let(::add)
                    }
                }
                else -> emptyList()
            }
        }
    }
}

data class InstalledAddon(
    val id: String,
    val protocol: AddonProtocol,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    val homepage: String = "",
    val sourceUrl: String = "",
    val manifestUrl: String = "",
    val indexUrl: String = "",
    val iconUrl: String = "",
    val iconBase64: String = "",
    val labels: List<String> = emptyList(),
    val resources: List<String> = listOf("search", "stream"),
    val enabled: Boolean = true,
    val allowDownloads: Boolean = true,
    val checkValidLossless: Boolean = false,
    val noStreamCache: Boolean = false,
    val settingsSchema: List<AddonSettingSpec> = emptyList(),
    val settingsValues: Map<String, String> = emptyMap(),
    val mobileSettingsValues: Map<String, String> = emptyMap(),
    val scriptFileName: String = "",
    val installedAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    val unifiedSourceId: String get() = UnifiedSourceId.forAddon(id)
    val supportsIsrc: Boolean get() = resources.any { it.equals("isrc", ignoreCase = true) }

    fun resolvedSettings(isMeteredNetwork: Boolean): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (spec in settingsSchema) {
            map[spec.key] = spec.effectiveValue(settingsValues, mobileSettingsValues, isMeteredNetwork)
        }
        for ((k, v) in settingsValues) {
            if (k !in map) map[k] = v
        }
        return map
    }

    fun qualitySettingSpec(): AddonSettingSpec? =
        settingsSchema.firstOrNull {
            it.type == AddonSettingType.SELECT &&
                (it.key.equals("quality", ignoreCase = true) ||
                    it.key.equals("audioQuality", ignoreCase = true) ||
                    it.key.contains("quality", ignoreCase = true))
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("protocol", protocol.name)
        put("name", name)
        put("version", version)
        put("description", description)
        put("author", author)
        put("homepage", homepage)
        put("sourceUrl", sourceUrl)
        put("manifestUrl", manifestUrl)
        put("indexUrl", indexUrl)
        put("iconUrl", iconUrl)
        put("iconBase64", iconBase64)
        put("labels", JSONArray(labels))
        put("resources", JSONArray(resources))
        put("enabled", enabled)
        put("allowDownloads", allowDownloads)
        put("checkValidLossless", checkValidLossless)
        put("noStreamCache", noStreamCache)
        put("settingsSchema", JSONArray().apply { settingsSchema.forEach { put(it.toJson()) } })
        put("settingsValues", JSONObject().apply { settingsValues.forEach { (k, v) -> put(k, v) } })
        put("mobileSettingsValues", JSONObject().apply { mobileSettingsValues.forEach { (k, v) -> put(k, v) } })
        put("scriptFileName", scriptFileName)
        put("installedAtMs", installedAtMs)
        put("updatedAtMs", updatedAtMs)
    }

    companion object {
        fun sanitizeId(raw: String): String {
            val cleaned = raw.trim()
                .lowercase(Locale.ROOT)
                .removePrefix("addon:")
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .trim('-', '.', '_')
                .take(80)
            return cleaned.ifBlank { "addon-${System.currentTimeMillis() and 0xFFFFF}" }
        }

        fun fromJson(obj: JSONObject): InstalledAddon? {
            val id = sanitizeId(obj.optString("id"))
            val name = obj.optString("name").trim().ifBlank { return null }
            val protocol = runCatching { AddonProtocol.valueOf(obj.optString("protocol")) }
                .getOrDefault(AddonProtocol.HTTP_ADDON)
            return InstalledAddon(
                id = id,
                protocol = protocol,
                name = name,
                version = obj.optString("version", "1.0.0").ifBlank { "1.0.0" },
                description = obj.optString("description"),
                author = obj.optString("author"),
                homepage = obj.optString("homepage"),
                sourceUrl = obj.optString("sourceUrl"),
                manifestUrl = obj.optString("manifestUrl"),
                indexUrl = obj.optString("indexUrl"),
                iconUrl = obj.optString("iconUrl"),
                iconBase64 = obj.optString("iconBase64"),
                labels = jsonStringList(obj.optJSONArray("labels")),
                resources = jsonStringList(obj.optJSONArray("resources")).ifEmpty { listOf("search", "stream") },
                enabled = obj.optBoolean("enabled", true),
                allowDownloads = obj.optBoolean("allowDownloads", true),
                checkValidLossless = obj.optBoolean("checkValidLossless", false),
                noStreamCache = obj.optBoolean("noStreamCache", false),
                settingsSchema = AddonSettingSpec.parseList(obj.optJSONArray("settingsSchema")),
                settingsValues = jsonStringMap(obj.optJSONObject("settingsValues")),
                mobileSettingsValues = jsonStringMap(obj.optJSONObject("mobileSettingsValues")),
                scriptFileName = obj.optString("scriptFileName"),
                installedAtMs = obj.optLong("installedAtMs", System.currentTimeMillis()),
                updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis()),
            )
        }

        internal fun jsonStringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) add(s)
                }
            }
        }

        internal fun jsonStringMap(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!obj.isNull(k)) out[k] = obj.optString(k)
            }
            return out
        }
    }
}

data class CatalogAddonEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val category: String = "music",
    val downloadUrl: String,
    val iconUrl: String = "",
    val labels: List<String> = emptyList(),
    val protocolHint: AddonProtocol = AddonProtocol.SPINE_JS,
) {
    val url: String get() = downloadUrl
    val protocol: AddonProtocol get() = protocolHint
}

sealed interface AddonDiscoverResult {
    data class Installed(val addon: InstalledAddon, val updatedExisting: Boolean = false) : AddonDiscoverResult
    data class Catalog(val indexUrl: String, val entries: List<CatalogAddonEntry>) : AddonDiscoverResult {
        val sourceUrl: String get() = indexUrl
        val title: String
            get() = indexUrl.substringAfter("://", indexUrl)
                .substringBefore('/')
                .ifBlank { "Addon Catalog" }
    }
}

enum class AddonTransport {
    PROGRESSIVE,
    HLS,
    DASH,
}

data class AddonTrackCandidate(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
    val explicit: Boolean? = null,
    val artworkUrl: String? = null,
    val formatHint: String? = null,
    val qualityLabel: String? = null,
    val isHiRes: Boolean = false,
    val isAtmos: Boolean = false,
    val directStreamUrl: String? = null,
)

data class AddonResolvedStream(
    val addonId: String,
    val addonName: String,
    val url: String,
    val downloadUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val audioMimeType: String = "audio/flac",
    val codecs: String = "flac",
    val transport: AddonTransport = AddonTransport.PROGRESSIVE,
    val inlineManifest: String? = null,
    val bitrate: Int = 0,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val contentLength: Long? = null,
    val qualityLabel: String = "Lossless",
    val isLossless: Boolean = true,
    val isHiRes: Boolean = false,
    val isAtmos: Boolean = false,
    val expiresAtMs: Long = System.currentTimeMillis() + 20 * 60_000L,
    val noStreamCache: Boolean = false,
    val allowDownloads: Boolean = true,
    val matchedTitle: String? = null,
    val matchedArtist: String? = null,
    val matchedAlbum: String? = null,
    val matchedDurationMs: Long? = null,
    val matchedIsrc: String? = null,
) {
    val uri: String get() = url
    val bitrateBps: Int? get() = bitrate.takeIf { it > 0 }
}

data class AddonCheckReport(
    val addonId: String,
    val ok: Boolean,
    val latencyMs: Long,
    val summary: String,
    val details: String = "",
    val checkedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Identifiers in the unified fallback order combining installed Addons and built-in sources
 * (Monochrome, Tidal, Qobuz, Deezer, Apple Music, JioSaavn, YouTube).
 */
object UnifiedSourceId {
    const val ADDON_PREFIX = "addon:"
    const val BUILTIN_PREFIX = "builtin:"

    val MONOCHROME = forBuiltIn(AudioSourceType.MONOCHROME)
    val TIDAL = forBuiltIn(AudioSourceType.TIDAL)
    val QOBUZ = forBuiltIn(AudioSourceType.QOBUZ)
    val DEEZER = forBuiltIn(AudioSourceType.DEEZER)
    val APPLE = forBuiltIn(AudioSourceType.APPLE)
    val JIOSAAVN = forBuiltIn(AudioSourceType.JIOSAAVN)
    val YOUTUBE = forBuiltIn(AudioSourceType.YOUTUBE)

    val DEFAULT_BUILTIN_ORDER: List<String> = listOf(
        MONOCHROME,
        TIDAL,
        QOBUZ,
        DEEZER,
        APPLE,
        JIOSAAVN,
        YOUTUBE,
    )

    fun forAddon(addonId: String): String = "$ADDON_PREFIX${InstalledAddon.sanitizeId(addonId)}"

    fun forBuiltIn(type: AudioSourceType): String = "$BUILTIN_PREFIX${type.name}"

    fun isAddon(sourceId: String): Boolean = sourceId.startsWith(ADDON_PREFIX)

    fun addonIdOrNull(sourceId: String): String? =
        if (sourceId.startsWith(ADDON_PREFIX)) sourceId.removePrefix(ADDON_PREFIX).takeIf { it.isNotBlank() } else null

    fun builtInTypeOrNull(sourceId: String): AudioSourceType? {
        val raw = if (sourceId.startsWith(BUILTIN_PREFIX)) {
            sourceId.removePrefix(BUILTIN_PREFIX)
        } else if (!sourceId.contains(':')) {
            sourceId
        } else {
            return null
        }
        return runCatching { AudioSourceType.valueOf(raw.trim().uppercase(Locale.ROOT)) }.getOrNull()
    }

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(ADDON_PREFIX)) {
            val id = InstalledAddon.sanitizeId(trimmed.removePrefix(ADDON_PREFIX))
            return "$ADDON_PREFIX$id"
        }
        val builtIn = builtInTypeOrNull(trimmed) ?: return null
        if (builtIn == AudioSourceType.AMAZON || builtIn == AudioSourceType.QOBUZ_BACKUP || builtIn == AudioSourceType.ADDON) {
            return null
        }
        return forBuiltIn(builtIn)
    }

    fun builtInTitle(type: AudioSourceType): String = when (type) {
        AudioSourceType.MONOCHROME -> "Monochrome"
        AudioSourceType.TIDAL -> "Tidal"
        AudioSourceType.QOBUZ -> "Qobuz"
        AudioSourceType.QOBUZ_BACKUP -> "Qobuz Backup"
        AudioSourceType.DEEZER -> "Deezer"
        AudioSourceType.APPLE -> "Apple Music"
        AudioSourceType.AMAZON -> "Amazon Music"
        AudioSourceType.JIOSAAVN -> "JioSaavn"
        AudioSourceType.YOUTUBE -> "YouTube"
        AudioSourceType.ADDON -> "Addon"
    }

    fun builtInSubtitle(type: AudioSourceType): String = when (type) {
        AudioSourceType.MONOCHROME -> "Hi-Res FLAC up to 24-bit / 192 kHz (Multi-instance)"
        AudioSourceType.TIDAL -> "Hi-Res FLAC up to 24-bit / 192 kHz"
        AudioSourceType.QOBUZ -> "Studio Masters up to 24-bit / 192 kHz"
        AudioSourceType.QOBUZ_BACKUP -> "Qobuz Backup Instance"
        AudioSourceType.DEEZER -> "CD Lossless 16-bit / 44.1 kHz FLAC"
        AudioSourceType.APPLE -> "ALAC Lossless / AAC"
        AudioSourceType.AMAZON -> "Amazon Ultra HD"
        AudioSourceType.JIOSAAVN -> "AAC 320 kbps"
        AudioSourceType.YOUTUBE -> "Opus / AAC fallback"
        AudioSourceType.ADDON -> "External Addon / Module"
    }

    /**
     * Merges a persisted unified order CSV, optional legacy [AudioSourceOrderKey] CSV,
     * and the currently installed [addons] into a complete, deduplicated unified fallback order.
     *
     * Newly installed addons not yet present in [savedUnifiedCsv] are placed at the top of the
     * chain (Monochrome / ArchiveTune / Mochy-Music module-first default), while preserving any
     * explicit reordering the user already configured.
     */
    fun buildUnifiedOrder(
        savedUnifiedCsv: String?,
        legacyBuiltInCsv: String?,
        addons: List<InstalledAddon>,
    ): List<String> {
        val installedIds = addons.map { it.unifiedSourceId }
        val installedSet = installedIds.toSet()

        val legacyBuiltIns = legacyBuiltInCsv
            ?.split(',')
            ?.mapNotNull(::normalize)
            ?.filter { !isAddon(it) }
            .orEmpty()

        val baseBuiltIns = (legacyBuiltIns + DEFAULT_BUILTIN_ORDER).distinct()

        if (savedUnifiedCsv.isNullOrBlank()) {
            return (installedIds + baseBuiltIns).distinct()
        }

        val parsedSaved = savedUnifiedCsv
            .split(',')
            .mapNotNull(::normalize)
            .filter { !isAddon(it) || it in installedSet }
            .distinct()

        val missingAddons = installedIds.filter { it !in parsedSaved }
        val result = ArrayList<String>(missingAddons.size + parsedSaved.size + DEFAULT_BUILTIN_ORDER.size)
        // Insert newly added addons right at the top so a freshly installed addon takes effect immediately
        result.addAll(missingAddons)
        result.addAll(parsedSaved)
        for (builtIn in baseBuiltIns) {
            if (builtIn !in result) {
                if (builtIn == YOUTUBE) {
                    result.add(YOUTUBE)
                } else {
                    val ytIdx = result.indexOf(YOUTUBE)
                    if (ytIdx >= 0) result.add(ytIdx, builtIn) else result.add(builtIn)
                }
            }
        }
        return result.distinct()
    }
}

data class UnifiedSourceEntry(
    val sourceId: String,
    val title: String,
    val subtitle: String,
    val badge: String = "",
    val enabled: Boolean,
    val isBuiltIn: Boolean = false,
    val addon: InstalledAddon? = null,
    val builtInType: AudioSourceType? = null,
) {
    val isAddon: Boolean get() = !isBuiltIn && addon != null
}

/**
 * Per-song source override codec supporting BOTH installed Addons (`addon:<id>`) and built-in
 * sources (`builtin:TIDAL`, `builtin:JIOSAAVN`, `builtin:YOUTUBE`, etc.) while remaining 100%
 * backward-compatible with legacy `mediaId=TIDAL;mediaId2=YOUTUBE` strings stored in
 * `SongSourceOverrideKey`.
 */
object UnifiedSongSourceOverride {
    private const val MAX_ENTRIES = 500

    fun parse(raw: String?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (raw.isNullOrBlank()) return out
        for (entry in raw.split(';')) {
            val idx = entry.indexOf('=')
            if (idx <= 0 || idx == entry.lastIndex) continue
            val id = entry.substring(0, idx).trim()
            val targetRaw = entry.substring(idx + 1).trim()
            if (id.isEmpty() || targetRaw.isEmpty()) continue
            val normalized = UnifiedSourceId.normalize(targetRaw) ?: continue
            out[cleanMediaId(id)] = normalized
        }
        return out
    }

    fun get(raw: String?, mediaId: String): String? = parse(raw)[cleanMediaId(mediaId)]

    fun encode(overrides: Map<String, String>): String {
        val entries = overrides.entries.toList().takeLast(MAX_ENTRIES)
        return entries.joinToString(";") { (id, sourceId) -> "${cleanMediaId(id)}=$sourceId" }
    }

    fun withOverride(raw: String?, mediaId: String, sourceId: String?): String {
        val clean = cleanMediaId(mediaId)
        val map = parse(raw)
        map.remove(clean)
        if (!sourceId.isNullOrBlank()) {
            UnifiedSourceId.normalize(sourceId)?.let { map[clean] = it }
        }
        return encode(map)
    }

    fun cleanMediaId(mediaId: String): String =
        mediaId.trim()
            .removePrefix("youtube://")
            .removePrefix("youtube_")
            .replace(';', '_')
            .replace('=', '_')
}
