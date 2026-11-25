@file:Suppress("DEPRECATION")
package com.unshoo.pixelmusic.data.equalizer

import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Android's built-in audio effects (Equalizer, BassBoost, Virtualizer).
 * Attaches to ExoPlayer's audio session ID for real-time audio processing.
 * 
 * Thread-safe: All effect operations run on the main thread.
 * Crossfade compatible: Effects are attached to the audio session, not the player instance.
 */
@Suppress("DEPRECATION")
@Singleton
class EqualizerManager @Inject constructor() {
    
    companion object {
        private const val TAG = "EqualizerManager"
        private const val NUM_BANDS = 10
        private const val MIN_LEVEL = -15
        private const val MAX_LEVEL = 15
        private const val MAX_LOUDNESS_GAIN_MB = 1000
    }
    
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var currentAudioSessionId: Int = 0

    val isAttached: Boolean
        get() = equalizer != null && currentAudioSessionId != 0

    val hasAnyEnabledEffects: Boolean
        get() = _isEnabled.value ||
            _bassBoostEnabled.value ||
            _virtualizerEnabled.value ||
            _loudnessEnhancerEnabled.value
    
    // Normalized band levels (-15 to +15 for UI)
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
    
    // Actual millibel range from the device's equalizer
    private var minEqLevel: Short = -1500
    private var maxEqLevel: Short = 1500

    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null

    // Global device capabilities (Checking existence of effect UUIDs)
    private var isBassBoostSupportedGlobal = false
    private var isVirtualizerSupportedGlobal = false
    
    init {
        checkDeviceSupport()
    }
    
    private fun checkDeviceSupport() {
        try {
            val effects = android.media.audiofx.AudioEffect.queryEffects()
            isBassBoostSupportedGlobal = effects.any { it.type == android.media.audiofx.AudioEffect.EFFECT_TYPE_BASS_BOOST }
            isVirtualizerSupportedGlobal = effects.any { it.type == android.media.audiofx.AudioEffect.EFFECT_TYPE_VIRTUALIZER }
            Timber.tag(TAG).d("Global Support Check - BassBoost: $isBassBoostSupportedGlobal, Virtualizer: $isVirtualizerSupportedGlobal")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to query global audio effects")
        }
    }

    private fun markBassBoostUnavailable(reason: String) {
        bassBoost?.runCatching { release() }
        bassBoost = null
        Timber.tag(TAG).w("BassBoost unavailable for current session: %s", reason)
    }

    private fun markVirtualizerUnavailable(reason: String) {
        virtualizer?.runCatching { release() }
        virtualizer = null
        Timber.tag(TAG).w("Virtualizer unavailable for current session: %s", reason)
    }

    /**
     * Attaches the equalizer to an audio session ID.
     * Call this when the player is created or swapped during crossfade.
     */
    suspend fun attachToAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) {
            Timber.tag(TAG).w("Invalid audio session ID: 0")
            return
        }
        
        if (currentAudioSessionId == audioSessionId && equalizer != null) {
            Timber.tag(TAG).d("Already attached to session $audioSessionId")
            return
        }
        if (companionSessionId == audioSessionId) {
            promoteCompanionSession(audioSessionId)
            return
        }
        
        Timber.tag(TAG).d("Attaching to audio session: $audioSessionId")
        release()
        
        try {
            // Initialize Equalizer
            equalizer = try {
                Equalizer(0, audioSessionId).apply {
                    minEqLevel = bandLevelRange[0]
                    maxEqLevel = bandLevelRange[1]
                    enabled = _isEnabled.value
                }
            } catch (e: Exception) {
                // A session can be published before AudioTrack exists. Keep the user's
                // settings intact so playback can retry after the sink starts.
                Timber.tag(TAG).w(
                    e,
                    "Audio effects unavailable for session %d; will retry during playback.",
                    audioSessionId
                )
                release()
                return
            }
            
            // Apply the user's bands immediately: the retries below can take >1 s, and the
            // new session used to play flat (EQ "gone") for that whole time.
            applyBandLevels(_bandLevels.value)

            // Retry loop for effects that might fail initially
            val maxRetries = 3
            var retryCount = 0
            
            while (bassBoost == null && retryCount < maxRetries) {
                try {
                    bassBoost = BassBoost(0, audioSessionId).apply {
                        enabled = _bassBoostEnabled.value
                        if (strengthSupported) {
                            setStrength(_bassBoostStrength.value.toShort())
                        }
                    }
                    if (bassBoost != null) Timber.tag(TAG).d("BassBoost initialized on attempt ${retryCount + 1}")
                } catch (e: Exception) {
                    Timber.tag(TAG).w("BassBoost init failed (attempt ${retryCount + 1}): ${e.message}")
                    if (retryCount < maxRetries - 1) kotlinx.coroutines.delay(300)
                }
                retryCount++
            }
            if (bassBoost == null) {
                markBassBoostUnavailable("No effect engine was created for audio session $audioSessionId after $maxRetries attempts")
            }
            
            retryCount = 0
            while (virtualizer == null && retryCount < maxRetries) {
                 try {
                    virtualizer = Virtualizer(0, audioSessionId).apply {
                        enabled = _virtualizerEnabled.value
                        if (strengthSupported) {
                            setStrength(_virtualizerStrength.value.toShort())
                        }
                    }
                    if (virtualizer != null) Timber.tag(TAG).d("Virtualizer initialized on attempt ${retryCount + 1}")
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Virtualizer init failed (attempt ${retryCount + 1}): ${e.message}")
                    if (retryCount < maxRetries - 1) kotlinx.coroutines.delay(300)
                }
                retryCount++
            }
            if (virtualizer == null) {
                markVirtualizerUnavailable("No effect engine was created for audio session $audioSessionId after $maxRetries attempts")
            }

            // Initialize Loudness Enhancer
            loudnessEnhancer = try {
                android.media.audiofx.LoudnessEnhancer(audioSessionId).apply {
                    setTargetGain(_loudnessEnhancerStrength.value.coerceIn(0, MAX_LOUDNESS_GAIN_MB))
                    enabled = _loudnessEnhancerEnabled.value
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "LoudnessEnhancer not supported on this device")
                null
            }
            
            currentAudioSessionId = audioSessionId
            
            // Apply current band levels with proper mapping
            val deviceBandCount = equalizer?.numberOfBands?.toInt() ?: 0
            Timber.tag(TAG).d("Device supports $deviceBandCount bands, UI has ${_bandLevels.value.size} bands")
            applyBandLevels(_bandLevels.value)
            applyCurrentEffectStateToAttachedEffects()
            
            Timber.tag(TAG).d("Effects attached successfully. EQ bands: ${equalizer?.numberOfBands}, Range: $minEqLevel to $maxEqLevel")
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to initialize audio effects")
            release()
        }
    }

    // ---- Crossfade companion ------------------------------------------------------------
    // Audio effects belong to ONE audio session. During a crossfade the incoming song plays on
    // the standby player's own session, so it used to fade in with no EQ for the whole
    // transition and only got the EQ at the swap. A companion set of effects mirrors the
    // current settings onto the incoming session while both players are audible; at the swap
    // the primary attaches to that same session (sharing the engine), then the companion goes.
    private var companionSessionId: Int = 0
    private var companionEqualizer: Equalizer? = null
    private var companionBassBoost: BassBoost? = null
    private var companionVirtualizer: Virtualizer? = null
    private var companionLoudness: android.media.audiofx.LoudnessEnhancer? = null

    fun attachCompanionSession(audioSessionId: Int) {
        if (!hasAnyEnabledEffects) return
        if (audioSessionId == 0 || audioSessionId == currentAudioSessionId) return
        if (audioSessionId == companionSessionId) return
        releaseCompanion()
        val primary = equalizer
        try {
            if (_isEnabled.value && primary != null) {
                companionEqualizer = Equalizer(0, audioSessionId).apply {
                    val bands = minOf(numberOfBands, primary.numberOfBands)
                    for (b in 0 until bands) {
                        runCatching { setBandLevel(b.toShort(), primary.getBandLevel(b.toShort())) }
                    }
                    enabled = true
                }
            } else if (_isEnabled.value) {
                // The first deck can start before its AudioTrack reports a session.
                // Apply saved bands directly instead of fading the next song in flat.
                companionEqualizer = Equalizer(0, audioSessionId).apply {
                    val levels = _bandLevels.value
                    val range = bandLevelRange
                    val count = numberOfBands.toInt()
                    for (band in 0 until count) {
                        val start = band * levels.size / count
                        val end = ((band + 1) * levels.size / count).coerceAtLeast(start + 1)
                        val level = levels.subList(start, end).average().toInt().coerceIn(MIN_LEVEL, MAX_LEVEL)
                        val millibels = range[0] + (level - MIN_LEVEL) * (range[1] - range[0]) / (MAX_LEVEL - MIN_LEVEL)
                        runCatching { setBandLevel(band.toShort(), millibels.toShort()) }
                    }
                    enabled = true
                }
            }
            if (_bassBoostEnabled.value) {
                companionBassBoost = runCatching {
                    BassBoost(0, audioSessionId).apply {
                        if (strengthSupported) setStrength(_bassBoostStrength.value.coerceIn(0, 1000).toShort())
                        enabled = true
                    }
                }.getOrNull()
            }
            if (_virtualizerEnabled.value) {
                companionVirtualizer = runCatching {
                    Virtualizer(0, audioSessionId).apply {
                        if (strengthSupported) setStrength(_virtualizerStrength.value.coerceIn(0, 1000).toShort())
                        enabled = true
                    }
                }.getOrNull()
            }
            if (_loudnessEnhancerEnabled.value) {
                companionLoudness = runCatching {
                    android.media.audiofx.LoudnessEnhancer(audioSessionId).apply {
                        setTargetGain(_loudnessEnhancerStrength.value.coerceIn(0, MAX_LOUDNESS_GAIN_MB))
                        enabled = true
                    }
                }.getOrNull()
            }
            companionSessionId = audioSessionId
            Timber.tag(TAG).d("Companion effects attached to incoming session %d", audioSessionId)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Companion effects failed for session %d", audioSessionId)
            releaseCompanion()
        }
    }

    fun releaseCompanion() {
        companionEqualizer?.runCatching { release() }
        companionBassBoost?.runCatching { release() }
        companionVirtualizer?.runCatching { release() }
        companionLoudness?.runCatching { release() }
        companionEqualizer = null
        companionBassBoost = null
        companionVirtualizer = null
        companionLoudness = null
        companionSessionId = 0
    }

    /** Move the already audible incoming effects into the primary slot at the deck swap. */
    fun promoteCompanionSession(audioSessionId: Int): Boolean {
        if (audioSessionId == 0 || audioSessionId != companionSessionId) return false
        release()
        equalizer = companionEqualizer
        bassBoost = companionBassBoost
        virtualizer = companionVirtualizer
        loudnessEnhancer = companionLoudness
        companionEqualizer = null
        companionBassBoost = null
        companionVirtualizer = null
        companionLoudness = null
        companionSessionId = 0
        currentAudioSessionId = audioSessionId
        applyCurrentEffectStateToAttachedEffects()
        return true
    }

    suspend fun attachToAudioSessionIfNeeded(audioSessionId: Int) {
        if (!hasAnyEnabledEffects) {
            Timber.tag(TAG).d(
                "Skipping attachToAudioSession($audioSessionId): all audio effects are disabled"
            )
            releaseIfUnused()
            return
        }

        attachToAudioSession(audioSessionId)
    }
    
    /**
     * Enables or disables the equalizer.
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        try {
            equalizer?.enabled = enabled
            Timber.tag(TAG).d("Equalizer enabled: $enabled")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set equalizer enabled state")
        }
        releaseIfUnused()
    }
    
    /**
     * Sets the level for a specific band.
     * @param bandIndex 0-9 for UI bands
     * @param level -15 to +15 normalized level
     */
    fun setBandLevel(bandIndex: Int, level: Int) {
        if (bandIndex !in 0 until NUM_BANDS) return
        
        val clampedLevel = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        val newLevels = _bandLevels.value.toMutableList()
        newLevels[bandIndex] = clampedLevel
        _bandLevels.value = newLevels
        
        applyBandLevel(bandIndex, clampedLevel)
        
        // Switch to custom preset when manually adjusting
        _currentPresetName.value = "custom"
    }
    
    /**
     * Applies a preset to the equalizer.
     */
    fun applyPreset(preset: EqualizerPreset) {
        _currentPresetName.value = preset.name
        _bandLevels.value = preset.bandLevels
        applyBandLevels(preset.bandLevels)
        Timber.tag(TAG).d("Applied preset: ${preset.displayName}")
    }

    /**
     * Sets bass boost enabled state.
     */
    fun setBassBoostEnabled(enabled: Boolean) {
        if (!isBassBoostSupportedGlobal) {
            _bassBoostEnabled.value = false
            releaseIfUnused()
            return
        }
        _bassBoostEnabled.value = enabled
        try {
            bassBoost?.enabled = enabled
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set bass boost enabled")
        }
        releaseIfUnused()
    }
    
    /**
     * Sets bass boost strength (0-1000).
     */
    fun setBassBoostStrength(strength: Int) {
        if (!isBassBoostSupportedGlobal) return

        val clampedStrength = strength.coerceIn(0, 1000)
        _bassBoostStrength.value = clampedStrength
        
        try {
            bassBoost?.apply {
                if (strengthSupported) {
                    setStrength(clampedStrength.toShort())
                }
            }
            Timber.tag(TAG).d("Bass boost strength: $clampedStrength")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set bass boost")
        }
    }

    /**
     * Sets virtualizer enabled state.
     */
    fun setVirtualizerEnabled(enabled: Boolean) {
        if (!isVirtualizerSupportedGlobal) {
            _virtualizerEnabled.value = false
            releaseIfUnused()
            return
        }
        _virtualizerEnabled.value = enabled
        try {
            virtualizer?.enabled = enabled
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set virtualizer enabled")
        }
        releaseIfUnused()
    }
    
    /**
     * Sets virtualizer (surround) strength (0-1000).
     */
    fun setVirtualizerStrength(strength: Int) {
        if (!isVirtualizerSupportedGlobal) return

        val clampedStrength = strength.coerceIn(0, 1000)
        _virtualizerStrength.value = clampedStrength
        
        try {
            virtualizer?.apply {
                if (strengthSupported) {
                    setStrength(clampedStrength.toShort())
                }
            }
            Timber.tag(TAG).d("Virtualizer strength: $clampedStrength")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set virtualizer")
        }
    }

    /**
     * Sets loudness enhancer enabled state.
     */
    fun setLoudnessEnhancerEnabled(enabled: Boolean) {
        _loudnessEnhancerEnabled.value = enabled
        try {
            loudnessEnhancer?.enabled = enabled
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set loudness enhancer enabled")
        }
        releaseIfUnused()
    }

    /**
     * Sets loudness enhancer strength (gain in mB).
     * 0 to 1000mB (10dB) is used as a stable cross-device range.
     */
    fun setLoudnessEnhancerStrength(strength: Int) {
        val clampedStrength = strength.coerceIn(0, MAX_LOUDNESS_GAIN_MB)
        _loudnessEnhancerStrength.value = clampedStrength

        try {
            loudnessEnhancer?.setTargetGain(clampedStrength)
            Timber.tag(TAG).d("Loudness enhancer strength: $clampedStrength")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set loudness enhancer")
        }
    }
    
    /**
     * Restores equalizer state from saved preferences.
     */
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
        _bassBoostStrength.value = bassBoostStrength
        _virtualizerEnabled.value = virtualizerEnabled
        _virtualizerStrength.value = virtualizerStrength
        _loudnessEnhancerEnabled.value = loudnessEnabled
        _loudnessEnhancerStrength.value = loudnessStrength.coerceIn(0, MAX_LOUDNESS_GAIN_MB)
        
        val preset = if (presetName == "custom") {
            EqualizerPreset.custom(customBands)
        } else {
            EqualizerPreset.fromName(presetName)
        }
        
        _currentPresetName.value = preset.name
        _bandLevels.value = preset.bandLevels
        
        // Apply if already attached
        if (equalizer != null) {
            if (!hasAnyEnabledEffects) {
                releaseIfUnused()
                return
            }
            equalizer?.enabled = enabled
            applyBandLevels(preset.bandLevels)
            applyCurrentEffectStateToAttachedEffects()
        }
    }

    private fun releaseIfUnused() {
        if (!hasAnyEnabledEffects) {
            Timber.tag(TAG).d("Releasing audio effects because all effect toggles are disabled")
            release()
            releaseCompanion()
        }
    }

    private fun applyCurrentEffectStateToAttachedEffects() {
        try {
            bassBoost?.apply {
                enabled = _bassBoostEnabled.value
                if (strengthSupported) {
                    setStrength(_bassBoostStrength.value.coerceIn(0, 1000).toShort())
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed applying bass boost state")
        }

        try {
            virtualizer?.apply {
                enabled = _virtualizerEnabled.value
                if (strengthSupported) {
                    setStrength(_virtualizerStrength.value.coerceIn(0, 1000).toShort())
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed applying virtualizer state")
        }

        try {
            loudnessEnhancer?.apply {
                setTargetGain(_loudnessEnhancerStrength.value.coerceIn(0, MAX_LOUDNESS_GAIN_MB))
                enabled = _loudnessEnhancerEnabled.value
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed applying loudness state")
        }
    }
    
    private fun applyBandLevels(levels: List<Int>) {
        val eq = equalizer ?: return
        val deviceBandCount = eq.numberOfBands.toInt()
        
        if (deviceBandCount <= 0) return
        
        // Map UI bands (10) to device bands (typically 5)
        val uiBandCount = levels.size
        
        if (deviceBandCount >= uiBandCount) {
            levels.forEachIndexed { index, level ->
                applyBandLevelDirect(index, level)
            }
        } else {
            val ratio = uiBandCount.toFloat() / deviceBandCount.toFloat()
            
            for (deviceBand in 0 until deviceBandCount) {
                val startUiBand = (deviceBand * ratio).toInt()
                val endUiBand = ((deviceBand + 1) * ratio).toInt().coerceAtMost(uiBandCount)
                
                var sum = 0
                var count = 0
                for (uiBand in startUiBand until endUiBand) {
                    if (uiBand < levels.size) {
                        sum += levels[uiBand]
                        count++
                    }
                }
                
                val averageLevel = if (count > 0) sum / count else 0
                applyBandLevelDirect(deviceBand, averageLevel)
            }
        }
    }
    
    private fun applyBandLevelDirect(bandIndex: Int, normalizedLevel: Int) {
        val eq = equalizer ?: return
        if (bandIndex >= eq.numberOfBands) return
        
        // Convert normalized level (-15 to +15) to device millibel range
        val range = maxEqLevel - minEqLevel
        val millibelLevel = (minEqLevel + (normalizedLevel + 15) * range / 30).toShort()
        
        try {
            eq.setBandLevel(bandIndex.toShort(), millibelLevel)
            Timber.tag(TAG).v("Set band $bandIndex to $millibelLevel mB (normalized: $normalizedLevel)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set band $bandIndex level")
        }
    }
    
    private fun applyBandLevel(bandIndex: Int, normalizedLevel: Int) {
        val currentLevels = _bandLevels.value.toMutableList()
        if (bandIndex < currentLevels.size) {
            currentLevels[bandIndex] = normalizedLevel.coerceIn(MIN_LEVEL, MAX_LEVEL)
            applyBandLevels(currentLevels)
        }
    }
    
    /**
     * Gets the center frequencies for all bands.
     */
    fun getBandFrequencies(): List<Int> {
        val eq = equalizer ?: return listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        return (0 until eq.numberOfBands).map { band ->
            eq.getCenterFreq(band.toShort()) / 1000
        }
    }
    
    /**
     * Checks if bass boost is supported on this device.
     */
    fun isBassBoostSupported(): Boolean = isBassBoostSupportedGlobal
    
    /**
     * Checks if virtualizer is supported on this device.
     */
    fun isVirtualizerSupported(): Boolean = isVirtualizerSupportedGlobal

    /**
     * Checks if loudness enhancer is supported on this device.
     */
    fun isLoudnessEnhancerSupported(): Boolean = loudnessEnhancer != null || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT
    
    /**
     * Releases all audio effect resources.
     */
    fun release() {
        try {
            equalizer?.release()
            bassBoost?.release()
            virtualizer?.release()
            loudnessEnhancer?.release()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error releasing audio effects")
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        currentAudioSessionId = 0
        Timber.tag(TAG).d("Audio effects released")
    }
}
