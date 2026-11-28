/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.models

import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_AUDIOBOOK
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PLAYLIST
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
import kotlinx.serialization.Serializable

/**
 * Two row: a big thumbnail, a title, and a subtitle
 * Used in [GridRenderer] and [MusicCarouselShelfRenderer]
 * Item type: song, video, album, playlist, artist
 */
@Serializable
data class MusicTwoRowItemRenderer(
    val title: Runs = Runs(emptyList()),
    val subtitle: Runs? = null,
    val subtitleBadges: List<Badges>? = null,
    val menu: Menu? = null,
    val thumbnailRenderer: ThumbnailRenderer = ThumbnailRenderer(),
    val navigationEndpoint: NavigationEndpoint = NavigationEndpoint(),
    val thumbnailOverlay: MusicResponsiveListItemRenderer.Overlay? = null,
) {
    private val browsePageType: String?
        get() = navigationEndpoint.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType

    private val browseId: String?
        get() = navigationEndpoint.browseEndpoint?.browseId

    val isSong: Boolean
        get() = navigationEndpoint.endpoint is WatchEndpoint ||
            ((browsePageType == MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE || browseId?.startsWith("MPED") == true) &&
                thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.anyWatchEndpoint != null)
    val isPlaylist: Boolean
        get() = browsePageType == MUSIC_PAGE_TYPE_PLAYLIST ||
            browsePageType == MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE ||
            browseId?.startsWith("MPSP") == true
    val isAlbum: Boolean
        get() =
            navigationEndpoint.browseEndpoint
                ?.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig
                ?.pageType ==
                MUSIC_PAGE_TYPE_ALBUM ||
                navigationEndpoint.browseEndpoint
                    ?.browseEndpointContextSupportedConfigs
                    ?.browseEndpointContextMusicConfig
                    ?.pageType ==
                MUSIC_PAGE_TYPE_AUDIOBOOK
    val isArtist: Boolean
        get() =
            navigationEndpoint.browseEndpoint
                ?.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig
                ?.pageType ==
                MUSIC_PAGE_TYPE_ARTIST
}
