package com.unshoo.pixelmusic.data.image

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import coil.fetch.DrawableResult
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.unshoo.pixelmusic.data.telegram.TelegramRepository
import okio.Path.Companion.toPath
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

import com.unshoo.pixelmusic.data.network.deezer.DeezerApiService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom Coil Fetcher for Telegram album art.
 * Handles URIs in format: telegram_art://chatId/messageId
 * 
 * Optimized to prevent blocking the UI thread for high-quality art downloads/searches.
 * Returns minithumbnail immediately and upgrades to high-quality in background.
 */
class TelegramCoilFetcher(
    private val context: Context,
    private val uri: Uri,
    private val telegramRepository: TelegramRepository,
    private val cacheDir: File,
    private val telegramCacheManager: com.unshoo.pixelmusic.data.telegram.TelegramCacheManager?,
    private val deezerApiService: DeezerApiService
) : Fetcher {

    companion object {
        private val backgroundArtScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        private val recentlyLoggedFailures = ConcurrentHashMap<String, Long>()
        private const val LOG_FAILURE_COOLDOWN_MS = 60_000L
        private val extractionMapMutex = Mutex()
        private val extractionLocks = ConcurrentHashMap<String, Mutex>()

        private fun shouldLogFailure(key: String): Boolean {
            val now = System.currentTimeMillis()
            val lastLogged = recentlyLoggedFailures[key]
            return if (lastLogged == null || now - lastLogged > LOG_FAILURE_COOLDOWN_MS) {
                recentlyLoggedFailures[key] = now
                true
            } else false
        }
    }

    override suspend fun fetch(): FetchResult? {
        val chatId = uri.host?.toLongOrNull()
        val messageId = uri.pathSegments.firstOrNull()?.toLongOrNull()
        if (chatId == null || messageId == null) return null

        val key = "${chatId}_${messageId}"
        val isThumbRequest = uri.getQueryParameter("quality") == "thumb"

        // 0. YOUTUBE MUSIC ART (preferred): proper full-resolution album art matched by
        // title + artist. Resolved once, cached on disk, and returned before any low-res
        // Telegram thumbnail so the cover never starts blurry and never swaps (flickers).
        val ytmFile = File(cacheDir, "telegram_ytm_art_${key}.jpg")
        if (ytmFile.exists() && ytmFile.length() > 0) {
            return SourceResult(
                source = coil.decode.ImageSource(ytmFile.absolutePath.toPath(), okio.FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK
            )
        }

        // BUGFIX (flicker): `?quality=thumb` is the placeholder request the mini player / carousel
        // issue alongside the real one. It used to run the whole pipeline (network lookup, deep
        // fetch) a second time in parallel and race the main request. It now only returns what is
        // already on disk / in the message, and kicks off the YT Music lookup for the main request.
        if (isThumbRequest) {
            backgroundArtScope.launch { runCatching { fetchYtMusicCover(chatId, messageId, key, ytmFile) } }
            return fastLocalResult(chatId, messageId, key, allowMinithumbnail = true)
        }

        // Run the online lookup detached so a slow network never cancels it. The placeholder
        // (thumb) request is on screen meanwhile, so wait long enough that the YT Music cover is
        // normally the FIRST full-size image shown instead of a blurry Telegram one swapped later.
        val ytmJob = backgroundArtScope.async { fetchYtMusicCover(chatId, messageId, key, ytmFile) }
        val ytmResolved = withTimeoutOrNull(6000L) { ytmJob.await() }
        if (ytmResolved == null) {
            backgroundArtScope.launch {
                if (runCatching { ytmJob.await() }.getOrDefault(false)) {
                    telegramCacheManager?.notifyEmbeddedArtExtracted(chatId, messageId)
                }
            }
        }
        if (ytmResolved == true) {
            return SourceResult(
                source = coil.decode.ImageSource(ytmFile.absolutePath.toPath(), okio.FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.NETWORK
            )
        }

        val cachedDeezerFile = File(cacheDir, "telegram_deezer_art_${key}.jpg")
        val cachedArtFile = File(cacheDir, "telegram_embedded_art_${key}.jpg")

        // 1. Anything full-size already available locally (never the blurry minithumbnail here:
        // returning it as the main image is what left the full player with a blurred cover).
        fastLocalResult(chatId, messageId, key, allowMinithumbnail = false)?.let { return it }

        // 2. DEEP FETCH: short window for a quick Deezer / embedded-art match.
        val result = withTimeoutOrNull(2000L) {
            performDeepFetch(chatId, messageId, key, cachedDeezerFile, cachedArtFile)
        }
        if (result != null) return result

        // 3. Last resort: low-res minithumbnail now, refined in the background.
        telegramRepository.enqueueHighQualityArtFetch(chatId, messageId)
        return minithumbnailResult(chatId, messageId)
    }

    /** Cached full-size covers, then TDLib's downloaded thumbnail, then (optionally) the minithumbnail. No network. */
    private suspend fun fastLocalResult(chatId: Long, messageId: Long, key: String, allowMinithumbnail: Boolean): FetchResult? {
        val candidates = listOfNotNull(
            telegramCacheManager?.getPersistentArtFile(chatId, messageId),
            File(cacheDir, "telegram_deezer_art_${key}.jpg"),
            File(cacheDir, "telegram_embedded_art_${key}.jpg"),
        )
        candidates.firstOrNull { it.exists() && it.length() > 0 }?.let { file ->
            return SourceResult(
                source = coil.decode.ImageSource(file.absolutePath.toPath(), okio.FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK
            )
        }

        val message = telegramRepository.getMessage(chatId, messageId) ?: return null
        val fileId = extractFileIdFromContent(message.content)
        if (fileId != null) {
            val file = telegramRepository.getFile(fileId)
            if (file?.local?.isDownloadingCompleted == true && !file.local.path.isNullOrEmpty()) {
                return SourceResult(
                    source = coil.decode.ImageSource(file.local.path.toPath(), okio.FileSystem.SYSTEM),
                    mimeType = null,
                    dataSource = DataSource.DISK
                )
            }
        }
        return if (allowMinithumbnail) minithumbnailResult(chatId, messageId) else null
    }

    private suspend fun minithumbnailResult(chatId: Long, messageId: Long): FetchResult? {
        val message = telegramRepository.getMessage(chatId, messageId) ?: return null
        val data = extractMinithumbnail(message.content) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        return DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.MEMORY
        )
    }

    private suspend fun performDeepFetch(
        chatId: Long,
        messageId: Long,
        key: String,
        cachedDeezerFile: File,
        cachedArtFile: File
    ): FetchResult? {
        val noDeezerMarker = File(cacheDir, "telegram_deezer_art_${key}_none")
        if (!noDeezerMarker.exists()) {
            val message = telegramRepository.getMessage(chatId, messageId)
            if (message != null) {
                val title = when (val content = message.content) {
                    is TdApi.MessageAudio -> content.audio.title ?: ""
                    is TdApi.MessageDocument -> content.document.fileName ?: ""
                    else -> ""
                }
                val artist = (message.content as? TdApi.MessageAudio)?.audio?.performer ?: ""
                
                val cleanedTitle = cleanTitle(title)
                if (cleanedTitle.isNotBlank()) {
                    try {
                        val query = if (artist.isNotBlank() && artist != "Unknown Artist") {
                            "track:\"$cleanedTitle\" artist:\"$artist\""
                        } else cleanedTitle
                        
                        val searchResponse = deezerApiService.searchTrack(query, limit = 1)
                        val coverUrl = searchResponse.data.firstOrNull()?.album?.let { 
                            it.coverXl ?: it.coverBig ?: it.coverMedium ?: it.cover
                        }

                        if (coverUrl != null) {
                            val upgradedUrl = upgradeToHighResDeezerUrl(coverUrl)
                            if (downloadDeezerCover(upgradedUrl, cachedDeezerFile)) {
                                return SourceResult(
                                    source = coil.decode.ImageSource(cachedDeezerFile.absolutePath.toPath(), okio.FileSystem.SYSTEM),
                                    mimeType = "image/jpeg",
                                    dataSource = DataSource.NETWORK
                                )
                            }
                        }
                        noDeezerMarker.createNewFile()
                    } catch (_: Exception) {}
                }
            }
        }

        // Try embedded art extraction
        val embeddedArtPath = tryExtractEmbeddedArtIfSafe(chatId, messageId)
        if (embeddedArtPath != null) {
            return SourceResult(
                source = coil.decode.ImageSource(embeddedArtPath.toPath(), okio.FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK
            )
        }

        return null
    }

    /**
     * Looks the song up on YouTube Music (title + performer from the Telegram message) and
     * downloads the matching song's album art in high resolution. Returns true when [outFile]
     * now holds the cover. A "_none" marker avoids repeating failed lookups.
     */
    private suspend fun fetchYtMusicCover(chatId: Long, messageId: Long, key: String, outFile: File): Boolean {
        val noneMarker = File(cacheDir, "telegram_ytm_art_${key}_none")
        if (noneMarker.exists()) return false
        val lock = extractionMapMutex.withLock { extractionLocks.getOrPut("ytm_$key") { Mutex() } }
        return lock.withLock {
            if (outFile.exists() && outFile.length() > 0) return@withLock true
            try {
                val message = telegramRepository.getMessage(chatId, messageId) ?: return@withLock false
                val rawTitle = when (val content = message.content) {
                    is TdApi.MessageAudio -> content.audio.title?.takeIf { it.isNotBlank() } ?: content.audio.fileName ?: ""
                    is TdApi.MessageDocument -> content.document.fileName ?: ""
                    else -> ""
                }
                val artist = (message.content as? TdApi.MessageAudio)?.audio?.performer
                    ?.takeIf { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
                val title = cleanTitle(rawTitle)
                if (title.isBlank()) {
                    noneMarker.createNewFile()
                    return@withLock false
                }
                val query = listOfNotNull(title, artist).joinToString(" ")
                val result = unshoo.ianshulyadav.pixelmusic.innertube.YouTube.search(
                    query,
                    unshoo.ianshulyadav.pixelmusic.innertube.YouTube.SearchFilter.FILTER_SONG
                ).getOrNull()
                val song = result?.items
                    ?.filterIsInstance<unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem>()
                    ?.firstOrNull { it.thumbnail.isNotBlank() }
                val coverUrl = com.unshoo.pixelmusic.data.remote.youtube.upgradeThumbnailUrlToHighQuality(song?.thumbnail)
                if (coverUrl.isNullOrBlank()) {
                    noneMarker.createNewFile()
                    return@withLock false
                }
                val tmp = File(cacheDir, "telegram_ytm_art_${key}.tmp")
                if (downloadDeezerCover(coverUrl, tmp) && tmp.length() > 0) {
                    tmp.renameTo(outFile)
                    outFile.exists()
                } else {
                    tmp.delete()
                    false
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
        }
    }

    private fun extractMinithumbnail(content: TdApi.MessageContent): ByteArray? {
        return when (content) {
            is TdApi.MessageAudio -> content.audio.albumCoverMinithumbnail?.data
            is TdApi.MessageDocument -> content.document.minithumbnail?.data
            else -> null
        }
    }

    private suspend fun tryExtractEmbeddedArtIfSafe(chatId: Long, messageId: Long): String? {
        val key = "${chatId}_${messageId}"
        val cachedArtFile = File(cacheDir, "telegram_embedded_art_${key}.jpg")
        val noArtMarker = File(cacheDir, "telegram_embedded_art_${key}_none")

        if (cachedArtFile.exists() && cachedArtFile.length() > 0) return cachedArtFile.absolutePath
        if (noArtMarker.exists()) return null

        val lock = extractionMapMutex.withLock { extractionLocks.getOrPut(key) { Mutex() } }

        return lock.withLock {
            if (cachedArtFile.exists() && cachedArtFile.length() > 0) return@withLock cachedArtFile.absolutePath
            
            val message = telegramRepository.getMessage(chatId, messageId) ?: return@withLock null
            val audioFileId = when (val c = message.content) {
                is TdApi.MessageAudio -> c.audio.audio.id
                is TdApi.MessageDocument -> c.document.document.id
                else -> null
            } ?: return@withLock null

            val audioFile = telegramRepository.getFile(audioFileId)
            if (audioFile?.local?.isDownloadingCompleted != true || audioFile.local.path.isNullOrEmpty()) return@withLock null

            val extractedPath = extractAndCacheEmbeddedArt(audioFile.local.path, cachedArtFile, noArtMarker)
            if (extractedPath != null) {
                telegramCacheManager?.notifyEmbeddedArtExtracted(chatId, messageId)
            }
            extractedPath
        }
    }

    private fun extractAndCacheEmbeddedArt(audioFilePath: String, cacheFile: File, noArtMarker: File): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioFilePath)
            val pic = retriever.embeddedPicture
            if (pic != null && pic.isNotEmpty()) {
                // Validate image data before caching: inJustDecodeBounds only reads
                // dimensions without loading pixels — zero memory overhead.
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(pic, 0, pic.size, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    FileOutputStream(cacheFile).use { it.write(pic) }
                    cacheFile.absolutePath
                } else {
                    noArtMarker.createNewFile()
                    null
                }
            } else {
                noArtMarker.createNewFile()
                null
            }
        } catch (e: Exception) {
            noArtMarker.createNewFile()
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun extractFileIdFromContent(content: TdApi.MessageContent?): Int? {
        return when (content) {
            is TdApi.MessageAudio -> {
                val audio = content.audio
                val candidates = buildList {
                    audio.albumCoverThumbnail?.let(::add)
                    audio.externalAlbumCovers?.let(::addAll)
                }
                candidates.maxByOrNull { it.width * it.height }?.file?.id
            }
            is TdApi.MessageDocument -> content.document.thumbnail?.file?.id
            else -> null
        }
    }

    private fun cleanTitle(title: String): String = title
        .replace(Regex("\\.mp3$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^\\[\\d+]"), "")
        .replace(Regex("^\\d+\\s*-\\s*"), "")
        .trim()

    private val deezerSizeRegex = Regex("/\\d{2,4}x\\d{2,4}([\\-.])")
    private fun upgradeToHighResDeezerUrl(url: String): String = deezerSizeRegex.replace(url, "/1000x1000$1")

    private suspend fun downloadDeezerCover(url: String, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .build()
            com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                resp.body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output -> input.copyTo(output) }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    class Factory @Inject constructor(
        private val telegramRepository: TelegramRepository,
        private val telegramCacheManager: com.unshoo.pixelmusic.data.telegram.TelegramCacheManager,
        private val deezerApiService: DeezerApiService
    ) : Fetcher.Factory<Uri> {
        private var cacheDir: File? = null
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "telegram_art") return null
            val cache = cacheDir ?: options.context.cacheDir.also { cacheDir = it }
            return TelegramCoilFetcher(options.context, data, telegramRepository, cache, telegramCacheManager, deezerApiService)
        }
    }
}
