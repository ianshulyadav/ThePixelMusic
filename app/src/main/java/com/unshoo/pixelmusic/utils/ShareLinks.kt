package com.unshoo.pixelmusic.utils

import com.unshoo.pixelmusic.data.model.Song

/**
 * Links PixelMusic hands out when sharing a song.
 *
 * Online songs share `https://www.pixelmusic.me/listen?v=<id>` instead of a YouTube URL:
 * Android opens it straight in PixelMusic when installed (verified App Link, see
 * MainActivity "/listen"), and the website shows a "Get PixelMusic" page otherwise.
 * Songs that only exist on this phone can't be opened elsewhere, so they share the site.
 */
object ShareLinks {
    const val SITE = "https://www.pixelmusic.me"

    private val YT_ID = Regex("^[A-Za-z0-9_-]{11}$")

    fun forYoutubeId(videoId: String): String = "$SITE/listen?v=$videoId"

    fun forSong(song: Song): String =
        song.youtubeId?.takeIf { YT_ID.matches(it) }?.let(::forYoutubeId) ?: "$SITE/"

    fun isShareable(song: Song): Boolean = song.youtubeId?.let { YT_ID.matches(it) } == true
}
