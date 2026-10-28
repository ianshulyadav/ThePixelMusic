package com.unshoo.pixelmusic.presentation.components.scoped

import androidx.compose.animation.core.Spring
import com.google.common.truth.Truth.assertThat
import com.unshoo.pixelmusic.presentation.viewmodel.PlayerSheetState
import org.junit.Test

class SheetVerticalDragMathTest {

    @Test
    fun resolveVerticalSheetTargetState_highUpwardVelocity_expandsSheet() {
        val target = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.COLLAPSED,
            accumulatedDragY = 0f,
            minDragThresholdPx = 10f,
            verticalVelocity = -800f,
            velocityThreshold = 150f,
            currentFraction = 0.2f
        )
        assertThat(target).isEqualTo(PlayerSheetState.EXPANDED)
    }

    @Test
    fun resolveVerticalSheetTargetState_highDownwardVelocity_collapsesSheet() {
        val target = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.EXPANDED,
            accumulatedDragY = 5f,
            minDragThresholdPx = 10f,
            verticalVelocity = 900f,
            velocityThreshold = 150f,
            currentFraction = 0.85f
        )
        assertThat(target).isEqualTo(PlayerSheetState.COLLAPSED)
    }

    @Test
    fun resolveVerticalSheetTargetState_dragUpPastThreshold_expandsSheet() {
        val target = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.COLLAPSED,
            accumulatedDragY = -50f,
            minDragThresholdPx = 10f,
            verticalVelocity = 0f,
            velocityThreshold = 150f,
            currentFraction = 0.3f
        )
        assertThat(target).isEqualTo(PlayerSheetState.EXPANDED)
    }

    @Test
    fun resolveVerticalSheetTargetState_dragDownPastThreshold_collapsesSheet() {
        val target = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.EXPANDED,
            accumulatedDragY = 50f,
            minDragThresholdPx = 10f,
            verticalVelocity = 0f,
            velocityThreshold = 150f,
            currentFraction = 0.8f
        )
        assertThat(target).isEqualTo(PlayerSheetState.COLLAPSED)
    }

    @Test
    fun resolveVerticalSheetTargetState_expandedDraggedUpward_retainsExpanded() {
        // Rubberband past top
        val target = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.EXPANDED,
            accumulatedDragY = -40f,
            minDragThresholdPx = 10f,
            verticalVelocity = 0f,
            velocityThreshold = 150f,
            currentFraction = 1.0f
        )
        assertThat(target).isEqualTo(PlayerSheetState.EXPANDED)
    }

    @Test
    fun resolveVerticalSheetTargetState_midpointFallback() {
        val aboveMidpoint = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.COLLAPSED,
            accumulatedDragY = 0f,
            minDragThresholdPx = 100f,
            verticalVelocity = 0f,
            velocityThreshold = 150f,
            currentFraction = 0.6f
        )
        assertThat(aboveMidpoint).isEqualTo(PlayerSheetState.EXPANDED)

        val belowMidpoint = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.COLLAPSED,
            accumulatedDragY = 0f,
            minDragThresholdPx = 100f,
            verticalVelocity = 0f,
            velocityThreshold = 150f,
            currentFraction = 0.4f
        )
        assertThat(belowMidpoint).isEqualTo(PlayerSheetState.COLLAPSED)
    }

    @Test
    fun collapseSpringDampingForFraction_returnsCorrectInterpolation() {
        val start = collapseSpringDampingForFraction(0f)
        val stop = collapseSpringDampingForFraction(1f)
        assertThat(start).isEqualTo(Spring.DampingRatioNoBouncy)
        assertThat(stop).isEqualTo(Spring.DampingRatioLowBouncy)
    }

    @Test
    fun collapseInitialSquashForFraction_returnsCorrectRange() {
        val start = collapseInitialSquashForFraction(0f)
        val stop = collapseInitialSquashForFraction(1f)
        assertThat(start).isEqualTo(1.0f)
        assertThat(stop).isEqualTo(0.97f)
    }
}
