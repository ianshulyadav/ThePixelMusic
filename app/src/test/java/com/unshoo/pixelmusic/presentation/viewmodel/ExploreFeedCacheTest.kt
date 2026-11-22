package com.unshoo.pixelmusic.presentation.viewmodel

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExploreFeedCacheTest {
    @TempDir
    lateinit var cacheDir: File

    @Test
    fun `oversized replacement leaves the previous cache readable`() {
        val file = File(cacheDir, "explore_cache_v2.json")
        val previous = ExploreCacheModel(authKey = "account", continuation = "next")
        ExploreFeedCache.write(file, previous)
        ExploreFeedCache.write(file, previous.copy(continuation = "é".repeat(260 * 1024)))

        assertEquals(previous, ExploreFeedCache.read(file))
        assertEquals(listOf(file.name), cacheDir.list()?.toList())
    }

    @Test
    fun `oversized cache is ignored`() {
        val file = File(cacheDir, "explore_cache_v2.json")
        file.writeBytes(ByteArray(501 * 1024))

        assertNull(ExploreFeedCache.read(file))
    }
}
