package com.unshoo.pixelmusic.utils

import android.content.Context
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.database.LocalAudioCandidate
import com.unshoo.pixelmusic.data.model.youtube.Song
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Utility for detecting whether an audio file to be downloaded or streamed
 * is already present locally in the device storage / library with >= 80% similarity
 * and at least one matching artist, skipping redundant network downloads.
 */
object LocalAudioDuplicateMatcher {

    private const val TAG = "LocalAudioMatcher"
    private const val TITLE_SIMILARITY_THRESHOLD = 0.80
    private const val ARTIST_SIMILARITY_THRESHOLD = 0.80
    private const val DURATION_TOLERANCE_SECONDS = 15

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MatcherEntryPoint {
        fun musicDao(): MusicDao
    }

    /**
     * Compute Levenshtein edit distance between two strings.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val len1 = s1.length
        val len2 = s2.length
        var prev = IntArray(len2 + 1) { it }
        var curr = IntArray(len2 + 1)

        for (i in 1..len1) {
            curr[0] = i
            val c1 = s1[i - 1]
            for (j in 1..len2) {
                val cost = if (c1 == s2[j - 1]) 0 else 1
                curr[j] = min(
                    curr[j - 1] + 1,       // insertion
                    min(
                        prev[j] + 1,       // deletion
                        prev[j - 1] + cost // substitution
                    )
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[len2]
    }

    private val extensionSuffix = Regex("""(?i)\.[a-z0-9]{2,4}$""")
    private val titleAnnotation = Regex("""(?i)(?:\((official\s*(?:music)?\s*(?:video|audio|lyric\s*video)?|lyrics?|audio|full\s*song)\)|\[(official\s*(?:music)?\s*(?:video|audio|lyric\s*video)?|lyrics?|audio|full\s*song)\])""")
    private val featuringSuffix = Regex("""(?i)\b(?:ft|feat|featuring)\b.*""")
    private val punctuation = Regex("""[^\p{L}\p{M}\p{N}\s]""")
    private val whitespace = Regex("""\s+""")
    private val versionKeywords = listOf("remix", "instrumental", "karaoke", "cover", "acoustic", "live", "sped up", "speed up", "slowed", "reverb", "tribute")

    fun cleanMetadataText(text: String): String = text
        .replace(extensionSuffix, "")
        .replace(titleAnnotation, "")
        .replace(featuringSuffix, "")
        .replace(punctuation, " ")
        .replace(whitespace, " ")
        .trim()
        .lowercase(Locale.ROOT)

    /**
     * Calculate string similarity score between 0.0 and 1.0 using Levenshtein distance,
     * substring containment, and token-set overlap.
     */
    fun calculateStringSimilarity(s1: String, s2: String): Double {
        val c1 = cleanMetadataText(s1)
        val c2 = cleanMetadataText(s2)

        if (c1.isEmpty() || c2.isEmpty()) return 0.0
        if (c1 == c2) return 1.0

        val maxLen = max(c1.length, c2.length)
        val lev = levenshteinDistance(c1, c2)
        val levSim = 1.0 - (lev.toDouble() / maxLen.toDouble())

        // Substring containment ratio
        val containmentSim = if (c1.contains(c2) || c2.contains(c1)) {
            min(c1.length, c2.length).toDouble() / max(c1.length, c2.length).toDouble()
        } else {
            0.0
        }

        // Token set Jaccard/Dice overlap
        val tokens1 = c1.split(" ").filter { it.length > 1 }.toSet()
        val tokens2 = c2.split(" ").filter { it.length > 1 }.toSet()
        val tokenSim = if (tokens1.isNotEmpty() && tokens2.isNotEmpty()) {
            (2.0 * tokens1.intersect(tokens2).size) / (tokens1.size + tokens2.size)
        } else {
            0.0
        }

        return max(levSim, max(containmentSim, tokenSim))
    }

    /**
     * Split artists into distinct individual artist names.
     */
    fun splitArtists(artistString: String): List<String> {
        if (artistString.isBlank()) return emptyList()
        return artistString
            .split(Regex("\\s*[,/&;+、•|]\\s*|\\s+(?:feat\\.|ft\\.|featuring|vs\\.?)\\s+|\\s+and\\s+", RegexOption.IGNORE_CASE))
            .map { cleanMetadataText(it) }
            .filter { it.length >= 2 && !it.equals("various artists", ignoreCase = true) && !it.equals("unknown", ignoreCase = true) }
    }

    /**
     * Returns true if at least one artist name matches or is similar between two songs.
     */
    fun hasSimilarArtist(artist1: String, artist2: String): Boolean {
        val list1 = splitArtists(artist1)
        val list2 = splitArtists(artist2)

        if (list1.isEmpty() || list2.isEmpty()) {
            // If one artist string is missing, fall back to checking if the strings are not contradictory
            return true
        }

        for (a1 in list1) {
            for (a2 in list2) {
                if (a1 == a2) return true
                if ((a1.length >= 3 && a2.contains(a1)) || (a2.length >= 3 && a1.contains(a2))) return true
                if (calculateStringSimilarity(a1, a2) >= ARTIST_SIMILARITY_THRESHOLD) return true
            }
        }
        return false
    }

    /**
     * Checks if two songs are >= 80% identical with at least one matching artist
     * and compatible duration.
     */
    fun isSongMatch(
        targetTitle: String,
        targetArtist: String,
        targetDurationSeconds: Int?,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSeconds: Int?
    ): Boolean {
        if (targetDurationSeconds != null && targetDurationSeconds > 0 &&
            candidateDurationSeconds != null && candidateDurationSeconds > 0 &&
            abs(targetDurationSeconds - candidateDurationSeconds) > DURATION_TOLERANCE_SECONDS
        ) return false

        for (keyword in versionKeywords) {
            val targetHas = targetTitle.contains(keyword, ignoreCase = true)
            val candidateHas = candidateTitle.contains(keyword, ignoreCase = true)
            if (targetHas != candidateHas) {
                return false
            }
        }

        // 2. Title similarity check (>= 80%)
        val titleSim = calculateStringSimilarity(targetTitle, candidateTitle)
        if (titleSim < TITLE_SIMILARITY_THRESHOLD) {
            return false
        }

        // 3. Artist similarity check (at least one artist similar)
        if (!hasSimilarArtist(targetArtist, candidateArtist)) {
            return false
        }

        Timber.tag(TAG).d(
            "Found duplicate match! Target=\"$targetTitle\" ($targetArtist) vs Local=\"$candidateTitle\" ($candidateArtist), titleSim=$titleSim"
        )
        return true
    }

    /**
     * Search a provided list of local [LocalAudioCandidate]s for an 80%+ match using a [Song] object.
     */
    fun findMatchingLocalSong(
        localSongs: List<LocalAudioCandidate>,
        song: Song,
        allowLossless: Boolean = true,
        context: Context? = null
    ): LocalAudioCandidate? {
        val targetDurationSec = parseDurationToSeconds(song.duration)
        return findMatchingLocalSong(
            localSongs = localSongs,
            title = song.title,
            artist = song.artist,
            durationSeconds = targetDurationSec,
            allowLossless = allowLossless,
            context = context
        )
    }

    /**
     * Search a provided list of local [LocalAudioCandidate]s for an 80%+ match.
     */
    fun findMatchingLocalSong(
        localSongs: List<LocalAudioCandidate>,
        title: String,
        artist: String,
        durationSeconds: Int? = null,
        allowLossless: Boolean = true,
        context: Context? = null
    ): LocalAudioCandidate? {
        if (title.isBlank()) return null
        return localSongs.firstOrNull { localSong ->
            val path = localSong.filePath.ifBlank { localSong.contentUriString }
            if (path.isBlank()) return@firstOrNull false
            if (!allowLossless && isLosslessPath(path)) return@firstOrNull false

            // Cheap metadata checks first; only touch the filesystem for an actual match
            // (stat()-ing every song in a large library made each lookup take seconds).
            val localDurationSec = if (localSong.duration > 0) (localSong.duration / 1000).toInt() else null
            val matches = isSongMatch(
                targetTitle = title,
                targetArtist = artist,
                targetDurationSeconds = durationSeconds,
                candidateTitle = localSong.title,
                candidateArtist = localSong.artistName,
                candidateDurationSeconds = localDurationSec
            )
            if (!matches) return@firstOrNull false
            if (context != null) {
                resolveLocalSongPath(context, localSong) != null
            } else {
                val file = File(path)
                file.isFile && file.length() > 0L
            }
        }
    }

    internal fun resolveLocalSongPath(context: Context, song: LocalAudioCandidate): String? =
        OfflineAudioResolver.readableAudioPath(context, song.filePath)
            ?: OfflineAudioResolver.readableAudioPath(context, song.contentUriString)

    /**
     * Search local database for an existing local audio file matching the song.
     * Returns the verified local file path if found, or null otherwise.
     */
    suspend fun findMatchingLocalFilePath(
        context: Context,
        song: Song,
        allowLossless: Boolean = true
    ): String? {
        return try {
            val musicDao = EntryPointAccessors.fromApplication(
                context.applicationContext,
                MatcherEntryPoint::class.java
            ).musicDao()

            val localSongs = musicDao.getLocalAudioCandidates()
            if (localSongs.isEmpty()) return null

            val targetDurationSec = parseDurationToSeconds(song.duration)
            val matched = findMatchingLocalSong(
                localSongs = localSongs,
                title = song.title,
                artist = song.artist,
                durationSeconds = targetDurationSec,
                allowLossless = allowLossless,
                context = context,
            )
            matched?.let { resolveLocalSongPath(context, it) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error checking local duplicate songs")
            null
        }
    }

    private fun isLosslessPath(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".alac")
    }

    private fun parseDurationToSeconds(durationStr: String?): Int? {
        if (durationStr.isNullOrBlank()) return null
        durationStr.toIntOrNull()?.let { return it }
        val parts = durationStr.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }
}
