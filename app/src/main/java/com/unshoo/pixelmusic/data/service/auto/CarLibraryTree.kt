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
import com.unshoo.pixelmusic.data.remote.youtube.toNativeSong
import com.unshoo.pixelmusic.data.repository.MusicRepository
import com.unshoo.pixelmusic.utils.MediaItemBuilder
import com.unshoo.pixelmusic.utils.OfflineAudioResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CarLibraryTree @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val engagementDao: EngagementDao
) {

    private val songCache = ConcurrentHashMap<String, Song>()
    @Volatile private var lastSearchQuery: String? = null
    @Volatile private var lastSearchResults: List<MediaItem> = emptyList()

    companion object {
        const val ROOT_ID = "root"

        /** A virtual root used when a car asks for "recent" through LibraryParams. */
        const val RECENTS_ID = "recents"

        const val FAVORITES_ID = "favorites"
        const val PLAYLISTS_ID = "playlists"
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

    // ---- browse -------------------------------------------------------------

    fun rootItems(): List<MediaItem> = listOf(
        folder(RECENTS_ID, "Recently played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        folder(FAVORITES_ID, "Liked songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        folder(PLAYLISTS_ID, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        folder(DOWNLOADS_ID, "Downloaded songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        folder(ALBUMS_ID, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        folder(ARTISTS_ID, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        folder(SONGS_ID, "All Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
    )

    fun rootItem(isRecentRoot: Boolean = false): MediaItem = folder(
        if (isRecentRoot) RECENTS_ID else ROOT_ID,
        if (isRecentRoot) "Recently played" else "PixelMusic",
        MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    )

    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val offset = (page.coerceAtLeast(0).toLong() * pageSize.coerceAtLeast(0).toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val limit = if (pageSize > 0) pageSize else Int.MAX_VALUE

        return when {
            parentId == ROOT_ID -> rootItems()
            parentId == RECENTS_ID -> recentSongs().drop(offset).take(limit)
                .map { song(it, CONTEXT_RECENTS, null, RECENTS_ID) }
            parentId == FAVORITES_ID -> favoriteSongs().drop(offset).take(limit)
                .map { song(it, CONTEXT_FAVORITES, null, FAVORITES_ID) }
            parentId == DOWNLOADS_ID -> downloadedSongs().drop(offset).take(limit)
                .map { song(it, CONTEXT_DOWNLOADS, null, DOWNLOADS_ID) }
            parentId == SONGS_ID -> musicRepository.getSongsPage(limit = limit, offset = offset)
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
        mediaId == FAVORITES_ID || mediaId == PLAYLISTS_ID || mediaId == DOWNLOADS_ID ||
            mediaId == ALBUMS_ID || mediaId == ARTISTS_ID || mediaId == SONGS_ID ->
            rootItems().firstOrNull { it.mediaId == mediaId }
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

    suspend fun search(query: String): List<MediaItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.equals(lastSearchQuery, ignoreCase = true) && lastSearchResults.isNotEmpty()) {
            return lastSearchResults
        }

        val results = LinkedHashMap<String, MediaItem>()

        // 1. Search local songs
        runCatching {
            musicRepository.searchSongs(trimmed).first()
                .take(MAX_SEARCH)
                .forEach { s ->
                    rememberSong(s)
                    results[SONG_PREFIX + s.id] = song(s, CONTEXT_SEARCH, trimmed, "$SEARCH_ID:$trimmed")
                }
        }

        // 2. Search YouTube Music online
        runCatching {
            val ytResult = YouTube.search(trimmed, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            ytResult?.items?.filterIsInstance<SongItem>()
                ?.take(MAX_SEARCH)
                ?.forEach { item ->
                    val nativeSong = item.toNativeSong()
                    rememberSong(nativeSong)
                    val key = SONG_PREFIX + nativeSong.id
                    if (!results.containsKey(key)) {
                        results[key] = song(nativeSong, CONTEXT_SEARCH, trimmed, "$SEARCH_ID:$trimmed")
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
        return list
    }

    // ---- the queue a folder represents --------------------------------------

    suspend fun songsOf(contextType: String, contextId: String?): List<Song> = when (contextType) {
        CONTEXT_RECENTS -> recentSongs()
        CONTEXT_FAVORITES -> favoriteSongs()
        CONTEXT_DOWNLOADS -> downloadedSongs()
        CONTEXT_ALL_SONGS -> musicRepository.getAllSongsOnce().onEach { rememberSong(it) }
        CONTEXT_SEARCH -> {
            val trimmed = contextId?.trim().orEmpty()
            if (trimmed.isNotEmpty()) {
                val matching = songCache.values.filter {
                    it.title.contains(trimmed, ignoreCase = true) || it.artist.contains(trimmed, ignoreCase = true)
                }
                if (matching.isNotEmpty()) matching else songCache.values.toList()
            } else {
                songCache.values.toList()
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
        isGrid: Boolean = false
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .setExtras(if (isGrid) gridStyle() else listStyle())
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

        return ids.mapNotNull { byId[it] ?: songCache[it] }.also { list ->
            list.forEach { rememberSong(it) }
        }
    }

    private suspend fun favoriteSongs(): List<Song> {
        val favs = musicRepository.getFavoriteSongsOnce()
        favs.forEach { rememberSong(it) }
        return favs
    }

    private suspend fun downloadedSongs(): List<Song> {
        val ytDownloaded = runCatching {
            AppDatabase.getInstance(context).songRepository().getDownloadedSongs()
                .map { it.toNativeSong() }
        }.getOrDefault(emptyList())

        val result = ytDownloaded.filter {
            OfflineAudioResolver.hasOfflineAudio(context, it)
        }.ifEmpty { ytDownloaded }

        result.forEach { rememberSong(it) }
        return result
    }

    private suspend fun songsForPlaylist(playlistId: String): List<Song> {
        val playlist = playlists().firstOrNull { it.id == playlistId } ?: return emptyList()

        if (playlist.id == "LM" || playlist.id == "liked" || playlist.name.equals("Liked Music", ignoreCase = true)) {
            val favs = favoriteSongs()
            if (favs.isNotEmpty()) return favs
        }

        var songs = musicRepository.getSongsByIdsOnce(playlist.songIds)
        if (songs.size < playlist.songIds.size || songs.isEmpty()) {
            val cleanId = playlistId.removePrefix("VL")
            val cachedYtPlaylist = runCatching {
                val repo = AppDatabase.getInstance(context).playlistRepository()
                repo.getPlaylistById(cleanId) ?: repo.getPlaylistById(playlistId)
            }.getOrNull()

            if (cachedYtPlaylist != null && cachedYtPlaylist.songs.isNotEmpty()) {
                val nativeYtSongs = cachedYtPlaylist.songs.map { it.toNativeSong() }
                runCatching { musicRepository.insertYoutubeSongs(nativeYtSongs) }
                songs = nativeYtSongs
            }
        }

        songs.forEach { rememberSong(it) }
        return songs
    }

    private suspend fun playlists(): List<Playlist> =
        playlistPreferencesRepository.userPlaylistsFlow.first()
}
