package com.unshoo.pixelmusic.data.lossless

import com.unshoo.pixelmusic.data.addons.AddonHealthTracker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LosslessStreamResolverFailoverTest {

    @BeforeEach
    @AfterEach
    fun resetState() {
        AddonHealthTracker.clearAll()
        LosslessStreamResolver.markMonochromeUp()
        LosslessStreamResolver.clearAll()
    }

    @Test
    fun `monochrome 429 marks monochrome down and preserves step-down across repeated calls`() {
        val videoId = "xptF3xvKmxc"
        val monoUrl = "https://tracks.monochrome.st/track/195370597835161600"

        assertTrue(LosslessStreamResolver.isMonochromeUp())

        // First report (e.g. LoadErrorHandlingPolicy intercepting HTTP 429).
        val firstStepDown = LosslessStreamResolver.markCurrentSourceFailedForTrack(
            mediaId = videoId,
            reason = "HTTP 429",
            failingUri = monoUrl,
        )
        assertTrue(firstStepDown)
        assertFalse(LosslessStreamResolver.isMonochromeUp())
        assertFalse(LosslessStreamResolver.isFailedForTrack(videoId))

        // Second report from onPlayerError / failover for the same failing Monochrome URL
        // must remain idempotent and keep step-down eligible instead of collapsing all lossless sources.
        val secondStepDown = LosslessStreamResolver.markCurrentSourceFailedForTrack(
            mediaId = videoId,
            reason = "Playback error ERROR_CODE_IO_BAD_HTTP_STATUS HTTP 429",
            failingUri = monoUrl,
        )
        assertTrue(secondStepDown)
        assertFalse(LosslessStreamResolver.isFailedForTrack(videoId))
    }

    @Test
    fun `non-monochrome error without active resolved entry still fails closed cleanly`() {
        val videoId = "noActiveEntry1"

        val stepDown = LosslessStreamResolver.markCurrentSourceFailedForTrack(
            mediaId = videoId,
            reason = "Generic error",
            failingUri = null,
        )

        assertFalse(stepDown)
        assertTrue(LosslessStreamResolver.isFailedForTrack(videoId))

        LosslessStreamResolver.clearFailedForTrack(videoId)
        assertFalse(LosslessStreamResolver.isFailedForTrack(videoId))
    }

    @Test
    fun `markMonochromeUp resets rate-limit cooldown immediately`() {
        LosslessStreamResolver.markMonochromeDown(backoffMs = 120_000L)
        assertFalse(LosslessStreamResolver.isMonochromeUp())

        LosslessStreamResolver.markMonochromeUp()
        assertTrue(LosslessStreamResolver.isMonochromeUp())
    }
}
