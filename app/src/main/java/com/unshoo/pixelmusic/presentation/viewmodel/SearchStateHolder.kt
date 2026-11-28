package com.unshoo.pixelmusic.presentation.viewmodel

import com.unshoo.pixelmusic.data.model.Album
import com.unshoo.pixelmusic.data.model.Artist
import com.unshoo.pixelmusic.data.model.Playlist
import com.unshoo.pixelmusic.data.model.SearchFilterType
import com.unshoo.pixelmusic.data.model.SearchHistoryItem
import com.unshoo.pixelmusic.data.model.SearchResultItem
import com.unshoo.pixelmusic.data.remote.youtube.toNativeSong
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SearchSuggestions
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.YTItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.filterVideo
import unshoo.ianshulyadav.pixelmusic.innertube.pages.PageHelper
import unshoo.ianshulyadav.pixelmusic.innertube.pages.PlaylistPage
import unshoo.ianshulyadav.pixelmusic.innertube.pages.SearchResult
import unshoo.ianshulyadav.pixelmusic.innertube.pages.SearchSummaryPage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.FlowPreview

import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.preferences.SearchSource
import com.unshoo.pixelmusic.data.remote.saavn.SaavnService
import com.unshoo.pixelmusic.data.remote.saavn.SaavnSong
import com.unshoo.pixelmusic.utils.ContentFilterUtils
import com.unshoo.pixelmusic.data.repository.MusicRepository
import kotlinx.coroutines.flow.first

internal interface SearchRemoteDataSource {
    suspend fun searchYouTubeSummary(query: String): Result<SearchSummaryPage>
    suspend fun searchYouTube(query: String, filter: YouTube.SearchFilter): Result<SearchResult>
    suspend fun searchYouTubeContinuation(token: String): Result<SearchResult>
    suspend fun searchYouTubeSuggestions(query: String): Result<SearchSuggestions>
    suspend fun fetchPlaylistPage(playlistId: String): Result<PlaylistPage>
    suspend fun searchSaavnSongs(query: String, limit: Int): Result<List<SaavnSong>>
    suspend fun prefetchSongUrl(context: android.content.Context, song: com.unshoo.pixelmusic.data.model.youtube.Song)
}

private object DefaultSearchRemoteDataSource : SearchRemoteDataSource {
    override suspend fun searchYouTubeSummary(query: String): Result<SearchSummaryPage> =
        YouTube.searchSummary(query)

    override suspend fun searchYouTube(query: String, filter: YouTube.SearchFilter): Result<SearchResult> =
        YouTube.search(query, filter)

    override suspend fun searchYouTubeContinuation(token: String): Result<SearchResult> =
        YouTube.searchContinuation(token)

    override suspend fun searchYouTubeSuggestions(query: String): Result<SearchSuggestions> =
        YouTube.searchSuggestions(query)

    override suspend fun fetchPlaylistPage(playlistId: String): Result<PlaylistPage> =
        YouTube.playlist(playlistId)

    override suspend fun searchSaavnSongs(query: String, limit: Int): Result<List<SaavnSong>> =
        SaavnService.searchSongs(query, limit = limit)

    override suspend fun prefetchSongUrl(
        context: android.content.Context,
        song: com.unshoo.pixelmusic.data.model.youtube.Song
    ) {
        com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.getSongPlayerUrl(
            context = context,
            song = song,
            allowLocal = false
        )
    }
}

@Singleton
class SearchStateHolder internal constructor(
    private val appContext: android.content.Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val musicRepository: MusicRepository,
    private val remoteDataSource: SearchRemoteDataSource,
    private val debounceMs: Long = SEARCH_DEBOUNCE_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext appContext: android.content.Context,
        userPreferencesRepository: UserPreferencesRepository,
        musicRepository: MusicRepository,
    ) : this(
        appContext = appContext,
        userPreferencesRepository = userPreferencesRepository,
        musicRepository = musicRepository,
        remoteDataSource = DefaultSearchRemoteDataSource,
    )

    companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val SEARCH_CACHE_SIZE = 150
        const val PLAYLIST_COUNT_CACHE_SIZE = 200
        const val MAX_PLAYLIST_COUNT_HYDRATIONS = 15
        val albumIdMap = java.util.concurrent.ConcurrentHashMap<Long, String>()
    }

    private data class SearchCacheEntry(
        val items: ImmutableList<SearchResultItem>,
        val continuationToken: String? = null,
    )

    private val searchResultCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, SearchCacheEntry>(SEARCH_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchCacheEntry>?): Boolean =
                size > SEARCH_CACHE_SIZE
        }
    )

    private val playlistSongCountCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Int>(PLAYLIST_COUNT_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
                size > PLAYLIST_COUNT_CACHE_SIZE
        }
    )

    private data class SearchRequest(
        val query: String,
        val filter: SearchFilterType,
        val requestId: Long,
        val immediate: Boolean = false,
        val bypassCache: Boolean = false,
    )

    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val searchResults = _searchResults.asStateFlow()

    private val _selectedSearchFilter = MutableStateFlow(SearchFilterType.ALL)
    val selectedSearchFilter = _selectedSearchFilter.asStateFlow()

    private val _searchHistory = MutableStateFlow<ImmutableList<SearchHistoryItem>>(persistentListOf())
    val searchHistory = _searchHistory.asStateFlow()

    private val searchRequests = MutableStateFlow<SearchRequest?>(null)
    private val latestSearchRequestId = AtomicLong(0L)

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null

    private var lastContinuationToken: String? = null
    private var activeSearchQuery: String? = null
    private var activeFilterType: SearchFilterType = SearchFilterType.ALL
    private var lastDispatchedQuery: String? = null
    private var lastDispatchedFilter: SearchFilterType? = null
    private var isLoadingMore = false

    private fun cacheKey(
        query: String,
        filter: SearchFilterType,
        source: SearchSource,
        pureYtMusic: Boolean,
        hideVideo: Boolean
    ): String =
        "${source.name}:${YouTube.locale}:${filter.name}:${pureYtMusic}:${hideVideo}:${query.lowercase().trim()}"

    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeSearchRequests()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchRequests() {
        searchJob?.cancel()
        searchJob = scope?.launch(start = CoroutineStart.UNDISPATCHED) {
            searchRequests
                .filterNotNull()
                .debounce { request -> if (request.immediate || request.query.isBlank()) 0L else debounceMs }
                .collectLatest { request ->
                    val query = request.query
                    val currentFilter = request.filter
                    if (query.isBlank()) {
                        _searchResults.value = persistentListOf()
                        _isSearching.value = false
                        lastContinuationToken = null
                        return@collectLatest
                    }

                    val isLocalMode = userPreferencesRepository.localModeEnabledFlow.first()
                    val source = if (isLocalMode) SearchSource.LOCAL else userPreferencesRepository.searchSourceFlow.first()
                    val pureYtMusic = userPreferencesRepository.pureYtMusicOnlyFlow.first()
                    val hideVideo = userPreferencesRepository.hideVideoFlow.first()
                    val key = cacheKey(query, currentFilter, source, pureYtMusic, hideVideo)
                    val cached = if (!request.bypassCache && source != SearchSource.LOCAL) searchResultCache[key] else null
                    if (cached != null && cached.items.isNotEmpty()) {
                        val hydratedCached = applyCachedPlaylistCounts(cached.items)
                        if (request.requestId == latestSearchRequestId.get()) {
                            _searchResults.value = hydratedCached
                            lastContinuationToken = cached.continuationToken
                            _isSearching.value = false
                            if (hydratedCached !== cached.items) {
                                searchResultCache.put(key, cached.copy(items = hydratedCached))
                            }
                            schedulePlaylistCountHydration(hydratedCached, request.requestId, key)
                        }
                        return@collectLatest
                    }

                    _isSearching.value = true

                    if (source == SearchSource.JIOSAAVN && currentFilter != SearchFilterType.PODCASTS) {
                        try {
                            val results = withContext(ioDispatcher) {
                                var saavnSongs = remoteDataSource.searchSaavnSongs(query, 30)
                                    .getOrDefault(emptyList())
                                    .mapNotNull { it.toPlayableSong() }
                                if (saavnSongs.isEmpty()) {
                                    val suggestion = remoteDataSource.searchYouTubeSuggestions(query)
                                        .getOrNull()
                                        ?.queries
                                        ?.firstOrNull { !it.equals(query, ignoreCase = true) }
                                    if (!suggestion.isNullOrBlank()) {
                                        saavnSongs = remoteDataSource.searchSaavnSongs(suggestion, 30)
                                            .getOrDefault(emptyList())
                                            .mapNotNull { it.toPlayableSong() }
                                    }
                                }
                                if (saavnSongs.isNotEmpty()) {
                                    saavnSongs.map { SearchResultItem.SongItem(it) }
                                } else {
                                    searchLocalWithFilter(query, currentFilter, isLocalMode = false, pureYtMusic = pureYtMusic, hideVideo = hideVideo)
                                }
                            }
                            if (request.requestId == latestSearchRequestId.get()) {
                                val immutable = results.toImmutableList()
                                _searchResults.value = immutable
                                lastContinuationToken = null
                                _isSearching.value = false
                                if (immutable.isNotEmpty()) {
                                    searchResultCache.put(key, SearchCacheEntry(immutable))
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (request.requestId == latestSearchRequestId.get()) {
                                Timber.e(e, "JioSaavn search error: $query")
                                _searchResults.value = persistentListOf()
                                _isSearching.value = false
                            }
                        }
                        return@collectLatest
                    }
                    if (source == SearchSource.LOCAL) {
                        try {
                            val finalResults = searchLocalWithFilter(
                                query = query,
                                filter = currentFilter,
                                isLocalMode = isLocalMode,
                                pureYtMusic = pureYtMusic,
                                hideVideo = hideVideo,
                            )
                            if (request.requestId == latestSearchRequestId.get()) {
                                val immutable = finalResults.toImmutableList()
                                _searchResults.value = immutable
                                lastContinuationToken = null
                                _isSearching.value = false
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (request.requestId == latestSearchRequestId.get()) {
                                Timber.e(e, "Local search error: $query")
                                _searchResults.value = persistentListOf()
                                _isSearching.value = false
                            }
                        }
                        return@collectLatest
                    }

                    try {
                        val results = withContext(ioDispatcher) {
                            val attempt = searchYouTube(query, currentFilter)
                            var remote = attempt.items
                            if (remote.isEmpty() && attempt.hadFailure) {
                                // One short retry when a request threw (e.g. cold visitor session reset).
                                kotlinx.coroutines.delay(250L)
                                remote = searchYouTube(query, currentFilter).items
                            }
                            if (remote.isEmpty()) {
                                searchLocalWithFilter(
                                    query = query,
                                    filter = currentFilter,
                                    isLocalMode = false,
                                    pureYtMusic = pureYtMusic,
                                    hideVideo = hideVideo,
                                )
                            } else {
                                remote
                            }
                        }
                        if (request.requestId == latestSearchRequestId.get()) {
                            val immutable = results.toImmutableList()
                            _searchResults.value = immutable
                            _isSearching.value = false
                            // Never cache "nothing": a failed search must not stick for this query.
                            if (immutable.isNotEmpty()) {
                                searchResultCache.put(key, SearchCacheEntry(immutable, lastContinuationToken))
                                schedulePlaylistCountHydration(immutable, request.requestId, key)
                            }

                            scope?.launch(ioDispatcher) {
                                try {
                                    kotlinx.coroutines.delay(800L)
                                    if (request.requestId != latestSearchRequestId.get()) return@launch
                                    val topSongs = immutable.filterIsInstance<SearchResultItem.SongItem>().take(2)
                                    topSongs.forEach { topItem ->
                                        if (request.requestId != latestSearchRequestId.get()) return@forEach
                                        val song = topItem.song
                                        val yId = song.youtubeId
                                            ?: song.id.removePrefix("youtube_").takeIf { it != song.id }
                                            ?: song.contentUriString.removePrefix("youtube://").takeIf { it != song.contentUriString }
                                        if (!yId.isNullOrBlank()) {
                                            val ytSong = com.unshoo.pixelmusic.data.model.youtube.Song(
                                                youtubeId = yId,
                                                title = song.title,
                                                artist = song.artist,
                                                album = song.album,
                                                duration = if (song.duration > 0) (song.duration / 1000).toString() else "",
                                                thumbnailHref = song.albumArtUriString.orEmpty()
                                            )
                                            remoteDataSource.prefetchSongUrl(appContext, ytSong)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (request.requestId == latestSearchRequestId.get()) {
                            Timber.e(e, "YouTube search error: $query")
                            _searchResults.value = persistentListOf()
                            _isSearching.value = false
                        }
                    }
                }
        }
    }

    private suspend fun searchLocalWithFilter(
        query: String,
        filter: SearchFilterType,
        isLocalMode: Boolean,
        pureYtMusic: Boolean,
        hideVideo: Boolean,
    ): List<SearchResultItem> {
        val libraryResults = musicRepository.searchAllOnce(query, filter)
        val results = if (isLocalMode) {
            withContext(ioDispatcher) {
                libraryResults.filter { item ->
                    item !is SearchResultItem.SongItem ||
                        item.song.isLocal ||
                        com.unshoo.pixelmusic.utils.OfflineAudioResolver.hasOfflineAudio(appContext, item.song)
                }
            }
        } else {
            libraryResults
        }
        val shouldFilterVideos = if (filter == SearchFilterType.VIDEOS || filter == SearchFilterType.PODCASTS) {
            false
        } else {
            pureYtMusic || hideVideo
        }
        return if (shouldFilterVideos) {
            results.filterNot { it is SearchResultItem.SongItem && ContentFilterUtils.isVideoSong(it.song) }
        } else {
            results
        }
    }

    /**
     * BUGFIX (search): shared mapping from an InnerTube YTItem to PixelMusic's UI-facing
     * SearchResultItem. Extracted from loadMoreSearch()'s inline when-block so the same,
     * already-correct logic can also drive the "All" tab below (previously that tab hand-rolled
     * its own 3-way parallel-search-and-interleave instead of using YouTube's own ranked
     * summary, and had no shared mapper).
     */
    private fun YTItem.toSearchResultItem(filterVideos: Boolean): SearchResultItem? =
        // One odd item (missing ids/artists) must not throw away the whole result page.
        runCatching { toSearchResultItemUnsafe(filterVideos) }
            .onFailure { Timber.w(it, "Skipping unmappable search item ${this.id}") }
            .getOrNull()

    private fun YTItem.toSearchResultItemUnsafe(filterVideos: Boolean): SearchResultItem? =
        when (this) {
            is SongItem -> {
                val nativeSong = toNativeSong()
                val isMusicVideo = this.isMusicVideo ||
                    com.unshoo.pixelmusic.utils.ContentFilterUtils.isVideoSong(nativeSong)
                if (!filterVideos || !isMusicVideo) {
                    SearchResultItem.SongItem(nativeSong)
                } else {
                    null
                }
            }
            is ArtistItem -> SearchResultItem.ArtistItem(
                Artist(id = ytArtistId(title), name = title, songCount = 0, imageUrl = thumbnail, channelId = id)
            )
            is AlbumItem -> {
                val longId = browseId.hashCode().toLong()
                albumIdMap[longId] = browseId
                AlbumIdMapper.putMapping(appContext, longId, browseId)
                SearchResultItem.AlbumItem(
                    Album(id = longId, title = title,
                        artist = artists?.joinToString { it.name }.orEmpty(),
                        year = year ?: 0, dateAdded = System.currentTimeMillis(),
                        albumArtUriString = thumbnail, songCount = 0)
                )
            }
            is PlaylistItem -> {
                val normalizedId = id.removePrefix("VL")
                val parsedCount = PageHelper.parsePlaylistSongCount(songCountText)
                    ?: playlistSongCountCache[normalizedId]
                if (parsedCount != null) {
                    playlistSongCountCache[normalizedId] = parsedCount
                }
                SearchResultItem.PlaylistItem(
                    Playlist(
                        id = id,
                        name = title,
                        songIds = emptyList(),
                        coverImageUri = thumbnail,
                        source = "YOUTUBE",
                        displaySongCount = parsedCount,
                    )
                )
            }
        }

    fun loadMoreSearch() {
        val token = lastContinuationToken
        val query = activeSearchQuery
        val filter = activeFilterType
        if (token == null || query == null || isLoadingMore) return

        isLoadingMore = true
        val requestIdAtStart = latestSearchRequestId.get()
        scope?.launch(ioDispatcher) {
            try {
                if (userPreferencesRepository.localModeEnabledFlow.first()) return@launch
                val result = remoteDataSource.searchYouTubeContinuation(token).getOrNull()
                if (result != null && requestIdAtStart == latestSearchRequestId.get()) {
                    val pureYtMusicOnly = userPreferencesRepository.pureYtMusicOnlyFlow.first()
                    val hideVideo = userPreferencesRepository.hideVideoFlow.first()
                    val shouldFilterVideos = if (filter == SearchFilterType.VIDEOS || filter == SearchFilterType.PODCASTS) {
                        false
                    } else {
                        pureYtMusicOnly || hideVideo
                    }
                    val newItems = result.items.mapNotNull { it.toSearchResultItem(shouldFilterVideos) }
                    if (newItems.isNotEmpty() && requestIdAtStart == latestSearchRequestId.get()) {
                        val currentList = _searchResults.value
                        val updatedList = (currentList + newItems).toImmutableList()
                        _searchResults.value = updatedList
                        lastContinuationToken = result.continuation
                        schedulePlaylistCountHydration(newItems, requestIdAtStart)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error loading more search results")
            } finally {
                isLoadingMore = false
            }
        }
    }

    private fun applyCachedPlaylistCounts(items: ImmutableList<SearchResultItem>): ImmutableList<SearchResultItem> {
        var changed = false
        val updated = items.map { item ->
            if (item is SearchResultItem.PlaylistItem &&
                item.playlist.source.equals("YOUTUBE", ignoreCase = true) &&
                item.playlist.displaySongCount == null
            ) {
                val cachedCount = playlistSongCountCache[item.playlist.id.removePrefix("VL")]
                if (cachedCount != null && cachedCount > 0) {
                    changed = true
                    item.copy(playlist = item.playlist.copy(displaySongCount = cachedCount))
                } else {
                    item
                }
            } else {
                item
            }
        }
        return if (changed) updated.toImmutableList() else items
    }

    private fun resolvePlaylistSongCount(page: PlaylistPage): Int? {
        val fromHeader = PageHelper.parsePlaylistSongCount(page.playlist.songCountText, allowBareDigits = true)
        return when {
            fromHeader != null && fromHeader > 0 -> fromHeader
            page.songs.isNotEmpty() -> page.songs.size
            else -> null
        }
    }

    private fun schedulePlaylistCountHydration(
        items: List<SearchResultItem>,
        requestId: Long,
        cacheKey: String? = null,
    ) {
        val playlistsToResolve = items
            .asSequence()
            .filterIsInstance<SearchResultItem.PlaylistItem>()
            .map { it.playlist }
            .filter {
                it.source.equals("YOUTUBE", ignoreCase = true) &&
                    it.displaySongCount == null &&
                    it.songIds.isEmpty() &&
                    playlistSongCountCache[it.id.removePrefix("VL")] == null
            }
            .map { it.id }
            .distinctBy { it.removePrefix("VL") }
            .take(MAX_PLAYLIST_COUNT_HYDRATIONS)
            .toList()
        if (playlistsToResolve.isEmpty()) return

        scope?.launch(ioDispatcher) {
            try {
                val semaphore = Semaphore(4)
                val resolvedAny = AtomicBoolean(false)
                coroutineScope {
                    playlistsToResolve.map { playlistId ->
                        async {
                            semaphore.withPermit {
                                if (requestId != latestSearchRequestId.get()) return@async
                                val page = remoteDataSource.fetchPlaylistPage(playlistId).getOrNull() ?: return@async
                                val count = resolvePlaylistSongCount(page) ?: return@async
                                playlistSongCountCache[playlistId.removePrefix("VL")] = count
                                resolvedAny.set(true)
                            }
                        }
                    }.awaitAll()
                }
                if (!resolvedAny.get() || requestId != latestSearchRequestId.get()) return@launch
                _searchResults.update { current ->
                    if (requestId == latestSearchRequestId.get()) applyCachedPlaylistCounts(current) else current
                }
                if (cacheKey != null) {
                    searchResultCache[cacheKey]?.let { entry ->
                        val updated = applyCachedPlaylistCounts(entry.items)
                        if (updated !== entry.items) {
                            searchResultCache.put(cacheKey, entry.copy(items = updated))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "Playlist song count hydration skipped")
            }
        }
    }

    private data class RemoteSearchOutcome(
        val items: List<SearchResultItem>,
        val hadFailure: Boolean,
    )

    private fun itemKey(item: SearchResultItem): String = when (item) {
        is SearchResultItem.SongItem -> "song_${item.song.id}"
        is SearchResultItem.ArtistItem -> "artist_${item.artist.id}"
        is SearchResultItem.AlbumItem -> "album_${item.album.id}"
        is SearchResultItem.PlaylistItem -> "playlist_${item.playlist.id}"
    }

    private suspend fun searchYouTube(
        query: String,
        filter: SearchFilterType,
        allowSuggestionFallback: Boolean = true,
    ): RemoteSearchOutcome {
        val pureYtMusicOnly = userPreferencesRepository.pureYtMusicOnlyFlow.first()
        val hideVideo = userPreferencesRepository.hideVideoFlow.first()
        val shouldFilterVideos = if (filter == SearchFilterType.VIDEOS || filter == SearchFilterType.PODCASTS) {
            false
        } else {
            pureYtMusicOnly || hideVideo
        }
        val items = mutableListOf<SearchResultItem>()
        val seenKeys = mutableSetOf<String>()
        var hadFailure = false

        activeSearchQuery = query
        activeFilterType = filter
        lastContinuationToken = null

        fun addUnique(mapped: SearchResultItem?) {
            if (mapped != null && seenKeys.add(itemKey(mapped))) {
                items.add(mapped)
            }
        }

        when (filter) {
            SearchFilterType.ALL -> {
                val summaryAttempt = remoteDataSource.searchYouTubeSummary(query)
                    .onFailure {
                        hadFailure = true
                        Timber.w(it, "searchSummary error for '$query'")
                    }
                val summaryResult = summaryAttempt.getOrNull()
                if (summaryResult != null && summaryResult.summaries.isNotEmpty()) {
                    val filteredSummary = if (shouldFilterVideos) summaryResult.filterVideo(true) else summaryResult
                    filteredSummary.summaries.forEach { summary ->
                        summary.items.forEach { ytItem ->
                            addUnique(ytItem.toSearchResultItem(shouldFilterVideos))
                        }
                    }
                }
                // The summary can fail, come back in a layout the parser does not know, or hold
                // only video shelves that "hide videos" removes. Fall back to both Songs and
                // Artists in parallel so either a song or artist search still produces results.
                if (items.isEmpty()) {
                    if (summaryResult == null) Timber.w("searchSummary failed for '$query', falling back to Songs & Artists search")
                    val (songsResult, artistsResult) = coroutineScope {
                        val songsDeferred = async {
                            remoteDataSource.searchYouTube(query, YouTube.SearchFilter.FILTER_SONG)
                                .onFailure { hadFailure = true }
                                .getOrNull()
                        }
                        val artistsDeferred = async {
                            remoteDataSource.searchYouTube(query, YouTube.SearchFilter.FILTER_ARTIST)
                                .onFailure { hadFailure = true }
                                .getOrNull()
                        }
                        songsDeferred.await() to artistsDeferred.await()
                    }
                    lastContinuationToken = songsResult?.continuation ?: artistsResult?.continuation
                    val mappedArtists = artistsResult?.items
                        ?.filterIsInstance<ArtistItem>()
                        ?.mapNotNull { it.toSearchResultItem(false) }
                        .orEmpty()
                    val mappedSongs = songsResult?.items
                        ?.filterIsInstance<SongItem>()
                        ?.let { list -> if (shouldFilterVideos) list.filterVideo(true) else list }
                        ?.mapNotNull { it.toSearchResultItem(shouldFilterVideos) }
                        .orEmpty()

                    // Prioritize close artist matches near the top when the query looks like an artist search.
                    val normalizedQuery = query.trim().lowercase()
                    val (closeArtists, otherArtists) = mappedArtists.partition { item ->
                        item is SearchResultItem.ArtistItem && (
                            item.artist.name.lowercase().contains(normalizedQuery) ||
                                normalizedQuery.contains(item.artist.name.lowercase())
                            )
                    }
                    closeArtists.take(2).forEach(::addUnique)
                    mappedSongs.forEach(::addUnique)
                    closeArtists.drop(2).forEach(::addUnique)
                    otherArtists.take(3).forEach(::addUnique)
                }
                if (items.isEmpty() && !shouldFilterVideos) {
                    val videosResult = remoteDataSource.searchYouTube(query, YouTube.SearchFilter.FILTER_VIDEO)
                        .onFailure { hadFailure = true }
                        .getOrNull()
                    lastContinuationToken = videosResult?.continuation
                    videosResult?.items?.forEach { ytItem ->
                        addUnique(ytItem.toSearchResultItem(false))
                    }
                }
            }
            SearchFilterType.SONGS -> {
                val result = remoteDataSource.searchYouTube(query, YouTube.SearchFilter.FILTER_SONG)
                    .onFailure { hadFailure = true }
                    .getOrNull()
                lastContinuationToken = result?.continuation
                result?.items?.filterIsInstance<SongItem>()
                    ?.let { list -> if (shouldFilterVideos) list.filterVideo(true) else list }
                    ?.forEach { songItem ->
                        addUnique(songItem.toSearchResultItem(shouldFilterVideos))
                    }
            }
            SearchFilterType.ARTISTS -> {
                val result = remoteDataSource.searchYouTube(query, YouTube.SearchFilter.FILTER_ARTIST)
                    .onFailure { hadFailure = true }
                    .getOrNull()
                lastContinuationToken = result?.continuation
                result?.items?.filterIsInstance<ArtistItem>()?.forEach { a ->
                    addUnique(a.toSearchResultItem(false))
                }
            }
            SearchFilterType.ALBUMS -> {
                val result = remoteDataSource.searchYouTube(query, YouTube.SearchFilter.FILTER_ALBUM)
                    .onFailure { hadFailure = true }
                    .getOrNull()
                lastContinuationToken = result?.continuation
                result?.items?.filterIsInstance<AlbumItem>()?.forEach { a ->
                    addUnique(a.toSearchResultItem(false))
                }
            }
            SearchFilterType.PLAYLISTS -> {
                val (featuredResult, communityResult) = coroutineScope {
                    val featuredDeferred = async {
                        remoteDataSource.searchYouTube(query, YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST)
                            .onFailure { hadFailure = true }
                            .getOrNull()
                    }
                    val communityDeferred = async {
                        remoteDataSource.searchYouTube(query, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST)
                            .onFailure { hadFailure = true }
                            .getOrNull()
                    }
                    featuredDeferred.await() to communityDeferred.await()
                }
                lastContinuationToken = featuredResult?.continuation ?: communityResult?.continuation
                featuredResult?.items?.filterIsInstance<PlaylistItem>()?.forEach {
                    addUnique(it.toSearchResultItem(false))
                }
                communityResult?.items?.filterIsInstance<PlaylistItem>()?.forEach {
                    addUnique(it.toSearchResultItem(false))
                }
            }
            SearchFilterType.VIDEOS -> {
                val result = remoteDataSource.searchYouTube(query, YouTube.SearchFilter.FILTER_VIDEO)
                    .onFailure { hadFailure = true }
                    .getOrNull()
                lastContinuationToken = result?.continuation
                result?.items?.filterIsInstance<SongItem>()?.forEach {
                    addUnique(it.toSearchResultItem(false))
                }
            }
            SearchFilterType.PODCASTS -> {
                val podcastBatch = com.unshoo.pixelmusic.data.remote.youtube.PodcastSearchHelper.search(
                    query = query,
                    searchByFilter = remoteDataSource::searchYouTube,
                    searchSummary = remoteDataSource::searchYouTubeSummary,
                )
                hadFailure = podcastBatch.hadFailure
                lastContinuationToken = podcastBatch.continuation
                podcastBatch.items.forEach { ytItem ->
                    addUnique(ytItem.toSearchResultItem(filterVideos = false))
                }
            }
        }

        // Typo recovery: when direct search returns nothing, ask YouTube's autocomplete/suggestions
        // endpoint for recommended items or the closest corrected query.
        if (items.isEmpty() && allowSuggestionFallback) {
            val suggestions = remoteDataSource.searchYouTubeSuggestions(query).getOrNull()
            if (suggestions != null) {
                suggestions.recommendedItems.forEach { recommended ->
                    val matchesFilter = when (filter) {
                        SearchFilterType.ALL -> true
                        SearchFilterType.SONGS -> recommended is SongItem
                        SearchFilterType.ARTISTS -> recommended is ArtistItem
                        SearchFilterType.ALBUMS -> recommended is AlbumItem
                        SearchFilterType.PLAYLISTS -> recommended is PlaylistItem
                        SearchFilterType.VIDEOS -> recommended is SongItem && recommended.isMusicVideo
                        SearchFilterType.PODCASTS -> (recommended is PlaylistItem && (recommended.isPodcast || recommended.id.startsWith("MPSP"))) ||
                            (recommended is SongItem && recommended.isPodcastEpisode)
                    }
                    if (matchesFilter) {
                        addUnique(recommended.toSearchResultItem(shouldFilterVideos))
                    }
                }
                if (items.isEmpty()) {
                    val corrected = suggestions.queries.firstOrNull {
                        it.isNotBlank() && !it.equals(query.trim(), ignoreCase = true)
                    }
                    if (!corrected.isNullOrBlank()) {
                        return searchYouTube(corrected, filter, allowSuggestionFallback = false)
                    }
                }
            }
        }

        return RemoteSearchOutcome(items = items, hadFailure = hadFailure)
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        if (_selectedSearchFilter.value == filterType) return
        _selectedSearchFilter.value = filterType
        activeSearchQuery?.let { query ->
            if (query.isNotBlank()) {
                performSearch(query, immediate = true, force = true)
            }
        }
    }

    fun performSearch(query: String) {
        performSearch(query, immediate = false, force = false)
    }

    fun performSearch(
        query: String,
        immediate: Boolean,
        force: Boolean,
        bypassCache: Boolean = false,
    ) {
        val normalized = query.trim()
        val filter = _selectedSearchFilter.value
        activeSearchQuery = normalized
        if (normalized.isBlank()) {
            latestSearchRequestId.incrementAndGet()
            lastDispatchedQuery = ""
            lastDispatchedFilter = filter
            lastContinuationToken = null
            _searchResults.value = persistentListOf()
            _isSearching.value = false
            searchRequests.value = SearchRequest("", filter, latestSearchRequestId.get(), immediate = true)
            return
        }
        if (!force &&
            normalized == lastDispatchedQuery &&
            filter == lastDispatchedFilter &&
            (_isSearching.value || _searchResults.value.isNotEmpty())
        ) {
            return
        }
        lastDispatchedQuery = normalized
        lastDispatchedFilter = filter
        val requestId = latestSearchRequestId.incrementAndGet()
        if (_searchResults.value.isEmpty() || immediate || bypassCache) {
            _isSearching.value = true
        }
        searchRequests.value = SearchRequest(
            query = normalized,
            filter = filter,
            requestId = requestId,
            immediate = immediate,
            bypassCache = bypassCache,
        )
    }

    fun refreshCurrentSearch() {
        val current = activeSearchQuery?.trim().orEmpty()
        if (current.isNotBlank()) {
            performSearch(current, immediate = true, force = true, bypassCache = true)
        }
    }

    fun loadSearchHistory(limit: Int = 15) {
        scope?.launch {
            val history = musicRepository.getRecentSearchHistory(limit)
            _searchHistory.value = history.toImmutableList()
        }
    }

    fun onSearchQuerySubmitted(query: String) {
        scope?.launch {
            if (query.isNotBlank()) {
                musicRepository.addSearchHistoryItem(query)
                loadSearchHistory()
            }
        }
    }

    fun deleteSearchHistoryItem(query: String) {
        scope?.launch {
            musicRepository.deleteSearchHistoryItemByQuery(query)
            loadSearchHistory()
        }
    }

    fun clearSearchHistory() {
        scope?.launch {
            musicRepository.clearSearchHistory()
            _searchHistory.value = persistentListOf()
        }
    }

    fun onCleared() {
        searchJob?.cancel()
        scope = null
    }

    private fun ytArtistId(name: String): Long =
        -(17_000_000_000_000L + kotlin.math.abs(name.lowercase().hashCode().toLong()))

    private fun ytAlbumId(name: String): Long =
        -(16_000_000_000_000L + kotlin.math.abs(name.lowercase().hashCode().toLong()))
}

/**
 * JioSaavn search hit -> playable [com.unshoo.pixelmusic.data.model.Song]. The song streams
 * straight from JioSaavn's CDN (320 kbps when available); its art uses the largest image.
 */
private fun com.unshoo.pixelmusic.data.remote.saavn.SaavnSong.toPlayableSong(): com.unshoo.pixelmusic.data.model.Song? {
    val streamUrl = com.unshoo.pixelmusic.data.remote.saavn.SaavnService.selectBestUrl(downloadUrl, "320kbps")
        ?: return null
    fun clean(text: String?): String = androidx.core.text.HtmlCompat
        .fromHtml(text.orEmpty(), androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
        .toString()
        .trim()
    val primary = artists.primary.ifEmpty { artists.all }
    val artistName = clean(primary.joinToString(", ") { it.name }).ifBlank { "Unknown Artist" }
    val art = image.lastOrNull { it.url.isNotBlank() }?.url
        ?.replace("150x150", "500x500")?.replace("50x50", "500x500")
    return com.unshoo.pixelmusic.data.model.Song.emptySong().copy(
        id = "saavn_$id",
        title = clean(name),
        artist = artistName,
        album = clean(album?.name),
        contentUriString = streamUrl,
        albumArtUriString = art,
        duration = (duration ?: 0) * 1000L,
        mimeType = "audio/mp4"
    )
}
