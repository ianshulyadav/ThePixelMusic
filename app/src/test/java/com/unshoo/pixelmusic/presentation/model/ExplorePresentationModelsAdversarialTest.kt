package com.unshoo.pixelmusic.presentation.model

import com.unshoo.pixelmusic.data.feed.FeedAlbum
import com.unshoo.pixelmusic.data.feed.FeedArtist
import com.unshoo.pixelmusic.data.feed.FeedSpotlight
import com.unshoo.pixelmusic.data.feed.RecentTrack
import com.unshoo.pixelmusic.data.feed.YouTubeMusicTrack
import com.unshoo.pixelmusic.data.feed.YouTubePlaylistSummary
import com.unshoo.pixelmusic.data.model.Playlist
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.presentation.viewmodel.ExploreUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint
import unshoo.ianshulyadav.pixelmusic.innertube.pages.HomePage

/**
 * Adversarial & Boundary Stress Test Suite for Milestone M1
 * Author: Challenger 2 (Empirical Challenger)
 *
 * Verifies:
 * 1. Null and empty safety across all presentation models:
 *    - ExploreSongItemUiModel, ExploreAlbumItemUiModel, ExplorePlaylistItemUiModel,
 *      ExploreArtistItemUiModel, ExploreHeroItemUiModel, ExploreChipUiModel,
 *      ExploreSectionUiModel, ExploreChartsUiModel
 * 2. Boundary behavior of ExploreScreenUiState (Loading, Content, Error) and
 *    all 26 backward-compatibility legacy accessors.
 * 3. Contract isolation: Public presentation models must NOT leak raw InnerTube models.
 */
@DisplayName("Explore Presentation Models & State Boundary Stress Tests (Challenger 2)")
class ExplorePresentationModelsAdversarialTest {

    @Nested
    @DisplayName("1. Presentation Models Null & Empty Safety")
    inner class PresentationModelsNullSafetyTests {

        @Test
        @DisplayName("ExploreSongItemUiModel: handles empty strings and null artwork gracefully")
        fun testSongItemUiModel_nullAndEmptySafety() {
            val emptySong = Song.emptySong()
            val modelWithoutArt = ExploreSongItemUiModel(
                id = "",
                title = "",
                subtitle = null,
                artworkUrl = null,
                durationText = null,
                explicit = false,
                nativeSong = emptySong
            )

            assertEquals("", modelWithoutArt.id)
            assertEquals("", modelWithoutArt.title)
            assertNull(modelWithoutArt.subtitle)
            assertNull(modelWithoutArt.artworkUrl)
            assertNull(modelWithoutArt.durationText)
            assertFalse(modelWithoutArt.explicit)
            assertNull(modelWithoutArt.thumbnail, "When artworkUrl and albumArtUriString are null, thumbnail must be null")

            // Test nativeSong fallback when artworkUrl is null
            val songWithArt = emptySong.copy(albumArtUriString = "content://album_art/123")
            val modelFallback = ExploreSongItemUiModel(
                id = "song_1",
                title = "Title",
                artworkUrl = null,
                nativeSong = songWithArt
            )
            assertEquals("content://album_art/123", modelFallback.thumbnail)

            // Test artworkUrl override over nativeSong
            val modelOverride = ExploreSongItemUiModel(
                id = "song_2",
                title = "Title",
                artworkUrl = "https://example.com/custom.jpg",
                nativeSong = songWithArt
            )
            assertEquals("https://example.com/custom.jpg", modelOverride.thumbnail)
        }

        @Test
        @DisplayName("ExploreAlbumItemUiModel: handles empty strings, null year, and browseId fallback")
        fun testAlbumItemUiModel_nullAndEmptySafety() {
            val defaultAlbum = ExploreAlbumItemUiModel(
                id = "album_123",
                title = "Album Title"
            )
            assertEquals("album_123", defaultAlbum.id)
            assertEquals("Album Title", defaultAlbum.title)
            assertNull(defaultAlbum.subtitle)
            assertNull(defaultAlbum.artworkUrl)
            assertNull(defaultAlbum.year)
            assertFalse(defaultAlbum.explicit)
            assertEquals("album_123", defaultAlbum.browseId, "browseId must default to id")
            assertNull(defaultAlbum.thumbnail)

            val customAlbum = ExploreAlbumItemUiModel(
                id = "",
                title = "",
                subtitle = null,
                artworkUrl = "https://example.com/album.jpg",
                year = null,
                explicit = true,
                browseId = "MPRE_custom"
            )
            assertEquals("", customAlbum.id)
            assertEquals("MPRE_custom", customAlbum.browseId)
            assertTrue(customAlbum.explicit)
            assertEquals("https://example.com/album.jpg", customAlbum.thumbnail)
        }

        @Test
        @DisplayName("ExplorePlaylistItemUiModel: handles null subtitles, artworkUrl, and songCountText")
        fun testPlaylistItemUiModel_nullAndEmptySafety() {
            val minimalPlaylist = ExplorePlaylistItemUiModel(
                id = "",
                title = ""
            )
            assertEquals("", minimalPlaylist.id)
            assertEquals("", minimalPlaylist.title)
            assertNull(minimalPlaylist.subtitle)
            assertNull(minimalPlaylist.artworkUrl)
            assertNull(minimalPlaylist.songCountText)
            assertNull(minimalPlaylist.thumbnail)

            val populatedPlaylist = ExplorePlaylistItemUiModel(
                id = "PL123",
                title = "Top Hits",
                subtitle = "PixelMusic Curated",
                artworkUrl = "https://example.com/playlist.jpg",
                songCountText = "50 songs"
            )
            assertEquals("https://example.com/playlist.jpg", populatedPlaylist.thumbnail)
            assertEquals("50 songs", populatedPlaylist.songCountText)
        }

        @Test
        @DisplayName("ExploreArtistItemUiModel: handles null artworkUrl and browseId")
        fun testArtistItemUiModel_nullAndEmptySafety() {
            val minimalArtist = ExploreArtistItemUiModel(
                id = "artist_1",
                name = "Test Artist"
            )
            assertEquals("artist_1", minimalArtist.id)
            assertEquals("Test Artist", minimalArtist.name)
            assertNull(minimalArtist.artworkUrl)
            assertNull(minimalArtist.browseId)
            assertNull(minimalArtist.thumbnail)

            val populatedArtist = ExploreArtistItemUiModel(
                id = "artist_2",
                name = "Artist With Art",
                artworkUrl = "https://example.com/artist.jpg",
                browseId = "UC12345"
            )
            assertEquals("https://example.com/artist.jpg", populatedArtist.thumbnail)
            assertEquals("UC12345", populatedArtist.browseId)
        }

        @Test
        @DisplayName("ExploreHeroItemUiModel: handles null subtitle, badgeText, and arbitrary clickPayload")
        fun testHeroItemUiModel_nullAndEmptySafety() {
            val minimalHero = ExploreHeroItemUiModel(
                id = "hero_1",
                title = "Hero Title"
            )
            assertEquals("hero_1", minimalHero.id)
            assertEquals("Hero Title", minimalHero.title)
            assertNull(minimalHero.subtitle)
            assertNull(minimalHero.artworkUrl)
            assertNull(minimalHero.badgeText)
            assertNull(minimalHero.clickPayload)
            assertNull(minimalHero.thumbnail)

            val payload = mapOf("key" to "value")
            val populatedHero = ExploreHeroItemUiModel(
                id = "hero_2",
                title = "Hero 2",
                subtitle = "Sub",
                artworkUrl = "https://example.com/hero.jpg",
                badgeText = "Spotlight",
                clickPayload = payload
            )
            assertEquals("https://example.com/hero.jpg", populatedHero.thumbnail)
            assertEquals("Spotlight", populatedHero.badgeText)
            assertSame(payload, populatedHero.clickPayload)
        }

        @Test
        @DisplayName("ExploreChipUiModel: supports primary and secondary constructors with null browseId fallback")
        fun testChipUiModel_constructorsAndNullSafety() {
            // Primary constructor defaults
            val chip1 = ExploreChipUiModel(title = "Relax")
            assertEquals("Relax", chip1.title)
            assertEquals("Relax", chip1.id, "id should default to title")
            assertFalse(chip1.isSelected)
            assertNull(chip1.browseId)
            assertNull(chip1.params)

            // Secondary constructor with null browseId -> fallback id = title
            val chip2 = ExploreChipUiModel(
                title = "Workout",
                browseId = null,
                params = "param_xyz"
            )
            assertEquals("Workout", chip2.title)
            assertEquals("Workout", chip2.id, "id should fall back to title when browseId is null")
            assertNull(chip2.browseId)
            assertEquals("param_xyz", chip2.params)
            assertFalse(chip2.isSelected)

            // Secondary constructor with non-null browseId -> id = browseId
            val chip3 = ExploreChipUiModel(
                title = "Focus",
                browseId = "FE_focus",
                params = "param_focus"
            )
            assertEquals("Focus", chip3.title)
            assertEquals("FE_focus", chip3.id, "id should equal browseId when present")
            assertEquals("FE_focus", chip3.browseId)
            assertEquals("param_focus", chip3.params)
        }

        @Test
        @DisplayName("ExploreChartsUiModel: default parameters create empty collections without NPE")
        fun testChartsUiModel_emptyCollections() {
            val emptyCharts = ExploreChartsUiModel()
            assertTrue(emptyCharts.tracks.isEmpty())
            assertTrue(emptyCharts.collections.isEmpty())
        }

        @Test
        @DisplayName("ExploreSectionUiModel: supports heterogeneous items and null label")
        fun testSectionUiModel_heterogeneousItems() {
            val emptySong = Song.emptySong()
            val items: List<ExploreItemUiModel> = listOf(
                ExploreSongItemUiModel(id = "s1", title = "Song 1", nativeSong = emptySong),
                ExploreAlbumItemUiModel(id = "a1", title = "Album 1"),
                ExplorePlaylistItemUiModel(id = "p1", title = "Playlist 1"),
                ExploreArtistItemUiModel(id = "ar1", name = "Artist 1"),
                ExploreHeroItemUiModel(id = "h1", title = "Hero 1")
            )

            val section = ExploreSectionUiModel(
                id = "sec_1",
                title = "Mixed Section",
                label = null,
                items = items
            )

            assertEquals("sec_1", section.id)
            assertEquals("Mixed Section", section.title)
            assertNull(section.label)
            assertEquals(5, section.items.size)
            assertTrue(section.items[0] is ExploreSongItemUiModel)
            assertTrue(section.items[1] is ExploreAlbumItemUiModel)
            assertTrue(section.items[2] is ExplorePlaylistItemUiModel)
            assertTrue(section.items[3] is ExploreArtistItemUiModel)
            assertTrue(section.items[4] is ExploreHeroItemUiModel)
        }
    }

    @Nested
    @DisplayName("2. ExploreScreenUiState Boundary & Backward Compatibility")
    inner class ExploreScreenUiStateBoundaryTests {

        @Test
        @DisplayName("ExploreScreenUiState.Loading: all 26 accessors evaluate to safe defaults with zero NPEs")
        fun testLoadingState_allAccessorsSafe() {
            val state: ExploreScreenUiState = ExploreScreenUiState.Loading

            // State invariants
            assertNull(state.legacyState)
            assertFalse(state.isSettled, "Loading state must have isSettled == false")
            assertFalse(state.isRefreshing)
            assertFalse(state.isContinuationLoading)
            assertTrue(state.isAdvancedExploreEnabled, "isAdvancedExploreEnabled must default to true")
            assertNull(state.error)
            assertEquals("All", state.selectedFilter)

            // Collection accessors must be empty, not null
            assertTrue(state.preparedYtHomeQuickPicks.isEmpty())
            assertTrue(state.moodChips.isEmpty())
            assertNull(state.activeMoodChip)
            assertTrue(state.explorePageSections.isEmpty())
            assertTrue(state.homePageSections.isEmpty())
            assertNull(state.homePageContinuation)
            assertTrue(state.preparedArtistsForYou.isEmpty())
            assertTrue(state.localTopArtists.isEmpty())
            assertTrue(state.preparedChartTracks.isEmpty())
            assertTrue(state.preparedChartPlaylistsAndAlbums.isEmpty())
            assertTrue(state.localRecentlyAddedSongs.isEmpty())
            assertTrue(state.localHighlyRotatoryTracks.isEmpty())
            assertNull(state.preparedSpotlight)
            assertTrue(state.preparedNewReleases.isEmpty())
            assertTrue(state.preparedAlbumsForYou.isEmpty())
            assertTrue(state.preparedOtherHomeSections.isEmpty())
            assertTrue(state.preparedDiscoverSongs.isEmpty())
            assertTrue(state.libraryPlaylists.isEmpty())
            assertTrue(state.localSongs.isEmpty())
            assertTrue(state.albumsForYou.isEmpty())
        }

        @Test
        @DisplayName("ExploreScreenUiState.Content without legacyState: all accessors evaluate cleanly")
        fun testContentState_withoutLegacyState_safeDefaults() {
            val content = ExploreScreenUiState.Content(
                isRefreshing = true,
                isContinuationLoading = true,
                selectedCategory = "Rock",
                categories = listOf(ExploreChipUiModel(title = "Rock", isSelected = true)),
                hasMoreContent = true,
                legacyState = null
            )
            val state: ExploreScreenUiState = content

            assertNull(state.legacyState)
            assertTrue(state.isSettled, "Content state must have isSettled == true")
            assertTrue(state.isRefreshing, "isRefreshing must delegate to Content.isRefreshing")
            assertTrue(state.isContinuationLoading, "isContinuationLoading must delegate to Content.isContinuationLoading")
            assertEquals("Rock", state.selectedFilter, "selectedFilter must delegate to Content.selectedCategory")
            assertNull(state.error)
            assertTrue(state.isAdvancedExploreEnabled)

            // Safe empty fallbacks
            assertTrue(state.preparedYtHomeQuickPicks.isEmpty())
            assertTrue(state.moodChips.isEmpty())
            assertNull(state.activeMoodChip)
            assertTrue(state.homePageSections.isEmpty())
            assertTrue(state.localSongs.isEmpty())
        }

        @Test
        @DisplayName("ExploreScreenUiState.Content with legacyState: accessors delegate faithfully")
        fun testContentState_withLegacyState_delegatesFaithfully() {
            val emptySong = Song.emptySong().copy(id = "s_leg_1", title = "Legacy Track")
            val sampleChip = HomePage.Chip("Chill", null, null)
            val legacy = ExploreUiState(
                isAdvancedExploreEnabled = false,
                preparedYtHomeQuickPicks = listOf(emptySong),
                moodChips = listOf(sampleChip),
                activeMoodChip = sampleChip,
                localSongs = mapOf("s_leg_1" to emptySong),
                homePageContinuation = "cont_12345"
            )

            val content = ExploreScreenUiState.Content(
                selectedCategory = "Chill",
                legacyState = legacy
            )
            val state: ExploreScreenUiState = content

            assertSame(legacy, state.legacyState)
            assertFalse(state.isAdvancedExploreEnabled, "Must read from legacyState")
            assertEquals(1, state.preparedYtHomeQuickPicks.size)
            assertEquals("s_leg_1", state.preparedYtHomeQuickPicks.first().id)
            assertEquals(1, state.moodChips.size)
            assertSame(sampleChip, state.activeMoodChip)
            assertEquals("cont_12345", state.homePageContinuation)
            assertEquals(1, state.localSongs.size)
        }

        @Test
        @DisplayName("ExploreScreenUiState.Error without cachedContent: surfaces message, safe defaults")
        fun testErrorState_withoutCachedContent_surfacesMessage() {
            val errorState: ExploreScreenUiState = ExploreScreenUiState.Error(
                message = "Network timeout 504",
                cachedContent = null
            )

            assertNull(errorState.legacyState)
            assertTrue(errorState.isSettled, "Error state is settled (not loading)")
            assertEquals("Network timeout 504", errorState.error)
            assertFalse(errorState.isRefreshing)
            assertFalse(errorState.isContinuationLoading)
            assertEquals("All", errorState.selectedFilter)
            assertTrue(errorState.preparedYtHomeQuickPicks.isEmpty())
            assertTrue(errorState.homePageSections.isEmpty())
        }

        @Test
        @DisplayName("ExploreScreenUiState.Error with cachedContent: preserves cachedContent.legacyState")
        fun testErrorState_withCachedContent_preservesCachedLegacyState() {
            val emptySong = Song.emptySong().copy(id = "s_cached", title = "Cached Song")
            val cachedLegacy = ExploreUiState(
                preparedYtHomeQuickPicks = listOf(emptySong)
            )
            val cachedContent = ExploreScreenUiState.Content(
                selectedCategory = "Jazz",
                legacyState = cachedLegacy
            )
            val errorState: ExploreScreenUiState = ExploreScreenUiState.Error(
                message = "Failed to refresh",
                cachedContent = cachedContent
            )

            assertSame(cachedLegacy, errorState.legacyState, "Error must expose cachedContent.legacyState")
            assertEquals("Failed to refresh", errorState.error)
            assertEquals(1, errorState.preparedYtHomeQuickPicks.size)
            assertEquals("s_cached", errorState.preparedYtHomeQuickPicks.first().id)
        }
    }

    @Nested
    @DisplayName("3. Contract Isolation: No Raw InnerTube Models in Public Contracts")
    inner class ContractIsolationTests {

        @Test
        @DisplayName("ExploreScreenUiState.Content constructor properties do NOT leak InnerTube types")
        fun testContentProperties_noInnerTubeLeakage() {
            val forbiddenPackagePrefix = "unshoo.ianshulyadav.pixelmusic.innertube.models"
            val fields = ExploreScreenUiState.Content::class.java.declaredFields

            for (field in fields) {
                if (field.name == "legacyState") continue // Explicitly tagged legacy wrapper
                val typeName = field.genericType.typeName
                assertFalse(
                    typeName.contains(forbiddenPackagePrefix),
                    "Field '${field.name}' in ExploreScreenUiState.Content leaks raw InnerTube type: $typeName"
                )
            }
        }

        @Test
        @DisplayName("Presentation models member fields do NOT leak raw InnerTube types")
        fun testPresentationModels_noInnerTubeLeakage() {
            val forbiddenPackagePrefix = "unshoo.ianshulyadav.pixelmusic.innertube"
            val presentationClasses = listOf(
                ExploreSongItemUiModel::class.java,
                ExploreAlbumItemUiModel::class.java,
                ExplorePlaylistItemUiModel::class.java,
                ExploreArtistItemUiModel::class.java,
                ExploreHeroItemUiModel::class.java,
                ExploreChipUiModel::class.java,
                ExploreChartsUiModel::class.java,
                ExploreSectionUiModel::class.java
            )

            for (clazz in presentationClasses) {
                for (field in clazz.declaredFields) {
                    val typeName = field.genericType.typeName
                    assertFalse(
                        typeName.contains(forbiddenPackagePrefix),
                        "Class ${clazz.simpleName}.${field.name} leaks raw InnerTube type: $typeName"
                    )
                }
            }
        }
    }
}
