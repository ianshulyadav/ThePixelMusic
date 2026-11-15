package com.unshoo.pixelmusic.data.service.player

/**
 * One rule for the SimpleCache key of a streamed track, shared by the player, the first-chunk
 * pre-cacher and the queue preloader: video id plus format (itag), or the source for non-YouTube
 * CDNs. Keying by video id alone let a refreshed URL in a different format (Opus vs AAC, or a
 * JioSaavn file) stitch onto the old format's cached spans: corrupt audio, parser errors and a
 * reload loop. Same-format refreshes keep reusing the cached bytes.
 */
object StreamCacheKeys {
    private val ITAG = Regex("""[?&]itag=(\d+)""")

    fun forStream(videoId: String, url: String?): String {
        if (url.isNullOrBlank() || !url.startsWith("http")) return videoId
        val tag = when {
            url.contains("saavncdn.com") || url.contains("jiosaavn.com") -> "saavn"
            else -> ITAG.find(url)?.groupValues?.get(1)
        }
        return if (tag.isNullOrBlank()) videoId else "$videoId#$tag"
    }
}
