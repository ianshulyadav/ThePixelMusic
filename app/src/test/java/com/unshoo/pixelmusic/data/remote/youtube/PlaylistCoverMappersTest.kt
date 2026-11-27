package com.unshoo.pixelmusic.data.remote.youtube

import com.unshoo.pixelmusic.data.preferences.AlbumArtQuality
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaylistCoverMappersTest {

    @Test
    fun `isSignedGoogleThumbnailUrl detects query signed tokens and ignores plain resize params`() {
        val signedSqpUrl =
            "https://yt3.ggpht.com/pl_custom_cover=w544-h544-l90-rj?sqp=-oaymwEWCKgBEF5IWvKriqkDCQgBFQAAiEIYAQ==&rs=AOn4CLB123"
        val signedSighUrl =
            "https://lh3.googleusercontent.com/custom-art?rs=AOn4CLTest&sigh=abc_123"
        val plainLh3Url =
            "https://lh3.googleusercontent.com/abc123=w226-h226-l90-rj"

        assertTrue(isSignedGoogleThumbnailUrl(signedSqpUrl))
        assertTrue(isSignedGoogleThumbnailUrl(signedSighUrl))
        assertFalse(isSignedGoogleThumbnailUrl(plainLh3Url))
    }

    @Test
    fun `signed YouTube playlist cover URLs are preserved without corrupting query tokens`() {
        val signedUrl =
            "https://yt3.ggpht.com/pl_custom_cover=w544-h544-l90-rj?sqp=-oaymwEWCKgBEF5IWvKriqkDCQgBFQAAiEIYAQ==&rs=AOn4CLB123"

        assertEquals(signedUrl, rewriteGoogleThumbnailUrl(signedUrl, widthPx = 1024))
        assertEquals(
            signedUrl,
            upgradeThumbnailUrlToQuality(signedUrl, AlbumArtQuality.ORIGINAL),
        )
        assertEquals(
            signedUrl,
            upgradeThumbnailUrlToQuality(signedUrl, AlbumArtQuality.HIGH),
        )
        assertEquals(signedUrl, upgradeThumbnailUrlToHighQuality(signedUrl))
    }

    @Test
    fun `unsigned googleusercontent and ytimg URLs upgrade cleanly per requested quality`() {
        val plainLh3 = "https://lh3.googleusercontent.com/abc123=w120-h120-l90-rj"
        assertEquals(
            "https://lh3.googleusercontent.com/abc123=w1024-h1024-l90-rj",
            upgradeThumbnailUrlToQuality(plainLh3, AlbumArtQuality.ORIGINAL),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/abc123=w800-h800-l90-rj",
            upgradeThumbnailUrlToQuality(plainLh3, AlbumArtQuality.HIGH),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/abc123=w512-h512-l90-rj",
            upgradeThumbnailUrlToQuality(plainLh3, AlbumArtQuality.MEDIUM),
        )

        val queryOnlyGoogleUrl = "https://yt3.googleusercontent.com/abc123?fc=1"
        assertEquals(
            "https://yt3.googleusercontent.com/abc123=w800-h800-l90-rj?fc=1",
            rewriteGoogleThumbnailUrl(queryOnlyGoogleUrl, widthPx = 800),
        )

        val ytImg = "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            upgradeThumbnailUrlToHighQuality(ytImg),
        )
        assertNull(upgradeThumbnailUrlToHighQuality(null))
        assertEquals("", upgradeThumbnailUrlToQuality("", AlbumArtQuality.ORIGINAL))
    }

    @Test
    fun `YoutubeHelper getBestThumbnailUrl extracts music, croppedSquare, and animated renderers`() {
        val croppedJson = Json.parseToJsonElement(
            """
            {
              "croppedSquareThumbnailRenderer": {
                "thumbnail": {
                  "thumbnails": [
                    { "url": "https://yt3.ggpht.com/small.jpg" },
                    { "url": "https://yt3.ggpht.com/large.jpg?sqp=abc&rs=xyz" }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val animatedJson = Json.parseToJsonElement(
            """
            {
              "musicAnimatedThumbnailRenderer": {
                "backupRenderer": {
                  "thumbnail": {
                    "thumbnails": [
                      { "url": "//yt3.ggpht.com/animated_backup.jpg" }
                    ]
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            "https://yt3.ggpht.com/large.jpg?sqp=abc&rs=xyz",
            YoutubeHelper.getBestThumbnailUrl(croppedJson),
        )
        assertEquals(
            "https://yt3.ggpht.com/animated_backup.jpg=w1024-h1024-l90-rj",
            YoutubeHelper.getBestThumbnailUrl(animatedJson),
        )
    }
}
