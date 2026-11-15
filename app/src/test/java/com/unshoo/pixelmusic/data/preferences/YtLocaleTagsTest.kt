package com.unshoo.pixelmusic.data.preferences

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YtLocaleTagsTest {
    @Test
    fun `hl is the base language, chinese keeps its script`() {
        assertEquals("en", YtLocaleTags.hl("en-IN"))
        assertEquals("pt", YtLocaleTags.hl("pt_BR"))
        assertEquals("he", YtLocaleTags.hl("iw"))
        assertEquals("zh-TW", YtLocaleTags.hl("zh-Hant-HK"))
        assertEquals("zh-CN", YtLocaleTags.hl("zh"))
        assertEquals("en", YtLocaleTags.hl(""))
    }

    @Test
    fun `default region is US except chinese`() {
        assertEquals("US", YtLocaleTags.defaultGl("hi-IN"))
        assertEquals("TW", YtLocaleTags.defaultGl("zh-TW"))
        assertEquals("SG", YtLocaleTags.defaultGl("zh-CN"))
    }

    @Test
    fun `search omits gl except for chinese`() {
        assertTrue(YtLocaleTags.searchOmitsGl("es-MX"))
        assertFalse(YtLocaleTags.searchOmitsGl("zh-Hant"))
    }
}
