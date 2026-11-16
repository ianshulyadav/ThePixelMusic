package com.unshoo.pixelmusic.data.preferences

import java.util.Locale

/**
 * How the app's language maps onto YouTube Music's `hl` / `gl` / Accept-Language.
 *
 * Same rules BitChord uses (github.com/kushagrasinghx/BitChord, `LocaleTags`):
 *  - `hl` is the base language ("en-IN" -> "en"); Chinese keeps its script as a region tag
 *    (Traditional -> "zh-TW", Simplified -> "zh-CN") because the script picks the catalogue.
 *  - Default `gl` is "US" (Chinese: TW / SG). A device-country guess sent users in countries where
 *    YouTube Music is not offered to an empty catalogue: zero home shelves and zero search results.
 *  - Search sends NO `gl` (except Chinese), so YouTube ranks by the connection's own region, which
 *    is what music.youtube.com itself does, instead of ranking everyone as if in one country.
 * An explicit "Content country" chosen in settings always wins over these defaults.
 */
object YtLocaleTags {
    const val ZH_HANT = "zh-Hant"
    const val ZH_HANS = "zh-Hans"

    /** "zh_TW", "zh-HK", "zh-Hant-TW" -> zh-Hant; "zh", "zh-CN" -> zh-Hans; "en-US" -> "en". */
    fun normalize(raw: String?): String {
        val tag = raw?.trim()?.replace('_', '-')?.takeIf { it.isNotBlank() } ?: return "en"
        val lower = tag.lowercase(Locale.ROOT)
        if (lower.startsWith("zh")) {
            return when {
                "hant" in lower -> ZH_HANT
                "hans" in lower -> ZH_HANS
                "tw" in lower || "hk" in lower || "mo" in lower -> ZH_HANT
                else -> ZH_HANS
            }
        }
        val base = Locale.forLanguageTag(tag).language.lowercase(Locale.ROOT)
            .ifBlank { lower.substringBefore('-') }
        return when (base) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            else -> base.ifBlank { "en" }
        }
    }

    fun hl(appTag: String?): String = when (val n = normalize(appTag)) {
        ZH_HANT -> "zh-TW"
        ZH_HANS -> "zh-CN"
        else -> n
    }

    fun defaultGl(appTag: String?): String = when (normalize(appTag)) {
        ZH_HANT -> "TW"
        ZH_HANS -> "SG"
        else -> "US"
    }

    /** True when search requests should omit `gl` and let YouTube use the connection's region. */
    fun searchOmitsGl(appTag: String?): Boolean = when (normalize(appTag)) {
        ZH_HANT, ZH_HANS -> false
        else -> true
    }

    fun acceptLanguage(appTag: String?): String = when (val n = normalize(appTag)) {
        ZH_HANT -> "zh-TW,zh-Hant;q=0.9,zh;q=0.8,en-US;q=0.7,en;q=0.6"
        ZH_HANS -> "zh-CN,zh-Hans;q=0.9,zh;q=0.8,en-US;q=0.7,en;q=0.6"
        "en" -> "en-US,en;q=0.9"
        else -> "$n,en-US;q=0.8,en;q=0.7"
    }
}
