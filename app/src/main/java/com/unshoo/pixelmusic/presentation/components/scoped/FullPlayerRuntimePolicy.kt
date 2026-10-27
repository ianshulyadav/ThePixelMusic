package com.unshoo.pixelmusic.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.unshoo.pixelmusic.presentation.viewmodel.PlayerSheetState

internal data class FullPlayerRuntimePolicy(
    val allowRealtimeUpdates: Boolean
)

/**
 * Gates high-frequency UI updates (progress bar sampling, animations) behind
 * conditions that only flip at expansion thresholds — not on every frame.
 *
 * [expansionFraction] is read inside [derivedStateOf], producing a Boolean that
 * changes only when crossing the 0.985 / 0.95 thresholds. This avoids per-frame
 * recomposition of the caller during gestures.
 *
 * [currentSheetState] and [bottomSheetOpenFraction] are used as `remember` keys
 * because they change infrequently (state transitions / queue sheet interactions).
 */
@Composable
internal fun rememberFullPlayerRuntimePolicy(
    currentSheetState: PlayerSheetState,
    expansionFraction: Animatable<Float, AnimationVector1D>,
    bottomSheetOpenFraction: Float
): FullPlayerRuntimePolicy {
    val allowRealtimeUpdates by remember(currentSheetState, bottomSheetOpenFraction) {
        derivedStateOf {
            val ef = expansionFraction.value
            val isOccluded = bottomSheetOpenFraction >= 0.08f

            // Enable live position updates once the player is >65% open.
            // The old 98.5% threshold was the cause of the seekbar "glitch" — the progress
            // bar existed in the composition but showed a stale position until the animation
            // fully settled. Starting at 65% means the seekbar receives real data while the
            // expand animation is still running, so it appears correct immediately on arrival.
            currentSheetState == PlayerSheetState.EXPANDED &&
                ef >= 0.65f &&
                !isOccluded
        }
    }

    return FullPlayerRuntimePolicy(
        allowRealtimeUpdates = allowRealtimeUpdates
    )
}
