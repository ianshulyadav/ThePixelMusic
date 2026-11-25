package com.unshoo.pixelmusic.data.service.player

import com.unshoo.pixelmusic.data.model.Curve
import com.unshoo.pixelmusic.data.model.TransitionMode
import com.unshoo.pixelmusic.data.model.TransitionSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrossfadeGainsTest {
    @Test
    fun defaultEightSecondFadeRisesGentlyAndCompletes() {
        val settings = TransitionSettings(durationMs = 8_000)
        val start = crossfadeGains(0f, settings)
        val atTwoSeconds = crossfadeGains(0.25f, settings)
        val atFourSeconds = crossfadeGains(0.5f, settings)
        val end = crossfadeGains(1f, settings)

        assertEquals(1f to 0f, start)
        assertTrue(atTwoSeconds.second in 0.14f..0.15f)
        assertEquals(0.5f, atFourSeconds.first, 0.0001f)
        assertEquals(0.5f, atFourSeconds.second, 0.0001f)
        assertEquals(0f to 1f, end)
    }

    @Test
    fun configuredCurvesAreRespectedWithoutHidingIncomingAudio() {
        val overlap = TransitionSettings(
            mode = TransitionMode.OVERLAP,
            curveIn = Curve.LINEAR,
            curveOut = Curve.EXP,
        )
        assertEquals(0.9375f, crossfadeGains(0.25f, overlap).first, 0.0001f)
        assertEquals(0.25f, crossfadeGains(0.25f, overlap).second, 0.0001f)

        val fadeInOut = overlap.copy(mode = TransitionMode.FADE_IN_OUT)
        assertTrue(crossfadeGains(0.25f, fadeInOut).second > 0f)
        val smooth = overlap.copy(mode = TransitionMode.SMOOTH)
        assertTrue(crossfadeGains(0.25f, smooth).second in 0.14f..0.15f)
    }
}
