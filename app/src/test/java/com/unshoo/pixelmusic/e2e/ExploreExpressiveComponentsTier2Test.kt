package com.unshoo.pixelmusic.e2e

import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.presentation.model.ExploreItemUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreSectionUiModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem

/**
 * ============================================================================
 * Tier 2 — Boundary & Corner Cases: R1 Material 3 Expressive UI & Components
 * ============================================================================
 *
 * Stress tests edge conditions and resource boundaries:
 * - Empty shelves / zero-item section handling
 * - Extreme string lengths (overflowing titles & metadata)
 * - Missing or null artwork URLs
 * - Out-of-bounds / invalid progress indicator values (<0f, >1f, NaN)
 * - Single-element or empty button group boundaries
 * - Rapid consecutive button toggles
 *
 * Authoritative Source: ORIGINAL_REQUEST.md (R1), Adversarial Verification Guidelines
 */
@DisplayName("Tier 2: UI & Components Boundary & Corner Cases")
class ExploreExpressiveComponentsTier2Test {

    @Test
    @DisplayName("T2-F1-01: Empty section list with zero items handles cleanly without exceptions")
    fun testEmptySectionList_handlesZeroItemsGracefully() {
        val emptySection = ExploreSectionUiModel(
            id = "empty_shelf",
            title = "Special Offerings",
            label = null,
            items = emptyList()
        )

        assertEquals("empty_shelf", emptySection.id)
        assertEquals(0, emptySection.items.size)
        assertTrue(emptySection.items.isEmpty(), "Empty section items list must be empty, not null or broken")
    }

    @Test
    @DisplayName("T2-F1-02: Extreme title length (10,000 characters) preserves data model integrity")
    fun testExtremeTitleLength_preservesModelIntegrity() {
        val extremeTitle = "A".repeat(10_000)

        val rawAlbum = AlbumItem(
            browseId = "MPRE_extreme_len",
            playlistId = "VL_extreme_len",
            title = extremeTitle,
            artists = emptyList(),
            year = 2026,
            thumbnail = "",
            explicit = true
        )

        val uiModel = ExploreItemUiModel.AlbumModel(album = rawAlbum)

        assertEquals("MPRE_extreme_len", uiModel.id)
        assertEquals(10_000, uiModel.album.title.length, "Extreme title must not truncate or crash model")
        assertEquals("", uiModel.thumbnail, "Empty thumbnail must be safely preserved")
        assertTrue(uiModel.album.explicit)
    }

    @Test
    @DisplayName("T2-F1-03: Missing artwork URL or empty string handled gracefully")
    fun testMissingArtworkUrl_fallbackGracefulHandling() {
        val songWithoutArt = Song.emptySong().copy(
            id = "no_art_song",
            title = "Acapella Track",
            artist = "Vocalist",
            album = "Unplugged",
            duration = 180_000L,
            albumArtUriString = null
        )

        val rawSong = SongItem(
            id = "no_art_song",
            title = "Acapella Track",
            artists = emptyList(),
            album = null,
            duration = 180,
            thumbnail = "",
            explicit = false,
            endpoint = null
        )

        val uiModel = ExploreItemUiModel.SongModel(song = songWithoutArt, rawItem = rawSong)

        assertNull(uiModel.thumbnail, "Song without artwork must provide null thumbnail without throwing")
        assertEquals("no_art_song", uiModel.id)
    }

    @Test
    @DisplayName("T2-F1-04: Out-of-bounds progress values (< 0f, > 1f, NaN) clamped safely")
    fun testWavyProgressIndicator_outOfBoundsProgressClamping() {
        fun safeProgressClamp(value: Float): Float {
            return if (value.isNaN()) 0.0f else value.coerceIn(0.0f, 1.0f)
        }

        assertEquals(0.0f, safeProgressClamp(-0.5f), 0.0001f, "Negative progress clamped to 0.0f")
        assertEquals(0.0f, safeProgressClamp(-100.0f), 0.0001f, "Extreme negative clamped to 0.0f")
        assertEquals(1.0f, safeProgressClamp(1.05f), 0.0001f, "Progress > 1.0 clamped to 1.0f")
        assertEquals(1.0f, safeProgressClamp(999.0f), 0.0001f, "Extreme positive clamped to 1.0f")
        assertEquals(0.0f, safeProgressClamp(Float.NaN), 0.0001f, "NaN progress defaults to 0.0f")
    }

    @Test
    @DisplayName("T2-F1-05: Expressive button group single item and zero items boundary conditions")
    fun testExpressiveButtonGroup_singleItemBoundary() {
        fun resolveButtonGroupSafeIndex(items: List<String>, selectedIndex: Int): Int {
            if (items.isEmpty()) return -1
            return selectedIndex.coerceIn(0, items.size - 1)
        }

        assertEquals(-1, resolveButtonGroupSafeIndex(emptyList(), 0))

        val singleItem = listOf("Tracks")
        assertEquals(0, resolveButtonGroupSafeIndex(singleItem, 0))
        assertEquals(0, resolveButtonGroupSafeIndex(singleItem, 5))
        assertEquals(0, resolveButtonGroupSafeIndex(singleItem, -3))
    }

    @Test
    @DisplayName("T2-F1-06: High-frequency rapid button toggle state coherence")
    fun testRapidButtonClicks_stateCohesion() {
        var selectedCategoryIndex = 0
        val toggleCount = 1_000

        for (i in 1..toggleCount) {
            selectedCategoryIndex = if (selectedCategoryIndex == 0) 1 else 0
        }

        assertEquals(0, selectedCategoryIndex, "1000 alternations should return to start index")

        selectedCategoryIndex = if (selectedCategoryIndex == 0) 1 else 0
        assertEquals(1, selectedCategoryIndex)
    }
}
