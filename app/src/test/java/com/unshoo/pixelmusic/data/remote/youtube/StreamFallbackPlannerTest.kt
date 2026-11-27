package com.unshoo.pixelmusic.data.remote.youtube

import androidx.datastore.preferences.core.emptyPreferences
import com.unshoo.pixelmusic.data.addons.AddonManager
import com.unshoo.pixelmusic.data.addons.UnifiedSourceId
import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceType
import com.unshoo.pixelmusic.data.preferences.StreamingAudioQuality
import com.unshoo.pixelmusic.data.remote.youtube.StreamFallbackPlanner.FallbackInput
import com.unshoo.pixelmusic.data.remote.youtube.StreamFallbackPlanner.ResolutionTier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StreamFallbackPlannerTest {

    @BeforeEach
    fun setUp() {
        StreamFallbackPlanner.invalidateAll()
    }

    @AfterEach
    fun tearDown() {
        StreamFallbackPlanner.invalidateAll()
    }

    private fun baseInput(
        allowLossless: Boolean = true,
        losslessEnabled: Boolean = true,
        saavnEnabled: Boolean = true,
        isNextInQueue: Boolean = true,
        effectiveQuality: StreamingAudioQuality = StreamingAudioQuality.HIGH,
        userConfiguredQuality: StreamingAudioQuality = StreamingAudioQuality.AUTO,
        preferLowFirst: Boolean = false,
        allowLosslessPreload: Boolean = true,
        isOnline: Boolean = true,
        unifiedSongOverride: String? = null,
        unifiedOrder: List<String> = UnifiedSourceId.DEFAULT_BUILTIN_ORDER,
        losslessFailedForTrack: Boolean = false,
        losslessCheckedUnavailable: Boolean = false,
    ) = FallbackInput(
        allowLossless = allowLossless,
        losslessEnabled = losslessEnabled,
        saavnEnabled = saavnEnabled,
        isNextInQueue = isNextInQueue,
        effectiveQuality = effectiveQuality,
        userConfiguredQuality = userConfiguredQuality,
        preferLowFirst = preferLowFirst,
        allowLosslessPreload = allowLosslessPreload,
        isOnline = isOnline,
        unifiedSongOverride = unifiedSongOverride,
        unifiedOrder = unifiedOrder,
        losslessFailedForTrack = losslessFailedForTrack,
        losslessCheckedUnavailable = losslessCheckedUnavailable,
    )

    @Test
    fun `next-song preload under AUTO mobile still checks lossless then JioSaavn when enabled`() {
        val plan = StreamFallbackPlanner.computePlan(
            baseInput(
                effectiveQuality = StreamingAudioQuality.LOW,
                userConfiguredQuality = StreamingAudioQuality.AUTO,
                preferLowFirst = true,
                allowLosslessPreload = false,
                saavnEnabled = true,
                isNextInQueue = true,
            )
        )
        assertTrue(plan.tryLossless, "Next-song preload should check lossless availability under AUTO mobile")
        assertTrue(plan.trySaavn, "Next-song preload should fall back to JioSaavn when enabled")
        assertFalse(plan.forceYoutubeOnly)
    }

    @Test
    fun `next-song preload skips JioSaavn when JioSaavn is disabled`() {
        val plan = StreamFallbackPlanner.computePlan(
            baseInput(
                saavnEnabled = false,
                isNextInQueue = true,
            )
        )
        assertTrue(plan.tryLossless)
        assertFalse(plan.trySaavn, "JioSaavn must stay skipped when disabled")
        assertFalse(plan.forceYoutubeOnly)
    }

    @Test
    fun `jioSaavn is disabled by default when preference key is unset`() {
        assertFalse(AddonManager.isBuiltInEnabled(AudioSourceType.JIOSAAVN, emptyPreferences()))
    }

    @Test
    fun `explicit LOW quality skips lossless on next-song preload unless overridden`() {
        val normalLow = StreamFallbackPlanner.computePlan(
            baseInput(
                effectiveQuality = StreamingAudioQuality.LOW,
                userConfiguredQuality = StreamingAudioQuality.LOW,
                preferLowFirst = true,
                allowLosslessPreload = false,
                isNextInQueue = true,
            )
        )
        assertFalse(normalLow.tryLossless)
        assertTrue(normalLow.trySaavn, "Enabled JioSaavn remains available as fallback for preloaded next song")

        val overriddenLow = StreamFallbackPlanner.computePlan(
            baseInput(
                effectiveQuality = StreamingAudioQuality.LOW,
                userConfiguredQuality = StreamingAudioQuality.LOW,
                preferLowFirst = true,
                allowLosslessPreload = false,
                isNextInQueue = true,
                unifiedSongOverride = "builtin:TIDAL",
            )
        )
        assertTrue(overriddenLow.tryLossless)
    }

    @Test
    fun `per-song overrides and unified order control lossless and jioSaavn routing`() {
        val ytPinned = StreamFallbackPlanner.computePlan(
            baseInput(unifiedSongOverride = "builtin:YOUTUBE")
        )
        assertFalse(ytPinned.tryLossless)
        assertFalse(ytPinned.trySaavn)
        assertTrue(ytPinned.forceYoutubeOnly)

        val saavnPinned = StreamFallbackPlanner.computePlan(
            baseInput(
                unifiedSongOverride = "builtin:JIOSAAVN",
                preferLowFirst = true,
                saavnEnabled = false,
            )
        )
        assertFalse(saavnPinned.tryLossless)
        assertTrue(saavnPinned.forceSaavn)
        assertTrue(saavnPinned.trySaavn)

        val ytBeforeSaavnOrder = listOf(
            "builtin:MONOCHROME",
            "builtin:YOUTUBE",
            "builtin:JIOSAAVN",
        )
        val orderedPlan = StreamFallbackPlanner.computePlan(
            baseInput(unifiedOrder = ytBeforeSaavnOrder)
        )
        assertTrue(orderedPlan.tryLossless)
        assertTrue(orderedPlan.youtubeBeforeSaavn)
        assertTrue(orderedPlan.forceYoutubeOnly)
        assertFalse(orderedPlan.trySaavn)
    }

    @Test
    fun `cached lookahead non-lossless URL upgrades when track becomes immediate next song`() {
        val nextPreloadPlan = StreamFallbackPlanner.computePlan(
            baseInput(
                losslessEnabled = true,
                saavnEnabled = true,
                isNextInQueue = true,
            )
        )

        // Previously cached at lookahead slot (allowLossless=false) as YouTube URL
        assertTrue(
            StreamFallbackPlanner.needsCacheUpgrade(
                cachedTier = ResolutionTier.FALLBACK_ONLY_NO_LOSSLESS,
                isCachedLossless = false,
                isCachedSaavn = false,
                plan = nextPreloadPlan,
            )
        )

        // Previously cached in fast inline AUTO mode (skipped both lossless & JioSaavn)
        assertTrue(
            StreamFallbackPlanner.needsCacheUpgrade(
                cachedTier = ResolutionTier.YOUTUBE_FAST_ONLY,
                isCachedLossless = false,
                isCachedSaavn = false,
                plan = nextPreloadPlan,
            )
        )

        // Already checked lossless and confirmed unavailable before falling back
        assertFalse(
            StreamFallbackPlanner.needsCacheUpgrade(
                cachedTier = ResolutionTier.LOSSLESS_CHECKED,
                isCachedLossless = false,
                isCachedSaavn = true,
                plan = nextPreloadPlan,
            )
        )

        // Pinned override must never be overridden by preload upgrade
        assertFalse(
            StreamFallbackPlanner.needsCacheUpgrade(
                cachedTier = ResolutionTier.OVERRIDE_PINNED,
                isCachedLossless = false,
                isCachedSaavn = false,
                plan = nextPreloadPlan,
            )
        )
    }

    @Test
    fun `lossless unavailable state prevents repeat probes until invalidated`() {
        val videoId = "youtube://dQw4w9WgXcQ"
        assertFalse(StreamFallbackPlanner.isLosslessUnavailable(videoId))

        StreamFallbackPlanner.markLosslessUnavailable(videoId, ttlMs = 60_000L)
        assertTrue(StreamFallbackPlanner.isLosslessUnavailable("dQw4w9WgXcQ"))

        StreamFallbackPlanner.recordCacheTier(listOf("dQw4w9WgXcQ_high"), ResolutionTier.LOSSLESS_CHECKED)
        assertEquals(ResolutionTier.LOSSLESS_CHECKED, StreamFallbackPlanner.tierForCacheKey("dQw4w9WgXcQ_high"))

        StreamFallbackPlanner.invalidateTrack("dQw4w9WgXcQ")
        assertFalse(StreamFallbackPlanner.isLosslessUnavailable("dQw4w9WgXcQ"))
        assertEquals(null, StreamFallbackPlanner.tierForCacheKey("dQw4w9WgXcQ_high"))
    }
}
