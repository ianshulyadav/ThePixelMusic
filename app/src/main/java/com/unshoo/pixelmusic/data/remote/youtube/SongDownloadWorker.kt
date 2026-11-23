package com.unshoo.pixelmusic.data.remote.youtube

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import com.unshoo.pixelmusic.data.database.youtube.AppDatabase
import com.unshoo.pixelmusic.data.model.youtube.Song
import com.unshoo.pixelmusic.data.offline.CloudOfflineRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.absoluteValue
import com.unshoo.pixelmusic.utils.YouTubeIdUtils
import com.unshoo.pixelmusic.utils.OfflineAudioResolver
import com.unshoo.pixelmusic.utils.LocalAudioDuplicateMatcher

class SongDownloadWorker(
    private val appContext: Context,
    private val params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun musicDao(): com.unshoo.pixelmusic.data.database.MusicDao
        fun cloudOfflineRepository(): CloudOfflineRepository
    }

    private val playlistRepository = AppDatabase.getInstance(appContext).playlistRepository()
    private val localSongRepository = AppDatabase.getInstance(appContext).songRepository()
    private val songRepository = SongRepository()
    private val entryPoint = EntryPointAccessors.fromApplication(
        appContext,
        WorkerEntryPoint::class.java
    )
    private val musicDao = entryPoint.musicDao()
    private val cloudOfflineRepository = entryPoint.cloudOfflineRepository()

    @OptIn(UnstableApi::class)
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val playlistId = params.inputData.getString(PLAYLIST_KEY)
            val songId = params.inputData.getString(SONG_KEY)
                ?: return@withContext Result.failure()

            if (cloudOfflineRepository.resolveLocalUri("youtube://$songId") != null) {
                return@withContext Result.success()
            }
            var song = localSongRepository.getSong(songId)
            val existingPath = DownloadHelper.findExistingDownload(appContext, songId, song?.audioFilePath, musicDao)
            if (existingPath != null) {
                if (song == null) {
                    localSongRepository.create(Song(youtubeId = songId, audioFilePath = existingPath,
                        isPermanentlyDownloaded = true, downloadTimestamp = System.currentTimeMillis()))
                } else if (existingPath != song.audioFilePath || !song.isPermanentlyDownloaded) {
                    localSongRepository.saveDownloadPath(songId, existingPath)
                }
                playlistRepository.appendSongIfMissing(Constants.Downloads.DOWNLOADED_PLAYLIST_ID, songId)
                OfflineAudioResolver.recordDownload(songId, existingPath)
                return@withContext Result.success()
            }
            val localSongs = musicDao.getLocalAudioCandidates()
            suspend fun mapLocalAudio(candidate: Song?): Boolean {
                if (candidate == null) return false
                val match = LocalAudioDuplicateMatcher.findMatchingLocalSong(localSongs, candidate, context = appContext)
                    ?: return false
                val path = LocalAudioDuplicateMatcher.resolveLocalSongPath(appContext, match) ?: return false
                val mapped = candidate.copy(audioFilePath = path, thumbnailPath = candidate.thumbnailPath ?: match.albumArtUriString)
                ensureYoutubeSongInLibrary(mapped)
                musicDao.updateSongFilePathAndParent(toUnifiedYoutubeSongId(songId), path, "/Cloud/YouTube")
                localSongRepository.saveDownloadPath(songId, path)
                playlistRepository.appendSongIfMissing(Constants.Downloads.DOWNLOADED_PLAYLIST_ID, songId)
                OfflineAudioResolver.recordDownload(songId, path)
                PixelMusicNotificationManager.showSongDownloadSuccess(appContext, mapped)
                return true
            }
            if (mapLocalAudio(song)) return@withContext Result.success()
            var fetchedSong: Song? = null
            val needsMetadata = song == null || song.album.isNullOrBlank()
            if (song == null || song.album.isNullOrBlank()) {
                songRepository.getSongInfo(songId).collect { apiResult ->
                    if (apiResult is ApiResult.Success) {
                        fetchedSong = apiResult.data
                    }
                }
                val fresh = fetchedSong
                if (fresh != null) {
                    song = song?.copy(
                        title = if (song.title.isBlank()) fresh.title else song.title,
                        artist = if (song.artist.isBlank()) fresh.artist else song.artist,
                        album = fresh.album ?: song.album,
                        albumBrowseId = fresh.albumBrowseId ?: song.albumBrowseId,
                        duration = if (song.duration.isBlank()) fresh.duration else song.duration,
                        thumbnailHref = if (song.thumbnailHref.isBlank()) fresh.thumbnailHref else song.thumbnailHref
                    ) ?: fresh
                    localSongRepository.create(song)
                    if (mapLocalAudio(song)) return@withContext Result.success()
                } else if (song == null) {
                    return@withContext Result.failure()
                }
            }

            if (playlistId != null) {
                val playlist = playlistRepository.getPlaylistById(playlistId)
                if (playlist != null) {
                    val playlistImage =
                        DownloadHelper.downloadImage(
                            appContext,
                            playlist.info.coverHref,
                            playlist.info.id
                        )
                    playlistRepository.insertPlaylist(
                        playlist.info.copy(
                            coverPath = playlistImage?.path
                        )
                    )
                }
            }

            try {
                if (!needsMetadata) {
                    songRepository.getSongInfo(song.youtubeId).collect { result ->
                        if (result is ApiResult.Success) fetchedSong = result.data
                    }
                }
                val fullSong = fetchedSong

                val thumbnailPath =
                    DownloadHelper.downloadImage(
                        appContext,
                        fullSong?.thumbnailHref ?: song.thumbnailHref,
                        song.youtubeId
                    )
                val tagAlbum = (fullSong?.album ?: song.album)?.takeIf { it.isNotBlank() } ?: "YouTube Music"
                var taggedDuringDownload = false
                val audioPath = DownloadHelper.downloadAudio(appContext, song, checkDeviceLibrary = false, onFileReady = { file ->
                        DownloadHelper.withLowPriorityPostProcessing {
                            embedAudioMetadata(file.absolutePath, song.title, song.artist, tagAlbum, thumbnailPath)
                        }
                        taggedDuringDownload = true
                })

                val updatedSong = song.copy(
                    thumbnailPath = thumbnailPath?.path ?: song.thumbnailPath,
                    audioFilePath = audioPath,
                    isPermanentlyDownloaded = audioPath != null,
                    downloadTimestamp = if (audioPath != null) System.currentTimeMillis() else 0L,
                    album = fullSong?.album ?: song.album,
                    albumBrowseId = fullSong?.albumBrowseId ?: song.albumBrowseId,
                )
                localSongRepository.create(updatedSong)

                if (audioPath != null && !taggedDuringDownload) {
                    embedAudioMetadata(
                        audioPath = audioPath,
                        title = updatedSong.title,
                        artist = updatedSong.artist,
                        album = updatedSong.album?.takeIf { it.isNotBlank() } ?: "YouTube Music",
                        thumbnailFile = thumbnailPath
                    )
                }

                ensureYoutubeSongInLibrary(updatedSong)

                if (audioPath != null) {
                    val mainId = toUnifiedYoutubeSongId(song.youtubeId)
                    val parentDir = if (audioPath.startsWith("content://")) {
                        "Music/PixelMusic"
                    } else {
                        File(audioPath).parentFile?.absolutePath ?: ""
                    }
                    musicDao.updateSongFilePathAndParent(mainId, audioPath, parentDir)
                    OfflineAudioResolver.recordDownload(songId, audioPath)
                    playlistRepository.appendSongIfMissing(Constants.Downloads.DOWNLOADED_PLAYLIST_ID, song.youtubeId)
                    PixelMusicNotificationManager.showSongDownloadSuccess(appContext, song)
                    Result.success()
                } else {
                    // audioPath == null means the audio file was never actually written
                    // (network error, storage limit, or resolution failure). Previously this
                    // still reported success, which caused WorkInfo.State.SUCCEEDED to be
                    // observed by the UI (marking the song as "downloaded" even though no
                    // file exists) and showed a misleading success notification.
                    PixelMusicNotificationManager.showSongDownloadFailed(appContext, song)
                    Result.failure()
                }
            } catch (_: CancellationException) {
                PixelMusicHelper.printd("Song download canceled ${song.title}")
                Result.failure()
            } catch (e: Exception) {
                PixelMusicHelper.printd("Song download failed: ${e.message}")
                e.printStackTrace()
                PixelMusicNotificationManager.showSongDownloadFailed(
                    appContext,
                    song
                )
                Result.failure()
            }
        }
    }

    private fun embedAudioMetadata(
        audioPath: String,
        title: String,
        artist: String,
        album: String,
        thumbnailFile: File?
    ) {
        try {
            val pfd = if (audioPath.startsWith("content://")) {
                appContext.contentResolver.openFileDescriptor(android.net.Uri.parse(audioPath), "rw")
            } else {
                val f = File(audioPath)
                if (!f.exists()) return
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE)
            } ?: return

            pfd.use { fd ->
                val metadata = TagLib.getMetadata(fd.dup().detachFd(), readPictures = false)
                val propertyMap = HashMap(metadata?.propertyMap ?: emptyMap())
                if (title.isNotBlank()) propertyMap["TITLE"] = arrayOf(title)
                if (artist.isNotBlank()) propertyMap["ARTIST"] = arrayOf(artist)
                if (album.isNotBlank()) propertyMap["ALBUM"] = arrayOf(album)

                TagLib.savePropertyMap(fd.dup().detachFd(), propertyMap)

                if (thumbnailFile != null && thumbnailFile.exists()) {
                    val bytes = thumbnailFile.readBytes()
                    if (bytes.isNotEmpty()) {
                        val pic = Picture(
                            data = bytes,
                            description = "Front Cover",
                            pictureType = "Front Cover",
                            mimeType = "image/jpeg"
                        )
                        TagLib.savePictures(fd.dup().detachFd(), arrayOf(pic))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("SongDownloadWorker").w(e, "Failed to embed TagLib metadata for %s", audioPath)
        }
    }

    private fun toUnifiedYoutubeSongId(youtubeId: String): Long =
        YouTubeIdUtils.toUnifiedYoutubeSongId(youtubeId)

    private fun toUnifiedYoutubeAlbumId(albumName: String): Long =
        YouTubeIdUtils.toUnifiedYoutubeAlbumId(albumName)

    private fun toUnifiedYoutubeArtistId(artistName: String): Long =
        YouTubeIdUtils.toUnifiedYoutubeArtistId(artistName)

    private fun parseYoutubeArtistNames(artistStr: String): List<String> {
        if (artistStr.isBlank()) return listOf("Unknown Artist")
        val parsed = artistStr
            .split(Regex("\\s*[,/&;+、•]\\s*|\\s+(?:feat\\.|ft\\.|vs)\\s+|\\s+and\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return if (parsed.isEmpty()) listOf("Unknown Artist") else parsed
    }

    private suspend fun ensureYoutubeSongInLibrary(song: Song) {
        val songId = toUnifiedYoutubeSongId(song.youtubeId)
        val title = song.title.ifBlank { "Downloaded Song" }
        val artist = song.artist.ifBlank { "Unknown Artist" }
        val artistNames = parseYoutubeArtistNames(artist)
        val primaryArtistName = artistNames.firstOrNull() ?: "Unknown Artist"
        val primaryArtistId = toUnifiedYoutubeArtistId(primaryArtistName)

        val artistsToInsert = artistNames.map { name ->
            com.unshoo.pixelmusic.data.database.ArtistEntity(
                id = toUnifiedYoutubeArtistId(name),
                name = name,
                trackCount = 0,
                imageUrl = null
            )
        }

        val crossRefsToInsert = artistNames.mapIndexed { index, name ->
            val artistId = toUnifiedYoutubeArtistId(name)
            com.unshoo.pixelmusic.data.database.SongArtistCrossRef(
                songId = songId,
                artistId = artistId,
                isPrimary = index == 0
            )
        }

        val albumName = song.album?.takeIf { it.isNotBlank() } ?: "YouTube Music"
        val albumId = toUnifiedYoutubeAlbumId(albumName)
        val albumToInsert = com.unshoo.pixelmusic.data.database.AlbumEntity(
            id = albumId,
            title = albumName,
            artistName = primaryArtistName,
            artistId = primaryArtistId,
            songCount = 0,
            dateAdded = System.currentTimeMillis(),
            year = 0,
            albumArtUriString = song.thumbnailPath ?: song.thumbnailHref
        )

        val artistsJson = try {
            val arr = org.json.JSONArray()
            artistNames.forEachIndexed { idx, name ->
                val obj = org.json.JSONObject()
                obj.put("id", toUnifiedYoutubeArtistId(name))
                obj.put("name", name)
                obj.put("primary", idx == 0)
                arr.put(obj)
            }
            arr.toString()
        } catch (e: Exception) {
            null
        }

        val durationMs = try {
            if (song.duration.contains(":")) {
                val parts = song.duration.split(":")
                when (parts.size) {
                    1 -> parts[0].toLong() * 1000L
                    2 -> (parts[0].toLong() * 60L + parts[1].toLong()) * 1000L
                    3 -> ((parts[0].toLong() * 3600L + parts[1].toLong() * 60L + parts[2].toLong())) * 1000L
                    else -> 0L
                }
            } else {
                song.duration.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            0L
        }

        val songEntity = com.unshoo.pixelmusic.data.database.SongEntity(
            id = songId,
            title = title,
            artistName = artist,
            artistId = primaryArtistId,
            albumArtist = null,
            albumName = albumName,
            albumId = albumId,
            contentUriString = "youtube://${song.youtubeId}",
            albumArtUriString = song.thumbnailPath ?: song.thumbnailHref,
            duration = durationMs,
            genre = song.genre?.takeIf { it.isNotBlank() } ?: "YouTube Music",
            filePath = "",
            parentDirectoryPath = "youtube://",
            isFavorite = false,
            lyrics = null,
            trackNumber = 0,
            year = 0,
            dateAdded = System.currentTimeMillis(),
            mimeType = when (song.audioFilePath?.substringAfterLast('.', "")?.lowercase()) {
                "m4a", "mp4" -> "audio/mp4"
                "flac" -> "audio/flac"
                "mp3" -> "audio/mpeg"
                "ogg", "opus" -> "audio/ogg"
                else -> "audio/webm"
            },
            bitrate = null,
            sampleRate = null,
            telegramChatId = null,
            telegramFileId = null,
            artistsJson = artistsJson,
            sourceType = com.unshoo.pixelmusic.data.database.SourceType.YOUTUBE,
            albumBrowseId = song.albumBrowseId
        )

        val existing = musicDao.getSongByIdOnce(songId)
        if (existing != null && existing.albumName == "YouTube Music" && albumName != "YouTube Music") {
            musicDao.insertAlbumsIgnoreConflicts(listOf(albumToInsert))
            musicDao.updateSongAlbum(songId, albumName, albumId, song.albumBrowseId)
        }

        musicDao.incrementalSyncMusicData(
            songs = listOf(songEntity),
            albums = listOf(albumToInsert),
            artists = artistsToInsert,
            crossRefs = crossRefsToInsert,
            deletedSongIds = emptyList()
        )
    }

    companion object {
        const val PLAYLIST_KEY = "playlist"
        const val SONG_KEY = "song"
    }
}
