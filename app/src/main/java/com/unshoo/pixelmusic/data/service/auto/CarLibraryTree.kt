package com.unshoo.pixelmusic.data.service.auto

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.unshoo.pixelmusic.data.database.EngagementDao
import com.unshoo.pixelmusic.data.database.youtube.AppDatabase
import com.unshoo.pixelmusic.data.model.Album
import com.unshoo.pixelmusic.data.model.Artist
import com.unshoo.pixelmusic.data.model.Playlist
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.preferences.PlaylistPreferencesRepository
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.remote.youtube.toNativeSong
import com.unshoo.pixelmusic.data.repository.MusicRepository
import com.unshoo.pixelmusic.utils.ContentFilterUtils
import com.unshoo.pixelmusic.utils.MediaItemBuilder
import com.unshoo.pixelmusic.utils.OfflineAudioResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository
import com.unshoo.pixelmusic.data.stats.StatsTimeRange
import com.unshoo.pixelmusic.utils.YouTubeIdUtils
import unshoo.ianshulyadav.pixelmusic.innertube.models.filterVideo
import unshoo.ianshulyadav.pixelmusic.innertube.models.WatchEndpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber


@Singleton
class CarLibraryTree @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val engagementDao: EngagementDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val playbackStatsRepository: PlaybackStatsRepository
) {

    private val songCache = ConcurrentHashMap<String, Song>()
    @Volatile private var lastSearchQuery: String? = null
    @Volatile var lastSearchResults: List<MediaItem> = emptyList()
        private set
    @Volatile var lastSearchSongs: List<Song> = emptyList()
        private set

    companion object {
        const val ROOT_ID = "root"

        /** A virtual root used when a car asks for "recent" through LibraryParams. */
        const val RECENTS_ID = "recents"

        const val FAVORITES_ID = "favorites"
        const val PLAYLISTS_ID = "playlists"
        const val MORE_ID = "more"
        const val HISTORY_ID = "history"
        const val DOWNLOADS_ID = "downloads"
        const val ALBUMS_ID = "albums"
        const val ARTISTS_ID = "artists"
        const val SONGS_ID = "songs"
        const val SEARCH_ID = "search"

        private const val ALBUM_PREFIX = "album:"
        private const val ARTIST_PREFIX = "artist:"
        private const val PLAYLIST_PREFIX = "playlist:"
        private const val SONG_PREFIX = "song:"

        /** What a queue context is. Kept in the item's extras so the service needs no state. */
        const val KEY_CONTEXT_TYPE = "com.unshoo.pixelmusic.auto.CONTEXT_TYPE"
        const val KEY_CONTEXT_ID = "com.unshoo.pixelmusic.auto.CONTEXT_ID"
        const val KEY_CONTEXT_PARENT = "com.unshoo.pixelmusic.auto.CONTEXT_PARENT"

        const val CONTEXT_RECENTS = "recents"
        const val CONTEXT_HISTORY = "history"
        const val CONTEXT_FAVORITES = "favorites"
        const val CONTEXT_DOWNLOADS = "downloads"
        const val CONTEXT_ALL_SONGS = "all_songs"
        const val CONTEXT_ALBUM = "album"
        const val CONTEXT_ARTIST = "artist"
        const val CONTEXT_PLAYLIST = "playlist"
        const val CONTEXT_SEARCH = "search"

        private const val MAX_RECENTS = 50
        private const val MAX_SEARCH = 40

        // Android Auto content-style hints.
        const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.extra.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_CONTENT_STYLE_SUPPORTED_AX = "androidx.media.contentstyle.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_PLAYABLE_HINT"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_PLAYABLE_HINT"

        const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
        const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 2
        const val CONTENT_STYLE_CATEGORY_LIST_ITEM_HINT_VALUE = 3
        const val CONTENT_STYLE_CATEGORY_GRID_ITEM_HINT_VALUE = 4

        private fun categoryStyle(isGrid: Boolean = false) = styleExtras(
            if (isGrid) CONTENT_STYLE_CATEGORY_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_CATEGORY_LIST_ITEM_HINT_VALUE,
            CONTENT_STYLE_LIST_ITEM_HINT_VALUE
        )

        fun styleExtras(browsableHint: Int, playableHint: Int): Bundle = Bundle().apply {
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY, playableHint)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED_AX, true)
        }

        private fun gridStyle() = styleExtras(
            CONTENT_STYLE_GRID_ITEM_HINT_VALUE,
            CONTENT_STYLE_LIST_ITEM_HINT_VALUE
        )

        private fun listStyle() = styleExtras(
            CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
            CONTENT_STYLE_LIST_ITEM_HINT_VALUE
        )

        /** The DB id behind a `song:` media id, or the id it already was. */
        fun songIdOf(mediaId: String): String = mediaId.removePrefix(SONG_PREFIX)
    }

    fun rememberSong(song: Song) {
        songCache[song.id] = song
        songCache[SONG_PREFIX + song.id] = song
        if (song.id.startsWith("youtube_")) {
            val bare = song.id.removePrefix("youtube_")
            songCache[bare] = song
            songCache[SONG_PREFIX + bare] = song
        }
    }

    suspend fun findSong(mediaId: String): Song? {
        val bareId = songIdOf(mediaId)
        songCache[bareId]?.let { return it }
        songCache[mediaId]?.let { return it }

        // 1. Try local music repository
        val local = musicRepository.getSong(bareId).first()
        if (local != null) {
            rememberSong(local)
            return local
        }

        // 2. Try downloaded songs
        val downloaded = downloadedSongs().firstOrNull { it.id == bareId || it.id == mediaId }
        if (downloaded != null) {
            rememberSong(downloaded)
            return downloaded
        }

        // 3. Try AppDatabase youtube song repository
        val cleanYtId = if (bareId.startsWith("youtube_")) bareId.removePrefix("youtube_") else bareId
        val ytSong = runCatching {
            AppDatabase.getInstance(context).songRepository().getSong(cleanYtId)
        }.getOrNull()
        if (ytSong != null) {
            val native = ytSong.toNativeSong()
            rememberSong(native)
            return native
        }

        return null
    }

    private fun autoResourceUri(resourceId: Int): Uri = Uri.Builder()
        .scheme(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath("drawable")
        .appendPath(context.resources.getResourceEntryName(resourceId))
        .build()

    // ---- browse -------------------------------------------------------------

    fun moreItems(): List<MediaItem> = listOf(
        folder(
            mediaId = HISTORY_ID,
            title = "Recently played",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_recents),
            isCategory = true
        ),
        folder(
            mediaId = DOWNLOADS_ID,
            title = "Downloaded songs",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_downloads),
            isCategory = true
        ),
        folder(
            mediaId = ALBUMS_ID,
            title = "Albums",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_albums),
            isCategory = true
        ),
        folder(
            mediaId = ARTISTS_ID,
            title = "Artists",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_artists),
            isCategory = true
        ),
        folder(
            mediaId = SONGS_ID,
            title = "All Songs",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_songs),
            isCategory = true
        ),
    )

    fun rootItems(selectedId: String? = null): List<MediaItem> {
        return listOf(
            folder(
                mediaId = RECENTS_ID,
                title = "For you",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_for_you_filled),
                isCategory = true
            ),
            folder(
                mediaId = FAVORITES_ID,
                title = "Liked",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_favorites_filled),
                isCategory = true
            ),
            folder(
                mediaId = PLAYLISTS_ID,
                title = "Playlists",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_playlists),
                isCategory = true
            ),
            folder(
                mediaId = HISTORY_ID,
                title = "Recently played",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_recents),
                isCategory = true
            ),
            folder(
                mediaId = DOWNLOADS_ID,
                title = "Downloaded songs",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_downloads),
                isCategory = true
            ),
            folder(
                mediaId = ALBUMS_ID,
                title = "Albums",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_albums),
                isCategory = true
            ),
            folder(
                mediaId = ARTISTS_ID,
                title = "Artists",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_artists),
                isCategory = true
            ),
            folder(
                mediaId = SONGS_ID,
                title = "All Songs",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                artworkUri = autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_songs),
                isCategory = true
            ),
        )
    }

    fun rootItem(isRecentRoot: Boolean = false): MediaItem = folder(
        mediaId = if (isRecentRoot) RECENTS_ID else ROOT_ID,
        title = if (isRecentRoot) "For you" else "PixelMusic",
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        artworkUri = if (isRecentRoot) autoResourceUri(com.unshoo.pixelmusic.R.drawable.ic_auto_for_you_filled) else null,
        isCategory = isRecentRoot
    )

    suspend fun children(parentId: String, page: Int, pageSize: Int, selectedId: String? = null): List<MediaItem> {
        val offset = (page.coerceAtLeast(0).toLong() * pageSize.coerceAtLeast(0).toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val limit = if (pageSize > 0) pageSize else Int.MAX_VALUE

        return when {
            parentId == ROOT_ID -> rootItems(selectedId)
            parentId == MORE_ID -> moreItems()
            parentId == RECENTS_ID -> forYouSongs().drop(offset).take(limit)
                .map { song(it, CONTEXT_RECENTS, null, RECENTS_ID) }
            parentId == HISTORY_ID -> recentSongs().drop(offset).take(limit)
                .map { song(it, CONTEXT_HISTORY, null, HISTORY_ID) }
            parentId == FAVORITES_ID -> favoriteSongs().drop(offset).take(limit)
                .map { song(it, CONTEXT_FAVORITES, null, FAVORITES_ID) }
            parentId == DOWNLOADS_ID -> downloadedSongs().drop(offset).take(limit)
                .map { song(it, CONTEXT_DOWNLOADS, null, DOWNLOADS_ID) }
            parentId == SONGS_ID -> musicRepository.getSongsPage(limit = limit, offset = offset)
                .filter { isSongAllowed(it) }
                .onEach { rememberSong(it) }
                .map { song(it, CONTEXT_ALL_SONGS, null, SONGS_ID) }
            parentId == PLAYLISTS_ID -> playlists().drop(offset).take(limit).map(::playlistFolder)
            parentId == ALBUMS_ID -> musicRepository.getAlbumsPage(limit = limit, offset = offset, minTracks = 1)
                .map(::albumFolder)
            parentId == ARTISTS_ID -> musicRepository.getArtistsPage(limit = limit, offset = offset)
                .map(::artistFolder)
            else -> {
                val context = contextOf(parentId) ?: return emptyList()
                songsOf(context.first, context.second)
                    .drop(offset)
                    .take(limit)
                    .map { song(it, context.first, context.second, parentId) }
            }
        }
    }

    suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT_ID || mediaId == RECENTS_ID -> rootItem(isRecentRoot = mediaId == RECENTS_ID)
        mediaId == FAVORITES_ID || mediaId == PLAYLISTS_ID || mediaId == MORE_ID || mediaId == HISTORY_ID ||
            mediaId == DOWNLOADS_ID || mediaId == ALBUMS_ID || mediaId == ARTISTS_ID || mediaId == SONGS_ID ->
            rootItems().firstOrNull { it.mediaId == mediaId } ?: moreItems().firstOrNull { it.mediaId == mediaId }
        mediaId.startsWith(ALBUM_PREFIX) -> {
            val id = mediaId.removePrefix(ALBUM_PREFIX).toLongOrNull() ?: return null
            musicRepository.getAlbumById(id).first()?.let(::albumFolder)
        }
        mediaId.startsWith(ARTIST_PREFIX) -> {
            val id = mediaId.removePrefix(ARTIST_PREFIX).toLongOrNull() ?: return null
            musicRepository.getArtistById(id).first()?.let(::artistFolder)
        }
        mediaId.startsWith(PLAYLIST_PREFIX) -> {
            val id = mediaId.removePrefix(PLAYLIST_PREFIX)
            playlists().firstOrNull { it.id == id }?.let(::playlistFolder)
        }
        else -> findSong(mediaId)?.let { song(it, CONTEXT_ALL_SONGS, null, SONGS_ID) }
    }


    private suspend fun isSongAllowed(song: Song, query: String? = null): Boolean {
        val minDuration = runCatching { userPreferencesRepository.minSongDurationFlow.first() }.getOrDefault(10000)
        if (song.duration in 1 until minDuration) return false

        val pureYtMusicOnly = runCatching { userPreferencesRepository.pureYtMusicOnlyFlow.first() }.getOrDefault(false)
        val hideVideo = runCatching { userPreferencesRepository.hideVideoFlow.first() }.getOrDefault(false)
        if ((pureYtMusicOnly || hideVideo) && ContentFilterUtils.isVideoSong(song)) {
            return false
        }

        val blockedReleaseIds = runCatching { userPreferencesRepository.blockedReleaseIdsFlow.first() }.getOrDefault(emptySet())
        if (blockedReleaseIds.contains(song.id) || (song.albumId != 0L && blockedReleaseIds.contains(song.albumId.toString()))) {
            return false
        }

        val notInterestedSongIds = runCatching { userPreferencesRepository.notInterestedSongIdsFlow.first() }.getOrDefault(emptySet())
        if (notInterestedSongIds.contains(song.id)) {
            return false
        }

        val filterKeywords = runCatching { userPreferencesRepository.filterKeywordsFlow.first() }.getOrDefault(emptyList())
        val queryLower = query?.trim()?.lowercase().orEmpty()
        val activeFilterKeywords = if (queryLower.isNotEmpty()) {
            filterKeywords.filterNot { queryLower.contains(it.lowercase()) }
        } else {
            filterKeywords
        }

        if (ContentFilterUtils.matchesFilterKeywords(song.title, song.artist, song.album, activeFilterKeywords)) {
            return false
        }

        val filterCoverAndLofi = runCatching { userPreferencesRepository.filterCoverAndLofiFlow.first() }.getOrDefault(false)
        if (filterCoverAndLofi && !queryLower.contains("cover") && !queryLower.contains("lofi") && !queryLower.contains("lo-fi")) {
            if (ContentFilterUtils.isCoverOrLofi(song, activeFilterKeywords)) {
                return false
            }
        }

        return true
    }

    suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        if (trimmed.equals(lastSearchQuery, ignoreCase = true) && lastSearchResults.isNotEmpty()) {
            return@withContext lastSearchResults
        }

        val results = LinkedHashMap<String, MediaItem>()
        val foundSongs = ArrayList<Song>()

        // 1. Search local songs
        runCatching {
            musicRepository.searchSongs(trimmed).first()
                .filter { isSongAllowed(it, trimmed) }
                .take(MAX_SEARCH)
                .forEach { s ->
                    rememberSong(s)
                    foundSongs.add(s)
                    results[SONG_PREFIX + s.id] = song(s, CONTEXT_SEARCH, trimmed, "$SEARCH_ID:$trimmed")
                }
        }

        // 2. Search YouTube Music online
        val isLocalMode = runCatching { userPreferencesRepository.localModeEnabledFlow.first() }.getOrDefault(false)
        if (!isLocalMode) {
            // First try searchSummary (InnerTube ranked search - same as in-app search!)
            runCatching {
                val summaryResult = YouTube.searchSummary(trimmed).getOrNull()
                val summaryItems = summaryResult?.summaries?.flatMap { it.items }.orEmpty()
                val songItems = summaryItems.filterIsInstance<SongItem>()
                for (item in songItems) {
                    val nativeSong = item.toNativeSong()
                    if (isSongAllowed(nativeSong, trimmed)) {
                        rememberSong(nativeSong)
                        val key = SONG_PREFIX + nativeSong.id
                        if (!results.containsKey(key)) {
                            foundSongs.add(nativeSong)
                            results[key] = song(nativeSong, CONTEXT_SEARCH, trimmed, "$SEARCH_ID:$trimmed")
                            if (foundSongs.size >= MAX_SEARCH) break
                        }
                    }
                }
            }

            // If still less than 15 results, try FILTER_SONG
            if (foundSongs.size < 15) {
                runCatching {
                    val ytResult = YouTube.search(trimmed, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    val ytSongs = ytResult?.items?.filterIsInstance<SongItem>().orEmpty()
                    for (item in ytSongs) {
                        val nativeSong = item.toNativeSong()
                        if (isSongAllowed(nativeSong, trimmed)) {
                            rememberSong(nativeSong)
                            val key = SONG_PREFIX + nativeSong.id
                            if (!results.containsKey(key)) {
                                foundSongs.add(nativeSong)
                                results[key] = song(nativeSong, CONTEXT_SEARCH, trimmed, "$SEARCH_ID:$trimmed")
                                if (foundSongs.size >= MAX_SEARCH) break
                            }
                        }
                    }
                }
            }

            // Fallback to SongDataSource if still empty
            if (foundSongs.isEmpty()) {
                runCatching {
                    val rawSongs = com.unshoo.pixelmusic.data.remote.youtube.SongDataSource().search(trimmed)
                    for (ytSong in rawSongs) {
                        val nativeSong = ytSong.toNativeSong()
                        if (isSongAllowed(nativeSong, trimmed)) {
                            rememberSong(nativeSong)
                            val key = SONG_PREFIX + nativeSong.id
                            if (!results.containsKey(key)) {
                                foundSongs.add(nativeSong)
                                results[key] = song(nativeSong, CONTEXT_SEARCH, trimmed, "$SEARCH_ID:$trimmed")
                                if (foundSongs.size >= MAX_SEARCH) break
                            }
                        }
                    }
                }
            }
        }

        // 3. Search local albums & artists
        runCatching {
            musicRepository.searchAlbums(trimmed).first()
                .take(6)
                .forEach { results[ALBUM_PREFIX + it.id] = albumFolder(it) }
        }
        runCatching {
            musicRepository.searchArtists(trimmed).first()
                .take(6)
                .forEach { results[ARTIST_PREFIX + it.id] = artistFolder(it) }
        }

        val list = results.values.toList()
        lastSearchQuery = trimmed
        lastSearchResults = list
        lastSearchSongs = foundSongs
        list
    }

    // ---- the queue a folder represents --------------------------------------

    suspend fun songsOf(contextType: String, contextId: String?): List<Song> = when (contextType) {
        CONTEXT_RECENTS -> forYouSongs()
        CONTEXT_HISTORY -> recentSongs()
        CONTEXT_FAVORITES -> favoriteSongs()
        CONTEXT_DOWNLOADS -> downloadedSongs()
        CONTEXT_ALL_SONGS -> musicRepository.getAllSongsOnce().filter { isSongAllowed(it) }.onEach { rememberSong(it) }
        CONTEXT_SEARCH -> {
            val trimmed = contextId?.trim().orEmpty()
            if (trimmed.equals(lastSearchQuery, ignoreCase = true) && lastSearchSongs.isNotEmpty()) {
                lastSearchSongs
            } else if (trimmed.isNotEmpty()) {
                search(trimmed)
                lastSearchSongs
            } else {
                lastSearchSongs.ifEmpty { songCache.values.toList() }
            }
        }
        CONTEXT_ALBUM -> contextId?.toLongOrNull()
            ?.let { musicRepository.getSongsForAlbum(it).first().onEach { s -> rememberSong(s) } }
            .orEmpty()
        CONTEXT_ARTIST -> contextId?.toLongOrNull()
            ?.let { musicRepository.getSongsForArtist(it).first().onEach { s -> rememberSong(s) } }
            .orEmpty()
        CONTEXT_PLAYLIST -> contextId?.let { songsForPlaylist(it) }.orEmpty()
        else -> emptyList()
    }

    fun contextOfItem(item: MediaItem): Pair<String, String?>? {
        val extras = item.mediaMetadata.extras ?: return null
        val type = extras.getString(KEY_CONTEXT_TYPE)?.takeIf { it.isNotBlank() } ?: return null
        return type to extras.getString(KEY_CONTEXT_ID)
    }

    fun contextOf(parentId: String): Pair<String, String?>? = when {
        parentId == RECENTS_ID -> CONTEXT_RECENTS to null
        parentId == HISTORY_ID -> CONTEXT_HISTORY to null
        parentId == FAVORITES_ID -> CONTEXT_FAVORITES to null
        parentId == DOWNLOADS_ID -> CONTEXT_DOWNLOADS to null
        parentId == SONGS_ID -> CONTEXT_ALL_SONGS to null
        parentId == SEARCH_ID || parentId.startsWith("$SEARCH_ID:") ->
            CONTEXT_SEARCH to parentId.removePrefix("$SEARCH_ID:").takeIf { it.isNotBlank() }
        parentId.startsWith(ALBUM_PREFIX) -> CONTEXT_ALBUM to parentId.removePrefix(ALBUM_PREFIX)
        parentId.startsWith(ARTIST_PREFIX) -> CONTEXT_ARTIST to parentId.removePrefix(ARTIST_PREFIX)
        parentId.startsWith(PLAYLIST_PREFIX) -> CONTEXT_PLAYLIST to parentId.removePrefix(PLAYLIST_PREFIX)
        else -> null
    }

    // ---- item builders ------------------------------------------------------

    private fun folder(
        mediaId: String,
        title: String,
        mediaType: Int,
        artworkUri: Uri? = null,
        isGrid: Boolean = false,
        isCategory: Boolean = false
    ): MediaItem {
        val extras = when {
            isCategory -> categoryStyle(isGrid)
            isGrid -> gridStyle()
            else -> listStyle()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .setExtras(extras)
        artworkUri?.let(metadata::setArtworkUri)

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun albumFolder(album: Album): MediaItem = folder(
        mediaId = ALBUM_PREFIX + album.id,
        title = album.title,
        mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
        artworkUri = MediaItemBuilder.externalControllerArtworkUri(context, album.albumArtUriString),
        isGrid = true
    )

    private fun artistFolder(artist: Artist): MediaItem = folder(
        mediaId = ARTIST_PREFIX + artist.id,
        title = artist.name,
        mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
        artworkUri = MediaItemBuilder.externalControllerArtworkUri(context, artist.effectiveImageUrl),
        isGrid = true
    )

    private fun playlistFolder(playlist: Playlist): MediaItem = folder(
        mediaId = PLAYLIST_PREFIX + playlist.id,
        title = playlist.name,
        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
        artworkUri = MediaItemBuilder.externalControllerArtworkUri(context, playlist.coverImageUri),
        isGrid = true
    )

    private fun song(
        song: Song,
        contextType: String?,
        contextId: String?,
        parentId: String?
    ): MediaItem {
        rememberSong(song)
        val extras = Bundle().apply {
            contextType?.let { putString(KEY_CONTEXT_TYPE, it) }
            contextId?.let { putString(KEY_CONTEXT_ID, it) }
            parentId?.let { putString(KEY_CONTEXT_PARENT, it) }
            putLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION, song.duration.coerceAtLeast(0L))
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.displayArtist)
            .setAlbumTitle(song.album)
            .setDurationMs(song.duration.takeIf { it > 0 })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
        MediaItemBuilder.externalControllerArtworkUri(context, song.albumArtUriString)
            ?.let(metadata::setArtworkUri)

        return MediaItem.Builder()
            .setMediaId(SONG_PREFIX + song.id)
            .setUri(MediaItemBuilder.playbackUri(song))
            .setMimeType(MediaItemBuilder.playbackMimeType(song))
            .setMediaMetadata(metadata.build())
            .build()
    }

    @Volatile private var cachedForYouSongs: List<Song> = emptyList()
    @Volatile private var lastForYouFetchTimeMs: Long = 0L
    private val forYouMutex = Mutex()
    private val FOR_YOU_CACHE_TTL_MS = 2 * 60 * 1000L // 2 minutes (keeps it fresh and rotatory)

    fun invalidateForYouCache() {
        cachedForYouSongs = emptyList()
        lastForYouFetchTimeMs = 0L
    }

    suspend fun forYouSongs(forceRefresh: Boolean = false): List<Song> {
        if (!forceRefresh && cachedForYouSongs.isNotEmpty() &&
            System.currentTimeMillis() - lastForYouFetchTimeMs < FOR_YOU_CACHE_TTL_MS
        ) {
            return cachedForYouSongs
        }

        return forYouMutex.withLock {
            if (!forceRefresh && cachedForYouSongs.isNotEmpty() &&
                System.currentTimeMillis() - lastForYouFetchTimeMs < FOR_YOU_CACHE_TTL_MS
            ) {
                return@withLock cachedForYouSongs
            }

            val result = withContext(Dispatchers.IO) {
                buildForYouMix()
            }

            cachedForYouSongs = result
            lastForYouFetchTimeMs = System.currentTimeMillis()
            result.forEach { rememberSong(it) }
            result
        }
    }

    private suspend fun buildForYouMix(): List<Song> {
        // 1. Most played songs of the user according to the week and all time (top 20-25 candidates)
        val weeklySummary = runCatching {
            playbackStatsRepository.loadSummary(
                range = StatsTimeRange.WEEK,
                songs = emptyList()
            )
        }.getOrNull()

        val weeklyTopIds = weeklySummary?.songs?.map { it.songId }.orEmpty()
        val allTimeTopIds = runCatching {
            engagementDao.getTopPlayedSongs(25).map { it.songId }
        }.getOrDefault(emptyList())

        val combinedTopIds = (weeklyTopIds + allTimeTopIds).distinct()
        val topCandidates = resolveSongsByIds(combinedTopIds).filter { isSongAllowed(it) }

        // Highly rotatory: Shuffle the top 15-20 most played songs so that each session/opening
        // rotates dynamically among the user's top favorites instead of always anchoring on the single #1 track
        val rotatedTopSongs = topCandidates.take(20).shuffled()

        // 2. Random liked songs
        val likedSongs = favoriteSongs().shuffled()

        // 3. Downloaded songs only (mixed in, NOT local device library!)
        val downloads = downloadedSongs().shuffled()

        // 4. Recent history
        val recents = recentSongs().shuffled()

        // 5. Gather rotating seeds to fetch fresh recommendations from YouTube
        // Pick 2 random seeds from rotatedTopSongs and 1 from likedSongs
        val seeds = mutableListOf<Song>()
        rotatedTopSongs.firstOrNull()?.let { seeds.add(it) }
        likedSongs.firstOrNull()?.let { seeds.add(it) }
        if (rotatedTopSongs.size > 1) {
            seeds.add(rotatedTopSongs[1])
        } else if (recents.isNotEmpty()) {
            seeds.add(recents.first())
        }

        // Fetch fresh songs from YouTube using the rotating seeds
        val freshYouTubeSongs = fetchFreshYouTubeMix(seeds)

        val result = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()
        val seenSignatures = mutableSetOf<String>()

        fun normalizeText(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

        fun tryAdd(song: Song?): Boolean {
            if (song == null) return false
            val cleanId = (song.youtubeId?.takeIf { it.isNotBlank() }
                ?: YouTubeIdUtils.extractVideoId(song.contentUriString).takeIf { it.isNotBlank() }
                ?: YouTubeIdUtils.extractVideoId(song.id).takeIf { it.isNotBlank() }
                ?: song.id.removePrefix("youtube_")).lowercase()

            val sig = "${normalizeText(song.title)}|${normalizeText(song.artist)}"

            if (seenIds.contains(cleanId)) return false
            if (sig.isNotBlank() && seenSignatures.contains(sig)) return false

            seenIds.add(cleanId)
            if (sig.isNotBlank()) seenSignatures.add(sig)
            result.add(song)
            return true
        }

        var topIndex = 0
        var freshIndex = 0
        var likedIndex = 0
        var downloadIndex = 0

        // Slot 0: Rotating top played song (dynamically chosen from top 10-20)
        if (rotatedTopSongs.isNotEmpty()) {
            tryAdd(rotatedTopSongs.getOrNull(topIndex++))
        }

        // Interleave so that most songs (~75-80%) are fresh from YouTube
        while (result.size < 28 &&
            (freshIndex < freshYouTubeSongs.size || topIndex < rotatedTopSongs.size ||
             likedIndex < likedSongs.size || downloadIndex < downloads.size)
        ) {
            var addedAny = false

            // 2 fresh YouTube songs
            repeat(2) {
                if (freshIndex < freshYouTubeSongs.size) {
                    if (tryAdd(freshYouTubeSongs.getOrNull(freshIndex++))) addedAny = true
                }
            }

            // 1 liked song
            if (likedIndex < likedSongs.size) {
                if (tryAdd(likedSongs.getOrNull(likedIndex++))) addedAny = true
            }

            // 2 fresh YouTube songs
            repeat(2) {
                if (freshIndex < freshYouTubeSongs.size) {
                    if (tryAdd(freshYouTubeSongs.getOrNull(freshIndex++))) addedAny = true
                }
            }

            // 1 rotated top played song
            if (topIndex < rotatedTopSongs.size) {
                if (tryAdd(rotatedTopSongs.getOrNull(topIndex++))) addedAny = true
            }

            // 1 downloaded song
            if (downloadIndex < downloads.size) {
                if (tryAdd(downloads.getOrNull(downloadIndex++))) addedAny = true
            }

            if (!addedAny) break
        }

        // Fill remaining slots with fresh YouTube songs
        while (result.size < 28 && freshIndex < freshYouTubeSongs.size) {
            tryAdd(freshYouTubeSongs.getOrNull(freshIndex++))
        }

        // Offline / fallback if needed (strictly downloads + recents, NO local library!)
        if (result.isEmpty()) {
            val fallback = (downloads + recents).distinctBy { it.id }
            fallback.take(20).forEach { tryAdd(it) }
        }

        return result
    }

    private suspend fun fetchFreshYouTubeMix(seeds: List<Song>): List<Song> {
        if (!hasInternet()) return emptyList()

        val pureYt = runCatching { userPreferencesRepository.pureYtMusicOnlyFlow.first() }.getOrDefault(false)
        val freshList = mutableListOf<Song>()
        val seenVideoIds = mutableSetOf<String>()

        val perSeedResults = mutableListOf<List<Song>>()
        for (seed in seeds) {
            val videoId = seed.youtubeId?.takeIf { it.isNotBlank() }
                ?: YouTubeIdUtils.extractVideoId(seed.contentUriString).takeIf { it.isNotBlank() }
                ?: YouTubeIdUtils.extractVideoId(seed.id).takeIf { it.isNotBlank() }

            if (videoId != null && videoId.length == 11 && seenVideoIds.add(videoId)) {
                val songsFromSeed = withTimeoutOrNull(4000L) {
                    try {
                        val radioResult = YouTube.next(
                            WatchEndpoint(playlistId = "RDAMVM$videoId", videoId = videoId)
                        ).getOrNull()
                        radioResult?.items
                            ?.filterIsInstance<SongItem>()
                            ?.filterVideo(pureYt)
                            ?.map { it.toNativeSong() }
                            ?.filter { isSongAllowed(it) }
                            .orEmpty()
                    } catch (e: Exception) {
                        Timber.tag("CarLibraryTree").w(e, "Failed to fetch YouTube radio mix for $videoId")
                        emptyList()
                    }
                }.orEmpty()

                if (songsFromSeed.isNotEmpty()) {
                    perSeedResults.add(songsFromSeed.shuffled())
                }
            }
        }

        // Interleave songs from each rotating seed so recommendations are diverse and balanced
        val maxSeedLen = perSeedResults.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxSeedLen) {
            for (seedList in perSeedResults) {
                if (i < seedList.size) {
                    freshList.add(seedList[i])
                }
            }
            if (freshList.size >= 40) break
        }

        // If no seeds or seeds returned empty, fetch top / trending music from YouTube
        if (freshList.isEmpty()) {
            withTimeoutOrNull(4000L) {
                try {
                    val searchResult = YouTube.search("Top Songs", YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    val items = searchResult?.items
                        ?.filterIsInstance<SongItem>()
                        ?.filterVideo(pureYt)
                        ?.map { it.toNativeSong() }
                        ?.filter { isSongAllowed(it) }
                        .orEmpty()
                    freshList.addAll(items)
                } catch (e: Exception) {
                    Timber.tag("CarLibraryTree").w(e, "Failed to fetch fallback YouTube songs")
                }
            }
        }

        return freshList
    }

    private fun hasInternet(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm?.activeNetwork)
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveSongsByIds(ids: List<String>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val byId = musicRepository.getSongsByIdsOnce(ids).associateBy { it.id }.toMutableMap()
        val missingIds = ids.filter { !byId.containsKey(it) }
        if (missingIds.isNotEmpty()) {
            val ytRepo = runCatching { AppDatabase.getInstance(context).songRepository() }.getOrNull()
            missingIds.forEach { id ->
                val cleanId = id.removePrefix("youtube_")
                val ytSong = ytRepo?.getSong(cleanId)
                if (ytSong != null) {
                    val native = ytSong.toNativeSong()
                    byId[id] = native
                    byId[native.id] = native
                }
            }
        }
        return ids.mapNotNull { byId[it] ?: songCache[it] }
    }


    private suspend fun recentSongs(): List<Song> {
        val ids = engagementDao.getRecentlyPlayedSongs(MAX_RECENTS).map { it.songId }
        if (ids.isEmpty()) return emptyList()
        val byId = musicRepository.getSongsByIdsOnce(ids).associateBy { it.id }.toMutableMap()

        val missingIds = ids.filter { !byId.containsKey(it) }
        if (missingIds.isNotEmpty()) {
            val ytRepo = runCatching { AppDatabase.getInstance(context).songRepository() }.getOrNull()
            missingIds.forEach { id ->
                val cleanId = id.removePrefix("youtube_")
                val ytSong = ytRepo?.getSong(cleanId)
                if (ytSong != null) {
                    val native = ytSong.toNativeSong()
                    byId[id] = native
                    byId[native.id] = native
                }
            }
        }

        return ids.mapNotNull { byId[it] ?: songCache[it] }
            .filter { isSongAllowed(it) }
            .also { list ->
                list.forEach { rememberSong(it) }
            }
    }

    private suspend fun favoriteSongs(): List<Song> {
        val favs = musicRepository.getFavoriteSongsOnce()
            .filter { isSongAllowed(it) }
        favs.forEach { rememberSong(it) }
        return favs
    }

    private suspend fun downloadedSongs(): List<Song> {
        val ytDownloaded = runCatching {
            AppDatabase.getInstance(context).songRepository().getDownloadedSongs()
                .map { it.toNativeSong() }
        }.getOrDefault(emptyList())

        val offlineYt = ytDownloaded.filter {
            OfflineAudioResolver.hasOfflineAudio(context, it)
        }.ifEmpty { ytDownloaded }

        val localDownloaded = runCatching {
            musicRepository.getAllSongsOnce().filter { song ->
                song.path.contains("/download", ignoreCase = true)
            }
        }.getOrDefault(emptyList())

        val combined = (offlineYt + localDownloaded).distinctBy { it.id }
            .filter { isSongAllowed(it) }
        combined.forEach { rememberSong(it) }
        return combined
    }

    private suspend fun songsForPlaylist(playlistId: String): List<Song> {
        val cleanId = playlistId.removePrefix("VL")
        val playlist = playlists().firstOrNull { it.id == playlistId || it.id.removePrefix("VL") == cleanId }

        val isLikedMusic = playlistId == "LM" || cleanId == "LM" || playlistId == "liked" ||
            playlist?.name?.equals("Liked Music", ignoreCase = true) == true

        if (isLikedMusic) {
            val favs = favoriteSongs()
            if (favs.isNotEmpty()) return favs
        }

        var songs = playlist?.let { musicRepository.getSongsByIdsOnce(it.songIds) }.orEmpty()
        val neededSongCount = playlist?.songIds?.size ?: 0

        if (songs.size < neededSongCount || songs.isEmpty()) {
            val cachedYtPlaylist = runCatching {
                val repo = AppDatabase.getInstance(context).playlistRepository()
                repo.getPlaylistById(cleanId)
                    ?: repo.getPlaylistById("VL$cleanId")
                    ?: repo.getPlaylistById(playlistId)
            }.getOrNull()

            if (cachedYtPlaylist != null && cachedYtPlaylist.songs.isNotEmpty()) {
                val nativeYtSongs = cachedYtPlaylist.songs.map { it.toNativeSong() }
                runCatching { musicRepository.insertYoutubeSongs(nativeYtSongs) }
                songs = nativeYtSongs
            }
        }

        if (playlist != null && songs.size < playlist.songIds.size) {
            val missingIds = playlist.songIds.filter { id -> songs.none { it.id == id || it.id == id.removePrefix("youtube_") } }
            if (missingIds.isNotEmpty()) {
                val ytSongRepo = runCatching { AppDatabase.getInstance(context).songRepository() }.getOrNull()
                if (ytSongRepo != null) {
                    val additional = missingIds.mapNotNull { id ->
                        val bare = id.removePrefix("youtube_")
                        ytSongRepo.getSong(bare)?.toNativeSong()
                    }
                    if (additional.isNotEmpty()) {
                        runCatching { musicRepository.insertYoutubeSongs(additional) }
                        songs = (songs + additional).distinctBy { it.id }
                    }
                }
            }
        }

        if (songs.isEmpty() && (playlist?.source == "YOUTUBE" || isLikedMusic || cleanId.startsWith("PL") || cleanId.startsWith("RD"))) {
            runCatching {
                val ytPlaylistResult = YouTube.playlist(cleanId).getOrNull()
                    ?: YouTube.playlist(playlistId).getOrNull()
                val fetched = ytPlaylistResult?.songs?.map { it.toNativeSong() }
                if (!fetched.isNullOrEmpty()) {
                    runCatching { musicRepository.insertYoutubeSongs(fetched) }
                    songs = fetched
                }
            }
        }

        songs.forEach { rememberSong(it) }
        return songs
    }

    private suspend fun playlists(): List<Playlist> =
        playlistPreferencesRepository.userPlaylistsFlow.first()
}
