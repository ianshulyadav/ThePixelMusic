package com.unshoo.pixelmusic.data.playlist

import android.content.Context
import android.net.Uri
import com.unshoo.pixelmusic.data.model.Playlist
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.repository.MusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem

@Singleton
class M3uManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository
) {

    private fun getSongExportPath(song: Song): String {
        val youtubeId = song.youtubeId 
            ?: if (song.id.startsWith("youtube_")) song.id.substringAfter("youtube_")
               else if (song.contentUriString.startsWith("youtube://")) song.contentUriString.substringAfter("youtube://")
               else null
        return if (youtubeId != null) {
            "youtube://$youtubeId"
        } else {
            song.path
        }
    }

    private fun parseYoutubeId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.startsWith("youtube://")) {
            return trimmed.substringAfter("youtube://").trim().takeIf { it.isNotEmpty() }
        }
        
        // Match YouTube URL patterns:
        // - y2u.be/ID
        // - youtu.be/ID
        // - youtube.com/watch?v=ID
        // - music.youtube.com/watch?v=ID
        // - youtube.com/embed/ID
        // - youtube.com/v/ID
        val regex = Regex(
            """(?:https?://)?(?:www\.|music\.)?(?:youtube\.com/(?:watch\?v=|embed/|v/)|youtu\.be/|y2u\.be/)([a-zA-Z0-9_-]{11})""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(trimmed)?.groupValues?.getOrNull(1)
    }

    private suspend fun searchYoutubeId(title: String, artist: String): String? {
        return try {
            val query = "$title $artist"
            val result = YouTube.search(
                query, 
                YouTube.SearchFilter.FILTER_SONG
            ).getOrNull()
            val songItem = result?.items?.firstOrNull { it is SongItem } as? SongItem
            songItem?.id
        } catch (e: Exception) {
            null
        }
    }

    suspend fun parseM3u(uri: Uri): Pair<String, List<String>> {
        val songIds = mutableListOf<String>()
        var playlistName = "Imported Playlist"

        // Load a filtered one-shot snapshot so import respects the current library visibility rules.
        val allSongs = musicRepository.getAllSongsOnce()
        
        // Build lookup maps for fast matching
        val songsByPath = allSongs.associateBy { it.path }
        val songsByFileName = allSongs.groupBy { it.path.substringAfterLast("/").substringBeforeLast(".") }
        val songsByContentUriFileName = allSongs.groupBy { it.contentUriString.substringAfterLast("/") }
        val songsByYoutubeId = allSongs.filter { it.youtubeId != null }.associateBy { it.youtubeId!! }
        val songsByTitleAndArtist = allSongs.groupBy { "${it.title.lowercase().trim()} - ${it.artist.lowercase().trim()}" }
        val songsByTitle = allSongs.groupBy { it.title.lowercase().trim() }

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String?
                var lastExtInf: String? = null
                while (reader.readLine().also { line = it } != null) {
                    val trimmedLine = line?.trim() ?: continue
                    if (trimmedLine.isEmpty()) {
                        continue
                    }
                    if (trimmedLine.startsWith("#EXTINF:")) {
                        lastExtInf = trimmedLine
                        continue
                    }
                    if (trimmedLine.startsWith("#")) {
                        continue
                    }
                    
                    val decodedLine = try {
                        java.net.URLDecoder.decode(trimmedLine, "UTF-8")
                    } catch (e: Exception) {
                        trimmedLine
                    }

                    val youtubeId = parseYoutubeId(decodedLine)
                    if (youtubeId != null) {
                        val songId = "youtube_$youtubeId"
                        val existingSong = songsByYoutubeId[youtubeId] ?: allSongs.find { it.id == songId || it.contentUriString == "youtube://$youtubeId" }
                        if (existingSong != null) {
                            songIds.add(existingSong.id)
                        } else {
                            // Extract info from EXTINF line if present
                            var title = "YouTube Song"
                            var artist = "Unknown Artist"
                            var durationMs = 0L
                            lastExtInf?.let { extInf ->
                                val durationPart = extInf.substringAfter(":", "").substringBefore(",", "")
                                val secs = durationPart.toLongOrNull() ?: 0L
                                durationMs = secs * 1000L
                                
                                val metaPart = extInf.substringAfter(",", "")
                                val parts = metaPart.split(Regex(" - |-"))
                                if (parts.size >= 2) {
                                    artist = parts[0].trim()
                                    title = parts.subList(1, parts.size).joinToString("-").trim()
                                } else if (metaPart.isNotBlank()) {
                                    title = metaPart.trim()
                                }
                            }
                            
                            val newSong = Song(
                                id = songId,
                                title = title,
                                artist = artist,
                                artistId = 0L,
                                album = "YouTube Music",
                                albumId = 0L,
                                path = "",
                                contentUriString = "youtube://$youtubeId",
                                albumArtUriString = null,
                                duration = durationMs,
                                genre = "YouTube",
                                mimeType = "audio/webm",
                                bitrate = 128,
                                sampleRate = 44100,
                                youtubeId = youtubeId
                            )
                            musicRepository.insertYoutubeSongs(listOf(newSong))
                            songIds.add(songId)
                        }
                        continue
                    }
                    
                    // 1) Try exact path match
                    val songByPath = songsByPath[decodedLine] ?: songsByPath[trimmedLine]
                    if (songByPath != null) {
                        songIds.add(songByPath.id)
                        continue
                    }
                    
                    // 2) Try filename match from path column
                    val fileName = decodedLine.substringAfterLast("/").substringBeforeLast(".")
                    val matchedSong = songsByFileName[fileName]?.firstOrNull()
                        ?: songsByContentUriFileName[fileName]?.firstOrNull()
                    if (matchedSong != null) {
                        songIds.add(matchedSong.id)
                        continue
                    }

                    // 3) Try Title & Artist fallback from lastExtInf
                    var extTitle = ""
                    var extArtist = ""
                    var extDurationMs = 0L
                    lastExtInf?.let { extInf ->
                        val durationPart = extInf.substringAfter(":", "").substringBefore(",", "")
                        val secs = durationPart.toLongOrNull() ?: 0L
                        extDurationMs = secs * 1000L
                        
                        val metaPart = extInf.substringAfter(",", "")
                        val parts = metaPart.split(Regex(" - |-"))
                        if (parts.size >= 2) {
                            extArtist = parts[0].trim()
                            extTitle = parts.subList(1, parts.size).joinToString("-").trim()
                        } else if (metaPart.isNotBlank()) {
                            extTitle = metaPart.trim()
                        }
                    }

                    if (extTitle.isNotBlank()) {
                        val key = "${extTitle.lowercase().trim()} - ${extArtist.lowercase().trim()}"
                        val matchByTitleAndArtist = songsByTitleAndArtist[key]?.firstOrNull()
                        if (matchByTitleAndArtist != null) {
                            songIds.add(matchByTitleAndArtist.id)
                            continue
                        }
                        
                        val matchByTitle = songsByTitle[extTitle.lowercase().trim()]?.firstOrNull()
                        if (matchByTitle != null) {
                            songIds.add(matchByTitle.id)
                            continue
                        }

                        // 4) On-the-fly YouTube lookup
                        val resolvedYtId = searchYoutubeId(extTitle, extArtist)
                        if (resolvedYtId != null) {
                            val songId = "youtube_$resolvedYtId"
                            val newSong = Song(
                                id = songId,
                                title = extTitle,
                                artist = extArtist.ifBlank { "Unknown Artist" },
                                artistId = 0L,
                                album = "YouTube Music",
                                albumId = 0L,
                                path = "",
                                contentUriString = "youtube://$resolvedYtId",
                                albumArtUriString = null,
                                duration = extDurationMs,
                                genre = "YouTube",
                                mimeType = "audio/webm",
                                bitrate = 128,
                                sampleRate = 44100,
                                youtubeId = resolvedYtId
                            )
                            musicRepository.insertYoutubeSongs(listOf(newSong))
                            songIds.add(songId)
                        }
                    }
                }
            }
        }

        // Try to get the filename as playlist name
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                playlistName = cursor.getString(nameIndex).removeSuffix(".m3u").removeSuffix(".m3u8")
            }
        }

        return Pair(playlistName, songIds)
    }

    fun generateM3u(playlist: Playlist, songs: List<Song>): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        for (song in songs) {
            sb.append("#EXTINF:${song.duration / 1000},${song.artist} - ${song.title}\n")
            sb.append("${getSongExportPath(song)}\n")
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------------
    // CSV support
    // ---------------------------------------------------------------------------

    private fun escapeCsv(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            '"' + value.replace("\"", "\"\"") + '"'
        } else value
    }

    fun generateCsv(songs: List<Song>): String {
        val sb = StringBuilder()
        sb.append("Title,Artist,Album,Duration (ms),Path\n")
        for (song in songs) {
            sb.append(escapeCsv(song.title)).append(',')
            sb.append(escapeCsv(song.artist)).append(',')
            sb.append(escapeCsv(song.album)).append(',')
            sb.append(song.duration).append(',')
            sb.append(escapeCsv(getSongExportPath(song))).append('\n')
        }
        return sb.toString()
    }

    suspend fun parseCsv(uri: Uri): Pair<String, List<String>> {
        val songIds = mutableListOf<String>()
        var playlistName = "Imported Playlist"

        val allSongs = musicRepository.getAllSongsOnce()
        val songsByPath = allSongs.associateBy { it.path }
        val songsByTitle = allSongs.groupBy { it.title.lowercase().trim() }
        val songsByFileName = allSongs.groupBy { it.path.substringAfterLast("/").substringBeforeLast(".") }
        val songsByYoutubeId = allSongs.filter { it.youtubeId != null }.associateBy { it.youtubeId!! }
        val songsByTitleAndArtist = allSongs.groupBy { "${it.title.lowercase().trim()} - ${it.artist.lowercase().trim()}" }

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var isFirstLine = true
                var line: String?
                
                var titleIdx = 0
                var artistIdx = 1
                var albumIdx = 2
                var durationIdx = 3
                var pathIdx = 4

                while (reader.readLine().also { line = it } != null) {
                    val trimmedLine = line?.trim() ?: continue
                    if (trimmedLine.isEmpty()) continue
                    
                    // Parse CSV row
                    val cols = splitCsvRow(trimmedLine)
                    
                    if (isFirstLine) {
                        isFirstLine = false
                        val lowercaseCols = cols.map { it.lowercase().trim() }
                        val t = lowercaseCols.indexOfFirst { it in listOf("title", "track", "track name", "name", "song", "song name", "trackname") }
                        val a = lowercaseCols.indexOfFirst { it in listOf("artist", "artist name", "artists", "artistname") }
                        val al = lowercaseCols.indexOfFirst { it in listOf("album", "album name", "album title", "albumname") }
                        val d = lowercaseCols.indexOfFirst { it in listOf("duration", "duration (ms)", "length", "time", "durationms") }
                        val p = lowercaseCols.indexOfFirst { it in listOf("path", "file", "filepath", "uri", "url", "location") }

                        if (t != -1 || a != -1) {
                            if (t != -1) titleIdx = t
                            if (a != -1) artistIdx = a
                            if (al != -1) albumIdx = al
                            if (d != -1) durationIdx = d
                            if (p != -1) pathIdx = p
                            continue
                        }
                    }
                    
                    val title = cols.getOrNull(titleIdx)?.trim() ?: ""
                    val artist = cols.getOrNull(artistIdx)?.trim() ?: ""
                    val album = cols.getOrNull(albumIdx)?.trim() ?: ""
                    val durationStr = if (durationIdx != -1) cols.getOrNull(durationIdx)?.trim() ?: "" else ""
                    val rawPath = if (pathIdx != -1) cols.getOrNull(pathIdx)?.trim() ?: "" else ""
                    val path = try {
                        java.net.URLDecoder.decode(rawPath, "UTF-8")
                    } catch (e: Exception) {
                        rawPath
                    }

                    if (title.isBlank() && path.isBlank()) continue

                    // Check if path is a YouTube song
                    val youtubeId = if (path.isNotBlank()) parseYoutubeId(path) else null
                    if (youtubeId != null) {
                        val songId = "youtube_$youtubeId"
                        val existingSong = songsByYoutubeId[youtubeId] ?: allSongs.find { it.id == songId || it.contentUriString == "youtube://$youtubeId" }
                        if (existingSong != null) {
                            songIds.add(existingSong.id)
                        } else {
                            val durationMs = durationStr.toLongOrNull() ?: 0L
                            val newSong = Song(
                                id = songId,
                                title = title.ifBlank { "YouTube Song" },
                                artist = artist.ifBlank { "Unknown Artist" },
                                artistId = 0L,
                                album = album.ifBlank { "YouTube Music" },
                                albumId = 0L,
                                path = "",
                                contentUriString = "youtube://$youtubeId",
                                albumArtUriString = null,
                                duration = durationMs,
                                genre = "YouTube",
                                mimeType = "audio/webm",
                                bitrate = 128,
                                sampleRate = 44100,
                                youtubeId = youtubeId
                            )
                            musicRepository.insertYoutubeSongs(listOf(newSong))
                            songIds.add(songId)
                        }
                        continue
                    }

                    // 1) Try exact path match
                    if (path.isNotBlank()) {
                        val byPath = songsByPath[path]
                        if (byPath != null) {
                            songIds.add(byPath.id)
                            continue
                        }
                        
                        // Try filename match from path column
                        val fileName = path.substringAfterLast("/").substringBeforeLast(".")
                        val byFile = songsByFileName[fileName]?.firstOrNull()
                        if (byFile != null) {
                            songIds.add(byFile.id)
                            continue
                        }
                    }

                    // 2) Try Title & Artist match
                    if (title.isNotBlank()) {
                        val key = "${title.lowercase().trim()} - ${artist.lowercase().trim()}"
                        val matchByTitleAndArtist = songsByTitleAndArtist[key]?.firstOrNull()
                        if (matchByTitleAndArtist != null) {
                            songIds.add(matchByTitleAndArtist.id)
                            continue
                        }
                        
                        // Try Title fallback
                        val matchByTitle = songsByTitle[title.lowercase().trim()]?.firstOrNull()
                        if (matchByTitle != null) {
                            songIds.add(matchByTitle.id)
                            continue
                        }

                        // 3) Try YouTube search on-the-fly
                        val resolvedYtId = searchYoutubeId(title, artist)
                        if (resolvedYtId != null) {
                            val songId = "youtube_$resolvedYtId"
                            val durationMs = durationStr.toLongOrNull() ?: 0L
                            val newSong = Song(
                                id = songId,
                                title = title,
                                artist = artist.ifBlank { "Unknown Artist" },
                                artistId = 0L,
                                album = album.ifBlank { "YouTube Music" },
                                albumId = 0L,
                                path = "",
                                contentUriString = "youtube://$resolvedYtId",
                                albumArtUriString = null,
                                duration = durationMs,
                                genre = "YouTube",
                                mimeType = "audio/webm",
                                bitrate = 128,
                                sampleRate = 44100,
                                youtubeId = resolvedYtId
                            )
                            musicRepository.insertYoutubeSongs(listOf(newSong))
                            songIds.add(songId)
                        }
                    }
                }
            }
        }

        // Derive playlist name from file name
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                playlistName = cursor.getString(nameIndex).removeSuffix(".csv")
            }
        }

        return Pair(playlistName, songIds)
    }

    /** Minimal CSV row splitter that handles double-quoted fields. */
    private fun splitCsvRow(row: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes && i + 1 < row.length && row[i + 1] == '"' -> {
                    current.append('"'); i++ // escaped quote
                }
                c == '"' && inQuotes -> inQuotes = false
                c == ',' && !inQuotes -> { result.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }
}
