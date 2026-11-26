package com.unshoo.pixelmusic.data.equalizer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the equalizer / bass boost / virtualizer / loudness settings and publishes them to the
 * in-app [EqualizerAudioProcessor]s (one inside each ExoPlayer) via [AudioEffectsDsp].
 *
 * This no longer creates platform android.media.audiofx effects on the audio session: those
 * effects take over the session's volume control (see [AudioEffectsDsp]), which broke the system
 * volume and per-deck crossfade gains, and they lost their settings on output-device changes.
 * The session-attach methods are kept so callers don't change; they only re-publish settings.
 */
@Singleton
class EqualizerManager @Inject constructor() {

    companion object {
        private const val TAG = "EqualizerManager"
        private const val NUM_BANDS = 10
        private const val MIN_LEVEL = -15
        private const val MAX_LEVEL = 15
        private const val MAX_LOUDNESS_GAIN_MB = 1000
    }

    /** Effects are processed in-app, so they are always "attached" to whatever is playing. */
    val isAttached: Boolean
        get() = true

    val hasAnyEnabledEffects: Boolean
        get() = _isEnabled.value ||
            _bassBoostEnabled.value ||
            _virtualizerEnabled.value ||
            _loudnessEnhancerEnabled.value

    // Normalized band levels (-15 to +15 for UI); 1 step = 1 dB.
    private val _bandLevels = MutableStateFlow(List(NUM_BANDS) { 0 })
    val bandLevels: StateFlow<List<Int>> = _bandLevels.asStateFlow()

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _currentPresetName = MutableStateFlow("flat")
    val currentPresetName: StateFlow<String> = _currentPresetName.asStateFlow()

    private val _bassBoostEnabled = MutableStateFlow(false)
    val bassBoostEnabled: StateFlow<Boolean> = _bassBoostEnabled.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow(0)
    val bassBoostStrength: StateFlow<Int> = _bassBoostStrength.asStateFlow()

    private val _virtualizerEnabled = MutableStateFlow(false)
    val virtualizerEnabled: StateFlow<Boolean> = _virtualizerEnabled.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow(0)
    val virtualizerStrength: StateFlow<Int> = _virtualizerStrength.asStateFlow()

    private val _loudnessEnhancerEnabled = MutableStateFlow(false)
    val loudnessEnhancerEnabled: StateFlow<Boolean> = _loudnessEnhancerEnabled.asStateFlow()

    private val _loudnessEnhancerStrength = MutableStateFlow(0)
    val loudnessEnhancerStrength: StateFlow<Int> = _loudnessEnhancerStrength.asStateFlow()

    /** Pushes the current settings to every player's audio processor. */
    private fun publish() {
        val levels = _bandLevels.value
        AudioEffectsDsp.params = AudioEffectsDsp.Params(
            eqEnabled = _isEnabled.value,
            bandGainsDb = FloatArray(NUM_BANDS) { i ->
                levels.getOrElse(i) { 0 }.coerceIn(MIN_LEVEL, MAX_LEVEL).toFloat()
            },
            bassBoostEnabled = _bassBoostEnabled.value,
            bassBoostStrength = _bassBoostStrength.value.coerceIn(0, 1000),
            virtualizerEnabled = _virtualizerEnabled.value,
            virtualizerStrength = _virtualizerStrength.value.coerceIn(0, 1000),
            loudnessEnabled = _loudnessEnhancerEnabled.value,
            loudnessGainMb = _loudnessEnhancerStrength.value.coerceIn(0, MAX_LOUDNESS_GAIN_MB),
        )
    }

    /** Kept for callers; effects follow the players automatically, so this only re-publishes. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun attachToAudioSession(audioSessionId: Int) {
        publish()
    }

    suspend fun attachToAudioSessionIfNeeded(audioSessionId: Int) {
        attachToAudioSession(audioSessionId)
    }

    /** Output-device changes no longer reset anything; re-publish for good measure. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun reattachAfterRouteChange(audioSessionId: Int) {
        publish()
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        Timber.tag(TAG).d("Equalizer enabled: $enabled")
        publish()
    }

    /**
     * Sets the level for a specific band.
     * @param bandIndex 0-9 for the 10 bands
     * @param level -15 to +15 (dB)
     */
    fun setBandLevel(bandIndex: Int, level: Int) {
        if (bandIndex !in 0 until NUM_BANDS) return
        val newLevels = _bandLevels.value.toMutableList()
        newLevels[bandIndex] = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        _bandLevels.value = newLevels
        // Switch to custom preset when manually adjusting
        _currentPresetName.value = "custom"
        publish()
    }

    fun applyPreset(preset: EqualizerPreset) {
        _currentPresetName.value = preset.name
        _bandLevels.value = preset.bandLevels
        publish()
        Timber.tag(TAG).d("Applied preset: ${preset.displayName}")
    }

    fun setBassBoostEnabled(enabled: Boolean) {
        _bassBoostEnabled.value = enabled
        publish()
    }

    /** Sets bass boost strength (0-1000). */
    fun setBassBoostStrength(strength: Int) {
        _bassBoostStrength.value = strength.coerceIn(0, 1000)
        publish()
    }

    fun setVirtualizerEnabled(enabled: Boolean) {
        _virtualizerEnabled.value = enabled
        publish()
    }

    /** Sets virtualizer (stereo width) strength (0-1000). */
    fun setVirtualizerStrength(strength: Int) {
        _virtualizerStrength.value = strength.coerceIn(0, 1000)
        publish()
    }

    fun setLoudnessEnhancerEnabled(enabled: Boolean) {
        _loudnessEnhancerEnabled.value = enabled
        publish()
    }

    /** Sets loudness gain in mB, 0 to 1000 (10 dB). */
    fun setLoudnessEnhancerStrength(strength: Int) {
        _loudnessEnhancerStrength.value = strength.coerceIn(0, MAX_LOUDNESS_GAIN_MB)
        publish()
    }

    /** Restores equalizer state from saved preferences. */
    fun restoreState(
        enabled: Boolean,
        presetName: String,
        customBands: List<Int>,
        bassBoostEnabled: Boolean,
        bassBoostStrength: Int,
        virtualizerEnabled: Boolean,
        virtualizerStrength: Int,
        loudnessEnabled: Boolean,
        loudnessStrength: Int
    ) {
        _isEnabled.value = enabled
        _bassBoostEnabled.value = bassBoostEnabled
        _bassBoostStrength.value = bassBoostStrength.coerceIn(0, 1000)
        _virtualizerEnabled.value = virtualizerEnabled
        _virtualizerStrength.value = virtualizerStrength.coerceIn(0, 1000)
        _loudnessEnhancerEnabled.value = loudnessEnabled
        _loudnessEnhancerStrength.value = loudnessStrength.coerceIn(0, MAX_LOUDNESS_GAIN_MB)

        val preset = if (presetName == "custom") {
            EqualizerPreset.custom(customBands)
        } else {
            EqualizerPreset.fromName(presetName)
        }
        _currentPresetName.value = preset.name
        _bandLevels.value = preset.bandLevels
        publish()
    }

    /** Center frequencies of the bands, in Hz. */
    fun getBandFrequencies(): List<Int> = AudioEffectsDsp.BAND_FREQUENCIES_HZ.toList()

    // All effects are implemented in-app, so they are available on every device.
    fun isBassBoostSupported(): Boolean = true

    fun isVirtualizerSupported(): Boolean = true

    fun isLoudnessEnhancerSupported(): Boolean = true

    /** Nothing to release: the processors live inside the players. */
    fun release() {
        Timber.tag(TAG).d("release(): no platform effects to free")
    }
}
