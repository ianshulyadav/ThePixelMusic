/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.pages

import unshoo.ianshulyadav.pixelmusic.innertube.models.Album
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.Artist
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicResponsiveListItemRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.YTItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.oddElements
import unshoo.ianshulyadav.pixelmusic.innertube.models.splitBySeparator

object SearchSuggestionPage {
    fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): YTItem? {
        val title = renderer.flexColumns
            .firstOrNull()
            ?.musicResponsiveListItemFlexColumnRenderer
            ?.text
            ?.runs
            ?.joinToString(separator = "") { it.text }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val thumbnailUrl = renderer.thumbnail?.getThumbnailUrl()
        return when {
            renderer.isSong -> {
                val videoId = renderer.playlistItemData?.videoId?.takeIf { it.isNotBlank() }
                    ?: renderer.navigationEndpoint?.watchEndpoint?.videoId?.takeIf { it.isNotBlank() }
                    ?: return null
                SongItem(
                    id = videoId,
                    title = title,
                    artists =
                        renderer.flexColumns
                            .getOrNull(1)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.splitBySeparator()
                            ?.getOrNull(1)
                            ?.oddElements()
                            ?.mapNotNull {
                                val name = it.text.trim()
                                if (name.isNotEmpty()) {
                                    Artist(
                                        name = name,
                                        id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                    )
                                } else null
                            }
                            .orEmpty(),
                    album =
                        renderer.flexColumns
                            .getOrNull(2)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.let {
                                val albumId = it.navigationEndpoint?.browseEndpoint?.browseId
                                if (it.text.isNotBlank()) {
                                    Album(name = it.text, id = albumId)
                                } else null
                            },
                    duration = null,
                    thumbnail = thumbnailUrl.orEmpty(),
                    explicit =
                        renderer.badges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null,
                )
            }
            renderer.isArtist -> {
                val browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId?.takeIf { it.isNotBlank() }
                    ?: return null
                ArtistItem(
                    id = browseId,
                    title = title,
                    thumbnail = thumbnailUrl,
                    shuffleEndpoint =
                        renderer.menu
                            ?.menuRenderer
                            ?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint,
                    radioEndpoint =
                        renderer.menu
                            ?.menuRenderer
                            ?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint,
                )
            }
            renderer.isAlbum -> {
                val browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId?.takeIf { it.isNotBlank() }
                    ?: return null
                val secondaryLine =
                    renderer.flexColumns
                        .getOrNull(1)
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text
                        ?.runs
                        ?.splitBySeparator()
                AlbumItem(
                    browseId = browseId,
                    playlistId =
                        renderer.menu
                            ?.menuRenderer
                            ?.items
                            ?.find {
                                it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                            }?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint
                            ?.playlistId
                            ?.takeIf { it.isNotBlank() }
                            ?: browseId,
                    title = title,
                    artists =
                        secondaryLine?.getOrNull(1)?.oddElements()?.mapNotNull {
                            val name = it.text.trim()
                            if (name.isNotEmpty()) {
                                Artist(
                                    name = name,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } else null
                        }?.takeIf { it.isNotEmpty() },
                    year =
                        secondaryLine
                            ?.lastOrNull()
                            ?.firstOrNull()
                            ?.text
                            ?.toIntOrNull(),
                    thumbnail = thumbnailUrl.orEmpty(),
                    explicit =
                        renderer.badges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null,
                )
            }
            else -> null
        }
    }
}
