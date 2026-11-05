package com.unshoo.pixelmusic.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import coil.imageLoader
import com.unshoo.pixelmusic.utils.AlbumArtUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

class SharedArtworkContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? {
        val url = uri.getQueryParameter("url") ?: uri.toString()
        return if (url.contains(".png", ignoreCase = true)) {
            "image/png"
        } else {
            DEFAULT_CONTENT_TYPE
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Shared artwork provider is read-only")
        }

        val file = resolveArtworkFile(uri)
            ?: throw FileNotFoundException("No artwork found for uri=$uri")

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val fileDescriptor = openFile(uri, mode)
        return AssetFileDescriptor(fileDescriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun resolveArtworkFile(uri: Uri): File? {
        val appContext = context?.applicationContext ?: return null
        val authority = authority(appContext.packageName)
        if (uri.authority != authority) return null

        val path = uri.path ?: return null
        when {
            path.startsWith("/$PATH_SONG/") -> {
                val songId = parseSongId(uri, appContext.packageName)
                if (songId != null) {
                    val file = AlbumArtUtils.ensureAlbumArtCachedFile(
                        appContext = appContext,
                        songId = songId
                    )
                    if (file?.exists() == true && file.isFile && file.canRead()) {
                        return file
                    }
                }
                val rawId = path.removePrefix("/$PATH_SONG/").substringBefore('?').substringBefore('/')
                if (rawId.isNotBlank()) {
                    val cached = File(getArtworkCacheDir(appContext), "${rawId}.jpg")
                    if (cached.exists() && cached.isFile && cached.canRead()) return cached
                }
            }
            path.startsWith("/$PATH_REMOTE") -> {
                val rawUrl = uri.getQueryParameter("url")
                    ?: path.removePrefix("/$PATH_REMOTE/").takeIf { it.isNotBlank() }
                if (!rawUrl.isNullOrBlank()) {
                    return resolveRemoteArtwork(appContext, rawUrl)
                }
            }
            path.startsWith("/$PATH_FILE") -> {
                val rawPath = uri.getQueryParameter("path")
                    ?: path.removePrefix("/$PATH_FILE/").takeIf { it.isNotBlank() }
                if (!rawPath.isNullOrBlank()) {
                    val file = File(rawPath)
                    if (file.exists() && file.isFile && file.canRead()) return file
                }
            }
        }
        return null
    }

    private fun resolveRemoteArtwork(appContext: Context, rawUrl: String): File? {
        val cacheDir = getArtworkCacheDir(appContext)
        val hash = rawUrl.hashCode().toString() + "_" + rawUrl.takeLast(12).filter { it.isLetterOrDigit() } + ".jpg"
        val targetFile = File(cacheDir, hash)
        if (targetFile.exists() && targetFile.isFile && targetFile.length() > 0L) {
            return targetFile
        }

        // Try reading from Coil disk cache if present
        try {
            appContext.imageLoader.diskCache?.openSnapshot(rawUrl)?.use { snapshot ->
                val diskFile = snapshot.data.toFile()
                if (diskFile.exists() && diskFile.isFile && diskFile.length() > 0L) {
                    return diskFile
                }
            }
        } catch (_: Throwable) {}

        // Download and cache synchronously for external controller
        return downloadAndCacheUrl(rawUrl, targetFile)
    }

    private fun downloadAndCacheUrl(rawUrl: String, targetFile: File): File? {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp_${System.currentTimeMillis()}")
        return try {
            val request = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            artworkHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                tempFile.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0L) {
                if (tempFile.renameTo(targetFile) || (targetFile.delete() && tempFile.renameTo(targetFile))) {
                    targetFile
                } else {
                    tempFile
                }
            } else {
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            tempFile.delete()
            null
        }
    }

    companion object {
        private const val AUTHORITY_SUFFIX = ".artwork"
        private const val PATH_SONG = "song"
        const val PATH_REMOTE = "remote"
        private const val PATH_FILE = "file"
        private const val DEFAULT_CONTENT_TYPE = "image/jpeg"

        private val artworkHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
        }

        private fun getArtworkCacheDir(context: Context): File {
            val dir = File(context.cacheDir, "shared_artwork")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun authority(packageName: String): String = packageName + AUTHORITY_SUFFIX

        fun buildSongUri(
            context: Context,
            songId: Long,
            cacheBustToken: String? = null
        ): Uri = buildSongUri(context.packageName, songId, cacheBustToken)

        internal fun buildSongUri(
            packageName: String,
            songId: Long,
            cacheBustToken: String? = null
        ): Uri {
            return Uri.parse(buildSongUriString(packageName, songId, cacheBustToken))
        }

        internal fun buildSongUriString(
            packageName: String,
            songId: Long,
            cacheBustToken: String? = null
        ): String {
            val baseUri = "content://${authority(packageName)}/$PATH_SONG/$songId"
            return cacheBustToken
                ?.takeIf { it.isNotBlank() }
                ?.let { "$baseUri?t=$it" }
                ?: baseUri
        }

        fun buildRemoteUri(
            packageName: String,
            remoteUrl: String
        ): Uri {
            return Uri.parse("content://${authority(packageName)}/$PATH_REMOTE?url=${Uri.encode(remoteUrl)}")
        }

        fun buildFileUri(
            packageName: String,
            file: File
        ): Uri {
            return Uri.parse("content://${authority(packageName)}/$PATH_FILE?path=${Uri.encode(file.absolutePath)}")
        }

        fun unwrapRemoteUrl(uriString: String?): String? {
            if (uriString.isNullOrBlank()) return null
            var current: String = uriString
            var depth = 0
            while (current.startsWith("content://") && (current.contains("/$PATH_REMOTE") || current.contains("/remote")) && depth < 5) {
                val rawUrl = if (current.contains("url=")) {
                    val encoded = current.substringAfter("url=").substringBefore('&')
                    runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
                } else {
                    val pathPart = current.substringAfter("/remote/").substringAfter("/$PATH_REMOTE/").substringBefore('?')
                    if (pathPart.isNotBlank() && pathPart != current) {
                        runCatching { java.net.URLDecoder.decode(pathPart, "UTF-8") }.getOrNull()
                    } else null
                }
                if (rawUrl.isNullOrBlank() || rawUrl == current) break
                current = rawUrl
                depth++
            }
            return current
        }

        internal fun parseSongId(uri: Uri, packageName: String? = null): Long? {
            return parseSongId(uri.toString(), packageName)
        }

        internal fun parseSongId(uriString: String, packageName: String? = null): Long? {
            val expectedPrefix = packageName
                ?.let(::authority)
                ?.let { "content://$it/$PATH_SONG/" }

            if (expectedPrefix != null && !uriString.startsWith(expectedPrefix)) {
                return null
            }

            val basePrefix = expectedPrefix ?: run {
                val authoritySeparator = "://"
                val schemeSplit = uriString.indexOf(authoritySeparator)
                if (schemeSplit < 0) return null
                val pathStart = uriString.indexOf('/', schemeSplit + authoritySeparator.length)
                if (pathStart < 0) return null
                val pathPrefix = uriString.substring(pathStart)
                if (!pathPrefix.startsWith("/$PATH_SONG/")) return null
                uriString.substring(0, pathStart) + "/$PATH_SONG/"
            }

            val songIdSegment = uriString
                .removePrefix(basePrefix)
                .substringBefore('?')
                .substringBefore('/')

            if (songIdSegment.isBlank()) {
                return null
            }
            return songIdSegment.toLongOrNull()
        }
    }
}
