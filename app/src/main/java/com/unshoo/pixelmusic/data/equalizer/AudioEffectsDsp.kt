package com.unshoo.pixelmusic.data.equalizer

/**
 * The current effect settings, shared by every [EqualizerAudioProcessor] (one per ExoPlayer).
 *
 * The EQ used to run as platform audio effects on the playback session. The platform Equalizer /
 * BassBoost / Virtualizer declare "volume control", which makes AudioFlinger hand the *whole
 * session's* volume to the effect instead of applying it per track. On Samsung devices that broke
 * the system volume (still audible at 0, keys sometimes ignored) and, with both crossfade decks on
 * one session, made the incoming track follow the outgoing track's fade-out. Processing in-app
 * keeps every AudioTrack effect-free, so the system volume and each deck's own fade gain are
 * applied normally, and both decks still get the EQ during a crossfade.
 *
 * [EqualizerManager] publishes a new immutable [Params] on every change; processors read the
 * @Volatile reference from the playback thread and rebuild their filters when it changes.
 */
object AudioEffectsDsp {

    /** Center frequencies of the 10 UI bands, in Hz. */
    val BAND_FREQUENCIES_HZ = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    class Params(
        val eqEnabled: Boolean,
        /** Gain per band in dB, same order as [BAND_FREQUENCIES_HZ]. */
        val bandGainsDb: FloatArray,
        val bassBoostEnabled: Boolean,
        /** 0..1000 */
        val bassBoostStrength: Int,
        val virtualizerEnabled: Boolean,
        /** 0..1000 */
        val virtualizerStrength: Int,
        val loudnessEnabled: Boolean,
        /** Target gain in millibels, 0..1000. */
        val loudnessGainMb: Int,
    ) {
        val anyActive: Boolean =
            (eqEnabled && bandGainsDb.any { it != 0f }) ||
                (bassBoostEnabled && bassBoostStrength > 0) ||
                (virtualizerEnabled && virtualizerStrength > 0) ||
                (loudnessEnabled && loudnessGainMb > 0)
    }

    @Volatile
    var params: Params = Params(
        eqEnabled = false,
        bandGainsDb = FloatArray(BAND_FREQUENCIES_HZ.size),
        bassBoostEnabled = false,
        bassBoostStrength = 0,
        virtualizerEnabled = false,
        virtualizerStrength = 0,
        loudnessEnabled = false,
        loudnessGainMb = 0,
    )
}
