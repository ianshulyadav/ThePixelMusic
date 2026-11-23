package com.unshoo.pixelmusic.data.database

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.unshoo.pixelmusic.data.database.youtube.AppDatabase
import com.unshoo.pixelmusic.data.model.youtube.Playlist
import com.unshoo.pixelmusic.data.model.youtube.PlaylistInfo
import com.unshoo.pixelmusic.data.model.youtube.PlaylistSongCrossRef
import com.unshoo.pixelmusic.data.model.youtube.Song
import com.unshoo.pixelmusic.data.remote.youtube.Constants
import com.unshoo.pixelmusic.data.remote.youtube.PixelMusicHelper
import com.unshoo.pixelmusic.data.remote.youtube.PlaylistDownloadWorker
import com.unshoo.pixelmusic.data.remote.youtube.SongDownloadWorker
import com.unshoo.pixelmusic.data.service.MusicService
import com.unshoo.pixelmusic.utils.MediaItemBuilder
import com.unshoo.pixelmusic.utils.OfflineAudioResolver
import com.unshoo.pixelmusic.utils.YouTubeIdUtils
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class YouTubePlaylistDownloadsTest {
    @Test
    fun catalogPlaylistImportPreservesExistingDownload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().useDatabase { database ->
            val downloadedSong = Song(
                youtubeId = "downloaded1",
                audioFilePath = "content://test/downloads/downloaded1",
                isPermanentlyDownloaded = true,
                downloadTimestamp = 123L,
            )
            database.songRepository().create(downloadedSong)

            val playlistId = "liked"
            val newSong = Song(youtubeId = "newSong0001")
            database.playlistRepository().insertPlaylistWithSongs(
                Playlist(
                    info = PlaylistInfo(id = playlistId),
                    unsortedSongs = listOf(Song(youtubeId = downloadedSong.youtubeId), newSong),
                    crossRefs = listOf(
                        PlaylistSongCrossRef(playlistId, downloadedSong.youtubeId, 0),
                        PlaylistSongCrossRef(playlistId, newSong.youtubeId, 1),
                    ),
                )
            )

            val songs = database.playlistRepository().getPlaylistById(playlistId)!!.songs
            assertEquals(listOf(downloadedSong.youtubeId, newSong.youtubeId), songs.map { it.youtubeId })
            assertEquals(downloadedSong.audioFilePath, songs.first().audioFilePath)
            assertEquals(downloadedSong.isPermanentlyDownloaded, songs.first().isPermanentlyDownloaded)
            assertEquals(downloadedSong.downloadTimestamp, songs.first().downloadTimestamp)
        }
    }

    @Test
    fun workersSkipCompletedTracksWithAndWithoutRecordedPaths() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appDatabase = AppDatabase.getInstance(context)
        val playlists = appDatabase.playlistRepository()
        val fixtureId = UUID.randomUUID().toString()
        val playlistId = "download-test-$fixtureId"
        val localSongId = "L${fixtureId.take(10)}"
        val indexedSongId = "I${fixtureId.take(10)}"
        val localFile = File.createTempFile("download-test-", ".m4a", context.cacheDir)
        val audioDirectory = PixelMusicHelper.getDownloadDirectory(context, Constants.Downloads.AUDIO_FILES_FOLDER)
        val indexedFile = File(audioDirectory, "$indexedSongId.m4a")
        try {
            localFile.writeBytes(byteArrayOf(1))
            indexedFile.writeBytes(byteArrayOf(1))
            OfflineAudioResolver.invalidateCache()
            playlists.insertPlaylistWithSongs(
                Playlist(
                    info = PlaylistInfo(id = playlistId, title = "Download regression test"),
                    unsortedSongs = listOf(
                        Song(youtubeId = localSongId, audioFilePath = localFile.absolutePath),
                        Song(youtubeId = indexedSongId),
                    ),
                    crossRefs = listOf(
                        PlaylistSongCrossRef(playlistId, localSongId, 0),
                        PlaylistSongCrossRef(playlistId, indexedSongId, 1),
                    ),
                )
            )
            val playlistWorker = TestListenableWorkerBuilder<PlaylistDownloadWorker>(context)
                .setInputData(workDataOf(PlaylistDownloadWorker.PLAYLIST_KEY to playlistId))
                .build()
            val songWorker = TestListenableWorkerBuilder<SongDownloadWorker>(context)
                .setInputData(workDataOf(SongDownloadWorker.SONG_KEY to indexedSongId))
                .build()

            assertEquals(ListenableWorker.Result.success(), playlistWorker.doWork())
            assertEquals(indexedFile.absolutePath, appDatabase.songRepository().getSong(indexedSongId)?.audioFilePath)
            assertEquals(ListenableWorker.Result.success(), songWorker.doWork())
            assertEquals(1L, localFile.length())
            assertEquals(1L, indexedFile.length())
        } finally {
            playlists.deleteFullPlaylist(playlistId)
            playlists.deleteCrossRef(Constants.Downloads.DOWNLOADED_PLAYLIST_ID, localSongId)
            playlists.deleteCrossRef(Constants.Downloads.DOWNLOADED_PLAYLIST_ID, indexedSongId)
            appDatabase.songRepository().deleteByIds(listOf(localSongId, indexedSongId))
            localFile.delete()
            indexedFile.delete()
            OfflineAudioResolver.invalidateCache()
        }
        Unit
    }

    @OptIn(UnstableApi::class)
    @Test
    fun explicitDownloadMapsLibraryAudioAndPlaysTheSameYoutubeItem() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = AppDatabase.getInstance(context)
        val dao = EntryPointAccessors.fromApplication(
            context, SongDownloadWorker.WorkerEntryPoint::class.java
        ).musicDao()
        val restartPhase = InstrumentationRegistry.getArguments().getString("localRestartPhase")
        val youtubeId = if (restartPhase == null) UUID.randomUUID().toString().replace("-", "").take(11) else "LocalTest01"
        val mediaStoreId = if (restartPhase == null) System.nanoTime().and(Long.MAX_VALUE) else 900_000_000_000_007L
        val youtubeRowId = YouTubeIdUtils.toUnifiedSongId(youtubeId)
        if (restartPhase == "verify") {
            val saved = requireNotNull(database.songRepository().getSong(youtubeId))
            try {
                assertTrue(saved.isPermanentlyDownloaded)
                assertMappedAudioPlays(context, requireNotNull(dao.getSongByIdOnce(youtubeRowId)).toSong())
            } finally {
                database.playlistRepository().deleteCrossRef(Constants.Downloads.DOWNLOADED_PLAYLIST_ID, youtubeId)
                database.songRepository().deleteByIds(listOf(youtubeId))
                dao.deleteSongsAndRelatedData(listOf(youtubeRowId, mediaStoreId))
                saved.audioFilePath?.let { File(it).delete() }
                OfflineAudioResolver.invalidateCache()
            }
            return@runBlocking
        }
        val title = "Aster $youtubeId"
        val wav = File.createTempFile("download-mapping-", ".wav", context.cacheDir)
        val restartDatabaseName = "mapping-restart-$youtubeId.db"
        var keepRestartFixture = false
        try {
            writeSilentWav(wav)
            val local = SongEntity(
                id = mediaStoreId, title = title, artistName = "Test Artist", artistId = mediaStoreId,
                albumName = "Test Album", albumId = mediaStoreId, contentUriString = "file://${wav.absolutePath}",
                albumArtUriString = null, duration = 6_000, genre = null, filePath = wav.absolutePath,
                parentDirectoryPath = context.cacheDir.absolutePath,
            )
            insertFixtureSong(dao, local)
            database.songRepository().create(Song(youtubeId, title, local.artistName, duration = "0:06"))
            val worker = TestListenableWorkerBuilder<SongDownloadWorker>(context)
                .setInputData(workDataOf(SongDownloadWorker.SONG_KEY to youtubeId))
                .build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())

            val mapped = requireNotNull(database.songRepository().getSong(youtubeId))
            assertEquals(wav.absolutePath, mapped.audioFilePath)
            assertTrue(mapped.isPermanentlyDownloaded)
            val libraryRow = requireNotNull(dao.getSongByIdOnce(youtubeRowId))
            assertEquals("youtube://$youtubeId", libraryRow.contentUriString)
            dao.incrementalSyncMusicData(
                listOf(libraryRow.copy(filePath = "", parentDirectoryPath = "youtube://")),
                emptyList(), emptyList(), emptyList(), emptyList(),
            )
            assertEquals(wav.absolutePath, dao.getSongByIdOnce(youtubeRowId)?.filePath)
            Room.databaseBuilder(context, AppDatabase::class.java, restartDatabaseName).build().useDatabase {
                it.songRepository().create(mapped)
            }
            Room.databaseBuilder(context, AppDatabase::class.java, restartDatabaseName).build().useDatabase {
                assertEquals(wav.absolutePath, it.songRepository().getSong(youtubeId)?.audioFilePath)
                assertTrue(it.songRepository().getSong(youtubeId)?.isPermanentlyDownloaded == true)
            }

            if (restartPhase == "prepare") {
                keepRestartFixture = true
                return@runBlocking
            }
            OfflineAudioResolver.refreshIndex(context)
            assertMappedAudioPlays(context, requireNotNull(dao.getSongByIdOnce(youtubeRowId)).toSong())
            assertTrue(wav.delete())
            OfflineAudioResolver.refreshIndex(context)
            assertNull(OfflineAudioResolver.getDownloadedAudioUri(context, youtubeId))
            assertEquals("youtube://$youtubeId", dao.getSongByIdOnce(youtubeRowId)?.contentUriString)
        } finally {
            if (!keepRestartFixture) {
                database.playlistRepository().deleteCrossRef(Constants.Downloads.DOWNLOADED_PLAYLIST_ID, youtubeId)
                database.songRepository().deleteByIds(listOf(youtubeId))
                dao.deleteSongsAndRelatedData(listOf(youtubeRowId, mediaStoreId))
                wav.delete()
                OfflineAudioResolver.invalidateCache()
            }
            context.deleteDatabase(restartDatabaseName)
        }
        Unit
    }

    @OptIn(UnstableApi::class)
    private suspend fun assertMappedAudioPlays(context: Context, track: com.unshoo.pixelmusic.data.model.Song) {
        withForegroundController(context) { controller ->
            playAndCheck(controller, track, 12_000)
        }
    }

    @OptIn(UnstableApi::class)
    @Test
    fun deletedAndUnreadableAudioFallBackToYoutube() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val corruptFile = File.createTempFile("broken-mapping-", ".wav", context.cacheDir)
        corruptFile.writeBytes(byteArrayOf(1, 2, 3))
        val dao = EntryPointAccessors.fromApplication(
            context, SongDownloadWorker.WorkerEntryPoint::class.java
        ).musicDao()
        val videoIds = listOf("jNQXAC9IVRw", "dQw4w9WgXcQ")
        val fixtureRows = mutableListOf<Long>()
        try {
            withForegroundController(context) { controller ->
                for ((index, videoId) in videoIds.withIndex()) {
                    val id = YouTubeIdUtils.toUnifiedSongId(videoId)
                    val path = if (index == 0) "${corruptFile.absolutePath}.missing" else corruptFile.absolutePath
                    val entry = SongEntity(
                        id = id, title = "YouTube fallback test", artistName = "Test Artist", artistId = id,
                        albumName = "Test Album", albumId = id, contentUriString = "youtube://$videoId",
                        albumArtUriString = null, duration = 0, genre = null, filePath = path,
                        parentDirectoryPath = context.cacheDir.absolutePath, sourceType = SourceType.YOUTUBE,
                    )
                    insertFixtureSong(dao, entry)
                    fixtureRows.add(id)
                    playAndCheck(controller, entry.toSong(), 35_000)
                }
            }
        } finally {
            dao.deleteSongsAndRelatedData(fixtureRows)
            corruptFile.delete()
        }
        Unit
    }

    @OptIn(UnstableApi::class)
    private suspend fun withForegroundController(context: Context, block: suspend (MediaController) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = if (isPrepareOnly()) null else instrumentation.startActivitySync(
            Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            val controller = withContext(Dispatchers.Main) {
                MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicService::class.java)))
                    .buildAsync()
            }.get(15, TimeUnit.SECONDS)
            try {
                block(controller)
            } finally {
                withContext(Dispatchers.Main) {
                    controller.stop()
                    controller.clearMediaItems()
                    controller.volume = 1f
                    controller.release()
                }
            }
        } finally {
            withContext(Dispatchers.Main) { activity?.finish() }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun playAndCheck(
        controller: MediaController,
        track: com.unshoo.pixelmusic.data.model.Song,
        timeoutMs: Long,
    ) {
        val prepareOnly = isPrepareOnly()
        withContext(Dispatchers.Main) {
            controller.volume = 0f
            controller.setMediaItem(MediaItemBuilder.build(track))
        }
        delay(300)
        withContext(Dispatchers.Main) {
            controller.prepare()
            if (!prepareOnly) controller.play()
        }
        val started = withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (withContext(Dispatchers.Main) {
                    controller.currentMediaItem?.mediaId == track.id &&
                        if (prepareOnly) controller.playbackState == Player.STATE_READY
                        else controller.isPlaying && controller.currentPosition > 100L
                }) break
                delay(50)
            }
            true
        }
        val state = withContext(Dispatchers.Main) {
            "state=${controller.playbackState}, playWhenReady=${controller.playWhenReady}, " +
                "suppression=${controller.playbackSuppressionReason}, id=${controller.currentMediaItem?.mediaId}, " +
                "position=${controller.currentPosition}, error=${controller.playerError?.errorCodeName}"
        }
        assertTrue("Playback did not start for ${track.id}: $state", started == true)
    }

    private fun isPrepareOnly(): Boolean =
        InstrumentationRegistry.getArguments().getString("checkPlaybackReadyOnly") == "true"

    private fun writeSilentWav(file: File) {
        val sampleRate = 16_000
        val audioBytes = sampleRate * 6 * 2
        val wav = ByteBuffer.allocate(44 + audioBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(audioBytes + 36).put("WAVE".toByteArray())
        wav.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        wav.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        wav.put("data".toByteArray()).putInt(audioBytes)
        file.writeBytes(wav.array())
    }

    private suspend fun insertFixtureSong(dao: MusicDao, song: SongEntity) {
        dao.insertArtistsIgnoreConflicts(listOf(ArtistEntity(song.artistId, song.artistName, 1)))
        dao.insertAlbumsIgnoreConflicts(
            listOf(AlbumEntity(song.albumId, song.albumName, song.artistName, song.artistId, null, 1, 0, 0))
        )
        assertEquals(song.id, dao.insertSongsIgnoreConflicts(listOf(song)).single())
    }

    private inline fun AppDatabase.useDatabase(block: (AppDatabase) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
