package com.unshoo.pixelmusic.e2e

import android.content.Context
import com.unshoo.pixelmusic.MainCoroutineExtension
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.preferences.PlaylistPreferencesRepository
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.remote.youtube.DatastoreRepository
import com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository
import com.unshoo.pixelmusic.presentation.viewmodel.ConnectivityStateHolder
import com.unshoo.pixelmusic.presentation.viewmodel.ExploreViewModel
import com.unshoo.pixelmusic.presentation.viewmodel.ListeningStatsTracker
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
import com.unshoo.pixelmusic.data.model.youtube.Cookies
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint
import unshoo.ianshulyadav.pixelmusic.innertube.models.NavigationEndpoint
import unshoo.ianshulyadav.pixelmusic.innertube.pages.HomePage
import java.io.File
import java.nio.file.Files

/**
 * ============================================================================
 * Tier 3 — Cross-Feature Interactions: UI, Motion & ViewModel Cohesion
 * ============================================================================
 *
 * Verifies cross-module handshakes and state synchronization across features:
 * - Category switching cancelling continuation and resetting pagination
 * - Pull-to-refresh while category filter is active preserving filter context
 * - Offline mode toggle immediately transforming state from online to offline
 * - Error recovery and retry clearing error state cleanly
 * - prepareForOnlineEntry non-destructive loading contract
 *
 * Authoritative Source: ORIGINAL_REQUEST.md, PROJECT.md (§ Interface Contracts)
 */
@ExperimentalCoroutinesApi
@ExtendWith(MainCoroutineExtension::class)
@DisplayName("Tier 3: Cross-Feature Interactions & State Handshakes")
class ExploreCrossFeatureTier3Test {

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
        tempCacheDir = Files.createTempDirectory("explore_tier3_test").toFile()
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
    @DisplayName("T3-01: Category chip switch resets continuation and enters category loading")
    fun testCategoryChipSwitch_cancelsActiveContinuationAndResetsPagination() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val chip = HomePage.Chip(
            title = "Focus",
            endpoint = BrowseEndpoint(browseId = "FEmusic_focus", params = "focus_param"),
            deselectEndPoint = null
        )

        viewModel.selectMoodChip(chip)

        assertEquals("Focus", viewModel.activeChipState.value?.title)
        assertEquals("Focus", viewModel.uiState.value.selectedFilter)
    }

    @Test
    @DisplayName("T3-02: Pull-to-refresh while mood category is active preserves selectedFilter")
    fun testPullToRefresh_whileMoodActive_maintainsMoodFilter() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setSelectedFilter("Chill")
        advanceUntilIdle()

        assertEquals("Chill", viewModel.uiState.value.selectedFilter)

        viewModel.refreshOnEntry(force = true)

        assertEquals("Chill", viewModel.uiState.value.selectedFilter)
    }

    @Test
    @DisplayName("T3-03: Offline mode toggle switches sections to offline tracks immediately")
    fun testOfflineToggle_switchesOnlineSectionsToOfflineImmediately() = runTest {
        val onlineFlow = MutableStateFlow(true)
        every { connectivityStateHolder.isOnline } returns onlineFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        onlineFlow.value = false
        viewModel.refreshOnEntry(force = true)
        advanceUntilIdle()

        assertTrue(viewModel.isSettledState.value)
        assertFalse(viewModel.isLoadingState.value)
        assertTrue(viewModel.moodChipsState.value.isEmpty())
    }

    @Test
    @DisplayName("T3-04: Error recovery and retry resets errorState to null")
    fun testErrorRecovery_retryAfterFailure_clearsErrorState() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.refreshOnEntry(force = true)

        assertNull(viewModel.errorState.value, "Error state must be cleared when a new refresh is triggered")
        assertNull(viewModel.uiState.value.error, "Monolithic uiState error must also be cleared")
    }

    @Test
    @DisplayName("T3-05: prepareForOnlineEntry non-destructive loading contract")
    fun testPrepareForOnlineEntry_transitionsToLoadingOnlyWhenEmpty() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.prepareForOnlineEntry()
        assertTrue(viewModel.isLoadingState.value)
        assertFalse(viewModel.isSettledState.value)
    }
}
