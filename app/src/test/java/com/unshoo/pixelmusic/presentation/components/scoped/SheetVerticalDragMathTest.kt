package com.unshoo.pixelmusic.presentation.components.scoped

import com.google.common.truth.Truth.assertThat
import com.unshoo.pixelmusic.presentation.viewmodel.PlayerSheetState
import org.junit.Test

class SheetVerticalDragMathTest {

    @Test
    fun resolveVerticalSheetTargetState_highUpwardVelocity_expandsSheet() {
        val target = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.COLLAPSED,
            accumulatedDragY = -50f,
            minDragThresholdPx = 10f,
            verticalVelocity = -800f,
            velocityThreshold = 350f,
            currentFraction = 0.2f
        )
        assertThat(target).isEqualTo(PlayerSheetState.EXPANDED)
    }

    @Test
    fun resolveVerticalSheetTargetState_highDownwardVelocity_collapsesSheet() {
        val target = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.EXPANDED,
            accumulatedDragY = 50f,
            minDragThresholdPx = 10f,
            verticalVelocity = 900f,
            velocityThreshold = 350f,
            currentFraction = 0.85f
        )
        assertThat(target).isEqualTo(PlayerSheetState.COLLAPSED)
    }

    @Test
    fun resolveVerticalSheetTargetState_expandedWithMinorDragDown_retainsExpanded() {
        // Minor drag down (only down to 80% expansion) without high fling velocity
        val target = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.EXPANDED,
            accumulatedDragY = 30f,
            minDragThresholdPx = 10f,
            verticalVelocity = 50f,
            velocityThreshold = 350f,
            currentFraction = 0.80f
        )
        assertThat(target).isEqualTo(PlayerSheetState.EXPANDED)
    }

    @Test
    fun resolveVerticalSheetTargetState_expandedWithSubstantialDragDown_collapsesSheet() {
        // Dragged down past 35% (fraction 0.50 < 0.65)
        val target = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.EXPANDED,
            accumulatedDragY = 300f,
            minDragThresholdPx = 10f,
            verticalVelocity = 100f,
            velocityThreshold = 350f,
            currentFraction = 0.50f
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
            velocityThreshold = 350f,
            currentFraction = 1.0f
        )
        assertThat(target).isEqualTo(PlayerSheetState.EXPANDED)
    }

    @Test
    fun resolveVerticalSheetTargetState_collapsedMinorDragUp_retainsCollapsed() {
        // Dragged up only slightly (fraction 0.20 <= 0.35)
        val target = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.COLLAPSED,
            accumulatedDragY = -20f,
            minDragThresholdPx = 10f,
            verticalVelocity = -100f,
            velocityThreshold = 350f,
            currentFraction = 0.20f
        )
        assertThat(target).isEqualTo(PlayerSheetState.COLLAPSED)
    }

    @Test
    fun resolveVerticalSheetTargetState_collapsedSubstantialDragUp_expandsSheet() {
        // Dragged up past 35% (fraction 0.45 > 0.35)
        val target = resolveVerticalSheetTargetState(
            currentSheetContentState = PlayerSheetState.COLLAPSED,
            accumulatedDragY = -180f,
            minDragThresholdPx = 10f,
            verticalVelocity = -100f,
            velocityThreshold = 350f,
            currentFraction = 0.45f
        )
        assertThat(target).isEqualTo(PlayerSheetState.EXPANDED)
    }

    @Test
    fun collapseAnimationDurationForFraction_returnsPhysicalDurationInRange() {
        val minDuration = collapseAnimationDurationForFraction(0f)
        val maxDuration = collapseAnimationDurationForFraction(1f)
        val midDuration = collapseAnimationDurationForFraction(0.5f)

        assertThat(minDuration).isAtLeast(160)
        assertThat(maxDuration).isAtMost(250)
        assertThat(midDuration).isGreaterThan(minDuration)
        assertThat(midDuration).isLessThan(maxDuration)
    }
}

