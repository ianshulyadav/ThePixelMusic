package com.unshoo.pixelmusic.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.database.toArtist
import com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver
import com.unshoo.pixelmusic.data.model.Artist
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper
import com.unshoo.pixelmusic.data.repository.MusicRepository
import com.unshoo.pixelmusic.data.telegram.TelegramRepository
import com.unshoo.pixelmusic.utils.AudioMeta
import com.unshoo.pixelmusic.utils.AudioMetaUtils
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class SongInfoBottomSheetViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
    private val musicRepository: MusicRepository,
    private val telegramRepository: TelegramRepository,
    private val downloadRepository: com.unshoo.pixelmusic.data.remote.youtube.DownloadRepository,
    private val userPreferencesRepository: com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository,
    private val offlineTrackDao: com.unshoo.pixelmusic.data.database.OfflineTrackDao,
    private val syncManager: com.unshoo.pixelmusic.data.worker.SyncManager,
) : ViewModel() {

    data class SongLocationInfo(
        val label: String = "Provider",
        val value: String = "",
        val isCloud: Boolean = false,
        val provider: String? = null,
        val filePath: String? = null,
    )

    private val _audioMeta = MutableStateFlow<AudioMeta?>(null)
    val audioMeta: StateFlow<AudioMeta?> = _audioMeta.asStateFlow()

    val notInterestedSongIds: StateFlow<Set<String>> = userPreferencesRepository.notInterestedSongIdsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptySet()
        )

    private val _songLocationInfo = MutableStateFlow(SongLocationInfo(label = "Provider", value = "", isCloud = false))
    val songLocationInfo: StateFlow<SongLocationInfo> = _songLocationInfo.asStateFlow()

    private val _isResolvingAudioMeta = MutableStateFlow(false)
    val isResolvingAudioMeta: StateFlow<Boolean> = _isResolvingAudioMeta.asStateFlow()

    private val _resolvedArtists = MutableStateFlow<List<Artist>>(emptyList())
    val resolvedArtists: StateFlow<List<Artist>> = _resolvedArtists.asStateFlow()
    private val _isSongDownloaded = MutableStateFlow(false)
    val isSongDownloaded: StateFlow<Boolean> = _isSongDownloaded.asStateFlow()

    private val _isSongDownloading = MutableStateFlow(false)
    val isSongDownloading: StateFlow<Boolean> = _isSongDownloading.asStateFlow()

    private var downloadJob: Job? = null

    fun loadArtistsForSong(song: Song) {
        val refs = song.artists
        if (refs.isEmpty() || refs.size < 2) {
            _resolvedArtists.value = emptyList()
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ids = refs.map { it.id }.filter { it != -1L && it != 0L }.distinct()
            val entitiesById = if (ids.isNotEmpty()) {
                musicDao.getArtistsByIds(ids).associateBy { it.id }
            } else {
                emptyMap()
            }
            val resolved = refs.map { ref ->
                entitiesById[ref.id]?.toArtist()
                    ?: Artist(id = ref.id, name = ref.name, songCount = 0)
            }
            _resolvedArtists.value = resolved
        }
    }

    private fun getLocalFilePath(song: Song): String? {
        return when {
            song.path.isNotBlank() && File(song.path).exists() -> song.path
            else -> null
        }
    }

    fun loadAudioMeta(song: Song) {
        _songLocationInfo.value = getSongLocationInfo(song)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val localPath = getLocalFilePath(song)
            val isLocalFile = song.path.isNotBlank() && File(song.path).exists() ||
                song.contentUriString.startsWith("content://") ||
                song.contentUriString.startsWith("file://")
            val isPureLocal = isLocalFile &&
                !song.id.startsWith("youtube_") && song.youtubeId == null &&
                getCloudProviderLabel(song.contentUriString) == null

            if (isPureLocal && localPath != null) {
                val meta = AudioMetaUtils.getAudioMetadata(
                    musicDao = musicDao,
                    id = song.id.toLongOrNull() ?: -1L,
                    filePath = localPath,
                    deepScan = true
                )
                _audioMeta.value = meta.copy(provider = "Local Storage")
                _songLocationInfo.value = SongLocationInfo(
                    label = "Path",
                    value = localPath,
                    isCloud = false,
                    provider = "Local Storage",
                    filePath = localPath
                )
                return@launch
            }

            // Online / streaming track (YouTube, Lossless, etc.)
            val videoId = song.youtubeId
                ?: song.contentUriString.substringAfter("youtube://").takeIf { it.isNotBlank() && !it.startsWith("http") }
                ?: song.id.removePrefix("youtube_")

            // 1. Check if LosslessStreamResolver already resolved this track
            val cachedLossless = LosslessStreamResolver.resultFor(videoId)
                ?: LosslessStreamResolver.resultFor(song.id)
            if (cachedLossless != null) {
                val providerStr = formatResolvedProvider(cachedLossless)
                _audioMeta.value = AudioMeta(
                    mimeType = cachedLossless.mimeType,
                    bitrate = cachedLossless.bitrate,
                    sampleRate = cachedLossless.sampleRate,
                    bitDepth = cachedLossless.bitDepth,
                    formatLabel = cachedLossless.label,
                    provider = providerStr
                )
                _songLocationInfo.value = SongLocationInfo(
                    label = "Provider",
                    value = providerStr,
                    isCloud = true,
                    provider = providerStr,
                    filePath = localPath
                )
                return@launch
            }

            // 2. If lossless streaming is enabled, resolve it dynamically
            val isLosslessEnabled = LosslessStreamResolver.isEnabled(context)
            if (isLosslessEnabled && videoId.isNotBlank()) {
                _isResolvingAudioMeta.value = true
                try {
                    val artistsList = song.artist.split(",", "&", "feat.", "ft.", ";")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .ifEmpty { listOf(song.artist).filter { it.isNotBlank() } }
                    val resolved = LosslessStreamResolver.resolve(
                        context = context,
                        request = LosslessStreamResolver.Request(
                            mediaId = videoId,
                            title = song.title,
                            artists = artistsList,
                            album = song.album,
                            durationMs = song.duration.takeIf { it > 0 }
                        ),
                        lowDataMode = false
                    )
                    if (resolved != null) {
                        val providerStr = formatResolvedProvider(resolved)
                        _audioMeta.value = AudioMeta(
                            mimeType = resolved.mimeType,
                            bitrate = resolved.bitrate,
                            sampleRate = resolved.sampleRate,
                            bitDepth = resolved.bitDepth,
                            formatLabel = resolved.label,
                            provider = providerStr
                        )
                        _songLocationInfo.value = SongLocationInfo(
                            label = "Provider",
                            value = providerStr,
                            isCloud = true,
                            provider = providerStr,
                            filePath = localPath
                        )
                        _isResolvingAudioMeta.value = false
                        return@launch
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Error resolving lossless metadata in SongInfo")
                } finally {
                    _isResolvingAudioMeta.value = false
                }
            }

            // 3. Lossless not found or disabled: if local file exists on disk, read its actual metadata!
            if (localPath != null) {
                val meta = AudioMetaUtils.getAudioMetadata(
                    musicDao = musicDao,
                    id = song.id.toLongOrNull() ?: -1L,
                    filePath = localPath,
                    deepScan = true
                )
                val fallbackProvider = getCloudProviderLabel(song.contentUriString)
                    ?: if (song.youtubeId != null || song.id.startsWith("youtube_")) "YouTube Music" else "Local Storage"
                _audioMeta.value = meta.copy(provider = fallbackProvider)
                _songLocationInfo.value = SongLocationInfo(
                    label = "Path",
                    value = localPath,
                    isCloud = false,
                    provider = fallbackProvider,
                    filePath = localPath
                )
                return@launch
            }

            // 4. Pure online streaming fallback: check actual YouTube / JioSaavn stream info
            val ytBitrate = YoutubeHelper.streamBitrateLruCache.get("${videoId}_high")
                ?: YoutubeHelper.streamBitrateLruCache.get("${videoId}_low")
                ?: YoutubeHelper.streamBitrateLruCache.snapshot().entries.find { it.key.startsWith("${videoId}_") }?.value
            val ytMime = YoutubeHelper.streamMimeTypeLruCache.get("${videoId}_high")
                ?: YoutubeHelper.streamMimeTypeLruCache.get("${videoId}_low")
                ?: YoutubeHelper.streamMimeTypeLruCache.snapshot().entries.find { it.key.startsWith("${videoId}_") }?.value

            val bitrate = ytBitrate ?: 160_000
            val mime = ytMime ?: "audio/webm; codecs=\"opus\""
            val sampleRate = if (mime.contains("opus", true)) 48000 else 44100
            val fallbackProvider = getCloudProviderLabel(song.contentUriString)
                ?: if (song.contentUriString.contains("saavn")) "JioSaavn" else "YouTube Music"
            _audioMeta.value = AudioMeta(
                mimeType = mime,
                bitrate = bitrate,
                sampleRate = sampleRate,
                bitDepth = null,
                formatLabel = null,
                provider = fallbackProvider
            )
            _songLocationInfo.value = SongLocationInfo(
                label = "Provider",
                value = fallbackProvider,
                isCloud = true,
                provider = fallbackProvider,
                filePath = null
            )
        }
    }

    fun getSongLocationInfo(song: Song): SongLocationInfo {
        val videoId = song.youtubeId
            ?: song.contentUriString.substringAfter("youtube://").takeIf { it.isNotBlank() && !it.startsWith("http") }
            ?: song.id.removePrefix("youtube_")
        val cachedLossless = LosslessStreamResolver.resultFor(videoId)
            ?: LosslessStreamResolver.resultFor(song.id)

        val localPath = getLocalFilePath(song)
        val cloudProvider = getCloudProviderLabel(song.contentUriString)
            ?: if (song.youtubeId != null || song.id.startsWith("youtube_")) "YouTube Music" else null

        val providerName = when {
            cachedLossless != null -> formatResolvedProvider(cachedLossless)
            cloudProvider != null -> cloudProvider
            localPath != null -> "Local Storage"
            else -> "YouTube Music"
        }

        return SongLocationInfo(
            label = if (localPath != null && cachedLossless == null) "Path" else "Provider",
            value = if (localPath != null && cachedLossless == null) localPath else providerName,
            isCloud = localPath == null || cachedLossless != null,
            provider = providerName,
            filePath = localPath,
        )
    }


    fun loadDownloadState(song: Song) {
        _isSongDownloaded.value = false
        _isSongDownloading.value = false

        val youtubeId = song.youtubeId
        val telegramFileId = song.telegramFileId
        if (youtubeId == null && telegramFileId == null) {
            return
        }

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            // Initial check
            if (telegramFileId != null) {
                _isSongDownloaded.value = telegramRepository.isFileCached(telegramFileId)
                return@launch
            }
            if (youtubeId == null) return@launch
            _isSongDownloaded.value = downloadRepository.isSongDownloaded(youtubeId)

            // Observe the work manager flow for this song
            downloadRepository.getSongDownloadWorkInfoFlow(youtubeId).collect { workInfos ->
                val active = workInfos.any {
                    it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED
                }
                _isSongDownloading.value = active

                if (workInfos.any { it.state == WorkInfo.State.SUCCEEDED }) {
                    _isSongDownloaded.value = true
                }
                if (workInfos.any { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED }) {
                    _isSongDownloaded.value = downloadRepository.isSongDownloaded(youtubeId)
                }
            }
        }
    }

    fun downloadYoutubeSong(song: Song) {
        val youtubeId = song.youtubeId ?: return
        viewModelScope.launch {
            val youtubeSong = com.unshoo.pixelmusic.data.model.youtube.Song(
                youtubeId = youtubeId,
                title = song.title,
                artist = song.artist,
                duration = com.unshoo.pixelmusic.utils.formatDuration(song.duration),
                thumbnailHref = song.albumArtUriString ?: ""
            )
            val playlist = com.unshoo.pixelmusic.data.model.youtube.Playlist(
                info = com.unshoo.pixelmusic.data.model.youtube.PlaylistInfo(
                    id = com.unshoo.pixelmusic.data.remote.youtube.Constants.Downloads.DOWNLOADED_PLAYLIST_ID,
                    title = "Downloaded Songs"
                ),
                unsortedSongs = listOf(youtubeSong),
                crossRefs = listOf(
                    com.unshoo.pixelmusic.data.model.youtube.PlaylistSongCrossRef(
                        playlistId = com.unshoo.pixelmusic.data.remote.youtube.Constants.Downloads.DOWNLOADED_PLAYLIST_ID,
                        songId = youtubeSong.youtubeId,
                        position = 0
                    )
                )
            )
            downloadRepository.downloadSong(playlist, youtubeSong)
        }
    }

    /**
     * JioSaavn search result: save JioSaavn's own audio file to Music/PixelMusic (so it appears in
     * the library and folder scans). Only when that file isn't available or the download fails is
     * the song matched on YouTube Music and downloaded through the normal YouTube flow.
     */
    fun downloadSaavnSong(song: Song) {
        viewModelScope.launch {
            _isSongDownloading.value = true
            val saved = runCatching { saveSaavnAudio(song) }.getOrElse {
                timber.log.Timber.w(it, "JioSaavn download failed for %s", song.title)
                false
            }
            if (saved) {
                _isSongDownloading.value = false
                _isSongDownloaded.value = true
                return@launch
            }
            val match = com.unshoo.pixelmusic.data.remote.saavn.SaavnYouTubeMatcher.match(song)
            if (match?.youtubeId != null) {
                downloadYoutubeSong(match)
            } else {
                _isSongDownloading.value = false
                android.widget.Toast.makeText(context, "Download not available for this song", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun saveSaavnAudio(song: Song): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val url = song.contentUriString
        if (!url.startsWith("https://") || !(url.contains("saavncdn.com") || url.contains("jiosaavn.com"))) {
            return@withContext false
        }
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
            .header("Accept-Language", "en-IN,en;q=0.9")
            .header("Cookie", "explicit_content=1")
            .build()
        val safeName = "${song.title} - ${song.artist.substringBefore(',')}"
            .replace(Regex("[\\/:*?\"<>|]"), "_")
            .trim()
            .take(120)
        val resolver = context.contentResolver
        val collectionForLookup = android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // Already saved earlier (same name in Music/PixelMusic)? Reuse it instead of a duplicate copy.
        val existing = runCatching {
            resolver.query(
                collectionForLookup,
                arrayOf(android.provider.MediaStore.Audio.Media._ID, android.provider.MediaStore.Audio.Media.SIZE),
                "${android.provider.MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("$safeName.m4a", "Music/PixelMusic%"),
                null
            )?.use { c ->
                if (c.moveToFirst() && c.getLong(1) > 0L) {
                    android.content.ContentUris.withAppendedId(collectionForLookup, c.getLong(0))
                } else null
            }
        }.getOrNull()
        if (existing != null) {
            finishSaavnDownload(song, existing)
            return@withContext true
        }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "$safeName.m4a")
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/PixelMusic")
            put(android.provider.MediaStore.Audio.Media.TITLE, song.title)
            put(android.provider.MediaStore.Audio.Media.ARTIST, song.artist)
            put(android.provider.MediaStore.Audio.Media.ALBUM, song.album)
            put(android.provider.MediaStore.Audio.Media.IS_MUSIC, 1)
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return@withContext false
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                val written = resolver.openOutputStream(itemUri, "w")?.use { out ->
                    resp.body.byteStream().copyTo(out)
                } ?: 0L
                if (written <= 0L) throw java.io.IOException("Empty download")
            }
            resolver.update(itemUri, android.content.ContentValues().apply {
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null)
            finishSaavnDownload(song, itemUri)
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw e
        }
    }

    /**
     * Makes a saved JioSaavn file a first-class download: embeds title/artist/album and the cover
     * into the file, registers it as a completed download (so it is listed in Downloads), scans it
     * into MediaStore and refreshes the app library so it shows with full metadata everywhere.
     */
    private suspend fun finishSaavnDownload(song: Song, itemUri: android.net.Uri) {
        val resolver = context.contentResolver
        val path = runCatching {
            resolver.query(itemUri, arrayOf(android.provider.MediaStore.Audio.Media.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

        // Cover art (JioSaavn 500x500) + tags embedded into the m4a.
        runCatching {
            val artUrl = song.albumArtUriString?.takeIf { it.startsWith("http") }
            val artBytes = artUrl?.let { url ->
                okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute()
                    .use { r -> if (r.isSuccessful) r.body.bytes() else null }
            }
            resolver.openFileDescriptor(itemUri, "rw")?.use { fd ->
                val meta = com.kyant.taglib.TagLib.getMetadata(fd.dup().detachFd(), readPictures = false)
                val props = HashMap(meta?.propertyMap ?: emptyMap())
                if (song.title.isNotBlank()) props["TITLE"] = arrayOf(song.title)
                if (song.artist.isNotBlank()) props["ARTIST"] = arrayOf(song.artist)
                if (song.album.isNotBlank()) props["ALBUM"] = arrayOf(song.album)
                com.kyant.taglib.TagLib.savePropertyMap(fd.dup().detachFd(), props)
                if (artBytes != null && artBytes.isNotEmpty()) {
                    com.kyant.taglib.TagLib.savePictures(
                        fd.dup().detachFd(),
                        arrayOf(com.kyant.taglib.Picture(data = artBytes, description = "Front Cover", pictureType = "Front Cover", mimeType = "image/jpeg"))
                    )
                }
            }
        }.onFailure { timber.log.Timber.w(it, "Tagging JioSaavn download failed") }

        if (!path.isNullOrBlank()) {
            val saavnId = song.id.removePrefix("saavn_")
            val file = java.io.File(path)
            val now = System.currentTimeMillis()
            runCatching {
                offlineTrackDao.upsert(
                    com.unshoo.pixelmusic.data.database.OfflineTrackEntity(
                        downloadId = "saavn_$saavnId",
                        attemptId = "saavn",
                        songId = song.id,
                        sourceUri = "saavn://$saavnId",
                        provider = "jiosaavn",
                        title = song.title,
                        mimeType = "audio/mp4",
                        localPath = path,
                        state = com.unshoo.pixelmusic.data.offline.OfflineDownloadStatus.COMPLETE.storageValue,
                        bytesDownloaded = file.length(),
                        totalBytes = file.length(),
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }.onFailure { timber.log.Timber.w(it, "Registering JioSaavn download failed") }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("audio/mp4"), null)
        }
        runCatching { syncManager.forceRefresh() }
    }

    fun deleteYoutubeSong(song: Song) {
        val youtubeId = song.youtubeId ?: return
        viewModelScope.launch {
            downloadRepository.deleteSong(youtubeId)
            // Also remove the unified library row for downloaded YouTube songs so the item
            // disappears immediately instead of staying until a later sync.
            song.id.toLongOrNull()?.let { runCatching { musicRepository.deleteById(it) } }
            _isSongDownloaded.value = false
            _isSongDownloading.value = false
        }
    }

    fun downloadTelegramSong(song: Song) {
        val fileId = song.telegramFileId ?: return
        viewModelScope.launch {
            _isSongDownloading.value = true
            val path = runCatching { telegramRepository.downloadFileAwait(fileId, priority = 16) }.getOrNull()
            _isSongDownloaded.value = !path.isNullOrBlank()
            _isSongDownloading.value = false
        }
    }

    fun cancelYoutubeSongDownload(song: Song) {
        val youtubeId = song.youtubeId ?: return
        downloadRepository.cancelSongDownload(youtubeId)
        _isSongDownloading.value = false
    }

    private fun getCloudProviderLabel(contentUriString: String): String? {
        return when {
            contentUriString.startsWith("telegram://") -> "Telegram"
            contentUriString.startsWith("gdrive://") -> "Google Drive"
            contentUriString.startsWith("netease://") -> "NetEase Cloud Music"
            contentUriString.startsWith("qqmusic://") -> "QQ Music"
            contentUriString.contains("saavn") -> "JioSaavn"
            contentUriString.startsWith("youtube://") || contentUriString.contains("youtube") || contentUriString.contains("googlevideo") -> "YouTube Music"
            else -> null
        }
    }

    fun likeOnYouTube(song: Song, like: Boolean, onResult: (Boolean) -> Unit) {
        val videoId = song.youtubeId ?: if (song.contentUriString.startsWith("youtube://")) {
            song.contentUriString.substringAfter("youtube://")
        } else if (song.id.startsWith("youtube_")) {
            song.id.substringAfter("youtube_")
        } else {
            null
        }
        viewModelScope.launch {
            if (like) {
                try {
                    musicRepository.setDislikedStatus(song.id, false)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to clear disliked status on like")
                }
            }
            if (videoId != null && YouTube.hasLoginCookie()) {
                val result = YouTube.likeVideo(videoId, like)
                onResult(result.isSuccess)
            } else {
                onResult(true)
            }
        }
    }

    fun dislikeOnYouTube(song: Song, dislike: Boolean, onResult: (Boolean) -> Unit) {
        val videoId = song.youtubeId ?: if (song.contentUriString.startsWith("youtube://")) {
            song.contentUriString.substringAfter("youtube://")
        } else if (song.id.startsWith("youtube_")) {
            song.id.substringAfter("youtube_")
        } else {
            null
        }
        viewModelScope.launch {
            try {
                musicRepository.setDislikedStatus(song.id, dislike)
                if (dislike) {
                    musicRepository.setFavoriteStatus(song.id, false)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to update disliked status on dislike")
            }

            if (videoId != null && YouTube.hasLoginCookie()) {
                val result = YouTube.dislikeVideo(videoId, dislike)
                onResult(result.isSuccess)
            } else {
                onResult(true)
            }
        }
    }

    fun setNotInterested(song: Song, notInterested: Boolean, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val ytId = song.youtubeId ?: if (song.id.startsWith("youtube_")) {
                    song.id.substringAfter("youtube_")
                } else if (song.contentUriString.startsWith("youtube://")) {
                    song.contentUriString.substringAfter("youtube://")
                } else null
                userPreferencesRepository.setSongNotInterested(listOfNotNull(song.id, ytId), notInterested)
                if (notInterested) {
                    com.unshoo.pixelmusic.data.remote.youtube.AutoQueueManager.pruneNotInterestedFromQueue(song.id, ytId)
                }
                onResult(true)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update not interested / recommend less status")
                onResult(false)
            }
        }
    }


    fun addToYouTubePlaylist(playlistId: String, song: Song, onResult: (String?) -> Unit) {
        val videoId = song.youtubeId ?: if (song.contentUriString.startsWith("youtube://")) {
            song.contentUriString.substringAfter("youtube://")
        } else if (song.id.startsWith("youtube_")) {
            song.id.substringAfter("youtube_")
        } else {
            onResult(null)
            return
        }
        viewModelScope.launch {
            val result = YouTube.addToPlaylist(playlistId, videoId)
            onResult(result.getOrNull())
        }
    }

    fun isLoggedIn(): Boolean = YouTube.hasLoginCookie()

    private fun formatResolvedProvider(result: LosslessStreamResolver.Result): String {
        val display = result.providerDisplayName?.takeIf { it.isNotBlank() }
        if (display != null) {
            val detail = result.label.removePrefix("$display · ").removePrefix("$display ").trim()
            return if (detail.isNotBlank() && !detail.equals(display, ignoreCase = true)) {
                "$display ($detail)"
            } else {
                display
            }
        }
        return "${result.source.name} (${result.label})"
    }
}
