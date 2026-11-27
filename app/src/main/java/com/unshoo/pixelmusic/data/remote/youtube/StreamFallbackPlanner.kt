package com.unshoo.pixelmusic.data.remote.youtube

import com.unshoo.pixelmusic.data.preferences.StreamingAudioQuality
import java.util.concurrent.ConcurrentHashMap

/**
 * Policy and resolution-tier tracker for the playback stream fallback chain:
 * Lossless -> JioSaavn (if enabled) -> YouTube Music.
 *
 * Ensures next-song preloading (`isNextInQueue = true`) checks lossless availability
 * first (unless pinned away or explicitly configured for low data saver), then falls
 * back to JioSaavn when enabled, then YouTube Music — and prevents lower-tier
 * lookahead or inline fast-start caches from suppressing lossless/JioSaavn upgrades
 * when a track reaches the next-up slot.
 */
object StreamFallbackPlanner {

    enum class ResolutionTier(val rank: Int) {
        /** Fast lowest-quality YouTube resolve that skipped both Lossless and JioSaavn. */
        YOUTUBE_FAST_ONLY(1),

        /** Fallback resolution (JioSaavn/YouTube) where Lossless was skipped (`allowLossless=false`). */
        FALLBACK_ONLY_NO_LOSSLESS(2),

        /** Full chain ran: Lossless won OR Lossless was checked and confirmed unavailable before fallback. */
        LOSSLESS_CHECKED(3),

        /** Explicit per-song source override was honored. */
        OVERRIDE_PINNED(4),
    }

    data class FallbackInput(
        val allowLossless: Boolean,
        val losslessEnabled: Boolean,
        val saavnEnabled: Boolean,
        val isNextInQueue: Boolean,
        val effectiveQuality: StreamingAudioQuality,
        val userConfiguredQuality: StreamingAudioQuality,
        val preferLowFirst: Boolean,
        val allowLosslessPreload: Boolean,
        val isOnline: Boolean,
        val unifiedSongOverride: String?,
        val unifiedOrder: List<String>,
        val losslessFailedForTrack: Boolean,
        val losslessCheckedUnavailable: Boolean,
    )

    data class FallbackPlan(
        val tryLossless: Boolean,
        val trySaavn: Boolean,
        val youtubeBeforeSaavn: Boolean,
        val forceYoutubeOnly: Boolean,
        val forceSaavn: Boolean,
    )

    private const val LOSSLESS_UNAVAILABLE_TTL_MS = 10 * 60_000L

    private val resolutionTierByCacheKey = ConcurrentHashMap<String, ResolutionTier>()
    private val losslessUnavailableUntilMs = ConcurrentHashMap<String, Long>()

    fun computePlan(input: FallbackInput): FallbackPlan {
        val overrideId = input.unifiedSongOverride
        val forceYoutubeOverride = overrideId == "builtin:YOUTUBE"
        val forceSaavnOverride = overrideId == "builtin:JIOSAAVN"
        val hasLosslessOverride = overrideId != null && !forceYoutubeOverride && !forceSaavnOverride

        val ytIdx = input.unifiedOrder.indexOf("builtin:YOUTUBE")
        val saavnIdx = input.unifiedOrder.indexOf("builtin:JIOSAAVN")
        val youtubeBeforeSaavn = ytIdx >= 0 && saavnIdx >= 0 && ytIdx < saavnIdx
        val forceYoutubeOnly = forceYoutubeOverride || (overrideId == null && youtubeBeforeSaavn)

        val explicitLowData = input.userConfiguredQuality == StreamingAudioQuality.LOW
        val allowLosslessForNext = !explicitLowData && (
            input.allowLosslessPreload ||
                input.userConfiguredQuality == StreamingAudioQuality.HIGH ||
                input.userConfiguredQuality == StreamingAudioQuality.AUTO
            )

        val canUseLosslessByPolicy = when {
            hasLosslessOverride -> true
            !input.isOnline -> false
            input.isNextInQueue -> allowLosslessForNext
            else -> !input.preferLowFirst
        }

        val tryLossless = input.allowLossless &&
            input.losslessEnabled &&
            !forceYoutubeOverride &&
            !forceSaavnOverride &&
            canUseLosslessByPolicy &&
            (hasLosslessOverride || (!input.losslessFailedForTrack && !input.losslessCheckedUnavailable))

        val trySaavn = when {
            forceYoutubeOverride -> false
            forceSaavnOverride -> input.isOnline
            forceYoutubeOnly -> false
            !input.isOnline -> false
            !input.saavnEnabled -> false
            input.isNextInQueue -> true
            else -> !input.preferLowFirst && input.effectiveQuality != StreamingAudioQuality.LOW
        }

        return FallbackPlan(
            tryLossless = tryLossless,
            trySaavn = trySaavn,
            youtubeBeforeSaavn = youtubeBeforeSaavn,
            forceYoutubeOnly = forceYoutubeOnly,
            forceSaavn = forceSaavnOverride,
        )
    }

    fun recordCacheTier(cacheKeys: Iterable<String>, tier: ResolutionTier) {
        for (key in cacheKeys) {
            if (key.isNotBlank()) {
                resolutionTierByCacheKey[key] = tier
            }
        }
    }

    fun tierForCacheKey(cacheKey: String): ResolutionTier? = resolutionTierByCacheKey[cacheKey]

    fun markLosslessUnavailable(videoId: String, ttlMs: Long = LOSSLESS_UNAVAILABLE_TTL_MS) {
        val clean = normalizeVideoId(videoId)
        if (clean.isBlank()) return
        losslessUnavailableUntilMs[clean] = System.currentTimeMillis() + ttlMs
    }

    fun isLosslessUnavailable(videoId: String): Boolean {
        val clean = normalizeVideoId(videoId)
        if (clean.isBlank()) return false
        val until = losslessUnavailableUntilMs[clean] ?: return false
        if (System.currentTimeMillis() >= until) {
            losslessUnavailableUntilMs.remove(clean, until)
            return false
        }
        return true
    }

    /**
     * Returns true when a cached [cachedUrl] at [cachedTier] should be upgraded for the
     * requested [plan] (for example, when a lookahead or fast inline YouTube resolve was cached
     * before a next-song preload with Lossless / JioSaavn enabled).
     */
    fun needsCacheUpgrade(
        cachedTier: ResolutionTier?,
        isCachedLossless: Boolean,
        isCachedSaavn: Boolean,
        plan: FallbackPlan,
    ): Boolean {
        if (cachedTier == ResolutionTier.OVERRIDE_PINNED) return false
        if (plan.forceYoutubeOnly) return false
        if (plan.forceSaavn) return !isCachedSaavn

        if (plan.tryLossless && !isCachedLossless && cachedTier != ResolutionTier.LOSSLESS_CHECKED) {
            return true
        }
        if (plan.trySaavn && !isCachedLossless && !isCachedSaavn &&
            (cachedTier == null || cachedTier == ResolutionTier.YOUTUBE_FAST_ONLY)
        ) {
            return true
        }
        return false
    }

    fun invalidateTrack(videoId: String) {
        val clean = normalizeVideoId(videoId)
        if (clean.isBlank()) return
        losslessUnavailableUntilMs.remove(clean)
        val prefix = "${clean}_"
        resolutionTierByCacheKey.keys.removeIf { it == clean || it.startsWith(prefix) }
    }

    fun invalidateAll() {
        resolutionTierByCacheKey.clear()
        losslessUnavailableUntilMs.clear()
    }

    private fun normalizeVideoId(raw: String): String =
        raw.removePrefix("youtube://").removePrefix("youtube_").removePrefix("monochrome_")
}
