package com.unshoo.pixelmusic.data.remote.youtube

import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.model.ArtistRef
import com.unshoo.pixelmusic.utils.extractArtistsFromTitle
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.pages.PageHelper

private val GOOGLE_SIZE_W_H_REGEX = Regex("=w\\d+-h\\d+[^?]*")
private val GOOGLE_SIZE_S_REGEX = Regex("=s\\d+[^?]*")
private val YTIMG_VIDEO_REGEX = Regex("/vi(?:_webp)?/([^/]+)/")

/**
 * Signed or tokenized Google/YouTube URLs (`?sqp=...&rs=...`, `&sigh=...`, `Signature=...`)
 * break with HTTP 400/403/404 if any resize segment is modified. Keep them verbatim.
 */
fun isSignedGoogleThumbnailUrl(url: String): Boolean {
    val qIdx = url.indexOf('?')
    if (qIdx < 0) return false
    val query = url.substring(qIdx)
    return query.contains("sqp=", ignoreCase = true) ||
        query.contains("rs=", ignoreCase = true) ||
        query.contains("sigh=", ignoreCase = true) ||
        query.contains("Signature=", ignoreCase = true) ||
        query.contains("Expires=", ignoreCase = true)
}

/**
 * Rewrites resize parameters on unsigned `googleusercontent.com` / `ggpht.com` URLs without
 * corrupting URLs that carry `?` query strings.
 */
fun rewriteGoogleThumbnailUrl(url: String, widthPx: Int, cropSquare: Boolean = false): String {
    if (isSignedGoogleThumbnailUrl(url)) return url
    val sizeParam = if (cropSquare) "=w$widthPx-h$widthPx-c-rj" else "=w$widthPx-h$widthPx-l90-rj"
    val sParam = "=s$widthPx"
    val qIdx = url.indexOf('?')
    val path = if (qIdx >= 0) url.substring(0, qIdx) else url
    val query = if (qIdx >= 0) url.substring(qIdx) else ""
    val rewrittenPath = path
        .replace(GOOGLE_SIZE_W_H_REGEX, sizeParam)
        .replace(GOOGLE_SIZE_S_REGEX, sParam)
        .let { updated ->
            when {
                updated.contains("=w") || updated.contains("=s") -> updated
                updated.contains("=") -> updated.substringBeforeLast("=") + sizeParam
                else -> "$updated$sizeParam"
            }
        }
    return rewrittenPath + query
}

fun upgradeThumbnailUrlToQuality(
    url: String?,
    quality: com.unshoo.pixelmusic.data.preferences.AlbumArtQuality = com.unshoo.pixelmusic.data.preferences.AlbumArtQuality.ORIGINAL
): String? {
    if (url.isNullOrBlank()) return url

    val widthPx = when (quality) {
        com.unshoo.pixelmusic.data.preferences.AlbumArtQuality.LOW -> 256
        com.unshoo.pixelmusic.data.preferences.AlbumArtQuality.MEDIUM -> 512
        com.unshoo.pixelmusic.data.preferences.AlbumArtQuality.HIGH -> 800
        com.unshoo.pixelmusic.data.preferences.AlbumArtQuality.ORIGINAL -> 1024
    }

    val ytQuality = when (quality) {
        com.unshoo.pixelmusic.data.preferences.AlbumArtQuality.LOW -> "mqdefault.jpg"
        com.unshoo.pixelmusic.data.preferences.AlbumArtQuality.MEDIUM -> "hqdefault.jpg"
        com.unshoo.pixelmusic.data.preferences.AlbumArtQuality.HIGH,
        com.unshoo.pixelmusic.data.preferences.AlbumArtQuality.ORIGINAL -> "maxresdefault.jpg"
    }

    return when {
        url.contains("googleusercontent.com") || url.contains("ggpht.com") ->
            rewriteGoogleThumbnailUrl(url, widthPx, cropSquare = false)
        url.contains("i.ytimg.com") || url.contains("img.youtube.com") -> {
            val match = YTIMG_VIDEO_REGEX.find(url)
            if (match != null) {
                val videoId = match.groupValues[1]
                "https://i.ytimg.com/vi/$videoId/$ytQuality"
            } else {
                url.substringBefore("?")
            }
        }
        else -> url
    }
}

fun upgradeThumbnailUrlToHighQuality(url: String?): String? =
    upgradeThumbnailUrlToQuality(url, com.unshoo.pixelmusic.data.preferences.AlbumArtQuality.ORIGINAL)


fun SongItem.toYoutubeSong(): com.unshoo.pixelmusic.data.model.youtube.Song {
    val artistName = artists.joinToString { it.name }
    val totalSeconds = duration ?: 0
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val durationString = if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
    return com.unshoo.pixelmusic.data.model.youtube.Song(
        youtubeId = id,
        title = title,
        artist = artistName,
        album = album?.name,
        albumBrowseId = album?.id,
        duration = durationString,
        thumbnailHref = upgradeThumbnailUrlToHighQuality(thumbnail) ?: ""
    )
}

fun SongItem.toNativeSong(): Song {
    val validArtists = artists.filterNot {
        PageHelper.isNonAlbumMetadata(it.name) || PageHelper.isMediaTypeMarker(it.name)
    }
    val rawArtistName = if (validArtists.isNotEmpty()) {
        validArtists.joinToString { it.name }
    } else {
        val (_, featArtists) = title.extractArtistsFromTitle()
        if (featArtists.isNotEmpty()) {
            featArtists.joinToString(", ")
        } else {
            "Unknown Artist"
        }
    }
    val artistNames = com.unshoo.pixelmusic.data.stream.CloudMusicUtils.parseArtistNames(rawArtistName)
    val artistRefs = artistNames.mapIndexed { index, name ->
        val originalArtist = validArtists.find { it.name.equals(name, ignoreCase = true) }
        val artistId = -(17_000_000_000_000L + kotlin.math.abs(name.lowercase().hashCode().toLong()))
        ArtistRef(
            id = artistId,
            name = name,
            isPrimary = index == 0,
            channelId = originalArtist?.id
        )
    }
    val artistName = artistNames.joinToString(", ")
    val primaryArtistId = artistRefs.firstOrNull()?.id ?: 0L
    val songId = "youtube_$id"
    val albumName = album?.name?.takeIf { it.isNotBlank() } ?: "YouTube Music"
    val albumId = -(16_000_000_000_000L + kotlin.math.abs(albumName.lowercase().hashCode().toLong()))
    
    return Song(
        id = songId,
        title = title,
        artist = artistName,
        artistId = primaryArtistId,
        artists = artistRefs,
        album = albumName,
        albumId = albumId,
        albumArtist = artistName,
        path = "",
        contentUriString = "youtube://$id",
        albumArtUriString = upgradeThumbnailUrlToHighQuality(thumbnail),
        duration = (duration ?: 0) * 1000L,
        genre = "YouTube",
        lyrics = null,
        isFavorite = likeStatus == "LIKE",
        isDisliked = likeStatus == "DISLIKE",
        trackNumber = 0,
        discNumber = null,
        year = 0,
        dateAdded = System.currentTimeMillis(),
        dateModified = System.currentTimeMillis(),
        mimeType = "audio/opus",
        bitrate = 128000,
        sampleRate = 44100,
        telegramFileId = null,
        telegramChatId = null,
        neteaseId = null,
        gdriveFileId = null,
        qqMusicMid = null,
        youtubeId = id,
        albumBrowseId = album?.id
    )
}

fun com.unshoo.pixelmusic.data.model.youtube.Song.toNativeSong(): Song {
    val cleanArtist = if (artist.isNotBlank() &&
        !PageHelper.isNonAlbumMetadata(artist) &&
        !PageHelper.isMediaTypeMarker(artist)) {
        artist
    } else {
        val (_, featArtists) = title.extractArtistsFromTitle()
        if (featArtists.isNotEmpty()) featArtists.joinToString(", ") else "Unknown Artist"
    }
    val artistNames = com.unshoo.pixelmusic.data.stream.CloudMusicUtils.parseArtistNames(cleanArtist)
    val artistRefs = artistNames.mapIndexed { index, name ->
        val artistId = -(17_000_000_000_000L + kotlin.math.abs(name.lowercase().hashCode().toLong()))
        ArtistRef(
            id = artistId,
            name = name,
            isPrimary = index == 0,
            channelId = null
        )
    }
    val artistName = artistNames.joinToString(", ")
    val primaryArtistId = artistRefs.firstOrNull()?.id ?: 0L
    val songId = "youtube_$youtubeId"
    val albumName = album?.takeIf { it.isNotBlank() } ?: "YouTube Music"
    val albumId = -(16_000_000_000_000L + kotlin.math.abs(albumName.lowercase().hashCode().toLong()))
    
    return Song(
        id = songId,
        title = title,
        artist = artistName,
        artistId = primaryArtistId,
        artists = artistRefs,
        album = albumName,
        albumId = albumId,
        albumArtist = artistName,
        path = audioFilePath.orEmpty(),
        contentUriString = "youtube://$youtubeId",
        albumArtUriString = upgradeThumbnailUrlToHighQuality(thumbnailPath ?: thumbnailHref),
        duration = parseDurationStringToMillis(duration),
        genre = "YouTube",
        lyrics = null,
        isFavorite = false,
        trackNumber = 0,
        discNumber = null,
        year = 0,
        dateAdded = System.currentTimeMillis(),
        dateModified = System.currentTimeMillis(),
        mimeType = "audio/opus",
        bitrate = 128000,
        sampleRate = 44100,
        telegramFileId = null,
        telegramChatId = null,
        neteaseId = null,
        gdriveFileId = null,
        qqMusicMid = null,
        youtubeId = youtubeId,
        albumBrowseId = albumBrowseId
    )
}

internal fun parseDurationStringToMillis(durationStr: String): Long {
    if (durationStr.isBlank()) return 0L
    val parts = durationStr.split(":")
    return try {
        when (parts.size) {
            1 -> {
                val raw = parts[0].toLong()
                if (raw >= 1000L) raw else raw * 1000L
            }
            2 -> (parts[0].toLong() * 60L + parts[1].toLong()) * 1000L
            3 -> ((parts[0].toLong() * 3600L + parts[1].toLong() * 60L + parts[2].toLong())) * 1000L
            else -> 0L
        }
    } catch (e: Exception) {
        0L
    }
}
