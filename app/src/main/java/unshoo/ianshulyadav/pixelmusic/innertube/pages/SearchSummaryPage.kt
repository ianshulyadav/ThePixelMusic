/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.pages

import com.unshoo.pixelmusic.utils.extractArtistsFromTitle
import unshoo.ianshulyadav.pixelmusic.innertube.models.Album
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.Artist
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicCardShelfRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicResponsiveListItemRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicTwoRowItemRenderer
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
    val correctedQuery: String? = null,
) {
    fun filterExplicit(enabled: Boolean) =
        if (enabled) {
            SearchSummaryPage(
                summaries = summaries.mapNotNull { s ->
                    SearchSummary(
                        title = s.title,
                        items =
                            s.items.filterExplicit().ifEmpty {
                                return@mapNotNull null
                            },
                    )
                },
                correctedQuery = correctedQuery,
            )
        } else {
            this
        }

    fun filterVideo(enabled: Boolean = true) =
        if (enabled) {
            SearchSummaryPage(
                summaries = summaries.mapNotNull { s ->
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
                correctedQuery = correctedQuery,
            )
        } else {
            this
        }

    companion object {
        fun fromMusicCardShelfRenderer(renderer: MusicCardShelfRenderer): YTItem? {
            val cardTitle = renderer.title.runs
                ?.joinToString(separator = "") { it.text }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val thumbnailUrl = renderer.thumbnail.getThumbnailUrl()
            val subtitle = renderer.subtitle.runs?.splitBySeparator()
            val isVideo = renderer.subtitle.runs?.any { run ->
                val text = run.text.trim().lowercase()
                text in setOf("video", "videos", "vídeo", "vídeos", "vidéo", "vidéos", "видео", "동영상", "视频", "動画")
            } == true || renderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs?.any { run ->
                val text = run.text.trim().lowercase()
                text.contains("video") || text.contains("vídeo") || text.contains("vidéo") || text.contains("видео")
            } == true

            fun parseCardArtists(): List<Artist> {
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
                    if (list.isNotEmpty()) return list
                }

                val fallbackSegment = subtitle?.firstOrNull { segment ->
                    val text = segment.firstOrNull()?.text?.trim().orEmpty()
                    text.isNotEmpty() && !PageHelper.isMediaTypeMarker(text) && !PageHelper.isNonAlbumMetadata(text)
                }
                return fallbackSegment?.oddElements()?.mapNotNull {
                    val name = it.text.trim()
                    if (name.isNotEmpty() && !PageHelper.isNonAlbumMetadata(name) && !PageHelper.isMediaTypeMarker(name)) {
                        Artist(name = name, id = it.navigationEndpoint?.browseEndpoint?.browseId)
                    } else null
                }.orEmpty()
            }

            return when {
                renderer.onTap.watchEndpoint != null -> {
                    val songTitle = cardTitle ?: return null
                    val parsedArtists = parseCardArtists().ifEmpty {
                        val (_, featArtists) = songTitle.extractArtistsFromTitle()
                        featArtists.map { Artist(name = it, id = null) }
                    }
                    SongItem(
                        id = renderer.onTap.watchEndpoint.videoId?.takeIf { it.isNotBlank() } ?: return null,
                        title = songTitle,
                        artists = parsedArtists,
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
                        thumbnail = thumbnailUrl.orEmpty(),
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                        endpoint = renderer.onTap.watchEndpoint,
                        isVideo = isVideo,
                    )
                }

                renderer.onTap.browseEndpoint?.isArtistEndpoint == true -> {
                    val browseId = renderer.onTap.browseEndpoint.browseId.takeIf { it.isNotBlank() } ?: return null
                    ArtistItem(
                        id = browseId,
                        title = cardTitle ?: return null,
                        thumbnail = thumbnailUrl,
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
                    val browseId = renderer.onTap.browseEndpoint.browseId.takeIf { it.isNotBlank() } ?: return null
                    AlbumItem(
                        browseId = browseId,
                        playlistId =
                            renderer.buttons
                                .firstOrNull()
                                ?.buttonRenderer
                                ?.command
                                ?.anyWatchEndpoint
                                ?.playlistId
                                ?.takeIf { it.isNotBlank() }
                                ?: browseId,
                        title = cardTitle ?: return null,
                        artists = parseCardArtists().takeIf { it.isNotEmpty() },
                        year = null,
                        thumbnail = thumbnailUrl.orEmpty(),
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.onTap.browseEndpoint?.isPlaylistEndpoint == true -> {
                    val playlistId = renderer.onTap.browseEndpoint.browseId
                        .removePrefix("VL")
                        .takeIf { it.isNotBlank() }
                        ?: return null
                    val playlistTitle = cardTitle
                        ?: renderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs
                            ?.joinToString(separator = "") { it.text }
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                        ?: return null
                    val authorText = renderer.subtitle.runs
                        ?.joinToString(separator = "") { it.text }
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    PlaylistItem(
                        id = playlistId,
                        title = playlistTitle,
                        author = authorText?.let { Artist(id = null, name = it) },
                        songCountText = PageHelper.extractPlaylistSongCountText(renderer.subtitle.runs),
                        thumbnail = thumbnailUrl,
                        playEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "PLAY_ARROW" }
                                ?.buttonRenderer
                                ?.let { it.command ?: it.navigationEndpoint }
                                ?.anyWatchEndpoint,
                        shuffleEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.buttonRenderer
                                ?.let { it.command ?: it.navigationEndpoint }
                                ?.anyWatchEndpoint,
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

        fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): YTItem? {
            val title = renderer.title.runs
                ?.joinToString(separator = "") { it.text }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val thumbnailUrl = renderer.thumbnailRenderer.getThumbnailUrl()
            val explicit = renderer.subtitleBadges?.any {
                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
            } == true
            val subtitleRuns = renderer.subtitle?.runs.orEmpty()
            return when {
                renderer.isSong -> {
                    val videoId = renderer.navigationEndpoint.watchEndpoint?.videoId?.takeIf { it.isNotBlank() }
                        ?: return null
                    val artists = subtitleRuns
                        .filter { it.navigationEndpoint?.browseEndpoint?.isArtistEndpoint == true }
                        .mapNotNull { run ->
                            run.text.trim().takeIf { it.isNotBlank() }?.let {
                                Artist(name = it, id = run.navigationEndpoint?.browseEndpoint?.browseId)
                            }
                        }
                    SongItem(
                        id = videoId,
                        title = title,
                        artists = artists,
                        album = PageHelper.extractAlbum(renderer),
                        duration = null,
                        thumbnail = thumbnailUrl.orEmpty(),
                        explicit = explicit,
                        endpoint = renderer.navigationEndpoint.watchEndpoint,
                    )
                }
                renderer.isArtist -> {
                    val browseId = renderer.navigationEndpoint.browseEndpoint?.browseId?.takeIf { it.isNotBlank() }
                        ?: return null
                    ArtistItem(
                        id = browseId,
                        title = title,
                        thumbnail = thumbnailUrl,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    )
                }
                renderer.isAlbum -> {
                    val browseId = renderer.navigationEndpoint.browseEndpoint?.browseId?.takeIf { it.isNotBlank() }
                        ?: return null
                    val playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                        ?.musicPlayButtonRenderer?.playNavigationEndpoint?.anyWatchEndpoint?.playlistId
                        ?.takeIf { it.isNotBlank() }
                        ?: browseId
                    val artists = subtitleRuns
                        .filter { it.navigationEndpoint?.browseEndpoint?.isArtistEndpoint == true }
                        .mapNotNull { run ->
                            run.text.trim().takeIf { it.isNotBlank() }?.let {
                                Artist(name = it, id = run.navigationEndpoint?.browseEndpoint?.browseId)
                            }
                        }
                        .takeIf { it.isNotEmpty() }
                    AlbumItem(
                        browseId = browseId,
                        playlistId = playlistId,
                        title = title,
                        artists = artists,
                        thumbnail = thumbnailUrl.orEmpty(),
                        explicit = explicit,
                    )
                }
                renderer.isPlaylist -> {
                    val id = renderer.navigationEndpoint.browseEndpoint?.browseId?.removePrefix("VL")?.takeIf { it.isNotBlank() }
                        ?: return null
                    val pageType = renderer.navigationEndpoint.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                    PlaylistItem(
                        id = id,
                        title = title,
                        author = subtitleRuns.firstOrNull()?.text?.takeIf { it.isNotBlank() }?.let { Artist(name = it, id = null) },
                        songCountText = PageHelper.extractPlaylistSongCountText(subtitleRuns),
                        thumbnail = thumbnailUrl,
                        playEndpoint = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint?.anyWatchEndpoint,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                        isPodcast = pageType == "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE" || id.startsWith("MPSP"),
                    )
                }
                else -> null
            }
        }
    }
}
