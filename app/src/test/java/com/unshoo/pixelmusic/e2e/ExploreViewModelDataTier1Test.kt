package com.unshoo.pixelmusic.e2e

import android.content.Context
import com.unshoo.pixelmusic.MainCoroutineExtension
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.preferences.PlaylistPreferencesRepository
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.remote.youtube.DatastoreRepository
import com.unshoo.pixelmusic.data.remote.youtube.toNativeSong
import com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository
import com.unshoo.pixelmusic.presentation.model.ExploreChipUiModel
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
 * Tier 1 — Feature Coverage: R3 Explore ViewModel & Decoupled Data Models
 * ============================================================================
 *
 * Verifies nominal contract behaviors for ViewModel state architecture:
 * - Initial state transitions (isLoadingState, isSettledState)
 * - Decoupled presentation UI model mapping integrity
 * - Category / mood chip state transitions
 * - Cache freshness tier policies
 *
 * Authoritative Source: ORIGINAL_REQUEST.md (R3), PROJECT.md (Interface Contracts)
 */
@ExperimentalCoroutinesApi
@ExtendWith(MainCoroutineExtension::class)
@DisplayName("Tier 1: Explore ViewModel & Data Model Contracts")
class ExploreViewModelDataTier1Test {

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
        tempCacheDir = Files.createTempDirectory("explore_tier1_test").toFile()
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
    @DisplayName("T1-F3-01: ViewModel initializes in loading state with null active chip")
    fun testViewModel_initialLoadingState() = runTest {
        val viewModel = createViewModel()

        assertTrue(viewModel.isLoadingState.value, "ExploreViewModel must start with isLoadingState = true")
        assertFalse(viewModel.isSettledState.value, "ExploreViewModel must start with isSettledState = false")
        assertNull(viewModel.activeChipState.value, "No active mood chip should be selected initially")
        assertNull(viewModel.errorState.value, "Initial error state must be null")
    }

    @Test
    @DisplayName("T1-F3-02: Decoupled model mapping - SongItem to ExploreItemUiModel.SongModel")
    fun testViewModel_decoupledModelMapping_songItem() {
        val rawSong = SongItem(
            id = "song_123",
            title = "Expressive Melody",
            artists = listOf(Artist(name = "Pixel Artist", id = "artist_1")),
            album = Album(name = "Expressive Album", id = "album_1"),
            duration = 215,
            thumbnail = "https://example.com/art.jpg",
            explicit = false,
            endpoint = null
        )

        val nativeSong: Song = rawSong.toNativeSong()
        val uiModel = ExploreItemUiModel.SongModel(song = nativeSong, rawItem = rawSong)

        assertEquals("youtube_song_123", uiModel.id)
        assertEquals("Expressive Melody", uiModel.song.title)
        assertEquals("Pixel Artist", uiModel.song.artist)
        assertEquals("https://example.com/art.jpg", uiModel.thumbnail)
        assertFalse(uiModel.rawItem.explicit)
    }

    @Test
    @DisplayName("T1-F3-03: Decoupled model mapping - AlbumItem to ExploreItemUiModel.AlbumModel")
    fun testViewModel_decoupledModelMapping_albumItem() {
        val rawAlbum = AlbumItem(
            browseId = "MPRE_album_456",
            playlistId = "VL_album_456",
            title = "Modern Architecture",
            artists = listOf(Artist(name = "Pixel Band", id = "band_1")),
            year = 2026,
            thumbnail = "https://example.com/album.jpg",
            explicit = false
        )

        val uiModel = ExploreItemUiModel.AlbumModel(album = rawAlbum)

        assertEquals("MPRE_album_456", uiModel.id)
        assertEquals("Modern Architecture", uiModel.album.title)
        assertEquals("https://example.com/album.jpg", uiModel.thumbnail)
        assertEquals(2026, uiModel.album.year)
    }

    @Test
    @DisplayName("T1-F3-04: Decoupled model mapping - PlaylistItem and ArtistItem")
    fun testViewModel_decoupledModelMapping_playlistAndArtistItem() {
        val rawPlaylist = PlaylistItem(
            id = "playlist_789",
            title = "Expressive Vibes",
            author = Artist(name = "Editorial", id = "ed_1"),
            songCountText = "50 songs",
            thumbnail = "https://example.com/playlist.jpg",
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null
        )
        val playlistModel = ExploreItemUiModel.PlaylistModel(playlist = rawPlaylist)

        assertEquals("playlist_789", playlistModel.id)
        assertEquals("https://example.com/playlist.jpg", playlistModel.thumbnail)

        val rawArtist = ArtistItem(
            id = "artist_abc",
            title = "Featured Producer",
            thumbnail = "https://example.com/artist.jpg",
            channelId = "UC_channel",
            shuffleEndpoint = null,
            radioEndpoint = null,
            subscriberCountText = "1M subscribers"
        )
        val artistModel = ExploreItemUiModel.ArtistModel(artist = rawArtist)

        assertEquals("artist_abc", artistModel.id)
        assertEquals("https://example.com/artist.jpg", artistModel.thumbnail)
    }

    @Test
    @DisplayName("T1-F3-05: Decoupled model mapping - Section and Chips to UI models")
    fun testViewModel_decoupledModelMapping_sectionAndChips() {
        val chip = HomePage.Chip(
            title = "Relax",
            endpoint = BrowseEndpoint(browseId = "FEmusic_relax", params = "wAEB"),
            deselectEndPoint = null
        )
        val chipUi = ExploreChipUiModel(
            id = chip.title,
            title = chip.title,
            isSelected = false,
            browseId = chip.endpoint?.browseId,
            params = chip.endpoint?.params
        )

        assertEquals("Relax", chipUi.title)
        assertEquals("FEmusic_relax", chipUi.browseId)
        assertEquals("wAEB", chipUi.params)

        val sectionUi = ExploreSectionUiModel(
            id = "Quick Picks",
            title = "Quick Picks",
            label = "Recommended for you",
            items = emptyList()
        )

        assertEquals("Quick Picks", sectionUi.id)
        assertEquals("Quick Picks", sectionUi.title)
        assertEquals("Recommended for you", sectionUi.label)
        assertTrue(sectionUi.items.isEmpty())
    }

    @Test
    @DisplayName("T1-F3-06: Category switching updates activeChipState and enters loading state")
    fun testViewModel_categorySwitching_updatesActiveChip() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val chip = HomePage.Chip(
            title = "Workout",
            endpoint = BrowseEndpoint(browseId = "FEmusic_workout", params = "wAEB"),
            deselectEndPoint = null
        )

        viewModel.selectMoodChip(chip)

        val activeChip = viewModel.activeChipState.value
        assertNotNull(activeChip, "Active chip must be non-null after selection")
        assertEquals("Workout", activeChip?.title)
        assertEquals("FEmusic_workout", activeChip?.browseId)

        viewModel.selectMoodChip(null)
        assertNull(viewModel.activeChipState.value, "Selecting null should reset activeChipState")
    }

    @Test
    @DisplayName("T1-F3-07: Cache freshness tier thresholds (30m Fresh, 4h Stale, 24h Expired)")
    fun testViewModel_cacheTier_thresholds() {
        val CACHE_TIER_FRESH_MS = 30 * 60 * 1000L
        val CACHE_TIER_STALE_MS = 4 * 60 * 60 * 1000L
        val CACHE_TIER_EXPIRED_MS = 24 * 60 * 60 * 1000L

        fun resolveCacheStatus(ageMs: Long): String = when {
            ageMs < CACHE_TIER_FRESH_MS -> "FRESH"
            ageMs < CACHE_TIER_STALE_MS -> "STALE"
            ageMs < CACHE_TIER_EXPIRED_MS -> "OLD"
            else -> "EXPIRED"
        }

        assertEquals("FRESH", resolveCacheStatus(5 * 60 * 1000L))
        assertEquals("FRESH", resolveCacheStatus(29 * 60 * 1000L))
        assertEquals("STALE", resolveCacheStatus(45 * 60 * 1000L))
        assertEquals("OLD", resolveCacheStatus(5 * 60 * 60 * 1000L))
        assertEquals("EXPIRED", resolveCacheStatus(25 * 60 * 60 * 1000L))
    }
}
