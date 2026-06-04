package com.unshoo.pixelmusic.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.remote.youtube.toNativeSong
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.preferences.QuickPicks
import com.unshoo.pixelmusic.data.repository.MusicRepository
import unshoo.ianshulyadav.pixelmusic.innertube.models.filterVideo
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.WatchEndpoint
import javax.inject.Inject

private const val PREFS_NAME = "quick_picks_cache"
private const val KEY_SONGS = "songs_json"
private const val KEY_CATEGORIES = "categories_json"
private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
// Cache valid for 4 hours (shorter than before so new releases appear faster)
private const val CACHE_MAX_AGE_MS = 4 * 60 * 60 * 1000L

@HiltViewModel
class QuickPicksViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val musicRepository: MusicRepository,
    private val engagementDao: com.unshoo.pixelmusic.data.database.EngagementDao
) : ViewModel() {

    private val _quickPicks = MutableStateFlow<List<Song>>(emptyList())
    val quickPicks: StateFlow<List<Song>> = _quickPicks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _categories = MutableStateFlow<List<String>>(listOf("All"))
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    init {
        // Immediately populate from cache so the UI shows something on relaunch
        loadFromCache()
        viewModelScope.launch {
            userPreferencesRepository.discoverFlow.collect { _ ->
                if (_selectedCategory.value == "All") {
                    loadQuickPicks("All")
                }
            }
        }
    }

    fun setCategory(category: String) {
        if (_selectedCategory.value == category && !_isLoading.value) return
        _selectedCategory.value = category
        loadQuickPicks(category)
    }

    fun refresh() {
        loadQuickPicks(_selectedCategory.value)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadFromCache() {
        try {
            prefs.getString(KEY_SONGS, null) ?: return
            val songsJson = prefs.getString(KEY_SONGS, null) ?: return
            val categoriesJson = prefs.getString(KEY_CATEGORIES, null)

            val songsArray = JSONArray(songsJson)
            val songs = mutableListOf<Song>()
            for (i in 0 until songsArray.length()) {
                songs.add(songFromJson(songsArray.getJSONObject(i)))
            }
            if (songs.isNotEmpty()) _quickPicks.value = songs

            if (categoriesJson != null) {
                val catArray = JSONArray(categoriesJson)
                val cats = mutableListOf<String>()
                for (i in 0 until catArray.length()) cats.add(catArray.getString(i))
                if (cats.isNotEmpty()) _categories.value = cats
            }
        } catch (e: Exception) {
            Timber.tag("QuickPicks").w(e, "Failed to load cache")
        }
    }

    private fun saveToCache(songs: List<Song>, categories: List<String>) {
        try {
            val songsArray = JSONArray()
            songs.forEach { songsArray.put(songToJson(it)) }
            val catArray = JSONArray()
            categories.forEach { catArray.put(it) }
            prefs.edit()
                .putString(KEY_SONGS, songsArray.toString())
                .putString(KEY_CATEGORIES, catArray.toString())
                .putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Timber.tag("QuickPicks").w(e, "Failed to save cache")
        }
    }

    private fun songToJson(song: Song): JSONObject = JSONObject().apply {
        put("id", song.id)
        put("title", song.title)
        put("artist", song.artist)
        put("artistId", song.artistId)
        put("album", song.album)
        put("albumId", song.albumId)
        put("albumArtist", song.albumArtist ?: "")
        put("path", song.path)
        put("contentUriString", song.contentUriString)
        put("albumArtUriString", song.albumArtUriString ?: "")
        put("duration", song.duration)
        put("genre", song.genre ?: "")
        put("mimeType", song.mimeType ?: "")
        put("bitrate", song.bitrate ?: 0)
        put("sampleRate", song.sampleRate ?: 0)
        put("youtubeId", song.youtubeId ?: "")
        put("albumBrowseId", song.albumBrowseId ?: "")
    }

    private fun songFromJson(obj: JSONObject): Song = Song(
        id = obj.optString("id"),
        title = obj.optString("title"),
        artist = obj.optString("artist", ""),
        artistId = obj.optLong("artistId", 0L),
        album = obj.optString("album", ""),
        albumId = obj.optLong("albumId", 0L),
        albumArtist = obj.optString("albumArtist").takeIf { it.isNotBlank() },
        path = obj.optString("path", ""),
        contentUriString = obj.optString("contentUriString", ""),
        albumArtUriString = obj.optString("albumArtUriString").takeIf { it.isNotBlank() },
        duration = obj.optLong("duration", 0L),
        genre = obj.optString("genre").takeIf { it.isNotBlank() },
        mimeType = obj.optString("mimeType").takeIf { it.isNotBlank() },
        bitrate = obj.optInt("bitrate", 0),
        sampleRate = obj.optInt("sampleRate", 0),
        youtubeId = obj.optString("youtubeId").takeIf { it.isNotBlank() },
        albumBrowseId = obj.optString("albumBrowseId").takeIf { it.isNotBlank() }
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Main load entry point
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadQuickPicks(category: String) {
        viewModelScope.launch {
            if (_quickPicks.value.isEmpty()) {
                _isLoading.value = true
            }
            try {
                if (category == "All") {
                    val discover = userPreferencesRepository.discoverFlow.first()
                    when (discover) {
                        QuickPicks.DONT_SHOW -> {
                            _quickPicks.value = emptyList()
                        }
                        QuickPicks.QUICK_PICKS -> {
                            // Try enhanced 5-bucket algorithm first
                            val songs = loadEnhancedQuickPicks()
                            if (songs.isNotEmpty()) {
                                _quickPicks.value = songs
                                saveToCache(songs, _categories.value)
                            }
                        }
                        QuickPicks.LAST_LISTEN -> {
                            val lastPlayed = withContext(Dispatchers.IO) {
                                musicRepository.getLastPlayedSong()
                            }
                            val related = if (lastPlayed != null) {
                                withContext(Dispatchers.IO) {
                                    val id = lastPlayed.id.toLongOrNull()
                                    if (id != null) musicRepository.getRelatedSongs(id, 50) else emptyList()
                                }
                            } else emptyList()

                            if (related.isNotEmpty()) {
                                val songs = related.shuffled().take(20)
                                _quickPicks.value = songs
                                saveToCache(songs, _categories.value)
                            } else {
                                val songs = loadEnhancedQuickPicks()
                                if (songs.isNotEmpty()) {
                                    _quickPicks.value = songs
                                    saveToCache(songs, _categories.value)
                                }
                            }
                        }
                    }
                } else {
                    loadCategoryQuickPicks(category)
                }
            } catch (e: Exception) {
                Timber.tag("QuickPicks").e(e, "Error loading quick picks")
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5-bucket parallel algorithm — all buckets fetched concurrently
    // Final output: flat shuffled list of ~20 songs
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun loadEnhancedQuickPicks(): List<Song> = coroutineScope {
        val pureYtMusicOnly = userPreferencesRepository.pureYtMusicOnlyFlow.first()

        // Bucket 1: New Releases — latest album songs from YouTube (4 songs)
        val newReleasesDeferred = async(Dispatchers.IO) {
            try {
                val albums = YouTube.newReleaseAlbums().getOrNull() ?: emptyList()
                val songsPool = mutableListOf<Song>()
                for (album in albums.take(6)) {
                    if (songsPool.size >= 4) break
                    val albumPage = YouTube.album(album.browseId).getOrNull() ?: continue
                    val songs = albumPage.songs
                        .filter { item ->
                            if (pureYtMusicOnly) {
                                val mvType = item.endpoint?.watchEndpointMusicSupportedConfigs
                                    ?.watchEndpointMusicConfig?.musicVideoType
                                mvType == "MUSIC_VIDEO_TYPE_ATV" || mvType == null
                            } else true
                        }
                        .take(2)
                        .map { it.toNativeSong() }
                    songsPool.addAll(songs)
                }
                songsPool.distinctBy { it.youtubeId ?: it.id }.take(4)
            } catch (e: Exception) {
                Timber.tag("QuickPicks").w(e, "New releases bucket failed")
                emptyList()
            }
        }

        // Bucket 2: Similar Artist Songs — radio from subscribed/favourite artist (5 songs)
        val similarArtistDeferred = async(Dispatchers.IO) {
            try {
                val subscribedIds = userPreferencesRepository.subscribedArtistIdsFlow.first()
                val artistBrowseId = subscribedIds.firstOrNull() ?: return@async emptyList<Song>()
                val artistPage = YouTube.artist(artistBrowseId).getOrNull() ?: return@async emptyList<Song>()

                // Get first song from the artist's Songs section and fetch its radio
                val songsSection = artistPage.sections.find {
                    it.title.contains("songs", ignoreCase = true) ||
                    it.title.contains("popular", ignoreCase = true)
                }
                val seedSong = songsSection?.items?.filterIsInstance<SongItem>()?.firstOrNull()
                    ?: return@async emptyList<Song>()

                val radioResult = YouTube.next(
                    WatchEndpoint(playlistId = "RDAMVM${seedSong.id}", videoId = seedSong.id)
                ).getOrNull()

                val songs = radioResult?.items
                    ?.filterIsInstance<SongItem>()
                    ?.filterVideo(pureYtMusicOnly)
                    ?.drop(1) // Skip the seed song itself
                    ?.take(5)
                    ?.map { it.toNativeSong() }
                    ?: emptyList()
                songs
            } catch (e: Exception) {
                Timber.tag("QuickPicks").w(e, "Similar artist bucket failed")
                emptyList()
            }
        }

        // Bucket 3: YouTube Quick Picks — home page Quick Picks section (6 songs)
        val ytQuickPicksDeferred = async(Dispatchers.IO) {
            try {
                val defaultHome = YouTube.home().getOrNull() ?: return@async emptyList<Song>()
                // Update categories from home chips
                val chipTitles = defaultHome.chips?.map { it.title } ?: emptyList()
                if (chipTitles.isNotEmpty()) {
                    _categories.value = listOf("All") + chipTitles
                }
                val quickPicksSection = defaultHome.sections.firstOrNull {
                    it.title.contains("quick picks", ignoreCase = true) ||
                    it.title.contains("quick", ignoreCase = true)
                } ?: defaultHome.sections.firstOrNull {
                    !it.title.contains("listen again", ignoreCase = true) &&
                    !it.title.contains("recently played", ignoreCase = true)
                }
                quickPicksSection?.items
                    ?.filterIsInstance<SongItem>()
                    ?.filterVideo(pureYtMusicOnly)
                    ?.take(6)
                    ?.map { it.toNativeSong() }
                    ?: emptyList()
            } catch (e: Exception) {
                Timber.tag("QuickPicks").w(e, "YT Quick Picks bucket failed")
                emptyList()
            }
        }

        // Bucket 4: Local Popular Songs — top played from local DB (3 songs)
        val localPopularDeferred = async(Dispatchers.IO) {
            try {
                val topEngagements = engagementDao.getTopPlayedSongs(10)
                if (topEngagements.isNotEmpty()) {
                    val songIds = topEngagements.map { it.songId }
                    val songs = musicRepository.getSongsByIds(songIds).first()
                    val songIdToPlayCount = topEngagements.associate { it.songId to it.playCount }
                    songs.sortedByDescending { songIdToPlayCount[it.id] ?: 0 }.take(3)
                } else {
                    musicRepository.getRandomSongs(3)
                }
            } catch (e: Exception) {
                Timber.tag("QuickPicks").w(e, "Local popular bucket failed")
                emptyList()
            }
        }

        // Bucket 5: Trending Charts — top chart songs (2 songs)
        val trendingDeferred = async(Dispatchers.IO) {
            try {
                val charts = YouTube.getChartsPage().getOrNull() ?: return@async emptyList<Song>()
                val topSongs = charts.sections
                    .flatMap { it.items }
                    .filterIsInstance<SongItem>()
                    .filterVideo(pureYtMusicOnly)
                    .take(2)
                    .map { it.toNativeSong() }
                topSongs
            } catch (e: Exception) {
                Timber.tag("QuickPicks").w(e, "Trending charts bucket failed")
                emptyList()
            }
        }

        // Await all 5 buckets concurrently
        val newReleases = newReleasesDeferred.await()
        val similarArtist = similarArtistDeferred.await()
        val ytQuickPicks = ytQuickPicksDeferred.await()
        val localPopular = localPopularDeferred.await()
        val trending = trendingDeferred.await()

        // Interleave buckets: new releases first (freshness), then mix the rest
        val combined = mutableListOf<Song>()
        combined.addAll(newReleases)
        combined.addAll(similarArtist)
        combined.addAll(ytQuickPicks)
        combined.addAll(trending)
        combined.addAll(localPopular)

        // De-duplicate by YouTube ID or song title+artist combo
        val deduplicated = combined.distinctBy { song ->
            song.youtubeId?.takeIf { it.isNotBlank() } ?: "${song.title.lowercase()}|${song.artist.lowercase()}"
        }

        // Shuffle within each half to keep new releases near the top but randomise order
        val topHalf = deduplicated.take(deduplicated.size / 2).shuffled()
        val bottomHalf = deduplicated.drop(deduplicated.size / 2).shuffled()
        (topHalf + bottomHalf).take(20)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Category-specific fetch (genre chips from YouTube home)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun loadCategoryQuickPicks(category: String) {
        val pureYtMusicOnly = userPreferencesRepository.pureYtMusicOnlyFlow.first()
        val songs = withContext(Dispatchers.IO) {
            try {
                val defaultHome = YouTube.home().getOrNull() ?: return@withContext emptyList<Song>()
                val matchingChip = defaultHome.chips?.firstOrNull {
                    it.title.equals(category, ignoreCase = true)
                }
                val targetHome = if (matchingChip?.endpoint?.params != null) {
                    YouTube.home(params = matchingChip.endpoint.params).getOrNull() ?: defaultHome
                } else defaultHome

                targetHome.sections
                    .filter {
                        !it.title.contains("listen again", ignoreCase = true) &&
                        !it.title.contains("recently played", ignoreCase = true)
                    }
                    .flatMap { it.items.filterIsInstance<SongItem>() }
                    .filterVideo(pureYtMusicOnly)
                    .distinctBy { it.id }
                    .take(25)
                    .map { it.toNativeSong() }
                    .shuffled()
            } catch (e: Exception) {
                Timber.tag("QuickPicks").e(e, "Category fetch failed: $category")
                emptyList()
            }
        }
        if (songs.isNotEmpty()) {
            _quickPicks.value = songs
        }
    }
}
