package com.unshoo.pixelmusic.data.service.player

import com.unshoo.pixelmusic.data.model.Curve
import com.unshoo.pixelmusic.data.model.TransitionMode
import com.unshoo.pixelmusic.data.model.TransitionSettings
import com.unshoo.pixelmusic.utils.envelope

/** Volume multipliers for the two decks at a point in the selected transition. */
internal fun crossfadeGains(progress: Float, settings: TransitionSettings): Pair<Float, Float> {
    val t = progress.coerceIn(0f, 1f)
    // The incoming player is already advancing during this transition. Keep its
    // entire opening audible instead of hiding the first half of the song.
    val curveOut = if (settings.mode == TransitionMode.SMOOTH) Curve.S_CURVE else settings.curveOut
    val curveIn = if (settings.mode == TransitionMode.SMOOTH) Curve.S_CURVE else settings.curveIn
    return (1f - envelope(t, curveOut)) to envelope(t, curveIn)
}
