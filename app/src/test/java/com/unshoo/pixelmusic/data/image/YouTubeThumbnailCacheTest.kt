package com.unshoo.pixelmusic.data.image

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class YouTubeThumbnailCacheTest {

    @BeforeEach
    @AfterEach
    fun reset() {
        YouTubeThumbnailCache.clear()
    }

    @Test
    fun `missing maxres is recorded and rewrites subsequent requests straight to hqdefault`() {
        val videoId = "dQw4w9WgXcQ"
        val maxResUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"

        assertEquals(
            listOf(
                maxResUrl,
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            ),
            YouTubeThumbnailCache.fallbackCandidates(maxResUrl),
        )
        assertFalse(YouTubeThumbnailCache.isMaxResMissing(videoId))

        YouTubeThumbnailCache.markMaxResMissingForUrl(maxResUrl)

        assertTrue(YouTubeThumbnailCache.isMaxResMissing(videoId))
        assertEquals(
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            YouTubeThumbnailCache.rewriteIfMaxResMissing(maxResUrl),
        )
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            ),
            YouTubeThumbnailCache.fallbackCandidates(maxResUrl),
        )
    }

    @Test
    fun `non-ytimg urls and low thumbnails are left untouched`() {
        val lh3Url = "https://lh3.googleusercontent.com/abc123=w1024-h1024-l90-rj"
        assertFalse(YouTubeThumbnailCache.isMaxResUrl(lh3Url))
        assertEquals(lh3Url, YouTubeThumbnailCache.rewriteIfMaxResMissing(lh3Url))

        val mqUrl = "https://i.ytimg.com/vi/abcDEF12345/mqdefault.jpg"
        YouTubeThumbnailCache.markMaxResMissingForUrl(mqUrl)
        assertFalse(YouTubeThumbnailCache.isMaxResMissing("abcDEF12345"))
        assertEquals(mqUrl, YouTubeThumbnailCache.rewriteIfMaxResMissing(mqUrl))
    }

    @Test
    fun `cache evicts oldest entries cleanly past capacity`() {
        for (i in 0..600) {
            YouTubeThumbnailCache.markMaxResMissing("vid_$i")
        }
        assertFalse(YouTubeThumbnailCache.isMaxResMissing("vid_0"))
        assertTrue(YouTubeThumbnailCache.isMaxResMissing("vid_600"))
    }
}
