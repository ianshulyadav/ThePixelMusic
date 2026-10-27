package com.unshoo.pixelmusic.presentation.components.scoped

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Coordinates GPU-heavy rendering decisions across the player hierarchy.
 *
 * Full-screen Gaussian blur ([androidx.compose.ui.draw.blur]) is the single most
 * expensive draw operation in the player. A RenderEffect blur on a full-screen
 * layer must re-run whenever that layer is transformed or redrawn, so during
 * sheet drags/flings — when the full player, queue sheet and lyrics sheet move
 * every frame — frame budgets are blown and motion stutters.
 *
 * This state reports whether any such "heavy motion" is currently active.
 * Heavy renderers (atmosphere background, lyric line defocus) read it and swap
 * to a cheaper configuration for the duration of the gesture, restoring full
 * visual quality the instant motion settles. The value only flips at motion
 * boundaries, so consumers recompose at most twice per gesture.
 *
 * Providers are lambdas over snapshot state (Animatable.isRunning, drag flags),
 * which keeps this class stable and allocation-free during animation.
 */
@Stable
class PlayerMotionState(
    private val playerSheetMotion: () -> Boolean = { false },
    private val queueSheetMotion: () -> Boolean = { false },
    private val overlayMotion: () -> Boolean = { false }
) {
    /** True while the player sheet, queue sheet or a modal overlay is being dragged/animated. */
    val isHeavyMotionActive: Boolean
        get() = playerSheetMotion() || queueSheetMotion() || overlayMotion()
}

/**
 * Default instance reports no motion, so renderers that sit outside the player
 * tree (or are rendered before the host is wired) keep full quality.
 */
val LocalPlayerMotionState = staticCompositionLocalOf { PlayerMotionState() }
