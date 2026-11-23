package com.unshoo.pixelmusic.data.remote.youtube

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.datastore.preferences.core.intPreferencesKey
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.model.youtube.Song
import com.unshoo.pixelmusic.data.preferences.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.unshoo.pixelmusic.data.preferences.DownloadAudioQuality
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.utils.OfflineAudioResolver
import com.unshoo.pixelmusic.utils.YouTubeIdUtils

object DownloadHelper {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DownloadHelperEntryPoint {
        fun userPreferencesRepository(): UserPreferencesRepository
    }

    private val client = YoutubeHelper.client.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun downloadImage(context: Context, imageUrl: String, id: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val imageDir =
                    PixelMusicHelper.getDownloadDirectory(context, Constants.Downloads.THUMBNAILS_FOLDER)
                val imageFile = File(imageDir, "$id.jpg")

                if (imageFile.exists()) {
                    PixelMusicHelper.printd("Song Image $id was already downloaded")
                    return@withContext imageFile
                }

                URL(imageUrl).openStream().use { input ->
                    imageFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                imageFile

            } catch (e: Exception) {
                PixelMusicHelper.printe(
                    tag = "PlaylistDownloadWorker",
                    message = "Error Downloading Thumbnail",
                    exception = e
                )
                null
            }
        }
    }

    /** 128 KB copy buffer: ~16x fewer read/write syscalls than the 8 KB default. */
    private const val IO_BUFFER = 128 * 1024

    /** Files smaller than this download over a single connection (splitting costs more than it saves). */
    private const val MIN_SPLIT_BYTES = 3L * 1024 * 1024
    private val downloadLocks = List(64) { Mutex() }

    /**
     * CPU/disk-heavy post-processing (container remux, tag writing) runs one file at a time on a
     * background-priority thread, so a playlist download never has several of them hammering
     * the CPU in parallel (the main cause of the phone heating up while downloading).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val postProcessDispatcher = Dispatchers.IO.limitedParallelism(1)

    suspend fun <T> withLowPriorityPostProcessing(block: () -> T): T = withContext(postProcessDispatcher) {
        val tid = android.os.Process.myTid()
        val previous = runCatching { android.os.Process.getThreadPriority(tid) }
            .getOrDefault(android.os.Process.THREAD_PRIORITY_DEFAULT)
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            block()
        } finally {
            runCatching { android.os.Process.setThreadPriority(previous) }
        }
    }

    /** Finds a finished download before the workers fetch metadata, artwork, or audio. */
    internal suspend fun findExistingDownload(
        context: Context,
        youtubeId: String,
        recordedPath: String?,
        musicDao: MusicDao,
    ): String? {
        OfflineAudioResolver.readableAudioPath(context, recordedPath)?.let { return it }
        val indexedUri = OfflineAudioResolver.getDownloadedAudioUri(context, youtubeId)
        OfflineAudioResolver.readableAudioPath(context, indexedUri?.toString())?.let { return it }
        val librarySong = musicDao.getSongByIdOnce(YouTubeIdUtils.toUnifiedSongId(youtubeId))
        if (librarySong == null) return null
        return OfflineAudioResolver.readableAudioPath(context, librarySong.filePath)
            ?: OfflineAudioResolver.readableAudioPath(context, librarySong.contentUriString)
    }

    /**
     * @param connections parallel HTTP range requests for one file. Kept low (4) by default;
     *   playlist downloads pass 2 because several songs already download side by side.
     * @param onFileReady called with the finished local file BEFORE it is copied to the user's
     *   download folder, so tags/artwork are written once on fast app storage instead of
     *   rewriting the file again through the Storage Access Framework afterwards.
     */
    suspend fun downloadAudio(
        context: Context,
        song: Song,
        connections: Int = 4,
        checkDeviceLibrary: Boolean = true,
        onFileReady: (suspend (File) -> Unit)? = null,
    ): String? {
        val lockIndex = (song.youtubeId.hashCode() and Int.MAX_VALUE) % downloadLocks.size
        return downloadLocks[lockIndex].withLock {
            downloadAudioToStorage(context, song, connections, checkDeviceLibrary, onFileReady)
        }
    }

    private suspend fun downloadAudioToStorage(
        context: Context,
        song: Song,
        connections: Int,
        checkDeviceLibrary: Boolean,
        onFileReady: (suspend (File) -> Unit)?,
    ): String? = withContext(Dispatchers.IO) {

        val userPreferencesRepository = try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                DownloadHelperEntryPoint::class.java
            ).userPreferencesRepository()
        } catch (_: Exception) {
            null
        }
        val downloadQuality = userPreferencesRepository?.downloadAudioQualityFlow?.first()
            ?: DownloadAudioQuality.HIGH
        val allowLossless = (downloadQuality == DownloadAudioQuality.MAX)

        // ── Check if audio file already exists locally with >= 80% data similarity and matching artist ──
        val existingLocalFile = if (checkDeviceLibrary && YoutubeHelper.useDeviceLibraryMatches) {
            com.unshoo.pixelmusic.utils.LocalAudioDuplicateMatcher.findMatchingLocalFilePath(
                context,
                song,
                allowLossless = allowLossless
            )
        } else null
        if (existingLocalFile != null) {
            PixelMusicHelper.printd("Download skipped: '${song.title}' already exists locally at $existingLocalFile (>=80% match)")
            return@withContext existingLocalFile
        }

        val repo = DatastoreRepository(context)
        val customPath = repo.customDownloadPath.first()
        val safeTitle = song.title.replace(Regex("[\\\\/:*?\"\\<>|]"), "_")
        val safeArtist = song.artist.replace(Regex("[\\\\/:*?\"\\<>|]"), "_")

        if (customPath.isNotBlank()) {
            try {
                val treeUri = Uri.parse(customPath)
                val documentDir = DocumentFile.fromTreeUri(context, treeUri)
                val baseName = "$safeTitle - $safeArtist"
                val extensions = buildList {
                    if (allowLossless) add("flac")
                    addAll(listOf("m4a", "opus", "ogg", "webm"))
                }
                for (ext in extensions) {
                    val existing = documentDir?.findFile("$baseName.$ext")
                    if (existing != null && existing.isCompleteFile()) {
                        return@withContext existing.uri.toString()
                    }
                }
            } catch (e: Exception) {
                PixelMusicHelper.printe("Error checking custom download path exists: ${e.message}")
            }
        }

        val audioDir =
            PixelMusicHelper.getDownloadDirectory(context, Constants.Downloads.AUDIO_FILES_FOLDER)
        val flacFile = File(audioDir, "${song.youtubeId}.flac")
        val m4aFile = File(audioDir, "${song.youtubeId}.m4a")
        val webmFile = File(audioDir, "${song.youtubeId}.webm")

        if (allowLossless && flacFile.exists() && flacFile.length() > 0) {
            return@withContext flacFile.absolutePath
        }
        if (m4aFile.exists() && m4aFile.length() > 0) {
            return@withContext m4aFile.absolutePath
        }
        val opusFile = File(audioDir, "${song.youtubeId}.opus")
        if (opusFile.exists() && opusFile.length() > 0) {
            return@withContext opusFile.absolutePath
        }
        if (webmFile.exists() && webmFile.length() > 0) {
            // Older builds saved AAC data under ".webm"; rename it to its real container so the
            // library, MediaStore and tag writers recognise it.
            val sniffed = sniffAudioContainer(webmFile)
            if (sniffed != null && sniffed.first != "webm") {
                val fixed = File(audioDir, "${song.youtubeId}.${sniffed.first}")
                if (webmFile.renameTo(fixed)) return@withContext fixed.absolutePath
            }
            // Real WebM/Opus from an older build: rewrap to Ogg so it is visible as music.
            if (AudioContainerRemuxer.remuxWebmToOgg(webmFile, opusFile)) {
                runCatching { webmFile.delete() }
                return@withContext opusFile.absolutePath
            }
            return@withContext webmFile.absolutePath
        }

        val tempFile = File(audioDir, "${song.youtubeId}.tmp")

        val remuxTemp = File(audioDir, "${song.youtubeId}.remux.tmp")
        fun cleanupTempFile() {
            tempFile.delete()
        }

        val isSaavnEnabled = userPreferencesRepository?.enableSaavnStreamingFlow?.first() ?: true
        val maxRetries = 3
        var lastException: Exception? = null
        var bypassLossless = !allowLossless
        var bypassSaavn = (downloadQuality == DownloadAudioQuality.LOW) || !isSaavnEnabled

        for (attempt in 1..maxRetries) {
            try {
                // Invalidate any cached/expired stream URL to guarantee fresh highest-quality URL
                YoutubeHelper.invalidateStreamCache(song.youtubeId)
                val url = YoutubeHelper.getSongPlayerUrlWithQuality(
                    context = context,
                    song = song,
                    maxBitrateKbps = 0,
                    forDownload = true,
                    bypassLossless = bypassLossless,
                    bypassSaavn = bypassSaavn
                )
                if (url.isBlank()) {
                    throw IOException("Empty stream URL for song ${song.youtubeId}")
                }

                val isLossless = url.contains(".flac", ignoreCase = true) ||
                    url.contains("flac", ignoreCase = true) ||
                    com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver.isLosslessUri(url)

                if (!allowLossless && isLossless) {
                    PixelMusicHelper.printd("Stream URL for ${song.youtubeId} resolved to lossless but download quality is $downloadQuality. Bypassing lossless and retrying...")
                    bypassLossless = true
                    throw IOException("Bypassing unwanted lossless stream for quality $downloadQuality")
                }

                val addonHeaders = com.unshoo.pixelmusic.data.addons.AddonStreamRegistry.headersFor(url)
                val isSegmentedManifest = com.unshoo.pixelmusic.data.addons.ManifestSegmentAssembler.isSegmentedStream(url) ||
                    com.unshoo.pixelmusic.data.addons.AddonStreamRegistry.metaFor(url)?.transport?.let {
                        it == com.unshoo.pixelmusic.data.addons.AddonTransport.HLS ||
                            it == com.unshoo.pixelmusic.data.addons.AddonTransport.DASH
                    } == true

                if (isSegmentedManifest) {
                    val assembled = com.unshoo.pixelmusic.data.addons.ManifestSegmentAssembler.assembleToFile(
                        httpClient = client,
                        streamUri = url,
                        outputFile = tempFile,
                        extraHeaders = addonHeaders,
                    )
                    val segExt = assembled.extension
                    val segMime = assembled.mimeType
                    val segDynamicName = "$safeTitle - $safeArtist.$segExt"
                    val segOutputFile = File(audioDir, "${song.youtubeId}.$segExt")

                    if (!tempFile.exists() || tempFile.length() <= 0) {
                        throw IOException("Assembled manifest file is zero bytes")
                    }
                    if (onFileReady != null) {
                        runCatching { onFileReady(tempFile) }
                            .onFailure { if (it is CancellationException) throw it }
                    }
                    if (customPath.isNotBlank()) {
                        val documentDir = DocumentFile.fromTreeUri(context, Uri.parse(customPath))
                        val outputUri = documentDir?.let { writeToDocumentTree(context, it, segDynamicName, tempFile) }
                        if (outputUri != null) {
                            cleanupTempFile()
                            return@withContext outputUri.toString()
                        }
                    }
                    if (!tempFile.renameTo(segOutputFile)) {
                        tempFile.copyTo(segOutputFile, overwrite = true)
                        cleanupTempFile()
                    }
                    return@withContext segOutputFile.absolutePath
                }

                val isSaavn = url.contains("saavncdn.com") || url.contains("jiosaavn.com")
                val resolvedLosslessMeta = com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver.resultFor(song.youtubeId)
                val isM4a = isSaavn || url.contains(".mp4") || url.contains(".m4a") ||
                    resolvedLosslessMeta?.mimeType?.contains("mp4", true) == true
                val ext = when {
                    isLossless && !isM4a -> "flac"
                    isM4a -> "m4a"
                    else -> "webm"
                }
                val mimeType = when {
                    ext == "flac" -> "audio/flac"
                    ext == "m4a" -> "audio/mp4"
                    else -> "audio/webm"
                }
                val dynamicFileName = "$safeTitle - $safeArtist.$ext"
                val outputFile = File(audioDir, "${song.youtubeId}.$ext")

                val userAgent = addonHeaders["User-Agent"] ?: if (isSaavn) {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
                } else {
                    Constants.YoutubeApi.USER_AGENT
                }

                // Determine total length for multi-part parallel downloading if requested
                var totalLength: Long = -1L
                try {
                    val headReq = Request.Builder()
                        .url(url)
                        .header("User-Agent", userAgent)
                        .apply {
                            addonHeaders.forEach { (k, v) -> header(k, v) }
                            if (isSaavn) {
                                header("Accept-Language", "en-IN,en;q=0.9")
                                header("Cookie", "explicit_content=1")
                            }
                        }
                        .header("Range", "bytes=0-0")
                        .build()

                    client.newCall(headReq).execute().use { res ->
                        val contentRange = res.header("Content-Range")
                        if (contentRange != null && contentRange.contains("/")) {
                            totalLength = contentRange.substringAfter("/").toLongOrNull() ?: -1L
                        }
                    }
                } catch (_: Exception) {}

                fun singleStreamDownload() {
                    // Single stream download with Range header
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", userAgent)
                        .apply {
                            addonHeaders.forEach { (k, v) -> header(k, v) }
                            if (isSaavn) {
                                header("Accept-Language", "en-IN,en;q=0.9")
                                header("Cookie", "explicit_content=1")
                            }
                        }
                        .header("Range", "bytes=0-")
                        .header("Accept", "*/*")
                        .build()

                    client.newCall(req).execute().use { response ->
                        if (!response.isSuccessful && response.code != 206) {
                            throw IOException("Failed download (HTTP ${response.code}) for song ${song.youtubeId}")
                        }

                        response.body.byteStream().use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output, IO_BUFFER)
                            }
                        }
                    }
                }

                val numConnections = connections.coerceIn(1, 8)
                if (totalLength >= MIN_SPLIT_BYTES && numConnections > 1) {
                    // Each range is written straight to its offset in the one output file: no
                    // per-chunk part files and no second pass concatenating them (halves disk I/O).
                    try {
                        java.io.RandomAccessFile(tempFile, "rw").use { raf -> raf.setLength(totalLength) }
                        val chunkSize = totalLength / numConnections
                        // coroutineScope: a failed chunk throws here instead of cancelling the whole download job.
                        kotlinx.coroutines.coroutineScope {
                        (0 until numConnections).map { i ->
                            async {
                                val start = i * chunkSize
                                val end = if (i == numConnections - 1) totalLength - 1 else (start + chunkSize - 1)

                                val req = Request.Builder()
                                    .url(url)
                                    .header("User-Agent", userAgent)
                                    .apply {
                                        addonHeaders.forEach { (k, v) -> header(k, v) }
                                        if (isSaavn) {
                                            header("Accept-Language", "en-IN,en;q=0.9")
                                            header("Cookie", "explicit_content=1")
                                        }
                                    }
                                    .header("Range", "bytes=$start-$end")
                                    .header("Accept", "*/*")
                                    .build()

                                client.newCall(req).execute().use { response ->
                                    if (response.code != 206) {
                                        throw IOException("Failed chunk $i (HTTP ${response.code})")
                                    }
                                    val expected = end - start + 1
                                    var written = 0L
                                    java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                                        val channel = raf.channel
                                        val buf = ByteArray(IO_BUFFER)
                                        response.body.byteStream().use { input ->
                                            while (true) {
                                                ensureActive()
                                                val n = input.read(buf)
                                                if (n < 0) break
                                                val bb = java.nio.ByteBuffer.wrap(buf, 0, n)
                                                while (bb.hasRemaining()) {
                                                    written += channel.write(bb, start + written)
                                                }
                                            }
                                        }
                                    }
                                    if (written != expected) {
                                        throw IOException("Chunk $i incomplete ($written/$expected bytes)")
                                    }
                                }
                            }
                        }.awaitAll()
                        }
                    } catch (e: CancellationException) {
                        cleanupTempFile()
                        throw e
                    } catch (e: Exception) {
                        // Server refused ranges (HTTP 200) or a chunk broke: one plain stream instead.
                        PixelMusicHelper.printd("Parallel download failed for ${song.youtubeId} (${e.message}); using a single stream")
                        cleanupTempFile()
                        singleStreamDownload()
                    }
                } else {
                    singleStreamDownload()
                }

                if (!tempFile.exists() || tempFile.length() <= 0) {
                    throw IOException("Downloaded file is zero bytes")
                }

                // BUGFIX: the container was guessed from the URL, but googlevideo URLs never contain
                // ".mp4"/".m4a" (the format is only in the `mime=` query), so every YouTube AAC
                // download was saved as ".webm" with mime audio/webm. Android then indexed it as a
                // *video*, the library/Files app did not list it as music, and TagLib could not
                // write tags. Name the file after its real container bytes instead.
                var (finalExt, finalMime) = sniffAudioContainer(tempFile) ?: (ext to mimeType)

                // WebM is indexed by Android as video, so it never appears in the music library or
                // folder scans. Rewrap the Opus stream into Ogg (no re-encode, no quality loss).
                if (finalExt == "webm") {
                    val oggTemp = remuxTemp
                    if (withLowPriorityPostProcessing { AudioContainerRemuxer.remuxWebmToOgg(tempFile, oggTemp) }) {
                        cleanupTempFile()
                        if (!oggTemp.renameTo(tempFile)) {
                            oggTemp.copyTo(tempFile, overwrite = true)
                            runCatching { oggTemp.delete() }
                        }
                        finalExt = "opus"
                        finalMime = "audio/ogg"
                    }
                }
                remuxTemp.delete()
                val finalFileName = "$safeTitle - $safeArtist.$finalExt"
                val finalOutputFile = File(audioDir, "${song.youtubeId}.$finalExt")

                // Tag the file while it is still on app storage (one cheap local rewrite).
                if (onFileReady != null) {
                    runCatching { onFileReady(tempFile) }
                        .onFailure { if (it is CancellationException) throw it }
                }

                // Copy to custom path if set
                if (customPath.isNotBlank()) {
                    val documentDir = DocumentFile.fromTreeUri(context, Uri.parse(customPath))
                    val outputUri = documentDir?.let { writeToDocumentTree(context, it, finalFileName, tempFile) }
                    if (outputUri != null) {
                        cleanupTempFile()
                        return@withContext outputUri.toString()
                    }
                }

                // Finished downloading audio file successfully
                if (!tempFile.renameTo(finalOutputFile)) {
                    tempFile.copyTo(finalOutputFile, overwrite = true)
                    cleanupTempFile()
                }
                if (finalOutputFile != outputFile) {
                    // A stale mis-named copy from an older build would shadow the correct file.
                    runCatching { if (outputFile.exists()) outputFile.delete() }
                }

                return@withContext finalOutputFile.absolutePath

            } catch (e: CancellationException) {
                remuxTemp.delete()
                cleanupTempFile()
                throw e
            } catch (e: Exception) {
                lastException = e
                remuxTemp.delete()
                cleanupTempFile()
                PixelMusicHelper.printe("Download attempt $attempt/$maxRetries failed for ${song.title}: ${e.message}")
                if (!bypassLossless) {
                    bypassLossless = true
                    PixelMusicHelper.printd("Lossless stream failed for ${song.title}, falling back to JioSaavn/Standard stream")
                } else if (!bypassSaavn) {
                    bypassSaavn = true
                    PixelMusicHelper.printd("High-quality stream failed for ${song.title}, falling back to YouTube audio stream")
                }
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(attempt * 600L)
                }
            }
        }

        PixelMusicHelper.printe("All $maxRetries download attempts failed for ${song.title}")
        return@withContext null
    }

    /**
     * Copies [source] into the user's download folder as exactly [fileName], atomically.
     *
     * BUGFIX ("Song.webm (1)" files): this used to `createFile(mime, fileName)` straight into
     * the folder on every attempt. When a copy died midway (network drop, worker stopped) the
     * half-written document stayed behind, and the retry's createFile got an auto-renamed
     * duplicate. For MIME types Android has no extension for (audio/webm) the counter lands
     * AFTER the extension ("…webm (1)"), so no player could open it. Partial files were also
     * mistaken for finished downloads by the "already exists" check.
     *
     * Now the data is written to "<name>.partial" first, and only a complete copy is renamed to
     * the final name (replacing any stale file of that name). On failure the partial is removed.
     */
    private fun writeToDocumentTree(context: Context, documentDir: DocumentFile, fileName: String, source: File): Uri? {
        val partialName = "$fileName.partial"
        documentDir.findFile(partialName)?.delete()
        val partial = documentDir.createFile("application/octet-stream", partialName) ?: return null
        try {
            val out = context.contentResolver.openOutputStream(partial.uri, "wt")
                ?: throw IOException("Cannot open ${partial.uri}")
            out.use { stream -> source.inputStream().use { it.copyTo(stream, IO_BUFFER) } }
            if (partial.length() != source.length()) {
                throw IOException("Incomplete copy (${partial.length()}/${source.length()} bytes)")
            }
            documentDir.findFile(fileName)?.let { stale -> if (stale.uri != partial.uri) stale.delete() }
            if (!partial.renameTo(fileName)) throw IOException("Rename to $fileName failed")
            // renameTo updates the DocumentFile's uri in place; re-resolve by name to be safe.
            return (documentDir.findFile(fileName) ?: partial).uri
        } catch (e: Exception) {
            runCatching { partial.delete() }
            if (e is CancellationException) throw e
            PixelMusicHelper.printe("Failed to write $fileName to download folder: ${e.message}")
            return null
        }
    }

    /** A finished file in the download folder (not a leftover partial / empty document). */
    private fun DocumentFile.isCompleteFile(): Boolean = exists() && isFile && length() > 0

    /**
     * Detects the audio container from its magic bytes: (extension, mime) or null if unknown.
     * MP4/M4A: "ftyp" at offset 4; Matroska/WebM: 1A 45 DF A3; FLAC: "fLaC"; Ogg: "OggS"; MP3: ID3/frame sync.
     */
    internal fun sniffAudioContainer(file: File): Pair<String, String>? = runCatching {
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        if (read < 4) return@runCatching null
        fun ascii(from: Int, len: Int) = String(head, from, len, Charsets.US_ASCII)
        when {
            read >= 8 && ascii(4, 4) == "ftyp" -> "m4a" to "audio/mp4"
            head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() ->
                "webm" to "audio/webm"
            ascii(0, 4) == "fLaC" -> "flac" to "audio/flac"
            ascii(0, 4) == "OggS" -> "ogg" to "audio/ogg"
            ascii(0, 3) == "ID3" || (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) -> "mp3" to "audio/mpeg"
            else -> null
        }
    }.getOrNull()

    suspend fun copyToPublicDownload(context: Context, sourceFilePath: String, songTitle: String, artistName: String): File? {
        try {
            val sourceFile = File(sourceFilePath)
            if (!sourceFile.exists()) return null

            val isFlac = sourceFilePath.endsWith(".flac", ignoreCase = true)
            val isM4a = sourceFilePath.endsWith(".m4a", ignoreCase = true) || sourceFilePath.endsWith(".mp4", ignoreCase = true)
            val isOgg = sourceFilePath.endsWith(".opus", ignoreCase = true) || sourceFilePath.endsWith(".ogg", ignoreCase = true)
            val isMp3 = sourceFilePath.endsWith(".mp3", ignoreCase = true)
            val ext = when {
                isFlac -> "flac"
                isM4a -> "m4a"
                isOgg -> sourceFilePath.substringAfterLast('.').lowercase()
                isMp3 -> "mp3"
                else -> "webm"
            }
            val mimeType = when {
                isFlac -> "audio/flac"
                isM4a -> "audio/mp4"
                isOgg -> "audio/ogg"
                isMp3 -> "audio/mpeg"
                else -> "audio/webm"
            }
            val safeTitle = songTitle.replace(Regex("[\\\\/:*?\"\\<>|]"), "_")
            val safeArtist = artistName.replace(Regex("[\\\\/:*?\"\\<>|]"), "_")
            val fileName = "$safeTitle - $safeArtist.$ext"

            val repo = DatastoreRepository(context)
            val customPath = repo.customDownloadPath.first()
            val publicDownloadDir = if (customPath.isNotBlank()) {
                File(customPath)
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PixelMusic")
            }
            if (!publicDownloadDir.exists()) {
                publicDownloadDir.mkdirs()
            }
            val destinationFile = File(publicDownloadDir, fileName)

            sourceFile.inputStream().use { input ->
                destinationFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(destinationFile.absolutePath),
                arrayOf(mimeType),
                null
            )

            return destinationFile
        } catch (e: Exception) {
            PixelMusicHelper.printe("Failed to copy to public downloads: ${e.message}", exception = e)
            return null
        }
    }
}
