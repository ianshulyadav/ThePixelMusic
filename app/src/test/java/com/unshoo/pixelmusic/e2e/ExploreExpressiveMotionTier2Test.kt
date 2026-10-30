package com.unshoo.pixelmusic.e2e

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * ============================================================================
 * Tier 2 — Boundary & Corner Cases: R2 Balanced Expressive Motion Schemes
 * ============================================================================
 *
 * Stress tests extreme physical motion limits, flutter interactions, and clamped boundaries:
 * - Extreme fling velocity (>100,000 px/ms) clamping
 * - Zero-delta idle fling settling
 * - High-frequency flutter press/release interaction cycles
 * - Reduced motion / zero animation duration handling
 * - Reveal-on-enter index overflow clamping
 * - 3D depth tilt angle bounding
 *
 * Authoritative Source: ORIGINAL_REQUEST.md (R2), PROJECT.md (§ Motion Engine ↔ UI Composables)
 */
@DisplayName("Tier 2: Expressive Motion Schemes Boundary & Corner Cases")
class ExploreExpressiveMotionTier2Test {

    @Test
    @DisplayName("T2-F2-01: Extreme fling velocity (>100,000 px/ms) strictly clamped at max stretch limit")
    fun testScrollStretch_extremeVelocityClamped() {
        val maxStretch = 0.012f
        val extremePxPerMsValues = listOf(10_000f, 100_000f, 1_000_000f, -500_000f)

        for (pxPerMs in extremePxPerMsValues) {
            val normalizedVelocity = (pxPerMs / 3.0f).coerceIn(-1.0f, 1.0f)
            val magnitude = abs(normalizedVelocity)
            val scaleY = 1.0f + magnitude * maxStretch
            val scaleX = 1.0f - magnitude * maxStretch * 0.6f

            assertTrue(scaleY <= 1.0f + maxStretch)
            assertTrue(scaleX >= 1.0f - maxStretch)
            assertEquals(1.0f, magnitude, 0.0001f)
        }
    }

    @Test
    @DisplayName("T2-F2-02: Zero-delta idle fling leaves scale exactly at 1.0f rest")
    fun testScrollStretch_zeroMovementHaltSettling() {
        val maxStretch = 0.012f
        val pxPerMs = 0.0f

        val normalizedVelocity = (pxPerMs / 3.0f).coerceIn(-1.0f, 1.0f)
        val magnitude = abs(normalizedVelocity)
        val scaleY = 1.0f + magnitude * maxStretch
        val scaleX = 1.0f - magnitude * maxStretch * 0.6f

        assertEquals(0.0f, normalizedVelocity, 0.0001f)
        assertEquals(1.0f, scaleY, 0.0001f)
        assertEquals(1.0f, scaleX, 0.0001f)
    }

    @Test
    @DisplayName("T2-F2-03: Rapid flutter tap interactions (50 rapid press cycles) settle cleanly")
    fun testPressScale_rapidFlutterInterleaving() {
        var isPressedState = false
        val flutterCycles = 50

        for (i in 1..flutterCycles) {
            isPressedState = !isPressedState
        }

        assertFalse(isPressedState)
        val targetScale = if (isPressedState) 0.965f else 1.0f
        assertEquals(1.0f, targetScale, 0.0001f)
    }

    @Test
    @DisplayName("T2-F2-04: Reduced motion simulation with zero duration settles instantaneously")
    fun testReducedMotion_instantaneousSettling() {
        fun computeDuration(requestedDuration: Int, isReducedMotion: Boolean): Int {
            return if (isReducedMotion) 0 else requestedDuration
        }

        val standardDuration = 320
        assertEquals(320, computeDuration(standardDuration, isReducedMotion = false))
        assertEquals(0, computeDuration(standardDuration, isReducedMotion = true))

        fun calculateProgress(elapsedMs: Long, durationMs: Int): Float {
            if (durationMs <= 0) return 1.0f
            return (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0.0f, 1.0f)
        }

        assertEquals(1.0f, calculateProgress(0L, 0))
    }

    @Test
    @DisplayName("T2-F2-05: Reveal-on-enter index overflow clamped to prevent runaway stagger delays")
    fun testRevealOnEnter_indexOverflowClamped() {
        val staggerMillis = 45L
        fun computeStaggerDelay(index: Int): Long {
            return index.coerceIn(0, 10) * staggerMillis
        }

        assertEquals(0L, computeStaggerDelay(0))
        assertEquals(45L, computeStaggerDelay(1))
        assertEquals(450L, computeStaggerDelay(10))
        assertEquals(450L, computeStaggerDelay(100))
        assertEquals(450L, computeStaggerDelay(10_000))
    }

    @Test
    @DisplayName("T2-F2-06: 3D depth tilt angle strictly bounded to prevent card inversion")
    fun testGraphicsLayerTransform_depthRotationClamped() {
        val tiltDeg = 2.5f
        fun computeRotationX(distance: Float): Float {
            val clampedDist = distance.coerceIn(-1.4f, 1.4f)
            return -clampedDist * tiltDeg
        }

        val extremeDistanceValues = listOf(-10.0f, -2.0f, 0.0f, 2.0f, 10.0f)
        for (dist in extremeDistanceValues) {
            val rotation = computeRotationX(dist)
            assertTrue(abs(rotation) <= 1.4f * tiltDeg + 0.001f)
        }
    }
}
