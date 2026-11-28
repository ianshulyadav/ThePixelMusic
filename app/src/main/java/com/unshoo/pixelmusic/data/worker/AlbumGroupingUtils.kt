package com.unshoo.pixelmusic.data.worker

import com.unshoo.pixelmusic.data.database.AlbumEntity
import com.unshoo.pixelmusic.data.database.SongEntity
import com.unshoo.pixelmusic.utils.LocalArtworkUri
import com.unshoo.pixelmusic.utils.normalizeMetadataText
import com.unshoo.pixelmusic.utils.normalizeMetadataTextOrEmpty
import com.unshoo.pixelmusic.utils.splitArtistsByDelimiters

internal data class AlbumGroupingKey(
    val normalizedTitle: String,
    val identity: String
)

internal fun resolveAlbumArtist(
    rawAlbumArtist: String?,
    metadataAlbumArtist: String?
): String? {
    return sequenceOf(metadataAlbumArtist, rawAlbumArtist)
        .mapNotNull { candidate ->
            candidate.normalizeMetadataText()
                ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
        }
        .firstOrNull()
}

internal fun buildAlbumGroupingKey(
    song: SongEntity,
    groupByAlbumNameOnly: Boolean = false
): AlbumGroupingKey {
    return buildAlbumGroupingKey(
        albumName = song.albumName,
        albumArtist = song.albumArtist,
        albumArtUriString = song.albumArtUriString,
        parentDirectoryPath = song.parentDirectoryPath,
        fallbackAlbumId = song.albumId,
        preferStableLocalIdentity = LocalArtworkUri.isLikelyLocalMedia(song.contentUriString),
        groupByAlbumNameOnly = groupByAlbumNameOnly
    )
}

internal fun buildAlbumGroupingKeys(
    album: AlbumEntity,
    groupByAlbumNameOnly: Boolean = false
): List<AlbumGroupingKey> {
    val normalizedTitle = album.title.normalizeMetadataTextOrEmpty()
        .ifBlank { "Unknown Album" }
        .lowercase()

    if (groupByAlbumNameOnly && !isDefaultAlbumTitle(normalizedTitle)) {
        return listOf(
            AlbumGroupingKey(
                normalizedTitle = normalizedTitle,
                identity = "name"
            )
        )
    }

    val albumKeys = mutableListOf<AlbumGroupingKey>()

    albumKeys += buildAlbumGroupingKey(
        albumName = album.title,
        albumArtist = album.artistName,
        albumArtUriString = album.albumArtUriString,
        parentDirectoryPath = null,
        fallbackAlbumId = album.id,
        groupByAlbumNameOnly = false
    )

    albumKeys += AlbumGroupingKey(
        normalizedTitle = normalizedTitle,
        identity = "media:${album.id}"
    )

    if (!album.albumArtUriString.isNullOrBlank()) {
        albumKeys += AlbumGroupingKey(
            normalizedTitle = normalizedTitle,
            identity = "art:${album.albumArtUriString.trim()}"
        )
    }

    return albumKeys.distinct()
}

internal fun chooseAlbumDisplayArtist(
    songs: List<SongEntity>,
    preferAlbumArtist: Boolean,
    groupByAlbumNameOnly: Boolean = false
): String {
    if (songs.isEmpty()) return "Unknown Artist"

    val albumArtists = songs.mapNotNull { song ->
        song.albumArtist.normalizeMetadataText()?.takeIf {
            it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true)
        }
    }
    val trackArtists = songs.map { song ->
        song.artistName.normalizeMetadataTextOrEmpty()
    }.filter {
        it.isNotBlank() &&
            !it.equals("<unknown>", ignoreCase = true) &&
            !it.equals("Unknown Artist", ignoreCase = true)
    }

    if (groupByAlbumNameOnly && songs.size > 1) {
        val dominantAlbumArtist = majorityValue(albumArtists)
        val albumArtistMajorityCount = dominantAlbumArtist?.let { candidate ->
            albumArtists.count { it.equals(candidate, ignoreCase = true) }
        } ?: 0
        val distinctTrackArtists = trackArtists.map { it.lowercase() }.distinct()
        val dominantTrackArtist = majorityValue(trackArtists)

        if (preferAlbumArtist &&
            dominantAlbumArtist != null &&
            (distinctTrackArtists.size <= 1 || albumArtistMajorityCount * 2 >= songs.size)
        ) {
            return dominantAlbumArtist
        }
        if (dominantTrackArtist != null) {
            return dominantTrackArtist
        }
        if (distinctTrackArtists.size > 1) {
            // Multiple track artists with no single consensus in a name-grouped compilation album.
            return dominantAlbumArtist?.takeIf { albumArtistMajorityCount * 2 >= songs.size }
                ?: "Various Artists"
        }
        return dominantAlbumArtist ?: "Unknown Artist"
    }

    val albumArtist = mostCommonValue(albumArtists)
    val trackArtist = mostCommonValue(trackArtists)

    return when {
        preferAlbumArtist && albumArtist != null -> albumArtist
        trackArtist != null -> trackArtist
        albumArtist != null -> albumArtist
        else -> "Unknown Artist"
    }
}

internal fun resolveAlbumDisplayArtistId(
    displayArtist: String,
    songs: List<SongEntity>,
    artistNameToId: Map<String, Long>,
    artistDelimiters: List<String>,
    wordDelimiters: List<String> = emptyList()
): Long {
    artistNameToId[displayArtist.trim()]?.let { return it }

    val primaryArtistName = displayArtist
        .splitArtistsByDelimiters(artistDelimiters, wordDelimiters)
        .firstOrNull()
        ?.trim()
    if (!primaryArtistName.isNullOrEmpty()) {
        artistNameToId[primaryArtistName]?.let { return it }
    }

    if (displayArtist.equals("Various Artists", ignoreCase = true)) {
        return 0L
    }

    return songs.firstOrNull()?.artistId ?: 0L
}

internal fun isDefaultAlbumTitle(normalizedLowerTitle: String): Boolean {
    return normalizedLowerTitle.isBlank() ||
        normalizedLowerTitle == "<unknown>" ||
        normalizedLowerTitle == "unknown" ||
        normalizedLowerTitle == "unknown album"
}

private fun majorityValue(values: List<String>): String? {
    if (values.isEmpty()) return null
    val grouped = values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .groupBy { it.lowercase() }
    val topGroup = grouped.values.maxByOrNull { it.size } ?: return null
    if (topGroup.size * 2 <= values.size) return null
    return mostCommonValue(topGroup)
}

private fun mostCommonValue(values: List<String>): String? {
    return values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()
        .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
        ?.key
}

private fun buildAlbumGroupingKey(
    albumName: String,
    albumArtist: String?,
    albumArtUriString: String?,
    parentDirectoryPath: String?,
    fallbackAlbumId: Long,
    preferStableLocalIdentity: Boolean = false,
    groupByAlbumNameOnly: Boolean = false
): AlbumGroupingKey {
    val normalizedTitle = albumName.normalizeMetadataTextOrEmpty()
        .ifBlank { "Unknown Album" }
        .lowercase()
    if (groupByAlbumNameOnly && !isDefaultAlbumTitle(normalizedTitle)) {
        return AlbumGroupingKey(
            normalizedTitle = normalizedTitle,
            identity = "name"
        )
    }
    val stableLocalIdentity = when {
        !parentDirectoryPath.isNullOrBlank() -> {
            "dir:${parentDirectoryPath.trim().lowercase()}"
        }
        else -> {
            "media:$fallbackAlbumId"
        }
    }
    val identity = when {
        !albumArtist.isNullOrBlank() -> {
            "artist:${albumArtist.normalizeMetadataTextOrEmpty().lowercase()}"
        }
        preferStableLocalIdentity -> {
            stableLocalIdentity
        }
        !albumArtUriString.isNullOrBlank() -> {
            "art:${albumArtUriString.trim()}"
        }
        !parentDirectoryPath.isNullOrBlank() -> {
            "dir:${parentDirectoryPath.trim().lowercase()}"
        }
        else -> {
            "media:$fallbackAlbumId"
        }
    }

    return AlbumGroupingKey(
        normalizedTitle = normalizedTitle,
        identity = identity
    )
}
