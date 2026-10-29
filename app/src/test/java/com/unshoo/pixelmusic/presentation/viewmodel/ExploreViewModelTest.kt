package com.unshoo.pixelmusic.presentation.viewmodel

import android.content.Context
import com.unshoo.pixelmusic.MainCoroutineExtension
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.preferences.PlaylistPreferencesRepository
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.remote.youtube.DatastoreRepository
import com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository
import com.unshoo.pixelmusic.presentation.model.ExploreScreenUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.Album
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.Artist
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.YTItem
import unshoo.ianshulyadav.pixelmusic.innertube.pages.HomePage
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Unit test suite for ExploreViewModel and Decoupled Data Models (Milestone M1).
 * Covers initial loading, 4-tier caching, offline fallback, category filtering,
 * continuation pagination, error handling, and force refresh.
 */
@ExperimentalCoroutinesApi
@DisplayName("ExploreViewModel Unit Tests (Milestone M1)")
class ExploreViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val playbackStatsRepository: PlaybackStatsRepository = mockk(relaxed = true)
    private val playlistPreferencesRepository: PlaylistPreferencesRepository = mockk(relaxed = true)
    private val userPreferencesRepository: UserPreferencesRepository = mockk(relaxed = true)
    private val musicDao: MusicDao = mockk(relaxed = true)
    private val listeningStatsTracker: ListeningStatsTracker = mockk(relaxed = true)
    private val datastoreRepository: DatastoreRepository = mockk(relaxed = true)
    private val connectivityStateHolder: ConnectivityStateHolder = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)

    private lateinit var tempCacheDir: File

    private val testGson by lazy {
        com.google.gson.GsonBuilder()
            .registerTypeAdapter(YTItem::class.java, TestYTItemTypeAdapter())
            .create()
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempCacheDir = Files.createTempDirectory("explore_vm_test").toFile()
        every { context.cacheDir } returns tempCacheDir

        every { userPreferencesRepository.localModeEnabledFlow } returns flowOf(false)
        every { userPreferencesRepository.advancedExplorePageFlow } returns flowOf(true)
        every { userPreferencesRepository.youtubePersonalizedExploreFlow } returns flowOf(true)
        every { userPreferencesRepository.youtubePersonalizedQueueFlow } returns flowOf(true)
        every { userPreferencesRepository.pureYtMusicOnlyFlow } returns flowOf(false)

        every { playlistPreferencesRepository.userPlaylistsFlow } returns flowOf(emptyList())
        every { musicDao.getHomeMixPreviewSongs(any(), any(), any()) } returns flowOf(emptyList())
        coEvery { musicDao.getSongsBySourceType(any()) } returns emptyList()
        every { datastoreRepository.cookies } returns flowOf(com.unshoo.pixelmusic.data.model.youtube.Cookies(""))
        every { connectivityStateHolder.isOnline } returns MutableStateFlow(true)

        mockkObject(YouTube)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(YouTube)
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
            context = context,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    private fun sampleSong(id: String, title: String): SongItem = SongItem(
        id = id,
        title = title,
        artists = listOf(Artist(name = "Test Artist", id = "artist_$id")),
        album = Album(name = "Test Album", id = "album_$id"),
        duration = 180,
        thumbnail = "https://example.com/art_$id.jpg",
        explicit = false,
        endpoint = null
    )

    private fun sampleSection(title: String, items: List<SongItem>): HomePage.Section = HomePage.Section(
        title = title,
        label = "Shelf Label",
        thumbnail = null,
        endpoint = null,
        items = items
    )

    private fun sampleChip(title: String, browseId: String, params: String): HomePage.Chip = HomePage.Chip(
        title = title,
        endpoint = BrowseEndpoint(browseId = browseId, params = params),
        deselectEndPoint = null
    )

    private fun createMockHomePage(
        sectionTitle: String = "Quick Picks",
        itemCount: Int = 5,
        chips: List<HomePage.Chip> = emptyList(),
        continuation: String? = null
    ): HomePage {
        val items = (1..itemCount).map { i -> sampleSong("song_$i", "Track $i") }
        val section = sampleSection(sectionTitle, items)
        return HomePage(
            chips = chips,
            sections = listOf(section),
            continuation = continuation
        )
    }

    private fun writeCache(
        sections: List<HomePage.Section> = emptyList(),
        chips: List<HomePage.Chip> = emptyList(),
        ageMs: Long = 0L
    ): File {
        val timestamp = System.currentTimeMillis() - ageMs
        val cache = ExploreCacheModel(
            sections = sections,
            moodChips = chips,
            timestamp = timestamp
        )
        val file = File(tempCacheDir, "explore_cache.json")
        file.writeText(testGson.toJson(cache))
        file.setLastModified(timestamp)
        return file
    }

    @Test
    @DisplayName("01: Online initial load with no cache transitions from Loading to Content")
    fun initialLoad_whenOnlineAndNoCache_emitsLoadingThenContent() = runTest(testDispatcher) {
        val mockHome = createMockHomePage("Online Quick Picks", 5)
        coEvery { YouTube.home(any(), any(), any()) } returns Result.success(mockHome)
        coEvery { YouTube.newReleaseAlbums() } returns Result.success(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val err = viewModel.errorState.value
        val loading = viewModel.isLoadingState.value
        val settled = viewModel.isSettledState.value
        assertTrue(state is ExploreScreenUiState.Content, "State was $state, err=$err, loading=$loading, settled=$settled")
        val content = state as ExploreScreenUiState.Content
        assertEquals("All", content.selectedCategory)
        assertFalse(content.isRefreshing)
        assertEquals(1, content.dynamicShelves.size)
        assertEquals("Online Quick Picks", content.dynamicShelves.first().title)
    }

    @Test
    @DisplayName("02: Fresh cache (<30m) loads immediately without network fetch")
    fun initialLoad_whenFreshCacheExists_loadsFromCacheWithoutNetworkFetch() = runTest(testDispatcher) {
        val cachedSection = sampleSection("Cached Fresh Picks", listOf(sampleSong("c1", "Cached Track 1")))
        val cachedChip = sampleChip("Cached Chip", "FEchip", "params")
        writeCache(
            sections = listOf(cachedSection),
            chips = listOf(cachedChip),
            ageMs = 5 * 60 * 1000L // 5 minutes old (< 30 min -> FRESH)
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ExploreScreenUiState.Content, "Fresh cache must emit Content")
        val content = state as ExploreScreenUiState.Content
        assertEquals("Cached Fresh Picks", content.dynamicShelves.firstOrNull()?.title)
        coVerify(exactly = 0) { YouTube.home(any(), any(), any()) }
    }

    @Test
    @DisplayName("03: Stale cache (30m-4h) loads cache immediately and triggers silent background refresh")
    fun initialLoad_whenStaleCacheExists_loadsCacheImmediatelyAndTriggersSilentRefresh() = runTest(testDispatcher) {
        val cachedSection = sampleSection("Stale Initial Section", listOf(sampleSong("s1", "Stale Track 1")))
        writeCache(
            sections = listOf(cachedSection),
            ageMs = 60 * 60 * 1000L // 1 hour old (30m-4h -> STALE)
        )

        val freshHome = createMockHomePage("Refreshed Fresh Section", 3)
        coEvery { YouTube.home(any(), any(), any()) } returns Result.success(freshHome)
        coEvery { YouTube.newReleaseAlbums() } returns Result.success(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        coVerify(atLeast = 1) { YouTube.home(any(), any(), any()) }
        val state = viewModel.uiState.value
        assertTrue(state is ExploreScreenUiState.Content)
        val content = state as ExploreScreenUiState.Content
        assertEquals("Refreshed Fresh Section", content.dynamicShelves.firstOrNull()?.title)
    }

    @Test
    @DisplayName("04: Expired cache (>24h) is deleted and full network fetch is performed")
    fun initialLoad_whenCacheExpired_clearsCacheAndPerformsFullNetworkFetch() = runTest(testDispatcher) {
        val cachedSection = sampleSection("Expired Section", listOf(sampleSong("e1", "Expired Track 1")))
        val cacheFile = writeCache(
            sections = listOf(cachedSection),
            ageMs = 25 * 3600 * 1000L // 25 hours old (> 24h -> EXPIRED)
        )

        val networkHome = createMockHomePage("Fresh Network Section", 4)
        coEvery { YouTube.home(any(), any(), any()) } returns Result.success(networkHome)
        coEvery { YouTube.newReleaseAlbums() } returns Result.success(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(cacheFile.exists(), "Cache file should be re-written with fresh network data")
        val updatedCache = testGson.fromJson(cacheFile.readText(), ExploreCacheModel::class.java)
        assertEquals("Fresh Network Section", updatedCache.sections.firstOrNull()?.title)
        assertFalse(updatedCache.sections.any { it.title == "Expired Section" }, "Expired sections should not be in cache")
        coVerify(atLeast = 1) { YouTube.home(any(), any(), any()) }
        val state = viewModel.uiState.value
        assertTrue(state is ExploreScreenUiState.Content)
        assertEquals("Fresh Network Section", (state as ExploreScreenUiState.Content).dynamicShelves.firstOrNull()?.title)
    }

    @Test
    @DisplayName("05: Offline mode loads local songs without network attempt")
    fun initialLoad_whenOffline_loadsLocalAndDownloadedSongs() = runTest(testDispatcher) {
        every { connectivityStateHolder.isOnline } returns MutableStateFlow(false)
        val localEntity = com.unshoo.pixelmusic.data.database.SongEntity(
            id = 101L,
            title = "Offline Song",
            artistName = "Local Artist",
            artistId = 1L,
            albumName = "Local Album",
            albumId = 1L,
            contentUriString = "content://media/1",
            albumArtUriString = null,
            duration = 180000L,
            genre = "Pop",
            filePath = "/storage/music/song.mp3",
            parentDirectoryPath = "/storage/music",
            sourceType = 0
        )
        coEvery { musicDao.getSongsBySourceType(0) } returns listOf(localEntity)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val content = withTimeout(5000) {
            viewModel.uiState.filterIsInstance<ExploreScreenUiState.Content>().first()
        }
        assertEquals("Local", content.selectedCategory)
        coVerify(exactly = 0) { YouTube.home(any(), any(), any()) }
    }

    @Test
    @DisplayName("06: Category selection updates selectedCategory and fetches mood shelves")
    fun selectCategory_whenChipSelected_fetchesMoodShelvesAndUpdatesState() = runTest(testDispatcher) {
        val relaxChip = sampleChip("Relax", "FEmusic_relax", "wAEB_relax")
        val initialHome = createMockHomePage("Home Feed", 3, chips = listOf(relaxChip))
        coEvery { YouTube.home(continuation = null, params = null, forceRefresh = any()) } returns Result.success(initialHome)
        coEvery { YouTube.newReleaseAlbums() } returns Result.success(emptyList())

        val relaxHome = createMockHomePage("Peaceful Piano", 4)
        coEvery { YouTube.home(continuation = null, params = "wAEB_relax", forceRefresh = any()) } returns Result.success(relaxHome)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectCategory("Relax")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ExploreScreenUiState.Content)
        val content = state as ExploreScreenUiState.Content
        assertEquals("Relax", content.selectedCategory)
        assertEquals("Peaceful Piano", content.dynamicShelves.firstOrNull()?.title)
    }

    @Test
    @DisplayName("07: Selecting 'All' restores base home feed sections")
    fun selectCategory_whenAllSelected_restoresHomeFeed() = runTest(testDispatcher) {
        val workoutChip = sampleChip("Workout", "FEmusic_workout", "wAEB_workout")
        val homeFeed = createMockHomePage("Original Home Section", 3, chips = listOf(workoutChip))
        coEvery { YouTube.home(continuation = null, params = null, forceRefresh = any()) } returns Result.success(homeFeed)
        coEvery { YouTube.newReleaseAlbums() } returns Result.success(emptyList())

        val workoutFeed = createMockHomePage("Power Workout", 4)
        coEvery { YouTube.home(continuation = null, params = "wAEB_workout", forceRefresh = any()) } returns Result.success(workoutFeed)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Switch to Workout
        viewModel.selectCategory("Workout")
        advanceUntilIdle()
        assertEquals("Workout", (viewModel.uiState.value as ExploreScreenUiState.Content).selectedCategory)

        // Switch back to All
        viewModel.selectCategory("All")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ExploreScreenUiState.Content)
        val content = state as ExploreScreenUiState.Content
        assertEquals("All", content.selectedCategory)
        assertEquals("Original Home Section", content.dynamicShelves.firstOrNull()?.title)
    }

    @Test
    @DisplayName("08: loadMore fetches continuation and appends new shelves")
    fun loadMore_whenContinuationAvailable_appendsNewShelves() = runTest(testDispatcher) {
        val homeWithCont = createMockHomePage("Page 1 Shelf", 2, continuation = "token_page_2")
        coEvery { YouTube.home(continuation = null, params = null, forceRefresh = any()) } returns Result.success(homeWithCont)
        coEvery { YouTube.newReleaseAlbums() } returns Result.success(emptyList())

        val contPage = createMockHomePage("Page 2 Shelf", 3, continuation = null)
        coEvery { YouTube.home(continuation = "token_page_2", params = null, forceRefresh = any()) } returns Result.success(contPage)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val initialContent = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals(1, initialContent.dynamicShelves.size)
        assertTrue(initialContent.hasMoreContent)

        viewModel.loadMore()
        advanceUntilIdle()

        val updatedContent = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals(2, updatedContent.dynamicShelves.size)
        assertEquals("Page 1 Shelf", updatedContent.dynamicShelves[0].title)
        assertEquals("Page 2 Shelf", updatedContent.dynamicShelves[1].title)
        assertFalse(updatedContent.hasMoreContent)
    }

    @Test
    @DisplayName("09: Network failure with no cache emits ExploreScreenUiState.Error")
    fun networkFailure_whenNoCache_emitsErrorState() = runTest(testDispatcher) {
        coEvery { YouTube.home(any(), any(), any()) } throws IOException("503 Service Unavailable")
        coEvery { YouTube.newReleaseAlbums() } returns Result.success(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ExploreScreenUiState.Error, "State must be Error on network failure")
        val errorState = state as ExploreScreenUiState.Error
        assertTrue(errorState.message.contains("503") || errorState.message.isNotBlank())
    }

    @Test
    @DisplayName("10: Force refresh bypasses fresh cache and queries network for latest feed")
    fun forceRefresh_bypassesCacheAndUpdatesFeed() = runTest(testDispatcher) {
        val cachedSection = sampleSection("Cached Shelf", listOf(sampleSong("c1", "Cached 1")))
        writeCache(
            sections = listOf(cachedSection),
            ageMs = 5 * 60 * 1000L // 5 minutes old (fresh)
        )

        val networkHome = createMockHomePage("Fresh From Network", 3)
        coEvery { YouTube.home(any(), any(), any()) } returns Result.success(networkHome)
        coEvery { YouTube.newReleaseAlbums() } returns Result.success(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Cache initially loaded, zero network calls
        assertEquals("Cached Shelf", (viewModel.uiState.value as ExploreScreenUiState.Content).dynamicShelves.firstOrNull()?.title)
        coVerify(exactly = 0) { YouTube.home(any(), any(), any()) }

        // Trigger force refresh
        viewModel.refresh(force = true)
        advanceUntilIdle()

        // Network call must have been executed
        coVerify(atLeast = 1) { YouTube.home(any(), any(), any()) }
        val updatedState = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals("Fresh From Network", updatedState.dynamicShelves.firstOrNull()?.title)
    }

    @Test
    @DisplayName("11: Rapid category switch from Category to 'All' while category fetch is in flight does NOT overwrite 'All' with stale category data")
    fun selectCategory_whenSwitchedToAllWhileCategoryInFlight_doesNotOverwriteAllWithStaleData() = runTest(testDispatcher) {
        // Given: Online initial load completes with base home feed and a "Relax" mood chip
        val relaxChip = sampleChip("Relax", "FEmusic_relax", "wAEB_relax")
        val homeFeed = createMockHomePage("Home Feed Shelf", 3, chips = listOf(relaxChip))
        coEvery { YouTube.home(continuation = null, params = null, forceRefresh = any()) } returns Result.success(homeFeed)
        coEvery { YouTube.newReleaseAlbums() } returns Result.success(emptyList())

        // In-flight deferred for category fetch to simulate slow network response
        val relaxDeferred = CompletableDeferred<Result<HomePage>>()
        coEvery { YouTube.home(continuation = null, params = "wAEB_relax", forceRefresh = any()) } coAnswers {
            relaxDeferred.await()
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        val initialContent = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals("All", initialContent.selectedCategory)
        assertEquals("Home Feed Shelf", initialContent.dynamicShelves.first().title)

        // When: User selects "Relax", suspending on in-flight network call
        viewModel.selectCategory("Relax")
        testScheduler.runCurrent()

        // And immediately switches back to "All" before "Relax" network call returns
        viewModel.selectCategory("All")
        testScheduler.runCurrent()

        val switchedBackContent = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals("All", switchedBackContent.selectedCategory)
        assertEquals("Home Feed Shelf", switchedBackContent.dynamicShelves.first().title)

        // Then: The slow "Relax" network call completes with stale category data
        val relaxFeed = createMockHomePage("Peaceful Piano (Stale)", 4)
        relaxDeferred.complete(Result.success(relaxFeed))
        advanceUntilIdle()

        // Invariant Verification:
        // 1. UI state must remain on "All", not overwritten by stale "Relax" shelves
        val finalState = viewModel.uiState.value
        assertTrue(finalState is ExploreScreenUiState.Content, "Final state must remain Content")
        val finalContent = finalState as ExploreScreenUiState.Content
        assertEquals("All", finalContent.selectedCategory, "Category must remain 'All'")
        assertEquals(1, finalContent.dynamicShelves.size, "Dynamic shelves count must be 1 (home feed only)")
        assertEquals("Home Feed Shelf", finalContent.dynamicShelves.first().title, "Dynamic shelves must NOT be overwritten by stale category data")
        assertFalse(finalContent.dynamicShelves.any { it.title.contains("Peaceful Piano") }, "Stale category shelf must NOT appear in dynamicShelves")

        // 2. Legacy state consistency
        assertEquals("All", viewModel.exploreUiState.value.selectedFilter)
        assertTrue(viewModel.exploreUiState.value.explorePageSections.isEmpty(), "explorePageSections must remain empty when 'All' is active")
    }

    @Test
    @DisplayName("12: loadMore() in a category appends continuation items to explorePageSections and dynamicShelves, preserving base homePageSections")
    fun loadMore_whenInCategory_appendsToCategorySectionsAndPreservesHomeFeed() = runTest(testDispatcher) {
        // Given: Home feed with base shelf and "Relax" mood chip
        val relaxChip = sampleChip("Relax", "FEmusic_relax", "wAEB_relax")
        val initialHome = createMockHomePage("Base Home Shelf", 3, chips = listOf(relaxChip), continuation = "home_token_initial")
        coEvery { YouTube.home(continuation = null, params = null, forceRefresh = any()) } returns Result.success(initialHome)
        coEvery { YouTube.newReleaseAlbums() } returns Result.success(emptyList())

        // Category "Relax" initial page with continuation token
        val relaxPage1 = createMockHomePage("Relax Shelf 1", 3, continuation = "relax_token_p2")
        coEvery { YouTube.home(continuation = null, params = "wAEB_relax", forceRefresh = any()) } returns Result.success(relaxPage1)

        // Category "Relax" continuation page
        val relaxPage2 = createMockHomePage("Relax Shelf 2 (Continuation)", 2, continuation = null)
        coEvery { YouTube.home(continuation = "relax_token_p2", params = null, forceRefresh = any()) } returns Result.success(relaxPage2)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // User navigates to "Relax" category
        viewModel.selectCategory("Relax")
        advanceUntilIdle()

        val categoryContent = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals("Relax", categoryContent.selectedCategory)
        assertEquals(1, categoryContent.dynamicShelves.size)
        assertEquals("Relax Shelf 1", categoryContent.dynamicShelves[0].title)
        assertTrue(categoryContent.hasMoreContent)

        // When: User triggers loadMore() inside "Relax" category
        viewModel.loadMore()
        advanceUntilIdle()

        // Then:
        // 1. UI state dynamicShelves must append Relax continuation without showing or corrupting base home feed
        val paginatedContent = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals("Relax", paginatedContent.selectedCategory)
        assertEquals(2, paginatedContent.dynamicShelves.size, "Category dynamicShelves must contain both initial and continuation shelves")
        assertEquals("Relax Shelf 1", paginatedContent.dynamicShelves[0].title)
        assertEquals("Relax Shelf 2 (Continuation)", paginatedContent.dynamicShelves[1].title)
        assertFalse(paginatedContent.hasMoreContent)
        assertFalse(paginatedContent.dynamicShelves.any { it.title == "Base Home Shelf" }, "Base Home Shelf must NOT be mixed into category dynamicShelves")

        // 2. Base homePageSections in legacy state must be completely preserved and uncontaminated
        val legacy = viewModel.exploreUiState.value
        assertEquals(1, legacy.homePageSections.size, "Base homePageSections count must remain 1")
        assertEquals("Base Home Shelf", legacy.homePageSections[0].title, "Base homePageSections must NOT be contaminated with category continuation")
        assertEquals(2, legacy.explorePageSections.size, "explorePageSections must contain the 2 category shelves")

        // 3. Returning to "All" category must cleanly restore uncontaminated base home feed
        viewModel.selectCategory("All")
        advanceUntilIdle()

        val restoredAllContent = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals("All", restoredAllContent.selectedCategory)
        assertEquals(1, restoredAllContent.dynamicShelves.size, "Dynamic shelves on 'All' must contain exactly the base home feed")
        assertEquals("Base Home Shelf", restoredAllContent.dynamicShelves[0].title)
        assertFalse(restoredAllContent.dynamicShelves.any { it.title.contains("Relax Shelf 2") }, "Category continuation must NOT leak into base home feed")
    }

    @Test
    @DisplayName("13: In-flight home refresh finishing after user category selection does NOT clobber the user's active category back to 'All'")
    fun homeRefresh_whenFinishesAfterCategorySelection_doesNotClobberActiveCategory() = runTest(testDispatcher) {
        // Given: Initial cached content so Explore loads immediately into Content state
        val workoutChip = sampleChip("Workout", "FEmusic_workout", "wAEB_workout")
        val cachedSection = sampleSection("Cached Initial Home", listOf(sampleSong("c1", "Track 1")))
        writeCache(
            sections = listOf(cachedSection),
            chips = listOf(workoutChip),
            ageMs = 5 * 60 * 1000L // 5 min (fresh cache loads immediately)
        )

        // Deferred for background refresh
        val refreshDeferred = CompletableDeferred<Result<HomePage>>()
        coEvery { YouTube.home(continuation = null, params = null, forceRefresh = any()) } coAnswers {
            refreshDeferred.await()
        }
        coEvery { YouTube.newReleaseAlbums() } returns Result.success(emptyList())

        // Workout category mock
        val workoutFeed = createMockHomePage("Power Workout Gym", 4)
        coEvery { YouTube.home(continuation = null, params = "wAEB_workout", forceRefresh = any()) } returns Result.success(workoutFeed)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Cache loaded
        val initialContent = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals("All", initialContent.selectedCategory)
        assertEquals("Cached Initial Home", initialContent.dynamicShelves.first().title)

        // When: Background refresh is triggered (e.g. manual pull-to-refresh or force refresh)
        viewModel.refresh(force = true)
        testScheduler.runCurrent() // Coroutine launches and suspends on refreshDeferred.await()

        // User navigates to "Workout" category while background home refresh is still in-flight
        viewModel.selectCategory("Workout")
        advanceUntilIdle()

        // Verify active state is Workout
        val activeWorkoutState = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals("Workout", activeWorkoutState.selectedCategory)
        assertEquals("Power Workout Gym", activeWorkoutState.dynamicShelves.first().title)

        // Now the in-flight background home refresh completes with fresh network home feed
        val freshHome = createMockHomePage("Fresh Network Home", 5)
        refreshDeferred.complete(Result.success(freshHome))
        advanceUntilIdle()

        // Then:
        // 1. User's active category view must NOT be clobbered back to "All" or have its shelves replaced
        val stateAfterRefresh = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals("Workout", stateAfterRefresh.selectedCategory, "Active category must remain 'Workout', never clobbered to 'All'")
        assertEquals(1, stateAfterRefresh.dynamicShelves.size)
        assertEquals("Power Workout Gym", stateAfterRefresh.dynamicShelves.first().title, "Dynamic shelves must remain 'Power Workout Gym'")
        assertFalse(stateAfterRefresh.dynamicShelves.any { it.title == "Fresh Network Home" }, "Fresh home shelves must NOT overwrite active category shelves")

        // 2. The refreshed home data must update background cache/homePageSections
        val legacy = viewModel.exploreUiState.value
        assertEquals("Fresh Network Home", legacy.homePageSections.firstOrNull()?.title, "Background homePageSections should be updated with fresh home data")

        // 3. When the user later navigates back to "All", they receive the fresh home feed
        viewModel.selectCategory("All")
        advanceUntilIdle()

        val allContent = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals("All", allContent.selectedCategory)
        assertEquals("Fresh Network Home", allContent.dynamicShelves.first().title)
    }

    @Test
    @DisplayName("14: Category fetch failure emits ExploreScreenUiState.Error or updates UI state so error is observable")
    fun selectCategory_whenNetworkFails_emitsErrorState() = runTest(testDispatcher) {
        // Given: Initial home feed with "Focus" mood chip
        val focusChip = sampleChip("Focus", "FEmusic_focus", "wAEB_focus")
        val initialHome = createMockHomePage("Home Feed Shelf", 3, chips = listOf(focusChip))
        coEvery { YouTube.home(continuation = null, params = null, forceRefresh = any()) } returns Result.success(initialHome)
        coEvery { YouTube.newReleaseAlbums() } returns Result.success(emptyList())

        // Category endpoints fail with IOException
        coEvery { YouTube.home(continuation = null, params = "wAEB_focus", forceRefresh = any()) } throws IOException("503 Service Unavailable")
        coEvery { YouTube.explore(browseId = any(), params = any()) } throws IOException("503 Service Unavailable")
        coEvery { YouTube.search(any(), any()) } throws IOException("503 Service Unavailable")

        val viewModel = createViewModel()
        advanceUntilIdle()

        val initialContent = viewModel.uiState.value as ExploreScreenUiState.Content
        assertEquals("All", initialContent.selectedCategory)

        // When: User selects "Focus" which fails
        viewModel.selectCategory("Focus")
        advanceUntilIdle()

        // Then:
        // 1. Single-source-of-truth UI state machine must transition to ExploreScreenUiState.Error
        val state = viewModel.uiState.value
        assertTrue(state is ExploreScreenUiState.Error, "Expected ExploreScreenUiState.Error on category failure, but was: $state")
        val errorState = state as ExploreScreenUiState.Error
        assertTrue(errorState.message.contains("503") || errorState.message.isNotBlank(), "Error message must be present and descriptive")
        assertNotNull(errorState.cachedContent, "Prior content should be retained in cachedContent for graceful degradation")

        // 2. Loading state must be dismissed
        assertFalse(viewModel.isLoadingState.value, "isLoadingState must be false after error")

        // 3. Legacy error state must also be populated
        assertNotNull(viewModel.exploreUiState.value.error, "Legacy error state must be populated")
    }

    private class TestYTItemTypeAdapter : com.google.gson.JsonSerializer<YTItem>, com.google.gson.JsonDeserializer<YTItem> {
        override fun serialize(src: YTItem, typeOfSrc: java.lang.reflect.Type, context: com.google.gson.JsonSerializationContext): com.google.gson.JsonElement {
            val obj = com.google.gson.JsonObject()
            when (src) {
                is SongItem -> {
                    obj.addProperty("type", "song")
                    obj.add("data", context.serialize(src, SongItem::class.java))
                }
                is AlbumItem -> {
                    obj.addProperty("type", "album")
                    obj.add("data", context.serialize(src, AlbumItem::class.java))
                }
                is PlaylistItem -> {
                    obj.addProperty("type", "playlist")
                    obj.add("data", context.serialize(src, PlaylistItem::class.java))
                }
                is ArtistItem -> {
                    obj.addProperty("type", "artist")
                    obj.add("data", context.serialize(src, ArtistItem::class.java))
                }
            }
            return obj
        }

        override fun deserialize(json: com.google.gson.JsonElement, typeOfT: java.lang.reflect.Type, context: com.google.gson.JsonDeserializationContext): YTItem? {
            return try {
                val obj = json.asJsonObject
                val type = obj.get("type")?.asString ?: return null
                val targetClass = when (type) {
                    "song", "SongItem" -> SongItem::class.java
                    "album", "AlbumItem" -> AlbumItem::class.java
                    "playlist", "PlaylistItem" -> PlaylistItem::class.java
                    "artist", "ArtistItem" -> ArtistItem::class.java
                    else -> null
                } ?: return null
                val data = obj.get("data") ?: obj
                context.deserialize(data, targetClass)
            } catch (_: Exception) {
                null
            }
        }
    }
}
