package com.unshoo.pixelmusic.data.lossless.cache

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * A Media3 DataSource wrapper that streams lossless audio directly from upstream
 * on the first play while sequentially recording it to a complete .flac file.
 *
 * Once the full file is recorded, any subsequent replay opens the local FileDataSource
 * with 0 MB network data, instantaneous seeking, and zero cache-seam cutouts.
 */
@UnstableApi
class LosslessCachingDataSource(
    private val context: Context,
    private val upstream: DataSource,
) : DataSource {

    private var tempFile: File? = null
    private var fileOutputStream: FileOutputStream? = null
    private var cacheKey: String? = null
    private var bytesWritten: Long = 0L
    private var cachingAborted: Boolean = false
    private var isPlayingFromCacheFile: Boolean = false
    private var localFileSource: FileDataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri

        // 1. If already a local file, delegate directly
        if (uri.scheme == "file") {
            isPlayingFromCacheFile = false
            return upstream.open(dataSpec)
        }

        // 2. Extract deterministic clean cache key
        val rawKey = dataSpec.key ?: LosslessFileCache.extractCacheKey(uri)
        cacheKey = rawKey

        // 3. Check if 100% complete cached file exists on disk
        if (rawKey != null) {
            val completeFile = LosslessFileCache.getCompleteFile(context, rawKey)
            if (completeFile != null && completeFile.isFile && completeFile.length() >= 64 * 1024L) {
                isPlayingFromCacheFile = true
                val fileDataSpec = dataSpec.buildUpon()
                    .setUri(Uri.fromFile(completeFile))
                    .build()
                val fileSource = FileDataSource()
                localFileSource = fileSource
                Timber.tag("LosslessCache").d("Serving %s directly from complete local cache (%d bytes)", rawKey, completeFile.length())
                return fileSource.open(fileDataSpec)
            }
        }

        isPlayingFromCacheFile = false

        // 4. Open upstream connection (direct HTTP / decryptor / DASH stitcher)
        val contentLength = upstream.open(dataSpec)

        // 5. Setup sequential file capture only if reading from byte 0
        if (rawKey != null && dataSpec.position == 0L) {
            try {
                val temp = LosslessFileCache.getTempFile(context, rawKey)
                if (temp.exists()) temp.delete()
                tempFile = temp
                fileOutputStream = FileOutputStream(temp)
                bytesWritten = 0L
                cachingAborted = false
            } catch (e: Exception) {
                Timber.tag("LosslessCache").w(e, "Could not open temp cache file for %s", rawKey)
                cachingAborted = true
            }
        } else {
            // Mid-stream seek: do not capture partial disjoint fragments
            cachingAborted = true
        }

        return contentLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (isPlayingFromCacheFile) {
            return localFileSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        }

        val bytesRead = upstream.read(buffer, offset, length)
        if (bytesRead > 0 && !cachingAborted) {
            try {
                fileOutputStream?.write(buffer, offset, bytesRead)
                bytesWritten += bytesRead
            } catch (e: Exception) {
                Timber.tag("LosslessCache").w(e, "Write failed for %s; aborting cache capture", cacheKey)
                abortCapture()
            }
        } else if (bytesRead == C.RESULT_END_OF_INPUT && !cachingAborted) {
            commitCapture()
        }
        return bytesRead
    }

    override fun getUri(): Uri? {
        return if (isPlayingFromCacheFile) localFileSource?.uri else upstream.uri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return if (isPlayingFromCacheFile) emptyMap() else upstream.responseHeaders
    }

    override fun close() {
        if (isPlayingFromCacheFile) {
            localFileSource?.close()
            localFileSource = null
            isPlayingFromCacheFile = false
            return
        }

        try {
            fileOutputStream?.flush()
            fileOutputStream?.close()
        } catch (_: Exception) {}
        fileOutputStream = null

        // Incomplete stream closed before reaching EOF: clean up temp file
        if (!cachingAborted && tempFile?.exists() == true) {
            tempFile?.delete()
        }
        tempFile = null
        cachingAborted = true

        upstream.close()
    }

    private fun commitCapture() {
        val key = cacheKey ?: return
        val temp = tempFile ?: return
        try {
            fileOutputStream?.flush()
            fileOutputStream?.close()
            fileOutputStream = null
            if (temp.exists() && bytesWritten >= 64 * 1024L) {
                LosslessFileCache.commitFile(context, key, temp, bytesWritten)
                Timber.tag("LosslessCache").i("Committed complete lossless cache for %s (%d bytes)", key, bytesWritten)
            } else {
                temp.delete()
            }
        } catch (e: Exception) {
            Timber.tag("LosslessCache").w(e, "Failed to commit cache file for %s", key)
            temp.delete()
        } finally {
            tempFile = null
            cachingAborted = true
        }
    }

    private fun abortCapture() {
        cachingAborted = true
        try {
            fileOutputStream?.close()
        } catch (_: Exception) {}
        fileOutputStream = null
        tempFile?.delete()
        tempFile = null
    }

    class Factory(
        private val context: Context,
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            LosslessCachingDataSource(context, upstreamFactory.createDataSource())
    }
}

