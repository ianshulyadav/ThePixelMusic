/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





@file:OptIn(ExperimentalSerializationApi::class)

package unshoo.ianshulyadav.pixelmusic.innertube.models

import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_AUDIOBOOK
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_LIBRARY_ARTIST
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PLAYLIST
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Typical list item
 * Used in [MusicCarouselShelfRenderer], [MusicShelfRenderer]
 * Appears in quick picks, search results, table items, etc.
 */
@Serializable
data class MusicResponsiveListItemRenderer(
    val badges: List<Badges>? = null,
    val fixedColumns: List<FlexColumn>? = null,
    val flexColumns: List<FlexColumn> = emptyList(),
    val thumbnail: ThumbnailRenderer? = null,
    val menu: Menu? = null,
    val playlistItemData: PlaylistItemData? = null,
    val overlay: Overlay? = null,
    val navigationEndpoint: NavigationEndpoint? = null,
    val likeStatus: String? = null,
) {
    private val browsePageType: String?
        get() = navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType

    private val browseId: String?
        get() = navigationEndpoint?.browseEndpoint?.browseId

    val isSong: Boolean
        get() = navigationEndpoint == null ||
            navigationEndpoint.watchEndpoint != null ||
            navigationEndpoint.watchPlaylistEndpoint != null ||
            ((browsePageType == MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE || browseId?.startsWith("MPED") == true) &&
                (!playlistItemData?.videoId.isNullOrBlank() ||
                    overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.anyWatchEndpoint != null))
    val isPlaylist: Boolean
        get() = browsePageType == MUSIC_PAGE_TYPE_PLAYLIST ||
            browsePageType == MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE ||
            (browsePageType == null && browseId?.let {
                it.startsWith("VL") || it.startsWith("PL") || it.startsWith("RD") || it.startsWith("MPSP")
            } == true)
    val isAlbum: Boolean
        get() = browsePageType == MUSIC_PAGE_TYPE_ALBUM ||
            browsePageType == MUSIC_PAGE_TYPE_AUDIOBOOK ||
            (browsePageType == null && browseId?.startsWith("MPRE") == true)
    val isArtist: Boolean
        get() = browsePageType == MUSIC_PAGE_TYPE_ARTIST ||
            browsePageType == MUSIC_PAGE_TYPE_LIBRARY_ARTIST ||
            (browsePageType == null && browseId?.startsWith("UC") == true)

    @Serializable
    data class FlexColumn(
        @JsonNames("musicResponsiveListItemFixedColumnRenderer")
        val musicResponsiveListItemFlexColumnRenderer: MusicResponsiveListItemFlexColumnRenderer = MusicResponsiveListItemFlexColumnRenderer(),
    ) {
        @Serializable
        data class MusicResponsiveListItemFlexColumnRenderer(
            val text: Runs? = null,
        )
    }

    @Serializable
    data class PlaylistItemData(
        val playlistSetVideoId: String? = null,
        val videoId: String = "",
    )

    @Serializable
    data class Overlay(
        val musicItemThumbnailOverlayRenderer: MusicItemThumbnailOverlayRenderer = MusicItemThumbnailOverlayRenderer(),
    ) {
        @Serializable
        data class MusicItemThumbnailOverlayRenderer(
            val content: Content = Content(),
        ) {
            @Serializable
            data class Content(
                val musicPlayButtonRenderer: MusicPlayButtonRenderer = MusicPlayButtonRenderer(),
            ) {
                @Serializable
                data class MusicPlayButtonRenderer(
                    val playNavigationEndpoint: NavigationEndpoint? = null,
                )
            }
        }
    }
}
