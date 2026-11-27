package com.unshoo.pixelmusic.data.remote.youtube

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.unshoo.pixelmusic.data.model.youtube.Song
import com.unshoo.pixelmusic.data.service.player.DualPlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import java.io.File

@OptIn(UnstableApi::class, ExperimentalCoilApi::class)
object QueuePreloadManager {

    private const val PRELOAD_LOOKAHEAD_CONCURRENCY = 2
    private var preloadJob: Job? = null
    private var scope: CoroutineScope? = null
    private var appContext: Context? = null
    private var datastoreRepository: DatastoreRepository? = null
    private var playerRef: Player? = null
    private var exoCache: ExoCache? = null
    private var engineRef: DualPlayerEngine? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            triggerPreload(includeCurrent = false)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                triggerPreload(includeCurrent = true)
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) triggerPreload()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            triggerPreload()
        }
    }

    fun attach(
        player: Player,
        context: Context,
        datastoreRepo: DatastoreRepository,
        coroutineScope: CoroutineScope,
        exoCacheInstance: ExoCache,
        engine: DualPlayerEngine? = null
    ) {
        scope = coroutineScope
        appContext = context.applicationContext
        datastoreRepository = datastoreRepo
        playerRef = player
        exoCache = exoCacheInstance
        engineRef = engine
        player.addListener(playerListener)
    }

    fun detach(player: Player?) {
        playerRef?.removeListener(playerListener)
        player?.removeListener(playerListener)
        playerRef = null
        preloadJob?.cancel()
        scope = null
        appContext = null
        datastoreRepository = null
        exoCache = null
        engineRef = null
    }

    fun updatePlayer(newPlayer: Player) {
        val oldPlayer = playerRef
        if (oldPlayer !== newPlayer) {
            oldPlayer?.removeListener(playerListener)
            playerRef = newPlayer
            newPlayer.addListener(playerListener)
        }
    }

    fun onControllerReady(player: Player) {
        updatePlayer(player)
    }

    private fun triggerPreload(includeCurrent: Boolean = false) {
        val currentScope = scope ?: return
        val player = playerRef ?: return
        val ctx = appContext ?: return

        preloadJob?.cancel()
        preloadJob = currentScope.launch(Dispatchers.IO) {
            val settings = datastoreRepository?.settings?.first() ?: return@launch
            if (!settings.preloadQueueEnabled) return@launch

            val playerState = withContext(Dispatchers.Main) {
                if (playerRef == null) null
                else Triple(player.currentMediaItemIndex, player.mediaItemCount, player.nextMediaItemIndex)
            } ?: return@launch

            val (currentIndex, totalCount, nextIndex) = playerState

            if (totalCount <= 0 || currentIndex < 0) return@launch
            val startIndex = if (includeCurrent) currentIndex else currentIndex + 1
            val endIndex = (currentIndex + settings.preloadQueueSize).coerceAtMost(totalCount - 1)
            val sequentialIndices = if (startIndex <= endIndex) (startIndex..endIndex).toList() else emptyList()
            val indicesAhead = (listOf(nextIndex) + sequentialIndices)
                .filter { it in 0 until totalCount && (includeCurrent || it != currentIndex) }
                .distinct()

            suspend fun preloadSlot(i: Int, includeArtworkAsync: Boolean) {
                if (i > currentIndex && i != nextIndex) {
                    val shouldYield = withContext(Dispatchers.Main) {
                        val engine = engineRef ?: return@withContext false
                        engine.isPlaybackBuffering || engine.bufferedAheadMs < 20_000L
                    }
                    if (shouldYield) {
                        delay(1_500L)
                    }
                }

                val mediaItem = withContext(Dispatchers.Main) {
                    if (playerRef != null && i < player.mediaItemCount) player.getMediaItemAt(i) else null
                } ?: return

                val videoId = resolveYoutubeVideoId(mediaItem) ?: return

                val durMs = mediaItem.mediaMetadata.extras
                    ?.getLong(com.unshoo.pixelmusic.utils.MediaItemBuilder.EXTERNAL_EXTRA_DURATION, 0L) ?: 0L
                val rawArtworkUri = com.unshoo.pixelmusic.utils.MediaItemBuilder.resolveArtworkUri(
                    mediaItem.mediaMetadata.extras?.getString(com.unshoo.pixelmusic.utils.MediaItemBuilder.EXTERNAL_EXTRA_ALBUM_ART)
                        ?: mediaItem.mediaMetadata.artworkUri?.toString(),
                    mediaItem.mediaId,
                    videoId
                )
                val upgradedThumb = upgradeThumbnailUrlToHighQuality(rawArtworkUri)
                    ?.takeIf { it.startsWith("http", ignoreCase = true) }
                val song = Song(
                    youtubeId = videoId,
                    title = mediaItem.mediaMetadata.title?.toString() ?: "",
                    artist = mediaItem.mediaMetadata.artist?.toString() ?: "",
                    album = mediaItem.mediaMetadata.albumTitle?.toString(),
                    duration = if (durMs > 0L) (durMs / 1_000L).toString() else "",
                    thumbnailHref = upgradedThumb.orEmpty()
                )

                val isImmediateNext = (i == nextIndex)
                if (includeArtworkAsync && (!rawArtworkUri.isNullOrBlank() || song.thumbnailHref.isNotBlank())) {
                    currentScope.launch(Dispatchers.IO) {
                        if (isImmediateNext && !rawArtworkUri.isNullOrBlank()) {
                            com.unshoo.pixelmusic.presentation.components.prefetchSmartImage(
                                ctx,
                                rawArtworkUri,
                                com.unshoo.pixelmusic.presentation.components.PlayerCoverTargetSize
                            )
                            com.unshoo.pixelmusic.presentation.components.blur.ArtworkGaussianBlur.load(
                                ctx,
                                rawArtworkUri
                            )
                        }
                        if (song.thumbnailHref.isNotBlank()) {
                            cacheArtworkIfMissing(ctx, videoId, song.thumbnailHref)
                        }
                    }
                }

                try {
                    // Only allow lossless on the immediate next queue track so deeper lookahead
                    // slots warm JioSaavn/YouTube fast without causing Monochrome 429 bursts.
                    val streamUrl = YoutubeHelper.getSongPlayerUrl(
                        ctx,
                        song,
                        allowLocal = false,
                        allowLossless = isImmediateNext,
                        isNextInQueue = isImmediateNext,
                    )
                    if (!streamUrl.isNullOrBlank() && (streamUrl.startsWith("http") ||
                            com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver.isLosslessUri(streamUrl))) {
                        val ytUri = "youtube://$videoId"
                        engineRef?.cachePreloadedStreamUri(ytUri, android.net.Uri.parse(streamUrl))
                        if (isImmediateNext &&
                            streamUrl.startsWith("http") &&
                            !com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver.isLosslessUri(streamUrl) &&
                            !streamUrl.contains("monochrome.st/track/") &&
                            !streamUrl.endsWith(".flac", ignoreCase = true)
                        ) {
                            engineRef?.preCacheFirstChunk(streamUrl, videoId)
                        }
                    }
                    if (isImmediateNext && !streamUrl.isNullOrBlank() &&
                        com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver.isLosslessUri(streamUrl)) {
                        withContext(Dispatchers.Main) {
                            val engine = engineRef
                            if (engine != null && playerRef === player &&
                                player.nextMediaItemIndex == i &&
                                engine.getPreparedNextMediaId() != mediaItem.mediaId) {
                                engine.prepareNext(
                                    DualPlayerEngine.TransitionTarget(
                                        mediaItem = mediaItem,
                                        absoluteIndex = i,
                                        queueSize = totalCount,
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // Priority 1: warm current (on resume) and immediate next track first so transitions are ready immediately.
            val priorityIndices = indicesAhead.filter { it == currentIndex || it == nextIndex }
            for (idx in priorityIndices) {
                preloadSlot(idx, includeArtworkAsync = true)
            }

            // Priority 2: warm deeper lookahead URLs with strictly bounded concurrency (2) while
            // keeping thumbnail fetches off the URL critical path.
            val deeperIndices = indicesAhead.filterNot { it == currentIndex || it == nextIndex }
            if (deeperIndices.isNotEmpty()) {
                val gate = Semaphore(PRELOAD_LOOKAHEAD_CONCURRENCY)
                coroutineScope {
                    deeperIndices.map { idx ->
                        async {
                            gate.withPermit {
                                preloadSlot(idx, includeArtworkAsync = true)
                            }
                        }
                    }.awaitAll()
                }
            }
        }
    }

    @kotlin.OptIn(ExperimentalCoilApi::class)
    private suspend fun cacheArtworkIfMissing(ctx: Context, videoId: String, thumbnailUrl: String) {
        try {
            val imageDir = PixelMusicHelper.getDownloadDirectory(
                ctx,
                Constants.Downloads.THUMBNAILS_FOLDER
            )
            val destFile = File(imageDir, "$videoId.jpg")
            if (destFile.exists() && destFile.length() > 0L) return

            // Reuse Coil's disk cache before touching the network.
            val diskCache = ctx.imageLoader.diskCache
            if (diskCache != null) {
                for (candidate in com.unshoo.pixelmusic.data.image.YouTubeThumbnailCache.fallbackCandidates(thumbnailUrl)) {
                    val copied = diskCache.openSnapshot(candidate)?.use { snapshot ->
                        val cachedFile = snapshot.data.toFile()
                        if (cachedFile.exists() && cachedFile.length() > 0L) {
                            cachedFile.copyTo(destFile, overwrite = true)
                            true
                        } else false
                    } ?: false
                    if (copied) return
                }
            }

            val artBytes = PixelMusicHelper.fetchArtworkBytes(thumbnailUrl)
            if (artBytes != null && artBytes.isNotEmpty()) {
                destFile.writeBytes(artBytes)
            }
        } catch (_: Exception) {
        }
    }

    private fun resolveYoutubeVideoId(mediaItem: MediaItem): String? {
        val mediaId = mediaItem.mediaId
        if (mediaId.startsWith("youtube_")) return mediaId.removePrefix("youtube_").takeIf { it.isNotBlank() }
        mediaItem.localConfiguration?.uri?.toString()
            ?.takeIf { it.startsWith("youtube://") }
            ?.substringAfter("youtube://")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        mediaItem.mediaMetadata.extras
            ?.getString(com.unshoo.pixelmusic.utils.MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)
            ?.takeIf { it.startsWith("youtube://") }
            ?.substringAfter("youtube://")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // Local files (MediaStore ids like "2681", content:// or file:// URIs) are not YouTube
        // songs. Treating any bare id as a video id made the preloader spend ~12 s per local
        // queue item failing every YouTube client in turn — starving network/CPU on low-end phones.
        val localUri = mediaItem.localConfiguration?.uri?.scheme
        if (localUri == "content" || localUri == "file") return null
        return mediaId.takeIf { YOUTUBE_VIDEO_ID.matches(it) }
    }

    private val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    private suspend fun prefetchAudioBytes(ctx: Context, videoId: String, streamUrl: String) {
        val cache = exoCache?.cache ?: return
        // Same format-aware key the player reads with; a bare video id would never be found.
        val cacheKey = com.unshoo.pixelmusic.data.service.player.StreamCacheKeys.forStream(videoId, streamUrl)
        if (cache.isCached(cacheKey, 0, 128 * 1024L)) return
        try {
            val uri = Uri.parse(streamUrl)
            val baseDataSourceFactory = DefaultDataSource.Factory(ctx)
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(baseDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val dataSource = cacheDataSourceFactory.createDataSource()
            val dataSpec = DataSpec.Builder()
                .setUri(uri)
                .setKey(cacheKey)
                .setPosition(0)
                .setLength(128 * 1024L)
                .build()

            val parentJob = kotlin.coroutines.coroutineContext[Job]
            val progressListener = CacheWriter.ProgressListener { _, _, _ ->
                if (parentJob != null && !parentJob.isActive) {
                    throw InterruptedException("Prefetch canceled")
                }
            }

            val cacheWriter = CacheWriter(
                dataSource,
                dataSpec,
                null,
                progressListener
            )

            withContext(Dispatchers.IO) {
                cacheWriter.cache()
            }
        } catch (_: Exception) {
        }
    }
}
