package com.unshoo.pixelmusic.presentation.components.blur

import android.os.Build
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import kotlin.math.roundToInt

/**
 * Cached, quantised Gaussian [RenderEffect]s for per-line lyrics blur.
 *
 * Lyrics lines animate their blur radius for 400ms whenever the active line changes. Creating a
 * new RenderEffect for every line on every frame churns native objects and invalidates every
 * layer; quantising to half-pixel steps means an animation reuses at most ~40 shared instances.
 */
object LyricsLineBlur {
    private const val STEPS_PER_PX = 2
    private const val MAX_RADIUS_PX = 48f
    private val effects = arrayOfNulls<RenderEffect>((MAX_RADIUS_PX * STEPS_PER_PX).toInt() + 1)

    /** Blur effect for [radiusPx], or null (no layer effect) for ~0 radius / API < 31. */
    fun effectFor(radiusPx: Float): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val step = (radiusPx.coerceIn(0f, MAX_RADIUS_PX) * STEPS_PER_PX).roundToInt()
        if (step <= 0) return null
        return effects[step] ?: run {
            val r = step.toFloat() / STEPS_PER_PX
            BlurEffect(r, r, TileMode.Decal).also { effects[step] = it }
        }
    }
}
