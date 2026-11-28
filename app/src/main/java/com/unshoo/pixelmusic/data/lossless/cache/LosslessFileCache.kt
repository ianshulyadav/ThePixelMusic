package com.unshoo.pixelmusic.data.lossless.cache

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Manages complete-file disk caching for lossless audio streams (FLAC).
 *
 * Unlike chunked byte-range caching (CacheDataSource) which chokes on third-party CDNs,
 * this cache stores complete contiguous audio files. A track is only served from cache
 * when 100% of its bytes have been sequentially captured. Replays and seeks are instant
 * with 0 MB network data used and zero cache-seam cutouts.
 */
object LosslessFileCache {
    private const val TAG = "LosslessFileCache"
    private const val DIR_NAME = "lossless_cache"
    private const val MAX_CACHE_BYTES = 1_500L * 1024 * 1024 // 1.5 GB limit
    private const val MIN_VALID_FLAC_BYTES = 64 * 1024L // Minimum 64 KB

    fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun cleanKey(rawKey: String): String {
        return rawKey.removePrefix("youtube_")
            .removePrefix("monochrome_")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(64)
    }

    fun extractCacheKey(uri: Uri): String? {
        val uriStr = uri.toString()
        val scheme = uri.scheme
        return when {
            scheme == "youtube" -> uri.host?.takeIf { it.isNotBlank() } ?: uriStr.removePrefix("youtube://")
            scheme == "monochrome" -> uri.host?.takeIf { it.isNotBlank() } ?: uriStr.removePrefix("monochrome://")
            uriStr.contains("monochrome.st/track/") -> uriStr.substringAfterLast("/").substringBefore("?").substringBefore("/")
            scheme == "deezer" -> uri.getQueryParameter("track_id") ?: uri.host
            scheme == "tidal-dash" -> uri.getQueryParameter("track_id") ?: uri.host
            uri.path?.endsWith(".flac", ignoreCase = true) == true -> uri.lastPathSegment?.removeSuffix(".flac")
            else -> null
        }?.let(::cleanKey)
    }

    /**
     * Returns the complete cached file on disk if it exists and is valid, updating its access time.
     */
    fun getCompleteFile(context: Context, key: String): File? {
        if (key.isBlank()) return null
        val clean = cleanKey(key)
        val file = File(getCacheDir(context), "$clean.flac")
        if (file.isFile && file.length() >= MIN_VALID_FLAC_BYTES) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        return null
    }

    fun getTempFile(context: Context, key: String): File {
        val clean = cleanKey(key)
        return File(getCacheDir(context), "$clean.flac.part")
    }

    data class CachedSourceMetadata(
        val sourceId: String,
        val label: String?,
        val sampleRate: Int?,
        val bitDepth: Int?,
        val bitrate: Int?,
    )

    private fun metaFileFor(cacheDir: File, clean: String): File = File(cacheDir, "$clean.flac.meta")

    fun getCachedSourceMetadata(context: Context, key: String): CachedSourceMetadata? {
        if (key.isBlank()) return null
        val clean = cleanKey(key)
        val metaFile = metaFileFor(getCacheDir(context), clean)
        if (!metaFile.isFile) return null
        return runCatching {
            val lines = metaFile.readLines(Charsets.UTF_8)
            val sourceId = lines.getOrNull(0)?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val label = lines.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            val sampleRate = lines.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val bitDepth = lines.getOrNull(3)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val bitrate = lines.getOrNull(4)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            CachedSourceMetadata(
                sourceId = sourceId,
                label = label,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                bitrate = bitrate,
            )
        }.getOrNull()
    }

    /**
     * Atomically commits a fully captured temp file into the permanent complete-file cache.
     */
    fun commitFile(
        context: Context,
        key: String,
        tempFile: File,
        expectedLength: Long = 0L,
        sourceMetadata: CachedSourceMetadata? = null,
    ) {
        val clean = cleanKey(key)
        val dir = getCacheDir(context)
        val dest = File(dir, "$clean.flac")
        if (dest.exists()) dest.delete()
        val success = tempFile.renameTo(dest)
        if (success) {
            dest.setLastModified(System.currentTimeMillis())
            val metaFile = metaFileFor(dir, clean)
            if (sourceMetadata != null) {
                runCatching {
                    metaFile.writeText(
                        listOf(
                            sourceMetadata.sourceId,
                            sourceMetadata.label.orEmpty(),
                            sourceMetadata.sampleRate?.toString().orEmpty(),
                            sourceMetadata.bitDepth?.toString().orEmpty(),
                            sourceMetadata.bitrate?.toString().orEmpty(),
                        ).joinToString("\n"),
                        Charsets.UTF_8,
                    )
                }
            } else if (metaFile.exists()) {
                metaFile.delete()
            }
            trimCacheAsync(context)
        }
    }

    private fun trimCacheAsync(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dir = getCacheDir(context)
                val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".flac") } ?: return@launch
                var totalBytes = files.sumOf { it.length() }
                if (totalBytes > MAX_CACHE_BYTES) {
                    val sorted = files.sortedBy { it.lastModified() }
                    for (file in sorted) {
                        if (totalBytes <= MAX_CACHE_BYTES) break
                        val size = file.length()
                        if (file.delete()) {
                            totalBytes -= size
                            metaFileFor(dir, file.name.removeSuffix(".flac")).takeIf { it.exists() }?.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "trimCacheAsync failed")
            }
        }
    }

    fun clear(context: Context) {
        try {
            getCacheDir(context).deleteRecursively()
        } catch (_: Exception) {}
    }
}

