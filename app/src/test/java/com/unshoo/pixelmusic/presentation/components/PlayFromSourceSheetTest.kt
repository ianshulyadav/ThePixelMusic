package com.unshoo.pixelmusic.presentation.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayFromSourceSheetTest {

    private val defaultCandidates = listOf(
        "builtin:MONOCHROME" to listOf("Monochrome", "MONOCHROME"),
        "builtin:APPLE" to listOf("Apple Music", "APPLE"),
        "builtin:JIOSAAVN" to listOf("JioSaavn", "JIOSAAVN"),
        "builtin:YOUTUBE" to listOf("YouTube", "YOUTUBE"),
    )

    @Test
    fun `active lossless source wins over stale YouTube sourceName so only one row is PLAYING`() {
        val winner = resolveActivePlayFromSourceId(
            activeLosslessSourceId = "builtin:MONOCHROME",
            activeSourceName = "YouTube",
            candidates = defaultCandidates,
        )

        assertEquals("builtin:MONOCHROME", winner)
    }

    @Test
    fun `fallback to activeSourceName handles Monochrome FLAC label cleanly`() {
        val winner = resolveActivePlayFromSourceId(
            activeLosslessSourceId = null,
            activeSourceName = "Monochrome (FLAC)",
            candidates = defaultCandidates,
        )

        assertEquals("builtin:MONOCHROME", winner)
    }

    @Test
    fun `matches YouTube cleanly when no lossless stream is active`() {
        val winner = resolveActivePlayFromSourceId(
            activeLosslessSourceId = null,
            activeSourceName = "YouTube",
            candidates = defaultCandidates,
        )

        assertEquals("builtin:YOUTUBE", winner)
    }
}
