package com.unshoo.pixelmusic.data.preferences

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale
import unshoo.ianshulyadav.pixelmusic.innertube.models.YouTubeClient
import unshoo.ianshulyadav.pixelmusic.innertube.models.YouTubeLocale

class YtLocaleTagsTest {
    @Test
    fun `hl is the base language, chinese keeps its script`() {
        assertEquals("en", YtLocaleTags.hl("en-IN"))
        assertEquals("pt", YtLocaleTags.hl("pt_BR"))
        assertEquals("he", YtLocaleTags.hl("iw"))
        assertEquals("zh-TW", YtLocaleTags.hl("zh-Hant-HK"))
        assertEquals("zh-CN", YtLocaleTags.hl("zh"))
        assertEquals("en", YtLocaleTags.hl(""))
        assertEquals("en", YtLocaleTags.hl("und"))
    }

    @Test
    fun `unset preferences send the device region in the InnerTube context`() {
        val locale = YtLocaleTags.resolve(null, null, Locale.forLanguageTag("en-IN"))
        val context = YouTubeClient.WEB_REMIX.toContext(locale, null, null)

        assertEquals(YouTubeLocale("IN", "en"), locale)
        assertEquals("IN", context.client.gl)
        assertEquals("en", context.client.hl)
    }

    @Test
    fun `saved content language and country are independent`() {
        val device = Locale.forLanguageTag("hi-IN")
        assertEquals(YouTubeLocale("US", "hi"), YtLocaleTags.resolve(YtLocaleTags.SYSTEM, "US", device))
        assertEquals(YouTubeLocale("IN", "es"), YtLocaleTags.resolve("es-MX", YtLocaleTags.SYSTEM, device))
        assertEquals(YouTubeLocale("HK", "zh-TW"), YtLocaleTags.resolve("zh-Hant-HK", "HK", device))
    }

    @Test
    fun `missing and unsupported device locales use English and US`() {
        assertEquals(YouTubeLocale.DEFAULT, YtLocaleTags.resolve(null, null, Locale.ROOT))
        assertEquals(YouTubeLocale.DEFAULT, YtLocaleTags.resolve(null, null, Locale.forLanguageTag("eo-ZZ")))
    }
}
