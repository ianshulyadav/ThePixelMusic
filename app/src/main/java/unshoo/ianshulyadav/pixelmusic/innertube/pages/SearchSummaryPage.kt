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
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicCardShelfRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicResponsiveListItemRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.YTItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.filterExplicit
import unshoo.ianshulyadav.pixelmusic.innertube.models.filterVideo
import unshoo.ianshulyadav.pixelmusic.innertube.models.oddElements
import unshoo.ianshulyadav.pixelmusic.innertube.models.splitBySeparator
import unshoo.ianshulyadav.pixelmusic.innertube.utils.parseTime

data class SearchSummary(
    val title: String,
    val items: List<YTItem>,
)

data class SearchSummaryPage(
    val summaries: List<SearchSummary>,
) {
    fun filterExplicit(enabled: Boolean) =
        if (enabled) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    SearchSummary(
                        title = s.title,
                        items =
                            s.items.filterExplicit().ifEmpty {
                                return@mapNotNull null
                            },
                    )
                },
            )
        } else {
            this
        }

    fun filterVideo(enabled: Boolean = true) =
        if (enabled) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    val lowerTitle = s.title.lowercase()
                    val isVideoShelf = lowerTitle.contains("video") ||
                        lowerTitle.contains("vídeo") ||
                        lowerTitle.contains("vidéo") ||
                        lowerTitle.contains("видео") ||
                        lowerTitle.contains("동영상") ||
                        lowerTitle.contains("视频") ||
                        lowerTitle.contains("動画")
                    if (isVideoShelf) return@mapNotNull null
                    val filteredItems = s.items.filterVideo()
                    if (filteredItems.isEmpty()) return@mapNotNull null
                    SearchSummary(
                        title = s.title,
                        items = filteredItems,
                    )
                },
            )
        } else {
            this
        }

    companion object {
        fun fromMusicCardShelfRenderer(renderer: MusicCardShelfRenderer): YTItem? {
            val subtitle = renderer.subtitle.runs?.splitBySeparator()
            val isVideo = renderer.subtitle.runs?.any { run ->
                val text = run.text.trim().lowercase()
                text in setOf("video", "videos", "vídeo", "vídeos", "vidéo", "vidéos", "видео", "동영상", "视频", "動画")
            } == true || renderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs?.any { run ->
                val text = run.text.trim().lowercase()
                text.contains("video") || text.contains("vídeo") || text.contains("vidéo") || text.contains("видео")
            } == true
            return when {
                renderer.onTap.watchEndpoint != null -> {
                    SongItem(
                        id = renderer.onTap.watchEndpoint.videoId ?: return null,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists = run {
                            val artistEndpointSegment = subtitle?.firstOrNull { segment ->
                                segment.any { PageHelper.isArtistEndpoint(it) }
                            }
                            if (artistEndpointSegment != null) {
                                val list = artistEndpointSegment.oddElements().mapNotNull {
                                    val name = it.text.trim()
                                    if (name.isNotEmpty() && !PageHelper.isNonAlbumMetadata(name) && !PageHelper.isMediaTypeMarker(name)) {
                                        Artist(name = name, id = it.navigationEndpoint?.browseEndpoint?.browseId)
                                    } else null
                                }
                                if (list.isNotEmpty()) return@run list
                            }

                            val fallbackSegment = subtitle?.firstOrNull { segment ->
                                val text = segment.firstOrNull()?.text?.trim().orEmpty()
                                text.isNotEmpty() && !PageHelper.isMediaTypeMarker(text) && !PageHelper.isNonAlbumMetadata(text)
                            }
                            fallbackSegment?.oddElements()?.mapNotNull {
                                val name = it.text.trim()
                                if (name.isNotEmpty() && !PageHelper.isNonAlbumMetadata(name) && !PageHelper.isMediaTypeMarker(name)) {
                                    Artist(name = name, id = it.navigationEndpoint?.browseEndpoint?.browseId)
                                } else null
                            }?.takeIf { it.isNotEmpty() }
                        } ?: return null,
                        album = PageHelper.extractAlbumFromRuns(renderer.subtitle.runs)
                            ?: subtitle?.getOrNull(2)?.firstOrNull()?.let {
                                Album(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            },
                        duration =
                            subtitle
                                ?.lastOrNull()
                                ?.firstOrNull()
                                ?.text
                                ?.parseTime(),
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                        endpoint = renderer.onTap.watchEndpoint,
                        isVideo = isVideo,
                    )
                }

                renderer.onTap.browseEndpoint?.isArtistEndpoint == true -> {
                    ArtistItem(
                        id = renderer.onTap.browseEndpoint.browseId,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        shuffleEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint,
                        radioEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MIX" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint,
                        subscriberCountText = renderer.subtitle.runs?.joinToString(separator = "") { it.text },
                    )
                }

                renderer.onTap.browseEndpoint?.isAlbumEndpoint == true -> {
                    AlbumItem(
                        browseId = renderer.onTap.browseEndpoint.browseId,
                        playlistId =
                            renderer.buttons
                                .firstOrNull()
                                ?.buttonRenderer
                                ?.command
                                ?.anyWatchEndpoint
                                ?.playlistId
                                ?: renderer.onTap.browseEndpoint.browseId,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists = subtitle?.firstOrNull { segment ->
                            segment.any { PageHelper.isArtistEndpoint(it) }
                        }?.oddElements()?.mapNotNull {
                            val name = it.text.trim()
                            if (name.isNotEmpty() && !PageHelper.isNonAlbumMetadata(name) && !PageHelper.isMediaTypeMarker(name)) {
                                Artist(name = name, id = it.navigationEndpoint?.browseEndpoint?.browseId)
                            } else null
                        } ?: subtitle?.firstOrNull { segment ->
                            val text = segment.firstOrNull()?.text?.trim().orEmpty()
                            text.isNotEmpty() && !PageHelper.isMediaTypeMarker(text) && !PageHelper.isNonAlbumMetadata(text)
                        }?.oddElements()?.mapNotNull {
                            val name = it.text.trim()
                            if (name.isNotEmpty() && !PageHelper.isNonAlbumMetadata(name) && !PageHelper.isMediaTypeMarker(name)) {
                                Artist(name = name, id = it.navigationEndpoint?.browseEndpoint?.browseId)
                            } else null
                        } ?: return null,
                        year = null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.onTap.browseEndpoint?.isPlaylistEndpoint == true -> {
                    PlaylistItem(
                        id =
                            renderer.onTap.browseEndpoint.browseId
                                .removePrefix("VL"),
                        title =
                            renderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs
                                ?.joinToString(separator = "") { it.text }
                                ?: return null,
                        author =
                            Artist(
                                id = null,
                                name = renderer.subtitle.runs?.joinToString { it.text } ?: return null,
                            ),
                        songCountText = null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        playEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "PLAY_ARROW" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint
                                ?: return null,
                        shuffleEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint
                                ?: return null,
                        radioEndpoint = null,
                    )
                }

                else -> null
            }
        }

        fun fromMusicResponsiveListItemRenderer(
            renderer: MusicResponsiveListItemRenderer,
            defaultArtist: Artist? = null
        ): YTItem? =
            SearchPage.toYTItem(renderer, defaultArtist)
    }
}
