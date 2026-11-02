package com.unshoo.pixelmusic.data.addons

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks live health, rolling latency, HTTP 429 rate-limit windows, adaptive endpoint cooldowns,
 * and negative track-miss caching across all installed Addons and Monochrome mirrors.
 *
 * Ensures dead/paused endpoints (e.g., disabled Vercel deployments, cold/offline servers, or
 * rate-limited hosts) are bypassed in 0 ms rather than stalling playback resolution on every song.
 */
object AddonHealthTracker {

    data class HealthState(
        val sourceId: String,
        val avgLatencyMs: Long = 0L,
        val consecutiveFailures: Int = 0,
        val cooldownUntilMs: Long = 0L,
        val lastError: String? = null,
        val lastSuccessAtMs: Long = 0L,
    ) {
        fun isHealthy(nowMs: Long = System.currentTimeMillis()): Boolean = cooldownUntilMs <= nowMs
        val inCooldown: Boolean get() = !isHealthy()
        val ewmaLatencyMs: Long get() = avgLatencyMs
    }

    private const val MISS_CACHE_TTL_MS = 20 * 60_000L
    private const val MAX_MISS_ENTRIES = 1024

    private val states = ConcurrentHashMap<String, HealthState>()
    private val originRateLimitsUntilMs = ConcurrentHashMap<String, Long>()
    private val trackMissesUntilMs = ConcurrentHashMap<String, Long>()

    fun stateOf(sourceId: String): HealthState =
        states[sourceId] ?: HealthState(sourceId = sourceId)

    fun allStates(): Map<String, HealthState> = HashMap(states)

    fun isAvailable(sourceId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val st = states[sourceId] ?: return true
        return st.cooldownUntilMs <= nowMs
    }

    fun isOriginRateLimited(url: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val origin = originOf(url) ?: return false
        val until = originRateLimitsUntilMs[origin] ?: return false
        if (until <= nowMs) {
            originRateLimitsUntilMs.remove(origin, until)
            return false
        }
        return true
    }

    fun noteOriginRateLimit(url: String, retryAfterHeader: String?, nowMs: Long = System.currentTimeMillis()) {
        val origin = originOf(url) ?: return
        val seconds = retryAfterHeader?.trim()?.toLongOrNull()?.coerceIn(2L, 600L) ?: 30L
        originRateLimitsUntilMs[origin] = nowMs + seconds * 1_000L
    }

    fun recordSuccess(sourceId: String, latencyMs: Long, nowMs: Long = System.currentTimeMillis()) {
        states.compute(sourceId) { _, prev ->
            val priorAvg = prev?.avgLatencyMs ?: 0L
            val nextAvg = if (priorAvg <= 0L) latencyMs.coerceAtLeast(1L) else ((priorAvg * 3L + latencyMs) / 4L)
            HealthState(
                sourceId = sourceId,
                avgLatencyMs = nextAvg,
                consecutiveFailures = 0,
                cooldownUntilMs = 0L,
                lastError = null,
                lastSuccessAtMs = nowMs,
            )
        }
    }

    fun recordFailure(
        sourceId: String,
        reason: String,
        permanentHint: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        states.compute(sourceId) { _, prev ->
            val fails = (prev?.consecutiveFailures ?: 0) + 1
            val cooldownMs = when {
                permanentHint -> 10 * 60_000L // e.g., HTTP 402 DEPLOYMENT_DISABLED / 404 manifest gone
                fails == 1 -> 15_000L
                fails == 2 -> 45_000L
                fails == 3 -> 120_000L
                else -> 5 * 60_000L
            }
            HealthState(
                sourceId = sourceId,
                avgLatencyMs = prev?.avgLatencyMs ?: 0L,
                consecutiveFailures = fails,
                cooldownUntilMs = nowMs + cooldownMs,
                lastError = reason.take(180),
                lastSuccessAtMs = prev?.lastSuccessAtMs ?: 0L,
            )
        }
    }

    fun reset(sourceId: String) {
        states.remove(sourceId)
        val prefix = "$sourceId|"
        trackMissesUntilMs.keys.removeIf { it.startsWith(prefix) }
    }

    fun clearAll() {
        states.clear()
        originRateLimitsUntilMs.clear()
        trackMissesUntilMs.clear()
    }

    fun hasRecentTrackMiss(sourceId: String, trackKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = "$sourceId|${trackKey.lowercase(Locale.ROOT)}"
        val until = trackMissesUntilMs[key] ?: return false
        if (until <= nowMs) {
            trackMissesUntilMs.remove(key, until)
            return false
        }
        return true
    }

    fun recordTrackMiss(sourceId: String, trackKey: String, nowMs: Long = System.currentTimeMillis()) {
        if (trackKey.isBlank()) return
        if (trackMissesUntilMs.size >= MAX_MISS_ENTRIES) {
            trackMissesUntilMs.entries.removeIf { it.value <= nowMs }
            if (trackMissesUntilMs.size >= MAX_MISS_ENTRIES) {
                trackMissesUntilMs.clear()
            }
        }
        trackMissesUntilMs["$sourceId|${trackKey.lowercase(Locale.ROOT)}"] = nowMs + MISS_CACHE_TTL_MS
    }

    fun clearTrackMissesForMedia(trackKey: String) {
        val suffix = "|${trackKey.lowercase(Locale.ROOT)}"
        trackMissesUntilMs.keys.removeIf { it.endsWith(suffix) }
    }

    private fun originOf(url: String): String? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        val scheme = url.substringBefore("://").lowercase(Locale.ROOT)
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase(Locale.ROOT)
        return if (host.isNotBlank()) "$scheme://$host" else null
    }
}
