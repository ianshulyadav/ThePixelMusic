package com.unshoo.pixelmusic.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.remote.youtube.toNativeSong
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import timber.log.Timber
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube as InnerTubeYouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.YTItem
import com.unshoo.pixelmusic.utils.AppReadinessSignal
import javax.inject.Inject

@HiltViewModel
class FavoriteArtistReleasesViewModel @Inject constructor(
    private val musicDao: MusicDao
) : ViewModel() {

    private val _releases = MutableStateFlow<List<YTItem>>(emptyList())
    val releases: StateFlow<List<YTItem>> = _releases.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    @Volatile
    private var hasLoadedThisSession = false

    init {
        viewModelScope.launch {
            AppReadinessSignal.awaitReady()
            kotlinx.coroutines.delay(4000L) // Stagger release fetching so it doesn't fight with first frame renders
            loadReleases(force = false)
        }
    }

    fun loadReleases(force: Boolean = false) {
        if (!force && hasLoadedThisSession && _releases.value.isNotEmpty()) {
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val browseIds = withContext(Dispatchers.IO) {
                    val favJson = musicDao.getFavoriteSongsArtistsJson()
                    val mostPlayedJson = musicDao.getMostPlayedYoutubeArtistsJson()
                    // Favourites count double so liked artists win ties against plain replays.
                    val weighted = favJson.map { it to 2 } + mostPlayedJson.map { it to 1 }

                    val scores = LinkedHashMap<String, Int>()
                    weighted.forEach { (json, weight) ->
                        try {
                            val arr = JSONArray(json)
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                // BUGFIX: artists_json stores the YouTube channel in "channelId";
                                // "id" is the app's internal numeric artist id. Reading only "id"
                                // found no artists, so this always showed hardcoded strangers.
                                val id = obj.optString("channelId").takeIf { it.startsWith("UC") }
                                    ?: obj.optString("id").takeIf { it.startsWith("UC") }
                                if (id != null) scores[id] = (scores[id] ?: 0) + weight
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error parsing artists_json in FavoriteArtistReleasesViewModel")
                        }
                    }
                    scores.entries.sortedByDescending { it.value }.map { it.key }.take(5)
                }

                // No listening data yet: show nothing rather than unrelated "popular" artists.
                if (browseIds.isEmpty()) {
                    _releases.value = emptyList()
                    hasLoadedThisSession = true
                    return@launch
                }

                val releaseKeywords = listOf("latest release", "singles", "single", "albums", "releases", "ep")
                val perArtist = withContext(Dispatchers.IO) {
                    browseIds.map { browseId ->
                        async {
                            InnerTubeYouTube.artist(browseId).fold(
                                onSuccess = { artistPage ->
                                    artistPage.sections
                                        .filter { section -> releaseKeywords.any { section.title.contains(it, ignoreCase = true) } }
                                        // Newest first: "Latest release" and "Singles" before full albums.
                                        .sortedBy { section ->
                                            when {
                                                section.title.contains("latest", true) -> 0
                                                section.title.contains("single", true) -> 1
                                                else -> 2
                                            }
                                        }
                                        .flatMap { it.items }
                                        .take(6)
                                },
                                onFailure = { e ->
                                    Timber.e(e, "Failed to load releases for artist: $browseId")
                                    emptyList()
                                }
                            )
                        }
                    }.awaitAll()
                }
                // Interleave artists so the row isn't all one artist before the next appears.
                val allReleases = mutableListOf<YTItem>()
                val maxLen = perArtist.maxOfOrNull { it.size } ?: 0
                for (i in 0 until maxLen) perArtist.forEach { list -> list.getOrNull(i)?.let(allReleases::add) }

                // Remove duplicates and limit to 15 items
                _releases.value = allReleases.distinctBy { it.id }.take(15)
                hasLoadedThisSession = true
            } catch (e: Exception) {
                Timber.e(e, "Error in loadReleases")
            } finally {
                _isLoading.value = false
            }
        }
    }
}
