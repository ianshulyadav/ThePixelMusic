package com.unshoo.pixelmusic.data.service.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class StreamCacheKeysTest {
    @Test
    fun `different formats of one song never share a cache key`() {
        val opus = StreamCacheKeys.forStream("dQw4w9WgX_Q", "https://rr1.googlevideo.com/videoplayback?expire=1&itag=251&id=x")
        val aac = StreamCacheKeys.forStream("dQw4w9WgX_Q", "https://rr1.googlevideo.com/videoplayback?itag=140&expire=1")
        val saavn = StreamCacheKeys.forStream("dQw4w9WgX_Q", "https://aac.saavncdn.com/123/abc_320.mp4")
        assertEquals("dQw4w9WgX_Q#251", opus)
        assertEquals("dQw4w9WgX_Q#140", aac)
        assertEquals("dQw4w9WgX_Q#saavn", saavn)
        assertNotEquals(opus, aac)
    }

    @Test
    fun `a refreshed link in the same format keeps the same key`() {
        val first = StreamCacheKeys.forStream("abc", "https://a.googlevideo.com/videoplayback?itag=251&expire=100&sig=1")
        val refreshed = StreamCacheKeys.forStream("abc", "https://b.googlevideo.com/videoplayback?expire=999&itag=251&sig=2")
        assertEquals(first, refreshed)
    }

    @Test
    fun `local files and unknown urls fall back to the video id`() {
        assertEquals("abc", StreamCacheKeys.forStream("abc", "file:///x.m4a"))
        assertEquals("abc", StreamCacheKeys.forStream("abc", null))
    }
}
