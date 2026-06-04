package com.unshoo.pixelmusic.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import timber.log.Timber
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.YTItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem
import unshoo.ianshulyadav.pixelmusic.innertube.pages.ExplorePage
import unshoo.ianshulyadav.pixelmusic.innertube.pages.HomePage
import unshoo.ianshulyadav.pixelmusic.innertube.pages.ChartsPage
import javax.inject.Inject
import kotlinx.coroutines.async

data class ExploreUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isContinuationLoading: Boolean = false,
    val homePageSections: List<HomePage.Section> = emptyList(),
    val homePageContinuation: String? = null,
    val newReleaseAlbums: List<AlbumItem> = emptyList(),
    val chartsPage: ChartsPage? = null,
    val error: String? = null,
    val selectedFilter: String = "All"
)

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val playbackStatsRepository: com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository,
    private val userPreferencesRepository: com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private val explorePrefs by lazy { context.getSharedPreferences("explore_guest_cache", Context.MODE_PRIVATE) }

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                restoreFromCache()
            }
            loadDataInternal(forceRefresh = false)
        }
    }

    private val gson by lazy {
        com.google.gson.GsonBuilder()
            .registerTypeAdapter(YTItem::class.java, YTItemTypeAdapter())
            .create()
    }

    private val cacheFile by lazy {
        java.io.File(context.cacheDir, "explore_cache.json")
    }

    private fun restoreFromCache() {
        try {
            if (cacheFile.exists()) {
                val json = cacheFile.readText()
                val cache = gson.fromJson(json, ExploreCacheModel::class.java)
                _uiState.update {
                    it.copy(
                        isLoading = true, // still loading fresh data
                        homePageSections = cache.sections,
                        homePageContinuation = cache.continuation,
                        newReleaseAlbums = cache.albums,
                        chartsPage = cache.charts
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore explore data from cache")
        }
    }

    private fun persistToCache(state: ExploreUiState) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cache = ExploreCacheModel(
                    sections = state.homePageSections,
                    albums = state.newReleaseAlbums,
                    charts = state.chartsPage,
                    continuation = state.homePageContinuation,
                    timestamp = System.currentTimeMillis()
                )
                val json = gson.toJson(cache)
                cacheFile.writeText(json)
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist explore data to cache")
            }
        }
    }

    fun loadData(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            loadDataInternal(forceRefresh)
        }
    }

    private suspend fun loadDataInternal(forceRefresh: Boolean) = coroutineScope {
        if (forceRefresh) {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
        } else {
            // Only show loading spinner if we have no cached data at all
            val hasCachedData = _uiState.value.homePageSections.isNotEmpty() ||
                    _uiState.value.newReleaseAlbums.isNotEmpty() ||
                    _uiState.value.chartsPage != null
            _uiState.update { it.copy(isLoading = !hasCachedData, error = null) }
        }
        try {
            // 1. Get history and candidateArtistId immediately (fast database/prefs calls)
            val history = withContext(Dispatchers.IO) {
                playbackStatsRepository.loadPlaybackHistory(limit = 15)
            }
            val candidateArtistId = withContext(Dispatchers.IO) {
                userPreferencesRepository.subscribedArtistIdsFlow.first().firstOrNull()
            }

            val userActivityQuery = if (history.isNotEmpty()) {
                val artistCounts = history.mapNotNull { it.artist }.groupingBy { it }.eachCount()
                artistCounts.maxByOrNull { it.value }?.key ?: "Bollywood"
            } else {
                "Bollywood"
            }

            val hasLogin = YouTube.hasLoginCookie()

            // 2. Fetch Explore sections parallelly
            val homeDeferred = async(Dispatchers.IO) { YouTube.home().getOrNull() }
            val exploreDeferred = async(Dispatchers.IO) { YouTube.explore().getOrNull() }
            val chartsDeferred = async(Dispatchers.IO) { YouTube.getChartsPage().getOrNull() }
            val newReleasesDeferred = async(Dispatchers.IO) { YouTube.newReleaseAlbums().getOrNull() }

            // Library / account-based parallel requests (only if logged in)
            val likedAlbumsDeferred: kotlinx.coroutines.Deferred<List<AlbumItem>>? = if (hasLogin) {
                async(Dispatchers.IO) {
                    YouTube.library("FEmusic_liked_albums").getOrNull()?.items?.filterIsInstance<AlbumItem>() ?: emptyList()
                }
            } else null

            val likedArtistsDeferred: kotlinx.coroutines.Deferred<List<ArtistItem>>? = if (hasLogin) {
                async(Dispatchers.IO) {
                    YouTube.library("FEmusic_liked_artists").getOrNull()?.items?.filterIsInstance<ArtistItem>() ?: emptyList()
                }
            } else null

            val recentActivityDeferred: kotlinx.coroutines.Deferred<List<YTItem>>? = if (hasLogin) {
                async(Dispatchers.IO) {
                    YouTube.libraryRecentActivity().getOrNull()?.items ?: emptyList()
                }
            } else null

            val personalPlaylistsDeferred: kotlinx.coroutines.Deferred<List<PlaylistItem>>? = if (hasLogin) {
                async(Dispatchers.IO) {
                    YouTube.library("FEmusic_liked_playlists").getOrNull()?.items?.filterIsInstance<PlaylistItem>() ?: emptyList()
                }
            } else null

            // Search community playlists in parallel
            val communityPlaylistsDeferred = async(Dispatchers.IO) {
                YouTube.search(
                    query = "$userActivityQuery playlist",
                    filter = YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST
                ).getOrNull()
            }

            val cachedArtistBrowseId = explorePrefs.getString("artist_id_${userActivityQuery}", null)
            val resolvedArtistId = candidateArtistId ?: cachedArtistBrowseId

            // Fetch similar artists in parallel if candidate ID exists or we have a cached ID
            val similarArtistPageDeferred: kotlinx.coroutines.Deferred<unshoo.ianshulyadav.pixelmusic.innertube.pages.ArtistPage?>? = if (resolvedArtistId != null) {
                async(Dispatchers.IO) { YouTube.artist(resolvedArtistId).getOrNull() }
            } else null

            // Search artist in parallel if guest search is needed and we don't have a cached ID
            val searchArtistPageDeferred: kotlinx.coroutines.Deferred<Pair<String, unshoo.ianshulyadav.pixelmusic.innertube.pages.ArtistPage?>?>? = if (resolvedArtistId == null && userActivityQuery != "Bollywood") {
                async(Dispatchers.IO) {
                    val searchResult = YouTube.search(userActivityQuery, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                    val artistItem = searchResult?.items?.find { it is ArtistItem } as? ArtistItem
                    val id = artistItem?.id
                    if (id != null) {
                        explorePrefs.edit().putString("artist_id_${userActivityQuery}", id).apply()
                        val artistPage = YouTube.artist(id).getOrNull()
                        Pair(artistItem.title, artistPage)
                    } else null
                }
            } else null

            // 3. Await all results
            val home = homeDeferred.await()
            val explore = exploreDeferred.await()
            val charts = chartsDeferred.await()
            val newReleasesResult = newReleasesDeferred.await()
            val likedAlbums = likedAlbumsDeferred?.await() ?: emptyList()
            val likedArtists = likedArtistsDeferred?.await() ?: emptyList()
            val recentActivityItems = recentActivityDeferred?.await() ?: emptyList()
            val personalPlaylists = personalPlaylistsDeferred?.await() ?: emptyList()
            val communityPlaylistsResult = communityPlaylistsDeferred.await()

            if (home == null && explore == null && charts == null) {
                // Only show error if we also have no cached data
                val hasCachedData = _uiState.value.homePageSections.isNotEmpty() ||
                        _uiState.value.newReleaseAlbums.isNotEmpty() ||
                        _uiState.value.chartsPage != null
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = if (!hasCachedData) "Failed to fetch explore data from YouTube Music. Please check your connection." else null
                    )
                }
            } else {
                // 4. Resolve similar artist section
                var similarSection: HomePage.Section? = null
                var artistNameForSection = ""

                if (similarArtistPageDeferred != null) {
                    val artistPage = similarArtistPageDeferred.await()
                    if (artistPage != null) {
                        artistNameForSection = artistPage.artist.title
                        val rawSimilarSection = artistPage.sections.find {
                            it.title.contains("fans", ignoreCase = true) ||
                            it.title.contains("similar", ignoreCase = true) ||
                            it.title.contains("like", ignoreCase = true)
                        }
                        if (rawSimilarSection != null && rawSimilarSection.items.isNotEmpty()) {
                            similarSection = HomePage.Section(
                                title = "Similar to $artistNameForSection",
                                label = "Based on your activity",
                                thumbnail = null,
                                endpoint = null,
                                items = rawSimilarSection.items.filterIsInstance<ArtistItem>()
                            )
                        }
                    }
                } else if (searchArtistPageDeferred != null) {
                    val pair = searchArtistPageDeferred.await()
                    if (pair != null) {
                        artistNameForSection = pair.first
                        val artistPage = pair.second
                        if (artistPage != null) {
                            val rawSimilarSection = artistPage.sections.find {
                                it.title.contains("fans", ignoreCase = true) ||
                                it.title.contains("similar", ignoreCase = true) ||
                                it.title.contains("like", ignoreCase = true)
                            }
                            if (rawSimilarSection != null && rawSimilarSection.items.isNotEmpty()) {
                                similarSection = HomePage.Section(
                                    title = "Similar to $artistNameForSection",
                                    label = "Based on your activity",
                                    thumbnail = null,
                                    endpoint = null,
                                    items = rawSimilarSection.items.filterIsInstance<ArtistItem>()
                                )
                            }
                        }
                    }
                }

                val communityPlaylists = communityPlaylistsResult?.items?.filterIsInstance<PlaylistItem>() ?: emptyList()

                val rawSections = home?.sections ?: emptyList()
                val updatedSections = rawSections.toMutableList()

                if (personalPlaylists.isNotEmpty()) {
                    updatedSections.removeAll { it.title.contains("trending", ignoreCase = true) }
                    updatedSections.add(0, HomePage.Section(
                        title = "Your Playlists",
                        label = "From your YouTube Music Account",
                        thumbnail = null,
                        endpoint = null,
                        items = personalPlaylists
                    ))
                } else {
                    if (communityPlaylists.isNotEmpty()) {
                        updatedSections.removeAll { it.title.contains("trending", ignoreCase = true) }
                        updatedSections.add(HomePage.Section(
                            title = "Community Playlists",
                            label = "Based on your activity for $userActivityQuery",
                            thumbnail = null,
                            endpoint = null,
                            items = communityPlaylists
                        ))
                    }
                }

                if (recentActivityItems.isNotEmpty()) {
                    updatedSections.add(0, HomePage.Section(
                        title = "Recently Played (YouTube)",
                        label = "From your YouTube Music Account",
                        thumbnail = null,
                        endpoint = null,
                        items = recentActivityItems
                    ))
                }

                if (similarSection != null) {
                    updatedSections.add(0, similarSection)
                }

                if (likedAlbums.isNotEmpty()) {
                    updatedSections.add(HomePage.Section(
                        title = "Your Liked Albums",
                        label = "From your YouTube Music Account",
                        thumbnail = null,
                        endpoint = null,
                        items = likedAlbums
                    ))
                }

                if (likedArtists.isNotEmpty()) {
                    updatedSections.add(HomePage.Section(
                        title = "Your Favorite Artists",
                        label = "From your YouTube Music Account",
                        thumbnail = null,
                        endpoint = null,
                        items = likedArtists
                    ))
                }

                val finalNewReleases = if (!newReleasesResult.isNullOrEmpty()) newReleasesResult else explore?.newReleaseAlbums ?: emptyList()

                val newState = ExploreUiState(
                    isLoading = false,
                    isRefreshing = false,
                    homePageSections = updatedSections,
                    homePageContinuation = home?.continuation,
                    newReleaseAlbums = finalNewReleases,
                    chartsPage = charts,
                    selectedFilter = _uiState.value.selectedFilter
                )
                _uiState.value = newState
                persistToCache(newState)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading Explore screen data")
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = e.localizedMessage ?: "Unknown error occurred"
                )
            }
        }
    }

    fun loadMore() {
        val currentState = _uiState.value
        val continuation = currentState.homePageContinuation
        if (currentState.isContinuationLoading || continuation == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isContinuationLoading = true) }
            try {
                val result = withContext(Dispatchers.IO) {
                    YouTube.home(continuation = continuation).getOrNull()
                }
                if (result != null) {
                    _uiState.update {
                        val newState = it.copy(
                            isContinuationLoading = false,
                            homePageSections = it.homePageSections + result.sections,
                            homePageContinuation = result.continuation
                        )
                        persistToCache(newState)
                        newState
                    }
                } else {
                    _uiState.update { it.copy(isContinuationLoading = false) }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error loading more Explore screen sections")
                _uiState.update { it.copy(isContinuationLoading = false) }
            }
        }
    }

    fun setSelectedFilter(filter: String) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }
}

private data class ExploreCacheModel(
    val sections: List<HomePage.Section>,
    val albums: List<AlbumItem>,
    val charts: ChartsPage?,
    val continuation: String?,
    val timestamp: Long
)

private class YTItemTypeAdapter : com.google.gson.JsonSerializer<YTItem>, com.google.gson.JsonDeserializer<YTItem> {
    override fun serialize(src: YTItem, typeOfSrc: java.lang.reflect.Type, context: com.google.gson.JsonSerializationContext): com.google.gson.JsonElement {
        val obj = context.serialize(src).asJsonObject
        obj.addProperty("type", src::class.java.simpleName)
        return obj
    }

    override fun deserialize(json: com.google.gson.JsonElement, typeOfT: java.lang.reflect.Type, context: com.google.gson.JsonDeserializationContext): YTItem {
        val obj = json.asJsonObject
        val type = obj.get("type").asString
        val clazz = when (type) {
            "SongItem" -> SongItem::class.java
            "AlbumItem" -> AlbumItem::class.java
            "PlaylistItem" -> PlaylistItem::class.java
            "ArtistItem" -> ArtistItem::class.java
            else -> throw com.google.gson.JsonParseException("Unknown type: $type")
        }
        return context.deserialize(obj, clazz)
    }
}
