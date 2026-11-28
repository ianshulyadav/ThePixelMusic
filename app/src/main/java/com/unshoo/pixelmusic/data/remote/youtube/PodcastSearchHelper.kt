package com.unshoo.pixelmusic.data.remote.youtube

import com.unshoo.pixelmusic.data.model.Playlist
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.remote.youtube.YouTubeItemFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.YTItem
import unshoo.ianshulyadav.pixelmusic.innertube.pages.PageHelper
import unshoo.ianshulyadav.pixelmusic.innertube.pages.SearchResult
import unshoo.ianshulyadav.pixelmusic.innertube.pages.SearchSummaryPage

internal data class PodcastSearchResultBatch(
    val items: List<YTItem>,
    val episodes: List<Song>,
    val shows: List<Playlist>,
    val continuation: String? = null,
    val hadFailure: Boolean = false,
)

/**
 * Shared podcast search orchestrator used by global Search ([SearchFilterType.PODCASTS])
 * and dedicated Podcasts search sections.
 */
internal object PodcastSearchHelper {

    suspend fun search(
        query: String,
        searchByFilter: suspend (query: String, filter: YouTube.SearchFilter) -> Result<SearchResult> = { q, filter ->
            YouTube.search(q, filter)
        },
        searchSummary: suspend (query: String) -> Result<SearchSummaryPage> = { q ->
            YouTube.searchSummary(q)
        },
        podcastFilterProvider: () -> YouTube.SearchFilter = { YouTube.effectivePodcastFilter() },
        discoveredEpisodeFilterProvider: () -> YouTube.SearchFilter? = { YouTube.discoveredEpisodeFilterOrNull() },
    ): PodcastSearchResultBatch = coroutineScope {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return@coroutineScope PodcastSearchResultBatch(emptyList(), emptyList(), emptyList())
        }

        var hadFailure = false
        val showsDeferred = async {
            searchByFilter(cleanQuery, podcastFilterProvider())
                .onFailure { hadFailure = true }
                .getOrNull()
        }
        val initialEpisodeFilter = discoveredEpisodeFilterProvider()
        val initialEpisodesDeferred = async {
            initialEpisodeFilter?.let { filter ->
                searchByFilter(cleanQuery, filter)
                    .onFailure { hadFailure = true }
                    .getOrNull()
            }
        }
        val summaryDeferred = async {
            searchSummary(cleanQuery)
                .onFailure { hadFailure = true }
                .getOrNull()
        }

        val showsResult = showsDeferred.await()
        val initialEpisodesResult = initialEpisodesDeferred.await()
        val summaryResult = summaryDeferred.await()

        val discoveredEpisodeFilter = discoveredEpisodeFilterProvider()
        val episodesResult = when {
            initialEpisodesResult != null -> initialEpisodesResult
            discoveredEpisodeFilter != null -> {
                searchByFilter(cleanQuery, discoveredEpisodeFilter)
                    .onFailure { hadFailure = true }
                    .getOrNull()
            }
            else -> null
        }

        val collected = mutableListOf<YTItem>()
        val seenIds = mutableSetOf<String>()

        fun addIfPodcastRelevant(item: YTItem) {
            val key = when (item) {
                is SongItem -> "ep_${item.id}"
                is PlaylistItem -> "show_${item.id.removePrefix("VL")}"
                else -> return
            }
            if (seenIds.add(key)) {
                collected.add(item)
            }
        }

        // Playable podcast episodes first so users can tap and listen immediately.
        episodesResult?.items?.forEach { item ->
            if (item is SongItem || item is PlaylistItem) {
                addIfPodcastRelevant(item)
            }
        }
        showsResult?.items?.filterIsInstance<SongItem>()?.forEach(::addIfPodcastRelevant)

        summaryResult?.summaries?.forEach { section ->
            val isPodcastSection = section.title.contains("episode", ignoreCase = true) ||
                section.title.contains("podcast", ignoreCase = true) ||
                section.title.contains("show", ignoreCase = true)
            section.items.forEach { item ->
                val matchesPodcastItem = when (item) {
                    is SongItem -> item.isPodcastEpisode || isPodcastSection
                    is PlaylistItem -> item.isPodcast ||
                        item.id.startsWith("MPSP") ||
                        YouTubeItemFilter.isPodcastOrEpisode(item.title, item.id) ||
                        isPodcastSection
                    else -> false
                }
                if (matchesPodcastItem) {
                    addIfPodcastRelevant(item)
                }
            }
        }

        // Podcast shows / channels follow episodes.
        showsResult?.items?.filterIsInstance<PlaylistItem>()?.forEach(::addIfPodcastRelevant)

        val orderedItems = collected.sortedBy { item ->
            when (item) {
                is SongItem -> 0
                is PlaylistItem -> 1
                else -> 2
            }
        }
        val nativeEpisodes = orderedItems
            .filterIsInstance<SongItem>()
            .map { it.toNativeSong() }
        val shows = orderedItems
            .filterIsInstance<PlaylistItem>()
            .map { playlistItem ->
                Playlist(
                    id = playlistItem.id,
                    name = playlistItem.title,
                    songIds = emptyList(),
                    coverImageUri = playlistItem.thumbnail,
                    source = "YOUTUBE",
                    displaySongCount = PageHelper.parsePlaylistSongCount(playlistItem.songCountText),
                )
            }

        PodcastSearchResultBatch(
            items = orderedItems,
            episodes = nativeEpisodes,
            shows = shows,
            continuation = episodesResult?.continuation ?: showsResult?.continuation,
            hadFailure = hadFailure,
        )
    }

    suspend fun loadShowEpisodes(showId: String): Result<List<Song>> =
        YouTube.playlist(showId).map { page ->
            page.songs.map { it.toNativeSong() }
        }
}
