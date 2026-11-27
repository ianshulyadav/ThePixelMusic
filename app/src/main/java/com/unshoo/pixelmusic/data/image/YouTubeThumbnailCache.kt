package com.unshoo.pixelmusic.data.image

import java.util.Collections

/**
 * Bounded process-wide negative cache for YouTube video IDs whose high-res
 * `maxresdefault.jpg` / `sddefault.jpg` / `hq720.jpg` thumbnail returned an error (typically 404).
 *
 * Shared across UI cards, preloading, color extraction, and MediaSession bitmap loading so a
 * missing 1280x720 frame only pays a single HTTP 404 round-trip once per session.
 */
object YouTubeThumbnailCache {
    private const val MAX_MISSING_ENTRIES = 512
    private val YTIMG_VI_REGEX =
        Regex("""i\.ytimg\.com/vi(?:_webp)?/([^/]+)/(maxresdefault|sddefault|hq720|hqdefault|mqdefault|default)""")

    private val missingMaxResIds: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                    size > MAX_MISSING_ENTRIES
            }
        )
    )

    fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return YTIMG_VI_REGEX.find(url)?.groupValues?.getOrNull(1)
    }

    fun isMaxResUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val quality = YTIMG_VI_REGEX.find(url)?.groupValues?.getOrNull(2) ?: return false
        return quality == "maxresdefault" || quality == "sddefault" || quality == "hq720"
    }

    fun isMaxResMissing(videoId: String?): Boolean =
        !videoId.isNullOrBlank() && missingMaxResIds.contains(videoId)

    fun markMaxResMissing(videoId: String?) {
        if (!videoId.isNullOrBlank()) {
            missingMaxResIds.add(videoId)
        }
    }

    fun markMaxResMissingForUrl(url: String?) {
        val videoId = extractVideoId(url) ?: return
        if (isMaxResUrl(url)) {
            missingMaxResIds.add(videoId)
        }
    }

    fun rewriteIfMaxResMissing(url: String): String {
        val match = YTIMG_VI_REGEX.find(url) ?: return url
        val videoId = match.groupValues[1]
        val quality = match.groupValues[2]
        if ((quality == "maxresdefault" || quality == "sddefault" || quality == "hq720") &&
            missingMaxResIds.contains(videoId)
        ) {
            return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        }
        return url
    }

    fun fallbackCandidates(url: String): List<String> {
        val match = YTIMG_VI_REGEX.find(url) ?: return listOf(url)
        val videoId = match.groupValues[1]
        val quality = match.groupValues[2]
        val isHigh = quality == "maxresdefault" || quality == "sddefault" || quality == "hq720"
        val hq = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        val mq = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        return when {
            isHigh && missingMaxResIds.contains(videoId) -> listOf(hq, mq)
            isHigh -> listOf(url, hq, mq)
            quality == "hqdefault" -> listOf(url, mq)
            else -> listOf(url)
        }
    }

    fun clear() {
        missingMaxResIds.clear()
    }
}
