package com.unshoo.pixelmusic.data.service.player

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which copy of a track is currently serving playback, pinned for
 * as long as the track is active.
 *
 * Ported from BitChord StreamChoice.
 * ExoPlayer opens a source many times over one play: the initial read, seeking
 * past the buffer, a resume, and continuation fetch when playback reaches the end
 * of what the cache holds.
 *
 * Pinning the stream guarantees that the continuation fetch cannot resolve to a
 * different audio format (e.g. YouTube Opus vs. WebM or AAC) mid-track, preventing
 * MatroskaExtractor EOFException and audio muting.
 */
object StreamChoice {

    data class PinnedStream(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val mimeType: String? = null,
        val bitrate: Int? = null,
        val at: Long = SystemClock.elapsedRealtime()
    )

    private val chosen = ConcurrentHashMap<String, PinnedStream>()

    /** Long enough to outlast any one play (15 mins), far short of any signed URL's 6-hour life. */
    private const val TTL_MS = 15 * 60 * 1000L
    private const val MAX_REMEMBERED = 48

    /**
     * The stream already serving [videoId], or null if this is the first read
     * for it or the last one was long enough ago that its URL is no longer trusted.
     */
    fun of(videoId: String): PinnedStream? {
        val cleanKey = sanitizeKey(videoId)
        val choice = chosen[cleanKey] ?: return null
        if (SystemClock.elapsedRealtime() - choice.at > TTL_MS) {
            chosen.remove(cleanKey)
            return null
        }
        return choice
    }

    /**
     * Records [url] and [headers] as the one authoritative copy of [videoId] this play is reading.
     */
    fun remember(
        videoId: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        mimeType: String? = null,
        bitrate: Int? = null
    ) {
        val cleanKey = sanitizeKey(videoId)
        if (cleanKey.isBlank() || url.isBlank()) return

        if (chosen.size >= MAX_REMEMBERED) {
            val now = SystemClock.elapsedRealtime()
            chosen.entries.removeIf { now - it.value.at > TTL_MS }
            if (chosen.size >= MAX_REMEMBERED) {
                chosen.minByOrNull { it.value.at }?.let { chosen.remove(it.key) }
            }
        }
        chosen[cleanKey] = PinnedStream(
            url = url,
            headers = headers,
            mimeType = mimeType,
            bitrate = bitrate,
            at = SystemClock.elapsedRealtime()
        )
    }

    /**
     * Releases [videoId] to be resolved afresh (e.g. when queue moves past track or on error).
     */
    fun forget(videoId: String) {
        chosen.remove(sanitizeKey(videoId))
    }

    fun clear() {
        chosen.clear()
    }

    private fun sanitizeKey(key: String): String {
        return key.removePrefix("youtube://")
            .removePrefix("youtube_")
            .removePrefix("monochrome://")
            .removePrefix("monochrome_")
            .trim()
    }
}
