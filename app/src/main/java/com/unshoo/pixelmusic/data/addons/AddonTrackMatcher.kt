package com.unshoo.pixelmusic.data.addons

import com.unshoo.pixelmusic.data.lossless.audiosource.DirectStream
import com.unshoo.pixelmusic.data.lossless.audiosource.TitleMatch
import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceType
import java.text.Normalizer
import java.util.Locale

/**
 * Evaluates candidate tracks returned by HTTP addons and JS modules against the target song.
 *
 * Combines PixelMusic's [TitleMatch] Jaro/Levenshtein-style similarity with ArchiveTune and
 * BitChord's recording-variant guards (`live`, `remix`, `acoustic`, `karaoke`, `instrumental`,
 * `sped up`, `slowed`, `cover`, `8d`) and exact ISRC matching.
 */
object AddonTrackMatcher {

    private val RECORDING_MARKERS = listOf(
        "live",
        "remix",
        "cover",
        "karaoke",
        "instrumental",
        "acoustic",
        "sped up",
        "slowed",
        "nightcore",
        "8d",
        "demo",
    ).map { marker -> Regex("(?:^|\\s)${Regex.escape(marker)}(?:\\s|$)") }

    data class RankedCandidate(
        val candidate: AddonTrackCandidate,
        val score: Double,
        val reason: String,
    )

    fun buildSearchQuery(title: String, artists: List<String>): String {
        val cleanTitle = title
            .replace(Regex("""\s*[\[(]\s*(?:(?:official\s+)?(?:music\s+)?(?:audio|video)|lyrics?|lyric\s+video|visualizer|hd|hq|4k)\s*[\])]""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*[\[(]\s*(?:feat\.?|ft\.?|featuring)\b[^)\]]*[\])]""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.+$""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val primaryArtist = artists.firstOrNull { it.isNotBlank() }
            ?.substringBefore(',')
            ?.substringBefore('&')
            ?.replace(Regex("""\s+(?:feat\.?|ft\.?|featuring)\b.*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            .orEmpty()
        return if (primaryArtist.isNotEmpty() && !cleanTitle.contains(primaryArtist, ignoreCase = true)) {
            "$cleanTitle $primaryArtist".trim()
        } else {
            cleanTitle.ifBlank { title.trim() }
        }
    }

    fun rankCandidates(
        wantedTitle: String,
        wantedArtists: List<String>,
        wantedAlbum: String?,
        wantedDurationMs: Long?,
        wantedIsrc: String?,
        candidates: List<AddonTrackCandidate>,
        trustedOverride: Boolean = false,
    ): List<RankedCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val normWantedIsrc = wantedIsrc?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length >= 10 }
        val wantedMarkerNorm = markerText(wantedTitle)

        val accepted = ArrayList<RankedCandidate>(candidates.size)
        for (cand in candidates) {
            if (cand.title.isBlank()) continue
            val candIsrc = cand.isrc?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length >= 10 }
            if (normWantedIsrc != null && candIsrc != null && normWantedIsrc == candIsrc) {
                accepted.add(RankedCandidate(cand, 1.05, "exact ISRC match ($candIsrc)"))
                continue
            }

            if (!trustedOverride) {
                val candMarkerNorm = markerText(cand.title)
                val markerMismatch = RECORDING_MARKERS.any { rx ->
                    rx.containsMatchIn(wantedMarkerNorm) != rx.containsMatchIn(candMarkerNorm)
                }
                if (markerMismatch) continue
            }

            val probeStream = DirectStream(
                uri = "https://addon.local/${cand.id}",
                mimeType = "audio/flac",
                codecs = "flac",
                contentLength = null,
                label = cand.qualityLabel ?: "Addon",
                source = AudioSourceType.ADDON,
                matchedTitle = cand.title,
                matchedArtist = cand.artists.joinToString(", ").ifBlank { null },
                matchedAlbum = cand.album,
                matchedDurationMs = cand.durationMs,
                trustedDirectId = trustedOverride,
            )
            val eval = TitleMatch.evaluate(
                wantedTitle = wantedTitle,
                wantedArtists = wantedArtists,
                wantedAlbum = wantedAlbum,
                wantedDurationMs = wantedDurationMs,
                stream = probeStream,
            )
            if (eval.accepted) {
                val qualityTieBreak = when {
                    cand.isHiRes -> 0.015
                    cand.formatHint?.equals("flac", true) == true ||
                        cand.qualityLabel?.contains("lossless", true) == true ||
                        cand.qualityLabel?.contains("flac", true) == true -> 0.008
                    else -> 0.0
                }
                accepted.add(RankedCandidate(cand, eval.score + qualityTieBreak, eval.reason))
            }
        }
        return accepted.sortedByDescending { it.score }
    }

    private fun markerText(raw: String): String =
        Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
