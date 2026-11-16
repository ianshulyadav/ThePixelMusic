/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.pages

import androidx.compose.runtime.Immutable
import unshoo.ianshulyadav.pixelmusic.innertube.models.Album
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.Artist
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicCarouselShelfRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicTwoRowItemRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SectionListRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.YTItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.WatchEndpoint
import unshoo.ianshulyadav.pixelmusic.innertube.models.oddElements
import unshoo.ianshulyadav.pixelmusic.innertube.models.filterExplicit
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicCardShelfRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicShelfRenderer

// PERF: see YTItem.kt for the full explanation - these types back every Explore-screen
// composable, and being unannotated (plain List-holding data classes) made those composables
// non-skippable, which is why the Explore screen never settled down the way other screens do.
@Immutable
data class HomePage(
    val chips: List<Chip>?,
    val sections: List<Section>,
    val continuation: String? = null,
) {
    @Immutable
    data class Chip(
        val title: String,
        val endpoint: BrowseEndpoint?,
        val deselectEndPoint: BrowseEndpoint?,
    ) {
        companion object {
            fun fromChipCloudChipRenderer(renderer: SectionListRenderer.Header.ChipCloudRenderer.Chip): Chip? {
                val title = renderer.chipCloudChipRenderer.text?.runs?.joinToString("") { it.text }?.takeIf { it.isNotBlank() }
                    ?: renderer.chipCloudChipRenderer.text?.runs?.firstOrNull()?.text
                    ?: return null
                return Chip(
                    title = title,
                    endpoint = renderer.chipCloudChipRenderer.navigationEndpoint.browseEndpoint,
                    deselectEndPoint = renderer.chipCloudChipRenderer.onDeselectedCommand?.browseEndpoint,
                )
            }
        }
    }

    @Immutable
    data class Section(
        val title: String,
        val label: String?,
        val thumbnail: String?,
        val endpoint: BrowseEndpoint?,
        val items: List<YTItem>,
        /**
         * True when the shelf is rendered as a list (musicResponsiveListItemRenderer items) rather
         * than two-row cards. YouTube Music uses this layout for "Quick picks"; it is the
         * locale-independent way to find that shelf (see HomeShelfClassifier).
         */
        val isListShelf: Boolean = false,
    ) {
        companion object {
            fun fromSectionListContent(content: SectionListRenderer.Content): Section? {
                return when {
                    content.musicCarouselShelfRenderer != null -> {
                        fromMusicCarouselShelfRenderer(content.musicCarouselShelfRenderer)
                    }
                    content.musicCardShelfRenderer != null -> {
                        val renderer = content.musicCardShelfRenderer
                        val title = renderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs?.firstOrNull()?.text
                            ?: renderer.title.runs?.firstOrNull()?.text
                            ?: ""
                        val items = renderer.contents?.mapNotNull { it.musicResponsiveListItemRenderer?.let { SearchPage.toYTItem(it) } }.orEmpty()
                        if (items.isEmpty()) return null
                        Section(
                            title = title,
                            label = renderer.subtitle.runs?.firstOrNull()?.text,
                            thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl(),
                            endpoint = renderer.onTap.browseEndpoint,
                            items = items
                        )
                    }
                    content.musicShelfRenderer != null -> {
                        val renderer = content.musicShelfRenderer
                        val title = renderer.title?.runs?.firstOrNull()?.text ?: ""
                        val items = renderer.contents?.mapNotNull { it.musicResponsiveListItemRenderer?.let { SearchPage.toYTItem(it) } }.orEmpty()
                        if (items.isEmpty()) return null
                        Section(
                            title = title,
                            label = null,
                            thumbnail = null,
                            endpoint = renderer.bottomEndpoint?.browseEndpoint,
                            items = items,
                            isListShelf = true
                        )
                    }
                    else -> null
                }
            }

            fun fromMusicCarouselShelfRenderer(renderer: MusicCarouselShelfRenderer): Section? {
                return Section(
                    title = renderer.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text ?: return null,
                    label = renderer.header.musicCarouselShelfBasicHeaderRenderer.strapline?.runs?.firstOrNull()?.text,
                    thumbnail = renderer.header.musicCarouselShelfBasicHeaderRenderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl(),
                    endpoint = renderer.header.musicCarouselShelfBasicHeaderRenderer.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint,
                    items = renderer.contents.mapNotNull {
                        when {
                            it.musicTwoRowItemRenderer != null -> fromMusicTwoRowItemRenderer(it.musicTwoRowItemRenderer)
                            it.musicResponsiveListItemRenderer != null -> SearchPage.toYTItem(it.musicResponsiveListItemRenderer)
                            else -> null
                        }
                    }.ifEmpty {
                        return null
                    },
                    isListShelf = renderer.contents.count { it.musicResponsiveListItemRenderer != null } * 2 > renderer.contents.size
                )
            }

            private fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): YTItem? {
                return when {
                    renderer.isSong -> {
                        val subtitleRuns = renderer.subtitle?.runs ?: return null
                        val (artistRuns, albumRuns) = subtitleRuns.partition { run ->
                            run.navigationEndpoint?.browseEndpoint?.browseId?.startsWith("UC") == true
                        }
                        val artists = artistRuns.map {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId ?: return null
                            )
                        }
                        SongItem(
                            id = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null,
                            title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                            artists = artists,
                            album = PageHelper.extractAlbum(renderer),
                            duration = null,
                            thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                            explicit = renderer.subtitleBadges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true
                        )
                    }
                    renderer.isAlbum -> {
                        AlbumItem(
                            browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                            playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                                ?.musicPlayButtonRenderer?.playNavigationEndpoint
                                ?.watchPlaylistEndpoint?.playlistId ?: return null,
                            title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                            artists = renderer.subtitle?.runs?.oddElements()?.drop(1)?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                                )
                            },
                            year = null,
                            thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                            explicit = renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null
                        )
                    }

                    renderer.isPlaylist -> {
                        val playlistId = renderer.navigationEndpoint.browseEndpoint?.browseId?.removePrefix("VL") ?: return null
                        val playlistTitle = renderer.title.runs?.firstOrNull()?.text ?: return null
                        val playlistThumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null
                        val authorName = renderer.subtitle?.runs?.lastOrNull()?.text
                            ?: renderer.subtitle?.runs?.firstOrNull()?.text
                            ?: "YouTube Music"
                        val playEndpoint = renderer.thumbnailOverlay
                            ?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.watchPlaylistEndpoint
                            ?: WatchEndpoint(playlistId = playlistId)
                        val shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                            ?: WatchEndpoint(playlistId = playlistId, params = "wAEB")
                        val radioEndpoint = renderer.menu?.menuRenderer?.items?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint

                        PlaylistItem(
                            id = playlistId,
                            title = playlistTitle,
                            author = Artist(
                                name = authorName,
                                id = null
                            ),
                            songCountText = null,
                            thumbnail = playlistThumbnail,
                            playEndpoint = playEndpoint,
                            shuffleEndpoint = shuffleEndpoint,
                            radioEndpoint = radioEndpoint
                        )
                    }

                    renderer.isArtist -> {
                        ArtistItem(
                            id = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                            title = renderer.title.runs?.lastOrNull()?.text ?: return null,
                            thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                            shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                                it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                            }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                            radioEndpoint = renderer.menu.menuRenderer.items.find {
                                it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                            }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                        )
                    }

                    else -> null
                }
            }
        }
    }

    fun filterExplicit(enabled: Boolean = true) =
        if (enabled) {
            copy(sections = sections.map {
                it.copy(items = it.items.filterExplicit())
            })
        } else this

}
