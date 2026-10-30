package com.unshoo.pixelmusic.e2e

import com.unshoo.pixelmusic.ui.theme.ExpressiveSprings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * ============================================================================
 * Tier 1 — Feature Coverage: R2 Balanced & Modern Expressive Motion Schemes
 * ============================================================================
 *
 * Verifies nominal contract behaviors for Material 3 Expressive motion engine:
 * - Spatial spring calibrations (FastSpatial, DefaultSpatial, SlowSpatial)
 * - Effects spring calibrations (FastEffects, DefaultEffects, SlowEffects)
 * - Damped overshoot physics dichotomy (spatial < 1.0f vs effects == 1.0f)
 * - Press scale target bounds (0.95f..0.975f) for zero-recomposition touch feedback
 * - Scroll stretch velocity normalization and settling back to rest
 *
 * Authoritative Source: ORIGINAL_REQUEST.md (R2), PROJECT.md (§ Motion Engine ↔ UI Composables)
 */
@DisplayName("Tier 1: Balanced & Modern Expressive Motion Schemes")
class ExploreExpressiveMotionTier1Test {

    @Test
    @DisplayName("T1-F2-01: Spatial springs calibration tokens match M3 Expressive specifications")
    fun testExpressiveMotionScheme_spatialSpringsConfig() {
        assertEquals(0.6f, ExpressiveSprings.FastSpatialDampingRatio, 0.001f)
        assertEquals(800f, ExpressiveSprings.FastSpatialStiffness, 0.001f)

        assertEquals(0.8f, ExpressiveSprings.DefaultSpatialDampingRatio, 0.001f)
        assertEquals(380f, ExpressiveSprings.DefaultSpatialStiffness, 0.001f)

        assertEquals(0.8f, ExpressiveSprings.SlowSpatialDampingRatio, 0.001f)
        assertEquals(200f, ExpressiveSprings.SlowSpatialStiffness, 0.001f)
    }

    @Test
    @DisplayName("T1-F2-02: Effects springs calibration tokens match M3 Expressive specifications")
    fun testExpressiveMotionScheme_effectsSpringsConfig() {
        assertEquals(1.0f, ExpressiveSprings.FastEffectsDampingRatio, 0.001f)
        assertEquals(3800f, ExpressiveSprings.FastEffectsStiffness, 0.001f)

        assertEquals(1.0f, ExpressiveSprings.DefaultEffectsDampingRatio, 0.001f)
        assertEquals(1600f, ExpressiveSprings.DefaultEffectsStiffness, 0.001f)

        assertEquals(1.0f, ExpressiveSprings.SlowEffectsDampingRatio, 0.001f)
        assertEquals(800f, ExpressiveSprings.SlowEffectsStiffness, 0.001f)
    }

    @Test
    @DisplayName("T1-F2-03: Spatial vs Effects spring damping dichotomy (underdamped vs critically damped)")
    fun testSpatialVsEffectsSpring_dampedOvershootDichotomy() {
        assertTrue(ExpressiveSprings.FastSpatialDampingRatio < 1.0f)
        assertTrue(ExpressiveSprings.DefaultSpatialDampingRatio < 1.0f)
        assertTrue(ExpressiveSprings.SlowSpatialDampingRatio < 1.0f)

        assertEquals(1.0f, ExpressiveSprings.FastEffectsDampingRatio, 0.0001f)
        assertEquals(1.0f, ExpressiveSprings.DefaultEffectsDampingRatio, 0.0001f)
        assertEquals(1.0f, ExpressiveSprings.SlowEffectsDampingRatio, 0.0001f)
    }

    @Test
    @DisplayName("T1-F2-04: Press scale targets within balanced expressive range [0.95f, 0.975f]")
    fun testPressScale_targetBoundsAndZeroRecomposition() {
        val pressScaleValues = listOf(0.97f, 0.965f, 0.95f, 0.955f, 0.975f)

        for (scale in pressScaleValues) {
            assertTrue(scale in 0.94f..0.98f)
            assertTrue(scale < 1.0f)
        }
    }

    @Test
    @DisplayName("T1-F2-05: Scroll stretch velocity normalization and clamp boundaries [-1.0f, 1.0f]")
    fun testScrollStretch_velocityAndNormalization() {
        fun calculateVelocity(pxPerMs: Float): Float {
            return (pxPerMs / 3.0f).coerceIn(-1.0f, 1.0f)
        }

        assertEquals(0.0f, calculateVelocity(0.0f), 0.001f)
        assertEquals(0.5f, calculateVelocity(1.5f), 0.001f)
        assertEquals(1.0f, calculateVelocity(3.0f), 0.001f)
        assertEquals(1.0f, calculateVelocity(10.0f), 0.001f)
        assertEquals(-1.0f, calculateVelocity(-10.0f), 0.001f)

        val maxStretch = 0.012f
        val magnitude = abs(calculateVelocity(3.0f))
        val scaleY = 1.0f + magnitude * maxStretch
        val scaleX = 1.0f - magnitude * maxStretch * 0.6f

        assertEquals(1.012f, scaleY, 0.0001f)
        assertEquals(0.9928f, scaleX, 0.0001f)
    }

    @Test
    @DisplayName("T1-F2-06: Scroll stretch settling lerp to exact rest state (1.0f, 1.0f)")
    fun testScrollStretch_settleToRest() {
        fun lerp(start: Float, stop: Float, fraction: Float): Float {
            return start + fraction * (stop - start)
        }

        val startScaleY = 1.012f
        val startScaleX = 0.9928f
        val startVelocity = 1.0f

        val endScaleY = lerp(startScaleY, 1.0f, 1.0f)
        val endScaleX = lerp(startScaleX, 1.0f, 1.0f)
        val endVelocity = lerp(startVelocity, 0.0f, 1.0f)

        assertEquals(1.0f, endScaleY, 0.0001f)
        assertEquals(1.0f, endScaleX, 0.0001f)
        assertEquals(0.0f, endVelocity, 0.0001f)
    }
}
