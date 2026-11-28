/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.models

import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_LIBRARY_ARTIST
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PLAYLIST
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_AUDIOBOOK
import kotlinx.serialization.Serializable

@Serializable
sealed class Endpoint

@Serializable
data class WatchEndpoint(
    val videoId: String? = null,
    val playlistId: String? = null,
    val playlistSetVideoId: String? = null,
    val params: String? = null,
    val index: Int? = null,
    val watchEndpointMusicSupportedConfigs: WatchEndpointMusicSupportedConfigs? = null,
) : Endpoint() {

    @Serializable
    data class WatchEndpointMusicSupportedConfigs(
        val watchEndpointMusicConfig: WatchEndpointMusicConfig = WatchEndpointMusicConfig(),
    ) {
        @Serializable
        data class WatchEndpointMusicConfig(
            val musicVideoType: String = "",
        ) {
            companion object {
                const val MUSIC_VIDEO_TYPE_OMV = "MUSIC_VIDEO_TYPE_OMV"
                const val MUSIC_VIDEO_TYPE_UGC = "MUSIC_VIDEO_TYPE_UGC"
                const val MUSIC_VIDEO_TYPE_ATV = "MUSIC_VIDEO_TYPE_ATV"
                const val MUSIC_VIDEO_TYPE_PODCAST_EPISODE = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
            }
        }
    }
}

@Serializable
data class BrowseEndpoint(
    val browseId: String = "",
    val params: String? = null,
    val browseEndpointContextSupportedConfigs: BrowseEndpointContextSupportedConfigs? = null,
) : Endpoint() {
    val isArtistEndpoint: Boolean
        get() {
            val pageType = browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
            return pageType == MUSIC_PAGE_TYPE_ARTIST ||
                pageType == MUSIC_PAGE_TYPE_LIBRARY_ARTIST ||
                (pageType.isNullOrBlank() && browseId.startsWith("UC"))
        }
    val isAlbumEndpoint: Boolean
        get() {
            val pageType = browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
            return pageType == MUSIC_PAGE_TYPE_ALBUM ||
                pageType == MUSIC_PAGE_TYPE_AUDIOBOOK ||
                (pageType.isNullOrBlank() && browseId.startsWith("MPRE"))
        }
    val isPlaylistEndpoint: Boolean
        get() {
            val pageType = browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
            return pageType == MUSIC_PAGE_TYPE_PLAYLIST ||
                pageType == MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE ||
                (pageType.isNullOrBlank() && (browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("MPSP")))
        }

    @Serializable
    data class BrowseEndpointContextSupportedConfigs(
        val browseEndpointContextMusicConfig: BrowseEndpointContextMusicConfig = BrowseEndpointContextMusicConfig(),
    ) {
        @Serializable
        data class BrowseEndpointContextMusicConfig(
            val pageType: String = "",
        ) {
            companion object {
                const val MUSIC_PAGE_TYPE_ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
                const val MUSIC_PAGE_TYPE_AUDIOBOOK = "MUSIC_PAGE_TYPE_AUDIOBOOK"
                const val MUSIC_PAGE_TYPE_PLAYLIST = "MUSIC_PAGE_TYPE_PLAYLIST"
                const val MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE = "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"
                const val MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE = "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE"
                const val MUSIC_PAGE_TYPE_ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
                const val MUSIC_PAGE_TYPE_LIBRARY_ARTIST = "MUSIC_PAGE_TYPE_LIBRARY_ARTIST"
                const val MUSIC_PAGE_TYPE_USER_CHANNEL = "MUSIC_PAGE_TYPE_USER_CHANNEL"
                const val MUSIC_PAGE_TYPE_TRACK_LYRICS = "MUSIC_PAGE_TYPE_TRACK_LYRICS"
                const val MUSIC_PAGE_TYPE_TRACK_RELATED = "MUSIC_PAGE_TYPE_TRACK_RELATED"
            }
        }
    }
}

@Serializable
data class SearchEndpoint(
    val params: String? = null,
    val query: String = "",
) : Endpoint()

@Serializable
data class QueueAddEndpoint(
    val queueInsertPosition: String = "",
    val queueTarget: QueueTarget = QueueTarget(),
) : Endpoint() {
    @Serializable
    data class QueueTarget(
        val videoId: String? = null,
        val playlistId: String? = null,
    )
}

@Serializable
data class ShareEntityEndpoint(
    val serializedShareEntity: String = "",
) : Endpoint()

@Serializable
data class DefaultServiceEndpoint(
    var subscribeEndpoint: SubscribeEndpoint? = null
) : Endpoint() {
    @Serializable
    data class SubscribeEndpoint(
        val channelIds: List<String> = emptyList(),
        val params: String? = null,
    ) : Endpoint()
}