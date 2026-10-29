package com.unshoo.pixelmusic.presentation.model

import androidx.compose.runtime.Immutable
import com.unshoo.pixelmusic.data.model.Playlist
import com.unshoo.pixelmusic.data.feed.FeedAlbum
import com.unshoo.pixelmusic.data.feed.FeedArtist
import com.unshoo.pixelmusic.data.feed.FeedSpotlight
import com.unshoo.pixelmusic.data.feed.RecentTrack
import com.unshoo.pixelmusic.data.feed.YouTubeMusicTrack
import com.unshoo.pixelmusic.data.feed.YouTubePlaylistSummary
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.presentation.viewmodel.ExploreUiState
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.pages.HomePage

/**
 * Base presentation contract for items appearing within Explore rails, carousels, and grids.
 */
@Immutable
sealed interface ExploreItemUiModel {
    val id: String
    val thumbnail: String?

    // Legacy wrappers maintained for backwards compatibility during migration
    @Immutable
    data class SongModel(val song: Song, val rawItem: SongItem) : ExploreItemUiModel {
        override val id: String get() = song.id
        override val thumbnail: String? get() = song.albumArtUriString
    }

    @Immutable
    data class AlbumModel(val album: AlbumItem) : ExploreItemUiModel {
        override val id: String get() = album.browseId
        override val thumbnail: String? get() = album.thumbnail
    }

    @Immutable
    data class PlaylistModel(val playlist: PlaylistItem) : ExploreItemUiModel {
        override val id: String get() = playlist.id
        override val thumbnail: String? get() = playlist.thumbnail
    }

    @Immutable
    data class ArtistModel(val artist: ArtistItem) : ExploreItemUiModel {
        override val id: String get() = artist.id
        override val thumbnail: String? get() = artist.thumbnail
    }
}

/**
 * Clean presentation model for songs in Explore shelves, quick picks, and charts.
 */
@Immutable
data class ExploreSongItemUiModel(
    override val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val durationText: String? = null,
    val explicit: Boolean = false,
    val nativeSong: Song
) : ExploreItemUiModel {
    override val thumbnail: String? get() = artworkUrl ?: nativeSong.albumArtUriString
}

/**
 * Clean presentation model for albums in new releases and albums-for-you shelves.
 */
@Immutable
data class ExploreAlbumItemUiModel(
    override val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val year: String? = null,
    val explicit: Boolean = false,
    val browseId: String = id
) : ExploreItemUiModel {
    override val thumbnail: String? get() = artworkUrl
}

/**
 * Clean presentation model for playlists in Explore shelves.
 */
@Immutable
data class ExplorePlaylistItemUiModel(
    override val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val songCountText: String? = null
) : ExploreItemUiModel {
    override val thumbnail: String? get() = artworkUrl
}

/**
 * Clean presentation model for artists in Explore artist carousels.
 */
@Immutable
data class ExploreArtistItemUiModel(
    override val id: String,
    val name: String,
    val artworkUrl: String? = null,
    val browseId: String? = null
) : ExploreItemUiModel {
    override val thumbnail: String? get() = artworkUrl
}

/**
 * Clean presentation model for spotlight hero banners.
 */
@Immutable
data class ExploreHeroItemUiModel(
    override val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val badgeText: String? = null,
    val clickPayload: Any? = null
) : ExploreItemUiModel {
    override val thumbnail: String? get() = artworkUrl
}

/**
 * Clean presentation model for category and mood filter chips.
 */
@Immutable
data class ExploreChipUiModel(
    val title: String,
    val id: String = title,
    val isSelected: Boolean = false,
    val browseId: String? = null,
    val params: String? = null
) {
    // Secondary constructor supporting (title, browseId, params) legacy order
    constructor(
        title: String,
        browseId: String?,
        params: String?
    ) : this(
        title = title,
        id = browseId ?: title,
        isSelected = false,
        browseId = browseId,
        params = params
    )
}

/**
 * Clean presentation model for Explore charts.
 */
@Immutable
data class ExploreChartsUiModel(
    val tracks: List<ExploreSongItemUiModel> = emptyList(),
    val collections: List<ExploreAlbumItemUiModel> = emptyList()
)

/**
 * Clean presentation model for dynamic Explore sections and shelves.
 */
@Immutable
data class ExploreSectionUiModel(
    val id: String,
    val title: String,
    val label: String? = null,
    val items: List<ExploreItemUiModel> = emptyList()
)

/**
 * Single source of truth UI state machine for the Explore screen.
 */
sealed interface ExploreScreenUiState {
    val legacyState: ExploreUiState?
        get() = when (this) {
            is Content -> legacyState
            is Error -> cachedContent?.legacyState
            is Loading -> null
        }

    val preparedYtHomeQuickPicks: List<Song> get() = legacyState?.preparedYtHomeQuickPicks ?: emptyList()
    val moodChips: List<HomePage.Chip> get() = legacyState?.moodChips ?: emptyList()
    val isRefreshing: Boolean get() = (this as? Content)?.isRefreshing ?: false
    val activeMoodChip: HomePage.Chip? get() = legacyState?.activeMoodChip
    val explorePageSections: List<HomePage.Section> get() = legacyState?.explorePageSections ?: emptyList()
    val homePageSections: List<HomePage.Section> get() = legacyState?.homePageSections ?: emptyList()
    val homePageContinuation: String? get() = legacyState?.homePageContinuation
    val isContinuationLoading: Boolean get() = (this as? Content)?.isContinuationLoading ?: false
    val isAdvancedExploreEnabled: Boolean get() = legacyState?.isAdvancedExploreEnabled ?: true
    val preparedArtistsForYou: List<FeedArtist> get() = legacyState?.preparedArtistsForYou ?: emptyList()
    val localTopArtists: List<FeedArtist> get() = legacyState?.localTopArtists ?: emptyList()
    val preparedChartTracks: List<YouTubeMusicTrack> get() = legacyState?.preparedChartTracks ?: emptyList()
    val preparedChartPlaylistsAndAlbums: List<YouTubePlaylistSummary> get() = legacyState?.preparedChartPlaylistsAndAlbums ?: emptyList()
    val localRecentlyAddedSongs: List<Song> get() = legacyState?.localRecentlyAddedSongs ?: emptyList()
    val localHighlyRotatoryTracks: List<RecentTrack> get() = legacyState?.localHighlyRotatoryTracks ?: emptyList()
    val preparedSpotlight: FeedSpotlight? get() = legacyState?.preparedSpotlight
    val preparedNewReleases: List<YouTubePlaylistSummary> get() = legacyState?.preparedNewReleases ?: emptyList()
    val preparedAlbumsForYou: List<FeedAlbum> get() = legacyState?.preparedAlbumsForYou ?: emptyList()
    val preparedOtherHomeSections: List<HomePage.Section> get() = legacyState?.preparedOtherHomeSections ?: emptyList()
    val preparedDiscoverSongs: List<Song> get() = legacyState?.preparedDiscoverSongs ?: emptyList()
    val isSettled: Boolean get() = this !is Loading
    val libraryPlaylists: List<Playlist> get() = legacyState?.libraryPlaylists ?: emptyList()
    val error: String? get() = (this as? Error)?.message
    val localSongs: Map<String, Song> get() = legacyState?.localSongs ?: emptyMap()
    val albumsForYou: List<Any> get() = (this as? Content)?.albumsForYou ?: (legacyState?.albumsForYou ?: emptyList())
    val selectedFilter: String get() = (this as? Content)?.selectedCategory ?: "All"

    data object Loading : ExploreScreenUiState

    data class Content(
        override val isRefreshing: Boolean = false,
        override val isContinuationLoading: Boolean = false,
        val selectedCategory: String = "All",
        val categories: List<ExploreChipUiModel> = emptyList(),
        val heroItems: List<ExploreHeroItemUiModel> = emptyList(),
        val quickPicks: List<ExploreSongItemUiModel> = emptyList(),
        val newReleases: List<ExploreAlbumItemUiModel> = emptyList(),
        override val albumsForYou: List<ExploreAlbumItemUiModel> = emptyList(),
        val charts: ExploreChartsUiModel? = null,
        val artistsForYou: List<ExploreArtistItemUiModel> = emptyList(),
        val dynamicShelves: List<ExploreSectionUiModel> = emptyList(),
        val hasMoreContent: Boolean = false,
        override val legacyState: ExploreUiState? = null
    ) : ExploreScreenUiState

    data class Error(
        val message: String,
        val cachedContent: Content? = null
    ) : ExploreScreenUiState
}

