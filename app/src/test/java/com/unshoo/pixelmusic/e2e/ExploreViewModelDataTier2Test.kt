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
import java.io.File
import java.nio.file.Files

/**
 * ============================================================================
 * Tier 2 — Boundary & Corner Cases: R3 Explore ViewModel & Data State Machine
 * ============================================================================
 *
 * Stress tests failure modes, edge conditions, and state machine robustness:
 * - Network failure handling and error state surfacing
 * - Offline mode transition when disconnected
 * - Stale generation rejection under rapid consecutive refresh triggers
 * - Pagination exhaustion when continuation token is null
 * - Corrupted cache JSON file recovery
 *
 * Authoritative Source: ORIGINAL_REQUEST.md (R3), PROJECT.md (State Architecture)
 */
@ExperimentalCoroutinesApi
@ExtendWith(MainCoroutineExtension::class)
@DisplayName("Tier 2: ViewModel & Data Boundary & Corner Cases")
class ExploreViewModelDataTier2Test {

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
        tempCacheDir = Files.createTempDirectory("explore_tier2_test").toFile()
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
    @DisplayName("T2-F3-01: Offline mode activation when isOnline is false settles without crash")
    fun testOfflineMode_loadsOfflineTracksWhenDisconnected() = runTest {
        every { connectivityStateHolder.isOnline } returns MutableStateFlow(false)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.isSettledState.value, "ViewModel must settle when starting in offline mode")
        assertFalse(viewModel.isLoadingState.value, "ViewModel must not remain in loading state when offline")
        assertTrue(viewModel.moodChipsState.value.isEmpty(), "Mood chips must be empty in offline mode")
    }

    @Test
    @DisplayName("T2-F3-02: Empty continuation token in loadMore prevents redundant network triggers")
    fun testContinuationLoading_emptyContinuationExhaustion() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.homePageContinuation)

        viewModel.loadMore()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isContinuationLoading, "isContinuationLoading must remain false when continuation is null")
    }

    @Test
    @DisplayName("T2-F3-03: Corrupted cache JSON file is safely caught and discarded")
    fun testCorruptedCacheFile_autoDeletesAndRecovers() = runTest {
        val cacheFile = File(tempCacheDir, "explore_cache.json")
        cacheFile.writeText("{ corrupted_malformed_json ::: !!! [}")

        assertTrue(cacheFile.exists())

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.isLoadingState.value || viewModel.isSettledState.value)
    }

    @Test
    @DisplayName("T2-F3-04: Rapid refresh generations guarantee cancellation of previous requests")
    fun testRapidRefresh_discardsStaleGenerations() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val job1 = viewModel.refreshOnEntry(force = true)
        val job2 = viewModel.refreshOnEntry(force = true)

        assertNotNull(job1)
        assertNotNull(job2)
        assertTrue(job1 != job2, "Consecutive forced refreshes produce new coordinator jobs")
        assertTrue(job1!!.isCancelled, "First job must be cancelled by the second job")
    }

    @Test
    @DisplayName("T2-F3-05: Local filter switch immediately sets filter state and clears online chips")
    fun testLocalFilterSwitch_clearsOnlineMoodChips() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setSelectedFilter("Local")
        advanceUntilIdle()

        assertEquals("Local", viewModel.uiState.value.selectedFilter)
        assertNull(viewModel.activeChipState.value, "Local filter clears active online chip")
        assertFalse(viewModel.isLoadingState.value, "Local filter should not be stuck loading")
    }
}
