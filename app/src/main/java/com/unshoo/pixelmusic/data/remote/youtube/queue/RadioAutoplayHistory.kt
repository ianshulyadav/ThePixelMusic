package com.unshoo.pixelmusic.data.remote.youtube.queue

/**
 * Tracks the progression of an autoplay station. A station's continuation is owned by
 * [YouTubeRadioQueue]; this class chooses the next seed when that station ends.
 * Ported from PixelMusic Desktop's `AutoplayRadio` seed, sent, and pool logic.
 */
internal class RadioAutoplayHistory(initialSeed: String?) {
    private val sent = HashSet<String>()
    private val followedSeeds = HashSet<String>()
    private val seedPool = ArrayList<String>()

    init {
        if (initialSeed != null) followedSeeds += initialSeed
    }

    fun record(videoId: String): Boolean {
        if (!isVideoId(videoId) || !sent.add(videoId)) return false
        seedPool += videoId
        if (sent.size > MAX_MEMORY) trimMemory()
        return true
    }

    fun nextSeed(): String? {
        val next = seedPool.asReversed().firstOrNull { it !in followedSeeds } ?: return null
        followedSeeds += next
        return next
    }

    fun reset(seed: String) {
        sent.clear()
        followedSeeds.clear()
        seedPool.clear()
        followedSeeds += seed
    }

    fun recentVideoIds(): List<String> = seedPool.takeLast(RECENT_MEMORY)

    private fun trimMemory() {
        // Keep recent seeds available without allowing a long-running radio to grow forever.
        val recent = seedPool.takeLast(RECENT_MEMORY)
        sent.clear()
        sent.addAll(recent)
        seedPool.clear()
        seedPool.addAll(recent)
        followedSeeds.retainAll(recent.toSet())
    }

    private fun isVideoId(id: String): Boolean =
        id.length == 11 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private companion object {
        const val MAX_MEMORY = 3_000
        const val RECENT_MEMORY = 1_500
    }
}
