package com.unshoo.pixelmusic.presentation.viewmodel

import android.content.Context
import com.unshoo.pixelmusic.data.model.SearchFilterType
import com.unshoo.pixelmusic.data.model.SearchResultItem
import com.unshoo.pixelmusic.data.preferences.SearchSource
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.remote.saavn.SaavnSong
import com.unshoo.pixelmusic.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.Artist as YtArtist
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem as YtArtistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem as YtPlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SearchSuggestions
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem as YtSongItem
import unshoo.ianshulyadav.pixelmusic.innertube.pages.PlaylistPage
import unshoo.ianshulyadav.pixelmusic.innertube.pages.SearchResult
import unshoo.ianshulyadav.pixelmusic.innertube.pages.SearchSummaryPage

@OptIn(ExperimentalCoroutinesApi::class)
class SearchStateHolderTest {

    private lateinit var appContext: Context
    private lateinit var prefs: UserPreferencesRepository
    private lateinit var musicRepository: MusicRepository
    private lateinit var localModeFlow: MutableStateFlow<Boolean>
    private lateinit var searchSourceFlow: MutableStateFlow<SearchSource>
    private lateinit var pureYtMusicFlow: MutableStateFlow<Boolean>
    private lateinit var hideVideoFlow: MutableStateFlow<Boolean>

    @BeforeEach
    fun setup() {
        appContext = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        musicRepository = mockk(relaxed = true)

        localModeFlow = MutableStateFlow(false)
        searchSourceFlow = MutableStateFlow(SearchSource.ONLINE)
        pureYtMusicFlow = MutableStateFlow(false)
        hideVideoFlow = MutableStateFlow(false)

        every { prefs.localModeEnabledFlow } returns localModeFlow
        every { prefs.searchSourceFlow } returns searchSourceFlow
        every { prefs.pureYtMusicOnlyFlow } returns pureYtMusicFlow
        every { prefs.hideVideoFlow } returns hideVideoFlow
        coEvery { musicRepository.searchAllOnce(any(), any()) } returns emptyList()
    }

    @Test
    fun `ALL search falls back to artists and songs when searchSummary fails`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val fakeRemote = FakeRemoteDataSource(
            summaryHandler = { Result.failure(IllegalStateException("summary failed")) },
            searchHandler = { query, filter ->
                when (filter) {
                    YouTube.SearchFilter.FILTER_ARTIST -> Result.success(
                        SearchResult(
                            items = listOf(
                                YtArtistItem(
                                    id = "UC_arijit",
                                    title = "Arijit Singh",
                                    thumbnail = "https://example.com/arijit.jpg",
                                    shuffleEndpoint = null,
                                    radioEndpoint = null,
                                )
                            )
                        )
                    )
                    YouTube.SearchFilter.FILTER_SONG -> Result.success(
                        SearchResult(
                            items = listOf(
                                YtSongItem(
                                    id = "vid_tum_hi_ho",
                                    title = "Tum Hi Ho",
                                    artists = listOf(YtArtist("Arijit Singh", "UC_arijit")),
                                    thumbnail = "https://example.com/song.jpg",
                                )
                            )
                        )
                    )
                    else -> Result.success(SearchResult(emptyList()))
                }
            }
        )

        val holder = SearchStateHolder(appContext, prefs, musicRepository, fakeRemote, debounceMs = 300L, ioDispatcher = dispatcher)
        holder.initialize(scope)

        holder.performSearch("Arijit Singh")
        scope.advanceUntilIdle()

        val results = holder.searchResults.value
        assertFalse(holder.isSearching.value)
        assertTrue(results.any { it is SearchResultItem.ArtistItem && it.artist.name == "Arijit Singh" })
        assertTrue(results.any { it is SearchResultItem.SongItem && it.song.title == "Tum Hi Ho" })
    }

    @Test
    fun `mistyped query recovers via searchSuggestions when direct search returns empty`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val fakeRemote = FakeRemoteDataSource(
            summaryHandler = { query ->
                if (query.equals("Blinding Lights", ignoreCase = true)) {
                    Result.success(
                        SearchSummaryPage(
                            summaries = listOf(
                                unshoo.ianshulyadav.pixelmusic.innertube.pages.SearchSummary(
                                    title = "Songs",
                                    items = listOf(
                                        YtSongItem(
                                            id = "vid_blinding",
                                            title = "Blinding Lights",
                                            artists = listOf(YtArtist("The Weeknd", "UC_weeknd")),
                                            thumbnail = "https://example.com/bl.jpg",
                                        )
                                    )
                                )
                            )
                        )
                    )
                } else {
                    Result.success(SearchSummaryPage(emptyList()))
                }
            },
            searchHandler = { query, filter ->
                if (query.equals("Blinding Lights", ignoreCase = true) && filter == YouTube.SearchFilter.FILTER_SONG) {
                    Result.success(
                        SearchResult(
                            items = listOf(
                                YtSongItem(
                                    id = "vid_blinding",
                                    title = "Blinding Lights",
                                    artists = listOf(YtArtist("The Weeknd", "UC_weeknd")),
                                    thumbnail = "https://example.com/bl.jpg",
                                )
                            )
                        )
                    )
                } else {
                    Result.success(SearchResult(emptyList()))
                }
            },
            suggestionsHandler = {
                Result.success(
                    SearchSuggestions(
                        queries = listOf("Blinding Lights"),
                        recommendedItems = emptyList(),
                    )
                )
            }
        )

        val holder = SearchStateHolder(appContext, prefs, musicRepository, fakeRemote, debounceMs = 300L, ioDispatcher = dispatcher)
        holder.initialize(scope)

        holder.performSearch("blnding lihgts")
        scope.advanceUntilIdle()

        val results = holder.searchResults.value
        assertEquals(1, results.size)
        val song = (results.first() as SearchResultItem.SongItem).song
        assertEquals("Blinding Lights", song.title)
    }

    @Test
    fun `performSearch sets isSearching immediately during debounce so empty state does not flash`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val fakeRemote = FakeRemoteDataSource(
            summaryHandler = {
                Result.success(
                    SearchSummaryPage(
                        summaries = listOf(
                            unshoo.ianshulyadav.pixelmusic.innertube.pages.SearchSummary(
                                title = "Songs",
                                items = listOf(
                                    YtSongItem(
                                        id = "vid_1",
                                        title = "Starboy",
                                        artists = listOf(YtArtist("The Weeknd", "UC_w")),
                                        thumbnail = "https://example.com/s.jpg",
                                    )
                                )
                            )
                        )
                    )
                )
            }
        )

        val holder = SearchStateHolder(appContext, prefs, musicRepository, fakeRemote, debounceMs = 300L, ioDispatcher = dispatcher)
        holder.initialize(scope)

        holder.performSearch("starboy")
        assertTrue(holder.isSearching.value)

        scope.advanceTimeBy(150L)
        scope.runCurrent()
        assertTrue(holder.isSearching.value)
        assertEquals(0, fakeRemote.summaryCalls)

        scope.advanceUntilIdle()
        assertFalse(holder.isSearching.value)
        assertEquals(1, fakeRemote.summaryCalls)
        assertEquals(1, holder.searchResults.value.size)
    }

    @Test
    fun `cached search result is preserved without wiping results on subsequent query return`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        var shouldFail = false
        val fakeRemote = FakeRemoteDataSource(
            summaryHandler = {
                if (shouldFail) {
                    Result.failure(RuntimeException("network down"))
                } else {
                    Result.success(
                        SearchSummaryPage(
                            summaries = listOf(
                                unshoo.ianshulyadav.pixelmusic.innertube.pages.SearchSummary(
                                    title = "Songs",
                                    items = listOf(
                                        YtSongItem(
                                            id = "vid_1",
                                            title = "Creepin",
                                            artists = listOf(YtArtist("Metro Boomin", "UC_m")),
                                            thumbnail = "https://example.com/c.jpg",
                                        )
                                    )
                                )
                            )
                        )
                    )
                }
            }
        )

        val holder = SearchStateHolder(appContext, prefs, musicRepository, fakeRemote, debounceMs = 300L, ioDispatcher = dispatcher)
        holder.initialize(scope)

        holder.performSearch("creepin")
        scope.advanceUntilIdle()
        assertEquals(1, holder.searchResults.value.size)

        holder.performSearch("")
        scope.advanceUntilIdle()
        assertEquals(0, holder.searchResults.value.size)

        shouldFail = true
        holder.performSearch("creepin")
        scope.advanceUntilIdle()
        assertEquals(1, holder.searchResults.value.size)
        assertFalse(holder.isSearching.value)
    }

    @Test
    fun `playlist search parses inline count and hydrates missing counts from playlist page`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val fakeRemote = FakeRemoteDataSource(
            searchHandler = { _, filter ->
                when (filter) {
                    YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST -> Result.success(
                        SearchResult(
                            items = listOf(
                                YtPlaylistItem(
                                    id = "RDCLAK5uy_hits",
                                    title = "Hits of 2026 (So Far)",
                                    author = YtArtist("YouTube Music", null),
                                    songCountText = null,
                                    thumbnail = "https://example.com/hits.jpg",
                                    playEndpoint = null,
                                    shuffleEndpoint = null,
                                    radioEndpoint = null,
                                )
                            )
                        )
                    )
                    YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST -> Result.success(
                        SearchResult(
                            items = listOf(
                                YtPlaylistItem(
                                    id = "PL_inline",
                                    title = "Bairan Inline",
                                    author = YtArtist("Creator", null),
                                    songCountText = "37 songs",
                                    thumbnail = "https://example.com/inline.jpg",
                                    playEndpoint = null,
                                    shuffleEndpoint = null,
                                    radioEndpoint = null,
                                ),
                                YtPlaylistItem(
                                    id = "PL_offline_fail",
                                    title = "Unresolved Playlist",
                                    author = YtArtist("Creator 2", null),
                                    songCountText = null,
                                    thumbnail = "https://example.com/fail.jpg",
                                    playEndpoint = null,
                                    shuffleEndpoint = null,
                                    radioEndpoint = null,
                                ),
                            )
                        )
                    )
                    else -> Result.success(SearchResult(emptyList()))
                }
            },
            playlistHandler = { playlistId ->
                when (playlistId) {
                    "RDCLAK5uy_hits" -> Result.success(
                        PlaylistPage(
                            playlist = YtPlaylistItem(
                                id = "RDCLAK5uy_hits",
                                title = "Hits of 2026 (So Far)",
                                author = YtArtist("YouTube Music", null),
                                songCountText = "50 songs",
                                thumbnail = "https://example.com/hits.jpg",
                                playEndpoint = null,
                                shuffleEndpoint = null,
                                radioEndpoint = null,
                            ),
                            songs = emptyList(),
                            songsContinuation = null,
                            continuation = null,
                        )
                    )
                    else -> Result.failure(RuntimeException("not available"))
                }
            }
        )

        val holder = SearchStateHolder(appContext, prefs, musicRepository, fakeRemote, debounceMs = 300L, ioDispatcher = dispatcher)
        holder.initialize(scope)
        holder.updateSearchFilter(SearchFilterType.PLAYLISTS)
        holder.performSearch("bairan")
        scope.advanceUntilIdle()

        val playlists = holder.searchResults.value.filterIsInstance<SearchResultItem.PlaylistItem>()
        assertEquals(3, playlists.size)
        assertEquals(50, playlists.first { it.playlist.id == "RDCLAK5uy_hits" }.playlist.displaySongCount)
        assertEquals(37, playlists.first { it.playlist.id == "PL_inline" }.playlist.displaySongCount)
        assertNull(playlists.first { it.playlist.id == "PL_offline_fail" }.playlist.displaySongCount)
        // PL_inline already had songCountText, so it should not be fetched remotely.
        assertEquals(2, fakeRemote.fetchedPlaylistIds.size)
        assertEquals(setOf("RDCLAK5uy_hits", "PL_offline_fail"), fakeRemote.fetchedPlaylistIds.toSet())
    }

    private class FakeRemoteDataSource(
        private val summaryHandler: suspend (String) -> Result<SearchSummaryPage> = { Result.success(SearchSummaryPage(emptyList())) },
        private val searchHandler: suspend (String, YouTube.SearchFilter) -> Result<SearchResult> = { _, _ -> Result.success(SearchResult(emptyList())) },
        private val suggestionsHandler: suspend (String) -> Result<SearchSuggestions> = { Result.success(SearchSuggestions(emptyList(), emptyList())) },
        private val playlistHandler: suspend (String) -> Result<PlaylistPage> = { Result.failure(IllegalStateException("unused")) },
        private val saavnHandler: suspend (String, Int) -> Result<List<SaavnSong>> = { _, _ -> Result.success(emptyList()) },
    ) : SearchRemoteDataSource {
        var summaryCalls = 0
            private set
        val fetchedPlaylistIds = mutableListOf<String>()

        override suspend fun searchYouTubeSummary(query: String): Result<SearchSummaryPage> {
            summaryCalls++
            return summaryHandler(query)
        }

        override suspend fun searchYouTube(query: String, filter: YouTube.SearchFilter): Result<SearchResult> =
            searchHandler(query, filter)

        override suspend fun searchYouTubeContinuation(token: String): Result<SearchResult> =
            Result.success(SearchResult(emptyList()))

        override suspend fun searchYouTubeSuggestions(query: String): Result<SearchSuggestions> =
            suggestionsHandler(query)

        override suspend fun fetchPlaylistPage(playlistId: String): Result<PlaylistPage> {
            synchronized(fetchedPlaylistIds) {
                fetchedPlaylistIds.add(playlistId)
            }
            return playlistHandler(playlistId)
        }

        override suspend fun searchSaavnSongs(query: String, limit: Int): Result<List<SaavnSong>> =
            saavnHandler(query, limit)

        override suspend fun prefetchSongUrl(
            context: Context,
            song: com.unshoo.pixelmusic.data.model.youtube.Song
        ) = Unit
    }
}
