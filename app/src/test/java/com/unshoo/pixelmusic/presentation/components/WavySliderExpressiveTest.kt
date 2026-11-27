package com.unshoo.pixelmusic.presentation.components

import com.unshoo.pixelmusic.data.preferences.DEFAULT_SEEKER_ANIMATION_DURATION_MS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WavySliderExpressiveTest {

    @Test
    fun `song switch from mid-song or end-of-song uses smooth spring transition`() {
        assertEquals(
            SliderProgressAnimationMode.SPRING_TRANSITION,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = true,
                isTransitionGlideActive = false,
                currentProgress = 0.43f,
                targetProgress = 0.0f,
            )
        )
        assertEquals(
            SliderProgressAnimationMode.SPRING_TRANSITION,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = true,
                isTransitionGlideActive = false,
                currentProgress = 0.99f,
                targetProgress = 0.0f,
            )
        )
        // Even switching very early in a song (e.g. 0:04 -> 0:00) glides cleanly instead of snapping.
        assertEquals(
            SliderProgressAnimationMode.SPRING_TRANSITION,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = true,
                isTransitionGlideActive = false,
                currentProgress = 0.02f,
                targetProgress = 0.0f,
            )
        )
    }

    @Test
    fun `ongoing transition glide stays in spring mode while new track begins advancing`() {
        assertEquals(
            SliderProgressAnimationMode.SPRING_TRANSITION,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = false,
                isTransitionGlideActive = true,
                currentProgress = 0.20f,
                targetProgress = 0.002f,
            )
        )
    }

    @Test
    fun `same song repeat-one or previous-to-start restart uses spring transition`() {
        assertEquals(
            SliderProgressAnimationMode.SPRING_TRANSITION,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = false,
                isTransitionGlideActive = false,
                currentProgress = 0.30f,
                targetProgress = 0.0f,
            )
        )
    }

    @Test
    fun `steady forward playback ticks keep linear or sub-pixel snap behavior`() {
        assertEquals(
            SliderProgressAnimationMode.LINEAR_TICK,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = false,
                isTransitionGlideActive = false,
                currentProgress = 0.250f,
                targetProgress = 0.256f,
            )
        )
        assertEquals(
            SliderProgressAnimationMode.SNAP,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = false,
                isTransitionGlideActive = false,
                currentProgress = 0.2500f,
                targetProgress = 0.2510f,
            )
        )
    }

    @Test
    fun `user drag, disabled slider, or performance mode snaps immediately`() {
        assertEquals(
            SliderProgressAnimationMode.SNAP,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = true,
                performanceModeEnabled = false,
                keyChanged = true,
                isTransitionGlideActive = true,
                currentProgress = 0.40f,
                targetProgress = 0.65f,
            )
        )
        assertEquals(
            SliderProgressAnimationMode.SNAP,
            resolveSliderProgressAnimationMode(
                enabled = false,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = true,
                isTransitionGlideActive = false,
                currentProgress = 0.40f,
                targetProgress = 0.0f,
            )
        )
        assertEquals(
            SliderProgressAnimationMode.SNAP,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = true,
                keyChanged = true,
                isTransitionGlideActive = false,
                currentProgress = 0.40f,
                targetProgress = 0.0f,
            )
        )
    }

    @Test
    fun `disabled seeker animation snaps on song switches while preserving forward ticks`() {
        assertEquals(
            SliderProgressAnimationMode.SNAP,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = true,
                isTransitionGlideActive = false,
                currentProgress = 0.43f,
                targetProgress = 0.0f,
                seekerAnimationEnabled = false,
            )
        )
        assertEquals(
            SliderProgressAnimationMode.SNAP,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = false,
                isTransitionGlideActive = false,
                currentProgress = 0.30f,
                targetProgress = 0.0f,
                seekerAnimationEnabled = false,
            )
        )
        assertEquals(
            SliderProgressAnimationMode.LINEAR_TICK,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = false,
                isTransitionGlideActive = false,
                currentProgress = 0.250f,
                targetProgress = 0.256f,
                seekerAnimationEnabled = false,
            )
        )
    }

    @Test
    fun `custom duration selects timed transition while untouched default keeps spring transition`() {
        assertEquals(
            SliderProgressAnimationMode.SPRING_TRANSITION,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = true,
                isTransitionGlideActive = false,
                currentProgress = 0.60f,
                targetProgress = 0.0f,
                seekerAnimationEnabled = true,
                seekerAnimationDurationMs = DEFAULT_SEEKER_ANIMATION_DURATION_MS,
            )
        )
        assertEquals(
            SliderProgressAnimationMode.TIMED_TRANSITION,
            resolveSliderProgressAnimationMode(
                enabled = true,
                isInteracting = false,
                performanceModeEnabled = false,
                keyChanged = true,
                isTransitionGlideActive = false,
                currentProgress = 0.60f,
                targetProgress = 0.0f,
                seekerAnimationEnabled = true,
                seekerAnimationDurationMs = 750,
            )
        )
    }
}
