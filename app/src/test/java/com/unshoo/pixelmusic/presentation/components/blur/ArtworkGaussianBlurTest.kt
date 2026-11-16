package com.unshoo.pixelmusic.presentation.components.blur

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtworkGaussianBlurTest {

    @Test
    fun `kernel is normalised, symmetric and peaks at the centre`() {
        val kernel = ArtworkGaussianBlur.gaussianKernel(5f)
        assertEquals(31, kernel.size) // ±3σ
        assertEquals(1f, kernel.sum(), 1e-4f)
        for (i in kernel.indices) assertEquals(kernel[i], kernel[kernel.lastIndex - i], 1e-6f)
        assertTrue(kernel[kernel.size / 2] > kernel[0])
    }

    @Test
    fun `uniform image is unchanged`() {
        val color = (0xFF shl 24) or (0x40 shl 16) or (0x80 shl 8) or 0xC0
        val pixels = IntArray(16 * 16) { color }
        ArtworkGaussianBlur.gaussianBlurArgb(pixels, 16, 16, 3f)
        assertTrue(pixels.all { it == color })
    }

    @Test
    fun `single bright pixel spreads symmetrically and keeps its energy`() {
        val w = 21
        val pixels = IntArray(w * w) { 0xFF000000.toInt() }
        pixels[10 * w + 10] = 0xFFFFFFFF.toInt()
        ArtworkGaussianBlur.gaussianBlurArgb(pixels, w, w, 2f)
        fun red(x: Int, y: Int) = (pixels[y * w + x] shr 16) and 0xFF
        assertTrue(red(10, 10) in 1..254)
        assertEquals(red(9, 10), red(11, 10))
        assertEquals(red(10, 9), red(10, 11))
        assertEquals(red(9, 10), red(10, 9))
        // Total red energy stays ~255 (rounding per pixel allows small drift).
        val total = (0 until w * w).sumOf { (pixels[it] shr 16) and 0xFF }
        assertTrue(total in 200..320, "energy=$total")
    }
}
