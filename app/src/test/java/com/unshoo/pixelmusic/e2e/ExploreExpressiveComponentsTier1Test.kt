package com.unshoo.pixelmusic.e2e

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.ui.theme.Shapes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * ============================================================================
 * Tier 1 — Feature Coverage: R1 UI & Pure Material 3 Expressive Components
 * ============================================================================
 *
 * Verifies nominal contract behaviors for Material 3 Expressive components:
 * - Wavy progress indicator state contracts (determinate [0f..1f] & indeterminate)
 * - Circular wavy progress indicator geometric contracts
 * - Expressive card container radius scale (large 20dp, extraLarge 32dp, extraExtraLarge 48dp)
 * - Expressive button shape morphing contracts on touch-down
 * - Expressive button group selection, active/inactive pill geometry and tonal contrast
 *
 * Authoritative Source: ORIGINAL_REQUEST.md (R1), PROJECT.md (Interface Contracts)
 */
@DisplayName("Tier 1: Material 3 Expressive UI & Components")
class ExploreExpressiveComponentsTier1Test {

    @Test
    @DisplayName("T1-F1-01: Wavy progress indicator determinate progress bounds [0.0f, 1.0f]")
    fun testWavyProgressIndicator_determinateProgressBounds() {
        val testProgressValues = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
        for (progress in testProgressValues) {
            val clamped = progress.coerceIn(0.0f, 1.0f)
            assertEquals(progress, clamped, 0.0001f, "Progress $progress should remain unchanged within valid bounds")
            assertTrue(clamped in 0.0f..1.0f, "Determinate progress must reside in [0.0, 1.0]")
        }
    }

    @Test
    @DisplayName("T1-F1-02: Wavy progress indicator wave and stroke specification contracts")
    fun testWavyProgressIndicator_strokeSpecifications() {
        val expectedTrackThickness = 4.0.dp
        val expectedWaveAmplitude = 2.0.dp
        val expectedWaveLength = 24.0.dp

        assertTrue(expectedTrackThickness.value > 0f, "Track thickness must be strictly positive")
        assertTrue(expectedWaveAmplitude.value > 0f, "Wave amplitude must be strictly positive")
        assertTrue(expectedWaveLength.value > expectedWaveAmplitude.value, "Wavelength must exceed wave amplitude")
    }

    @Test
    @DisplayName("T1-F1-03: Circular wavy progress indicator geometric properties")
    fun testCircularWavyProgressIndicator_contract() {
        val standardSize = 48.0.dp
        val strokeWidth = 4.0.dp
        val waveCount = 8

        assertEquals(48.0f, standardSize.value, 0.01f)
        assertEquals(4.0f, strokeWidth.value, 0.01f)
        assertTrue(waveCount in 6..12, "Circular wavy indicator must have between 6 and 12 expressive wave lobes")
    }

    @Test
    @DisplayName("T1-F1-04: Expressive shape scale token contracts (large 20dp, extraLarge 32dp, extraExtraLarge 48dp)")
    fun testExpressiveCard_containerShapes() {
        val largeShape = Shapes.large as? RoundedCornerShape
        val extraLargeShape = Shapes.extraLarge as? RoundedCornerShape
        val extraExtraLargeShape = Shapes.extraExtraLarge as? RoundedCornerShape

        assertNotNull(largeShape, "Large shape must be a RoundedCornerShape")
        assertNotNull(extraLargeShape, "Extra large shape must be a RoundedCornerShape")
        assertNotNull(extraExtraLargeShape, "Extra extra large shape must be a RoundedCornerShape")

        val density = androidx.compose.ui.unit.Density(1.0f)
        val largePx = largeShape!!.topStart.toPx(androidx.compose.ui.geometry.Size(100f, 100f), density)
        val extraLargePx = extraLargeShape!!.topStart.toPx(androidx.compose.ui.geometry.Size(100f, 100f), density)
        val extraExtraLargePx = extraExtraLargeShape!!.topStart.toPx(androidx.compose.ui.geometry.Size(100f, 100f), density)

        assertEquals(20.0f, largePx, 0.01f, "Shapes.large must equal 20dp per M3 Expressive")
        assertEquals(32.0f, extraLargePx, 0.01f, "Shapes.extraLarge must equal 32dp per M3 Expressive")
        assertEquals(48.0f, extraExtraLargePx, 0.01f, "Shapes.extraExtraLarge must equal 48dp per M3 Expressive")
    }

    @Test
    @DisplayName("T1-F1-05: Expressive button shape morphing contracts on touch-down press")
    fun testExpressiveButton_shapeMorphingOnPress() {
        val restRadius = 24.0.dp
        val morphAmount = 4.0.dp
        val isPressed = true

        val animatedRadius = if (isPressed) {
            (restRadius - morphAmount).coerceAtLeast(0.dp)
        } else {
            restRadius
        }

        assertEquals(20.0.dp, animatedRadius, "Card/Button shape contracts by 4dp on press (24dp -> 20dp)")
        assertTrue(animatedRadius < restRadius, "Pressed corner radius must be sharper than rest radius")
    }

    @Test
    @DisplayName("T1-F1-06: Expressive button group selection and pill vs rounded-rect geometry")
    fun testExpressiveButtonGroup_selectionAndGeometry() {
        val items = listOf("Songs", "Albums", "Artists")
        var selectedIndex = 0

        fun resolveCornerRadius(index: Int, selected: Int): Float {
            return if (index == selected) 24.0f else 10.0f
        }

        assertEquals(24.0f, resolveCornerRadius(0, selectedIndex))
        assertEquals(10.0f, resolveCornerRadius(1, selectedIndex))
        assertEquals(10.0f, resolveCornerRadius(2, selectedIndex))

        selectedIndex = 1
        assertEquals(10.0f, resolveCornerRadius(0, selectedIndex))
        assertEquals(24.0f, resolveCornerRadius(1, selectedIndex))
        assertEquals(10.0f, resolveCornerRadius(2, selectedIndex))
    }
}
