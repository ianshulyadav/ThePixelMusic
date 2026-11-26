package com.unshoo.pixelmusic.data.remote.youtube.queue

import androidx.media3.common.MediaItem
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.database.SongEntity
import com.unshoo.pixelmusic.data.database.toSong
import com.unshoo.pixelmusic.utils.MediaItemBuilder
import com.unshoo.pixelmusic.utils.YouTubeIdUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import unshoo.ianshulyadav.pixelmusic.innertube.models.WatchEndpoint

class ResilientRadioQueue(
    private var online: YouTubeRadioQueue?,
    private val musicDao: MusicDao,
    private val isOnline: () -> Boolean,
    private var localSeed: SongEntity? = null,
) : Queue {

    private val radioHistory = RadioAutoplayHistory(online?.currentVideoId)
    private val pendingOnline = ArrayDeque<MediaItem>()
    private val returnedVideoIds = HashSet<String>()
    private val usedLocalIds = mutableSetOf<Long>()

    override val preloadItem: MediaItem? = null

    override suspend fun getInitialStatus(): Queue.Status {
        val onlineQueue = online
        if (onlineQueue != null && isOnline()) {
            try {
                val status = onlineQueue.getInitialStatus()
                if (status.items.isNotEmpty()) return status
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return Queue.Status(title = null, items = emptyList(), mediaItemIndex = -1)
    }

    override fun hasNextPage(): Boolean = true

    override suspend fun nextPage(): List<MediaItem> {
        if (isOnline()) {
            val batch = mutableListOf<MediaItem>()
            drainPending(batch)
            if (batch.size >= RADIO_BATCH_SIZE) return batch

            if (online?.hasNextPage() != true) {
                radioHistory.nextSeed()?.let { online = YouTubeRadioQueue.radio(it) }
            }
            if (online?.hasNextPage() == true) {
                val page = try {
                    online?.nextPage().orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed station yields to the next delivered seed.
                    online = null
                    emptyList()
                }
                pendingOnline.addAll(page)
            }
            drainPending(batch)
            if (batch.isNotEmpty()) return batch
        }
        return fetchLocalFallback()
    }

    private fun drainPending(batch: MutableList<MediaItem>) {
        while (pendingOnline.isNotEmpty() && batch.size < RADIO_BATCH_SIZE) {
            val item = pendingOnline.removeFirst()
            val videoId = YouTubeIdUtils.extractVideoId(item.mediaId)
            if (videoId.length == 11 && returnedVideoIds.add(videoId)) batch += item
            if (returnedVideoIds.size > MAX_RETURNED_IDS) {
                returnedVideoIds.clear()
                returnedVideoIds.addAll(radioHistory.recentVideoIds())
            }
        }
    }

    /** Only songs accepted by the player become seeds for later stations. */
    fun recordAccepted(items: List<MediaItem>) {
        items.filter { it.mediaId.startsWith("youtube_") }.forEach {
            radioHistory.record(YouTubeIdUtils.extractVideoId(it.mediaId))
        }
    }

    fun reseedOnline(videoId: String) {
        pendingOnline.clear()
        returnedVideoIds.clear()
        radioHistory.reset(videoId)
        online = YouTubeRadioQueue.radio(videoId)
    }

    fun updateLocalSeed(entity: SongEntity?) {
        if (entity != null) localSeed = entity
    }

    private suspend fun fetchLocalFallback(): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            val candidates = mutableListOf<SongEntity>()
            val seed = localSeed

            if (seed != null && seed.sourceType == com.unshoo.pixelmusic.data.database.SourceType.LOCAL && seed.filePath.isNotBlank()) {
                runCatching {
                    candidates += musicDao.getLocalRelatedSongs(
                        songId = seed.id,
                        artistId = seed.artistId,
                        albumId = seed.albumId,
                        genre = seed.genre,
                        limit = 40,
                    )
                }
                if (seed.artistName.isNotBlank()) {
                    runCatching { candidates += musicDao.getSongsByArtistName(seed.artistName, 20) }
                }
                if (!seed.genre.isNullOrBlank()) {
                    runCatching { candidates += musicDao.getSongsByGenre(seed.genre, seed.id, 20) }
                }
            }
            if (candidates.isEmpty()) {
                return@withContext emptyList()
            }

            val fresh = candidates.distinctBy { it.id }.filter { it.id !in usedLocalIds }

            val picked = fresh.shuffled().take(15)
            picked.forEach { usedLocalIds += it.id }
            picked.map { MediaItemBuilder.build(it.toSong()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val RADIO_BATCH_SIZE = 20
        private const val MAX_RETURNED_IDS = 6_000
        fun radio(
            videoId: String?,
            musicDao: MusicDao,
            isOnline: () -> Boolean,
            localSeed: SongEntity? = null,
        ): ResilientRadioQueue = ResilientRadioQueue(
            online = videoId?.let { YouTubeRadioQueue.radio(it) },
            musicDao = musicDao,
            isOnline = isOnline,
            localSeed = localSeed,
        )

        fun resumed(
            endpoint: WatchEndpoint,
            continuation: String?,
            musicDao: MusicDao,
            isOnline: () -> Boolean,
            localSeed: SongEntity? = null,
        ): ResilientRadioQueue = ResilientRadioQueue(
            online = YouTubeRadioQueue(endpoint = endpoint, initialContinuation = continuation),
            musicDao = musicDao,
            isOnline = isOnline,
            localSeed = localSeed,
        )
    }
}
