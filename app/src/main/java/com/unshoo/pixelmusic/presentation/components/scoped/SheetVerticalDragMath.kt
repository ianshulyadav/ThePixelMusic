package com.unshoo.pixelmusic.presentation.components.scoped

import androidx.compose.ui.util.lerp
import com.unshoo.pixelmusic.presentation.viewmodel.PlayerSheetState
import kotlin.math.abs

internal data class SheetVerticalDragFrame(
    val translationY: Float,
    val expansionFraction: Float
)

internal fun computeSheetVerticalDragFrame(
    currentTranslationY: Float,
    dragAmount: Float,
    expandedY: Float,
    collapsedY: Float,
    miniHeightPx: Float,
    initialFractionOnDragStart: Float,
    initialYOnDragStart: Float
): SheetVerticalDragFrame {
    val newY = (currentTranslationY + dragAmount)
        .coerceIn(
            expandedY - miniHeightPx * 0.2f,
            collapsedY + miniHeightPx * 0.2f
        )
    val denominator = (collapsedY - expandedY).coerceAtLeast(1f)
    val dragRatio = (initialYOnDragStart - newY) / denominator
    val newFraction = (initialFractionOnDragStart + dragRatio).coerceIn(0f, 1f)
    return SheetVerticalDragFrame(
        translationY = newY,
        expansionFraction = newFraction
    )
}

internal fun resolveVerticalSheetTargetState(
    currentSheetContentState: PlayerSheetState,
    accumulatedDragY: Float,
    minDragThresholdPx: Float,
    verticalVelocity: Float,
    velocityThreshold: Float,
    currentFraction: Float
): PlayerSheetState {
    return when {
        // High-velocity fling dictates direction immediately
        abs(verticalVelocity) > velocityThreshold ->
            if (verticalVelocity < 0f) PlayerSheetState.EXPANDED else PlayerSheetState.COLLAPSED

        // If user was expanded and dragged up into overscroll/rubberband past top edge
        currentSheetContentState == PlayerSheetState.EXPANDED && accumulatedDragY <= 0f ->
            PlayerSheetState.EXPANDED

        // Positional settling with directional hysteresis when released with low velocity:
        // From EXPANDED: must drag past 35% down (fraction < 0.65f) to collapse; otherwise snaps back
        currentSheetContentState == PlayerSheetState.EXPANDED ->
            if (currentFraction < 0.65f) PlayerSheetState.COLLAPSED else PlayerSheetState.EXPANDED

        // From COLLAPSED: must pull up past 35% (fraction > 0.35f) to expand; otherwise settles back
        else ->
            if (currentFraction > 0.35f) PlayerSheetState.EXPANDED else PlayerSheetState.COLLAPSED
    }
}

internal fun collapseAnimationDurationForFraction(currentFraction: Float): Int {
    // Shorter, deterministic close animations feel smoother on midrange devices than
    // low-stiffness springs, especially while lists/pages are still settling after scroll.
    return lerp(170f, 235f, currentFraction.coerceIn(0f, 1f)).toInt()
}

internal fun collapseInitialSquashForFraction(currentFraction: Float): Float {
    // Keep the old "squish" extremely subtle. Large/bouncy scale changes are visually
    // expensive and made dismiss look laggy on low-end GPUs.
    return lerp(1.0f, 0.992f, currentFraction.coerceIn(0f, 1f))
}
