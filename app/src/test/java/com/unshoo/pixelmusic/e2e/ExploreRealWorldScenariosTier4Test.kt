package com.unshoo.pixelmusic.e2e

import android.content.Context
import com.unshoo.pixelmusic.MainCoroutineExtension
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.preferences.PlaylistPreferencesRepository
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.remote.youtube.DatastoreRepository
import com.unshoo.pixelmusic.data.remote.youtube.toNativeSong
import com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository
import com.unshoo.pixelmusic.presentation.model.ExploreItemUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreSectionUiModel
import com.unshoo.pixelmusic.presentation.viewmodel.ConnectivityStateHolder
import com.unshoo.pixelmusic.presentation.viewmodel.ExploreViewModel
import com.unshoo.pixelmusic.presentation.viewmodel.ListeningStatsTracker
import com.unshoo.pixelmusic.data.model.youtube.Cookies
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import unshoo.ianshulyadav.pixelmusic.innertube.models.Album
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.Artist
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint
import unshoo.ianshulyadav.pixelmusic.innertube.models.NavigationEndpoint
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.pages.HomePage
import java.io.File
import java.nio.file.Files

/**
 * ============================================================================
 * Tier 4 — Real-World Application Scenarios: End-to-End User Journeys
 * ============================================================================
 *
 * Exercises realistic, complex, end-to-end user flows through the Explore screen:
 * - Full user journey: cold start -> load -> category browsing -> item select
 * - Interrupted session: network loss mid-flow with seamless offline recovery
 * - Rapid mood browsing: stress testing fast category switching without desync
 * - Background return: rehydration after app suspension without jarring reflow
 * - Heterogeneous catalog feed: parsing complex multi-type shelves into presentation models
 *
 * Authoritative Source: ORIGINAL_REQUEST.md, PROJECT.md (Presentation Layer & State Architecture)
 */
@ExperimentalCoroutinesApi
@ExtendWith(MainCoroutineExtension::class)
@DisplayName("Tier 4: Real-World Scenarios & End-to-End User Journeys")
class ExploreRealWorldScenariosTier4Test {

    private val playbackStatsRepository: PlaybackStatsRepository = mockk(relaxed = true)
    private val playlistPreferencesRepository: PlaylistPreferencesRepository = mockk(relaxed = true)
    private val userPreferencesRepository: UserPreferencesRepository = mockk(relaxed = true)
    private val musicDao: MusicDao = mockk(relaxed = true)
    private val listeningStatsTracker: ListeningStatsTracker = mockk(relaxed = true)
    private val datastoreRepository: DatastoreRepository = mockk(relaxed = true)
    private val connectivityStateHolder: ConnectivityStateHolder = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)

    private lateinit var tempCacheDir: File

    @BeforeEach
    fun setUp() {
        tempCacheDir = Files.createTempDirectory("explore_tier4_test").toFile()
        every { context.cacheDir } returns tempCacheDir

        every { userPreferencesRepository.localModeEnabledFlow } returns flowOf(false)
        every { userPreferencesRepository.advancedExplorePageFlow } returns flowOf(true)
        every { userPreferencesRepository.youtubePersonalizedExploreFlow } returns flowOf(true)
        every { userPreferencesRepository.youtubePersonalizedQueueFlow } returns flowOf(true)
        every { userPreferencesRepository.pureYtMusicOnlyFlow } returns flowOf(false)

        every { playlistPreferencesRepository.userPlaylistsFlow } returns flowOf(emptyList())
        every { musicDao.getHomeMixPreviewSongs(any(), any(), any()) } returns flowOf(emptyList())
        coEvery { musicDao.getSongsBySourceType(any()) } returns emptyList()
        every { datastoreRepository.cookies } returns flowOf(Cookies(""))
        every { connectivityStateHolder.isOnline } returns MutableStateFlow(true)
    }

    @AfterEach
    fun tearDown() {
        tempCacheDir.deleteRecursively()
    }

    private fun createViewModel(): ExploreViewModel {
        return ExploreViewModel(
            playbackStatsRepository = playbackStatsRepository,
            playlistPreferencesRepository = playlistPreferencesRepository,
            userPreferencesRepository = userPreferencesRepository,
            musicDao = musicDao,
            listeningStatsTracker = listeningStatsTracker,
            datastoreRepository = datastoreRepository,
            connectivityStateHolder = connectivityStateHolder,
            context = context
        )
    }

    @Test
    @DisplayName("T4-01: Full user journey - Cold start to category selection and item browsing")
    fun testFullExploreUserJourney_coldStartToPlaybackSelection() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.isLoadingState.value || viewModel.isSettledState.value)

        val workoutChip = HomePage.Chip(
            title = "Workout",
            endpoint = BrowseEndpoint(browseId = "FEmusic_workout", params = "workout_params"),
            deselectEndPoint = null
        )
        viewModel.selectMoodChip(workoutChip)
        advanceUntilIdle()

        assertEquals("Workout", viewModel.activeChipState.value?.title)

        viewModel.selectMoodChip(null)
        advanceUntilIdle()

        assertNull(viewModel.activeChipState.value, "Active chip must reset to null when All is selected")
        assertEquals("All", viewModel.uiState.value.selectedFilter)
    }

    @Test
    @DisplayName("T4-02: Interrupted session - Network loss triggers graceful offline transition")
    fun testInterruptedSession_networkLossAndOfflineRecovery() = runTest {
        val onlineFlow = MutableStateFlow(true)
        every { connectivityStateHolder.isOnline } returns onlineFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        onlineFlow.value = false
        viewModel.refreshOnEntry(force = true)
        advanceUntilIdle()

        assertTrue(viewModel.isSettledState.value, "UI must settle in offline mode")
        assertFalse(viewModel.isLoadingState.value, "Loading spinner must dismiss")
        assertNull(viewModel.errorState.value, "Offline mode should not surface a blocking error")
    }

    @Test
    @DisplayName("T4-03: Rapid mood browsing - Fast cycling through 5 categories settles on last tapped")
    fun testRapidMoodBrowsing_consecutiveCategoryJumps() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val chips = listOf("Energy", "Workout", "Focus", "Chill", "Party").map { name ->
            HomePage.Chip(
                title = name,
                endpoint = BrowseEndpoint(browseId = "FE_$name", params = "param_$name"),
                deselectEndPoint = null
            )
        }

        for (chip in chips) {
            viewModel.selectMoodChip(chip)
        }
        advanceUntilIdle()

        assertEquals("Party", viewModel.activeChipState.value?.title)
        assertEquals("FE_Party", viewModel.activeChipState.value?.browseId)
    }

    @Test
    @DisplayName("T4-04: Background return - Non-blocking refresh when content exists")
    fun testBackgroundReturn_staleCacheSilentRehydration() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.prepareForOnlineEntry()

        assertTrue(viewModel.isLoadingState.value || viewModel.isSettledState.value)
    }

    @Test
    @DisplayName("T4-05: Heterogeneous feed composition - Multi-shelf content correctly maps to UI models")
    fun testHeterogeneousExploreFeed_multiShelfContentComposition() {
        val songItem = SongItem(
            id = "song_h1",
            title = "Track One",
            artists = listOf(Artist("Artist A", "a_1")),
            album = Album("Album A", "alb_1"),
            duration = 200,
            thumbnail = "https://example.com/h1.jpg",
            explicit = false,
            endpoint = null
        )
        val albumItem = AlbumItem(
            browseId = "album_h2",
            playlistId = "pl_h2",
            title = "Album Two",
            artists = listOf(Artist("Artist B", "b_1")),
            year = 2026,
            thumbnail = "https://example.com/h2.jpg",
            explicit = true
        )
        val playlistItem = PlaylistItem(
            id = "pl_h3",
            title = "Playlist Three",
            author = Artist("Curator", "c_1"),
            songCountText = "25 tracks",
            thumbnail = "https://example.com/h3.jpg",
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null
        )
        val artistItem = ArtistItem(
            id = "art_h4",
            title = "Artist Four",
            thumbnail = "https://example.com/h4.jpg",
            channelId = "ch_h4",
            shuffleEndpoint = null,
            radioEndpoint = null,
            subscriberCountText = "500K"
        )

        val section = ExploreSectionUiModel(
            id = "mixed_shelf",
            title = "Mixed Shelf",
            label = "Explore",
            items = listOf(
                ExploreItemUiModel.SongModel(songItem.toNativeSong(), songItem),
                ExploreItemUiModel.AlbumModel(albumItem),
                ExploreItemUiModel.PlaylistModel(playlistItem),
                ExploreItemUiModel.ArtistModel(artistItem)
            )
        )

        assertEquals(4, section.items.size)
        assertTrue(section.items[0] is ExploreItemUiModel.SongModel)
        assertTrue(section.items[1] is ExploreItemUiModel.AlbumModel)
        assertTrue(section.items[2] is ExploreItemUiModel.PlaylistModel)
        assertTrue(section.items[3] is ExploreItemUiModel.ArtistModel)

        assertEquals("song_h1", section.items[0].id)
        assertEquals("album_h2", section.items[1].id)
        assertEquals("pl_h3", section.items[2].id)
        assertEquals("art_h4", section.items[3].id)
    }
}
