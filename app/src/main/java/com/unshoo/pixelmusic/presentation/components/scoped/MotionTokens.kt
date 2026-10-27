package com.unshoo.pixelmusic.presentation.components.scoped

import androidx.compose.animation.core.CubicBezierEasing

/**
 * Single source of truth for expressive sheet/player motion.
 *
 * The curves mirror the Material 3 "Emphasized" easing set:
 *  - [EmphasizedDecelerateEasing]: entrances / expands — fast launch, soft landing.
 *  - [EmphasizedAccelerateEasing]: exits / collapses — gentle lift, rapid release.
 *
 * Keeping every sheet (full player, queue, lyrics) on the same tokens makes the
 * whole player hierarchy feel like one coordinated motion system.
 */
internal object MotionTokens {
    val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val StandardEmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1f)

    // Full player sheet
    const val PLAYER_EXPAND_DURATION_MS = 320
    const val PLAYER_COLLAPSE_DURATION_MS = 260

    // Queue sheet (distance-based duration is added on top)
    const val QUEUE_EXPAND_BASE_MS = 240
    const val QUEUE_EXPAND_TRAVEL_MS = 130
    const val QUEUE_COLLAPSE_BASE_MS = 220
    const val QUEUE_COLLAPSE_TRAVEL_MS = 120

    // Lyrics sheet enter/exit
    const val LYRICS_ENTER_DURATION_MS = 260
    const val LYRICS_EXIT_DURATION_MS = 220
    const val LYRICS_FADE_IN_MS = 200
    const val LYRICS_FADE_OUT_MS = 160
}
