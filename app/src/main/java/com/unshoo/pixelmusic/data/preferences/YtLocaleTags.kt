package com.unshoo.pixelmusic.data.preferences

import java.util.Locale
import unshoo.ianshulyadav.pixelmusic.innertube.models.YouTubeLocale

/**
 * How the app's language maps onto YouTube Music's `hl` / `gl` / Accept-Language.
 *
 *  - `hl` is the base language ("en-IN" -> "en"); Chinese keeps its script as a region tag
 *    (Traditional -> "zh-TW", Simplified -> "zh-CN") because the script picks the catalogue.
 *  - Saved content settings override the device language and region independently.
 */
object YtLocaleTags {
    const val SYSTEM = "system"
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

    fun supportedCountry(country: String?): String? =
        country?.trim()?.uppercase(Locale.ROOT)?.takeIf { it in SupportedYouTubeCountryCodes }

    fun resolve(language: String?, country: String?, deviceLocale: Locale): YouTubeLocale = YouTubeLocale(
        gl = supportedCountry(country) ?: supportedCountry(deviceLocale.country) ?: YouTubeLocale.DEFAULT.gl,
        hl = language?.trim()?.takeIf { it.isNotEmpty() && it != SYSTEM }?.let(::hl)
            ?: hl(deviceLocale.toLanguageTag()).takeIf { it == "zh-TW" || it == "zh-CN" || it in LanguageCodeToName }
            ?: YouTubeLocale.DEFAULT.hl,
    )

    fun acceptLanguage(appTag: String?): String = when (val n = normalize(appTag)) {
        ZH_HANT -> "zh-TW,zh-Hant;q=0.9,zh;q=0.8,en-US;q=0.7,en;q=0.6"
        ZH_HANS -> "zh-CN,zh-Hans;q=0.9,zh;q=0.8,en-US;q=0.7,en;q=0.6"
        "en" -> "en-US,en;q=0.9"
        else -> "$n,en-US;q=0.8,en;q=0.7"
    }
}
