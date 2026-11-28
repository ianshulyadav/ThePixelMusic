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
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicMultiRowListItemRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicResponsiveListItemRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.Run
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.WatchEndpoint
import unshoo.ianshulyadav.pixelmusic.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import unshoo.ianshulyadav.pixelmusic.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_PODCAST_EPISODE
import unshoo.ianshulyadav.pixelmusic.innertube.models.YTItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.clean
import unshoo.ianshulyadav.pixelmusic.innertube.models.oddElements
import unshoo.ianshulyadav.pixelmusic.innertube.models.splitBySeparator
import unshoo.ianshulyadav.pixelmusic.innertube.utils.parseTime
import com.unshoo.pixelmusic.utils.extractArtistsFromTitle

data class SearchResult(
    val items: List<YTItem>,
    val continuation: String? = null,
)

object SearchPage {
    fun toYTItem(
        renderer: MusicMultiRowListItemRenderer,
        defaultArtist: Artist? = null
    ): SongItem? {
        val title = renderer.title?.runs?.joinToString(separator = "") { it.text }?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val endpoint = renderer.watchEndpoint()
        val videoId = endpoint?.videoId?.takeIf { it.isNotBlank() } ?: return null
        val subtitleRuns = renderer.subtitle?.runs.orEmpty()
        val secondTitleRuns = renderer.secondTitle?.runs.orEmpty()
        val artistCandidates = (secondTitleRuns + subtitleRuns)
            .filter { run ->
                run.navigationEndpoint?.browseEndpoint?.browseId?.let {
                    it.startsWith("UC") || it.startsWith("MPSP")
                } == true
            }
            .mapNotNull { run ->
                run.text.trim().takeIf { it.isNotBlank() }?.let {
                    Artist(name = it, id = run.navigationEndpoint?.browseEndpoint?.browseId)
                }
            }
            .distinctBy { it.name.lowercase() }
        val artists = when {
            artistCandidates.isNotEmpty() -> artistCandidates
            defaultArtist != null -> listOf(defaultArtist)
            else -> {
                val fallbackText = secondTitleRuns
                    .joinToString(separator = "") { it.text }
                    .trim()
                    .takeIf { it.isNotBlank() }
                fallbackText?.let { listOf(Artist(name = it, id = null)) } ?: emptyList()
            }
        }
        val showRun = (secondTitleRuns + subtitleRuns).firstOrNull {
            it.navigationEndpoint?.browseEndpoint?.browseId?.startsWith("MPSP") == true
        }
        val duration = (subtitleRuns + secondTitleRuns)
            .asReversed()
            .firstNotNullOfOrNull { run -> parseFlexibleDurationSeconds(run.text) }

        return SongItem(
            id = videoId,
            title = title,
            artists = artists,
            album = showRun?.let {
                Album(
                    name = it.text,
                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                )
            },
            duration = duration,
            thumbnail = renderer.thumbnail?.getThumbnailUrl().orEmpty(),
            endpoint = endpoint,
            isEpisode = true,
        )
    }

    fun toYTItem(
        renderer: MusicResponsiveListItemRenderer,
        defaultArtist: Artist? = null
    ): YTItem? {
        val title = renderer.titleText ?: return null
        val thumbnail = renderer.thumbnail?.getThumbnailUrl()
        val metadata = renderer.metadataGroups()
        return when {
            renderer.isSong -> {
                val endpoint = renderer.watchEndpoint()
                val browseEndpoint = renderer.navigationEndpoint?.browseEndpoint
                val browsePageType = browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                val musicVideoType = endpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType
                val isEpisode = musicVideoType == MUSIC_VIDEO_TYPE_PODCAST_EPISODE ||
                    browsePageType == MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE ||
                    browseEndpoint?.browseId?.startsWith("MPED") == true ||
                    renderer.flexColumns.drop(1).any { col ->
                        col.musicResponsiveListItemFlexColumnRenderer.text?.runs?.any { run ->
                            val text = run.text.trim().lowercase()
                            text == "episode" || text == "podcast"
                        } == true
                    }
                val isVideo = !isEpisode && (
                    renderer.flexColumns.drop(1).any { col ->
                        col.musicResponsiveListItemFlexColumnRenderer.text?.runs?.any { run ->
                            val text = run.text.trim().lowercase()
                            text in setOf("video", "videos", "vídeo", "vídeos", "vidéo", "vidéos", "видео", "동영상", "视频", "動画")
                        } == true
                    } || (musicVideoType?.let {
                        it.isNotBlank() &&
                            it != MUSIC_VIDEO_TYPE_ATV &&
                            it != MUSIC_VIDEO_TYPE_PODCAST_EPISODE
                    } ?: false) || com.unshoo.pixelmusic.utils.ContentFilterUtils.isVideoTitle(title)
                )

                val extracted = PageHelper.extractArtists(renderer, title)
                val artists = if (extracted.isNotEmpty()) {
                    extracted
                } else if (defaultArtist != null) {
                    val (_, featArtists) = title.extractArtistsFromTitle()
                    if (featArtists.isNotEmpty()) {
                        listOf(defaultArtist) + featArtists.map { Artist(name = it, id = null) }
                    } else {
                        listOf(defaultArtist)
                    }
                } else {
                    val (_, featArtists) = title.extractArtistsFromTitle()
                    featArtists.map { Artist(name = it, id = null) }
                }

                val videoId = renderer.playlistItemData?.videoId?.takeIf { it.isNotBlank() }
                    ?: endpoint?.videoId?.takeIf { it.isNotBlank() }
                    ?: return null

                SongItem(
                    id = videoId,
                    title = title,
                    artists = artists,
                    album = PageHelper.extractAlbum(renderer)
                        ?: metadata.getOrNull(1)?.firstOrNull()?.let {
                            Album(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId,
                            )
                        },
                    duration = metadata.duration()
                        ?: renderer.flexColumns.drop(1)
                            .flatMap { it.musicResponsiveListItemFlexColumnRenderer.text?.runs.orEmpty() }
                            .asReversed()
                            .firstNotNullOfOrNull { run -> parseFlexibleDurationSeconds(run.text) },
                    thumbnail = thumbnail.orEmpty(),
                    explicit = renderer.isExplicit,
                    endpoint = endpoint,
                    isVideo = isVideo,
                    isEpisode = isEpisode,
                )
            }
            renderer.isArtist -> {
                val browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId?.takeIf { it.isNotBlank() }
                    ?: return null
                ArtistItem(
                    id = browseId,
                    title = title,
                    thumbnail = thumbnail,
                    playEndpoint = renderer.watchEndpoint(),
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
                AlbumItem(
                    browseId = browseId,
                    playlistId =
                        renderer.watchEndpoint()
                            ?.playlistId
                            ?.takeIf { it.isNotBlank() }
                            ?: browseId,
                    title = title,
                    artists = PageHelper.extractArtists(renderer, title).takeIf { it.isNotEmpty() }
                        ?: defaultArtist?.let { listOf(it) },
                    year = metadata.year(),
                    thumbnail = thumbnail.orEmpty(),
                    explicit = renderer.isExplicit,
                )
            }
            renderer.isPlaylist -> {
                val playlistMetadata = renderer.metadataGroups(clean = false)
                val browseEndpoint = renderer.navigationEndpoint?.browseEndpoint
                val id = browseEndpoint?.browseId?.removePrefix("VL")?.takeIf { it.isNotBlank() }
                    ?: renderer.watchEndpoint()?.playlistId?.removePrefix("VL")?.takeIf { it.isNotBlank() }
                    ?: return null
                val pageType = browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                PlaylistItem(
                    id = id,
                    title = title,
                    author = playlistMetadata.playlistAuthor(),
                    songCountText = PageHelper.extractPlaylistSongCountTextFromGroups(playlistMetadata),
                    thumbnail = thumbnail,
                    playEndpoint =
                        renderer.watchEndpoint(),
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
                    isPodcast = pageType == MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE || id.startsWith("MPSP"),
                )
            }
            else -> null
        }
    }
}

private val MusicResponsiveListItemRenderer.titleText: String?
    get() =
        flexColumns
            .firstOrNull()
            ?.musicResponsiveListItemFlexColumnRenderer
            ?.text
            ?.runs
            ?.joinToString(separator = "") { it.text }
            ?.takeIf { it.isNotBlank() }

private val MusicResponsiveListItemRenderer.isExplicit: Boolean
    get() =
        badges?.any {
            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
        } == true

private fun MusicResponsiveListItemRenderer.metadataGroups(clean: Boolean = true): List<List<Run>> {
    val groups =
        flexColumns
            .drop(1)
            .flatMap {
                it.musicResponsiveListItemFlexColumnRenderer.text?.runs?.splitBySeparator().orEmpty()
            }
    return if (clean) groups.clean() else groups
}

private fun MusicResponsiveListItemRenderer.watchEndpoint(): WatchEndpoint? =
    navigationEndpoint?.anyWatchEndpoint
        ?: overlay
            ?.musicItemThumbnailOverlayRenderer
            ?.content
            ?.musicPlayButtonRenderer
            ?.playNavigationEndpoint
            ?.anyWatchEndpoint

private fun List<Run>?.toArtists(): List<Artist> =
    this
        ?.oddElements()
        ?.map {
            Artist(
                name = it.text,
                id = it.navigationEndpoint?.browseEndpoint?.browseId,
            )
        }
        .orEmpty()

private fun List<List<Run>>.duration(): Int? {
    for (group in asReversed()) {
        for (run in group.asReversed()) {
            run.text.parseTime()?.let { return it }
        }
    }
    return null
}

private fun List<List<Run>>.year(): Int? {
    for (group in asReversed()) {
        for (run in group.asReversed()) {
            run.text.toIntOrNull()?.let { return it }
        }
    }
    return null
}

private fun List<List<Run>>.playlistAuthor(): Artist? {
    val authorIndex = if (size >= 3) 1 else 0
    return getOrNull(authorIndex)
        ?.firstOrNull()
        ?.let {
            Artist(
                name = it.text,
                id = it.navigationEndpoint?.browseEndpoint?.browseId,
            )
        }
}

internal fun parseFlexibleDurationSeconds(rawText: String): Int? {
    rawText.parseTime()?.let { return it }
    val clean = rawText.trim().lowercase()
    if (clean.isEmpty()) return null
    val hrMatch = Regex("""(\d+)\s*(?:hr|hrs|hour|hours|h)\b""").find(clean)?.groupValues?.get(1)?.toIntOrNull()
    val minMatch = Regex("""(\d+)\s*(?:min|mins|minute|minutes|m)\b""").find(clean)?.groupValues?.get(1)?.toIntOrNull()
    val secMatch = Regex("""(\d+)\s*(?:sec|secs|second|seconds|s)\b""").find(clean)?.groupValues?.get(1)?.toIntOrNull()
    if (hrMatch == null && minMatch == null && secMatch == null) return null
    return ((hrMatch ?: 0) * 3600) + ((minMatch ?: 0) * 60) + (secMatch ?: 0)
}
