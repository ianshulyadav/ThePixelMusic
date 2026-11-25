@file:Suppress("DEPRECATION")
package com.unshoo.pixelmusic.data.equalizer

import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    
    private val attachMutex = Mutex()

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var currentAudioSessionId: Int = 0
    private var attachedToLiveAudioTrack: Boolean = false

    @Volatile
    var hasRestoredState: Boolean = false
        private set

    val isAttached: Boolean
        get() = currentAudioSessionId != 0 &&
            (equalizer != null || bassBoost != null || virtualizer != null || loudnessEnhancer != null)

    fun hasEffectControl(): Boolean {
        val eqOk = equalizer?.let { eq ->
            runCatching { eq.hasControl() && (!_isEnabled.value || eq.enabled) }.getOrDefault(false)
        } ?: false
        if (eqOk) return true
        if (_isEnabled.value) return false
        return listOfNotNull(bassBoost, virtualizer, loudnessEnhancer).any { effect ->
            runCatching { effect.hasControl() }.getOrDefault(false)
        }
    }

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
    suspend fun attachToAudioSession(
        audioSessionId: Int,
        fromLiveAudioTrack: Boolean = false,
        forceReattach: Boolean = false
    ) {
        if (audioSessionId <= 0) {
            Timber.tag(TAG).w("Invalid audio session ID: %d", audioSessionId)
            return
        }

        attachMutex.withLock {
            val existingEq = equalizer
            val needsUpgradeToLiveTrack = fromLiveAudioTrack && !attachedToLiveAudioTrack
            val eqHealthy = if (_isEnabled.value) {
                existingEq != null &&
                    runCatching { existingEq.hasControl() && existingEq.enabled }.getOrDefault(false)
            } else {
                existingEq?.let { runCatching { it.hasControl() }.getOrDefault(false) }
                    ?: listOfNotNull(bassBoost, virtualizer, loudnessEnhancer).any {
                        runCatching { it.hasControl() }.getOrDefault(false)
                    }
            }

            if (!forceReattach && !needsUpgradeToLiveTrack && currentAudioSessionId == audioSessionId && eqHealthy) {
                ensureSecondaryEffectsForSession(audioSessionId)
                applyBandLevels(_bandLevels.value)
                applyCurrentEffectStateToAttachedEffects()
                Timber.tag(TAG).d("Already attached to session %d (liveTrack=%b)", audioSessionId, attachedToLiveAudioTrack)
                return
            }

            if (!forceReattach && companionSessionId == audioSessionId && promoteCompanionSessionInternal(audioSessionId)) {
                return
            }

            Timber.tag(TAG).d(
                "Attaching to audio session: %d (fromLiveAudioTrack=%b, forceReattach=%b)",
                audioSessionId,
                fromLiveAudioTrack,
                forceReattach
            )
            releaseInternal()

            try {
                // Initialize Equalizer
                val createdEq = try {
                    Equalizer(0, audioSessionId).apply {
                        val range = bandLevelRange
                        if (range != null && range.size >= 2) {
                            minEqLevel = range[0]
                            maxEqLevel = range[1]
                        }
                    }
                } catch (e: Exception) {
                    // A session can be published before AudioTrack exists. Keep the user's
                    // settings intact so playback can retry after the sink starts.
                    Timber.tag(TAG).w(
                        e,
                        "Audio effects unavailable for session %d; will retry during playback.",
                        audioSessionId
                    )
                    releaseInternal()
                    return
                }
                equalizer = createdEq

                // Apply the user's bands before enabling so the first processed frame uses them.
                applyBandLevels(_bandLevels.value)
                setEffectEnabled(createdEq, _isEnabled.value, "Equalizer", audioSessionId)

                attachSecondaryEffects(audioSessionId, allowRetry = fromLiveAudioTrack)

                currentAudioSessionId = audioSessionId
                attachedToLiveAudioTrack = fromLiveAudioTrack

                // Re-apply latest state in case UI updated during attach.
                val deviceBandCount = equalizer?.numberOfBands?.toInt() ?: 0
                Timber.tag(TAG).d("Device supports $deviceBandCount bands, UI has ${_bandLevels.value.size} bands")
                applyBandLevels(_bandLevels.value)
                setEffectEnabled(createdEq, _isEnabled.value, "Equalizer", audioSessionId)
                applyCurrentEffectStateToAttachedEffects()

                Timber.tag(TAG).d(
                    "Effects attached successfully (session=%d, enabled=%b, hasControl=%b, bands=%s, range=%d..%d)",
                    audioSessionId,
                    runCatching { createdEq.enabled }.getOrDefault(false),
                    runCatching { createdEq.hasControl() }.getOrDefault(false),
                    createdEq.numberOfBands,
                    minEqLevel,
                    maxEqLevel
                )
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to initialize audio effects")
                releaseInternal()
            }
        }
    }

    private suspend fun attachSecondaryEffects(audioSessionId: Int, allowRetry: Boolean) {
        if (isBassBoostSupportedGlobal && _bassBoostEnabled.value) {
            bassBoost = createBassBoostWithOptionalRetry(audioSessionId, maxAttempts = if (allowRetry) 2 else 1)
        }
        if (isVirtualizerSupportedGlobal && _virtualizerEnabled.value) {
            virtualizer = createVirtualizerWithOptionalRetry(audioSessionId, maxAttempts = if (allowRetry) 2 else 1)
        }
        if (_loudnessEnhancerEnabled.value) {
            loudnessEnhancer = createLoudnessEnhancer(audioSessionId)
        }
    }

    private fun ensureSecondaryEffectsForSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (isBassBoostSupportedGlobal && _bassBoostEnabled.value && bassBoost == null) {
            bassBoost = createBassBoostSync(audioSessionId)
        }
        if (isVirtualizerSupportedGlobal && _virtualizerEnabled.value && virtualizer == null) {
            virtualizer = createVirtualizerSync(audioSessionId)
        }
        if (_loudnessEnhancerEnabled.value && loudnessEnhancer == null) {
            loudnessEnhancer = createLoudnessEnhancer(audioSessionId)
        }
    }

    private suspend fun createBassBoostWithOptionalRetry(audioSessionId: Int, maxAttempts: Int): BassBoost? {
        repeat(maxAttempts) { attempt ->
            createBassBoostSync(audioSessionId)?.let { return it }
            if (attempt < maxAttempts - 1) kotlinx.coroutines.delay(150)
        }
        markBassBoostUnavailable("No effect engine was created for audio session $audioSessionId after $maxAttempts attempts")
        return null
    }

    private fun createBassBoostSync(audioSessionId: Int): BassBoost? {
        return try {
            BassBoost(0, audioSessionId).apply {
                if (strengthSupported) {
                    setStrength(_bassBoostStrength.value.coerceIn(0, 1000).toShort())
                }
                setEffectEnabled(this, _bassBoostEnabled.value, "BassBoost", audioSessionId)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("BassBoost init failed for session %d: %s", audioSessionId, e.message)
            null
        }
    }

    private suspend fun createVirtualizerWithOptionalRetry(audioSessionId: Int, maxAttempts: Int): Virtualizer? {
        repeat(maxAttempts) { attempt ->
            createVirtualizerSync(audioSessionId)?.let { return it }
            if (attempt < maxAttempts - 1) kotlinx.coroutines.delay(150)
        }
        markVirtualizerUnavailable("No effect engine was created for audio session $audioSessionId after $maxAttempts attempts")
        return null
    }

    private fun createVirtualizerSync(audioSessionId: Int): Virtualizer? {
        return try {
            Virtualizer(0, audioSessionId).apply {
                if (strengthSupported) {
                    setStrength(_virtualizerStrength.value.coerceIn(0, 1000).toShort())
                }
                setEffectEnabled(this, _virtualizerEnabled.value, "Virtualizer", audioSessionId)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Virtualizer init failed for session %d: %s", audioSessionId, e.message)
            null
        }
    }

    private fun createLoudnessEnhancer(audioSessionId: Int): android.media.audiofx.LoudnessEnhancer? {
        return try {
            android.media.audiofx.LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(_loudnessEnhancerStrength.value.coerceIn(0, MAX_LOUDNESS_GAIN_MB))
                setEffectEnabled(this, _loudnessEnhancerEnabled.value, "LoudnessEnhancer", audioSessionId)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "LoudnessEnhancer not supported on this device")
            null
        }
    }

    private fun setEffectEnabled(
        effect: AudioEffect,
        enabled: Boolean,
        effectName: String,
        audioSessionId: Int
    ): Boolean {
        return try {
            val status = effect.setEnabled(enabled)
            if (status != AudioEffect.SUCCESS) {
                Timber.tag(TAG).w(
                    "%s.setEnabled(%b) returned error status=%d for session=%d",
                    effectName,
                    enabled,
                    status,
                    audioSessionId
                )
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set %s enabled=%b on session %d", effectName, enabled, audioSessionId)
            false
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

    fun attachCompanionSession(audioSessionId: Int, forceReattach: Boolean = false) {
        if (!hasAnyEnabledEffects) return
        if (audioSessionId == 0 || audioSessionId == currentAudioSessionId) return
        if (!forceReattach && audioSessionId == companionSessionId && companionEqualizer != null) return
        releaseCompanion()
        val primary = equalizer
        try {
            if (_isEnabled.value && primary != null) {
                companionEqualizer = Equalizer(0, audioSessionId).apply {
                    val bands = minOf(numberOfBands, primary.numberOfBands)
                    for (b in 0 until bands) {
                        runCatching { setBandLevel(b.toShort(), primary.getBandLevel(b.toShort())) }
                    }
                    setEffectEnabled(this, true, "CompanionEqualizer", audioSessionId)
                }
            } else if (_isEnabled.value) {
                // The first deck can start before its AudioTrack reports a session.
                // Apply saved bands directly instead of fading the next song in flat.
                companionEqualizer = Equalizer(0, audioSessionId).apply {
                    val range = bandLevelRange
                    val minMb = if (range != null && range.size >= 2) range[0] else minEqLevel
                    val maxMb = if (range != null && range.size >= 2) range[1] else maxEqLevel
                    applyBandLevelsTo(this, _bandLevels.value, minMb, maxMb)
                    setEffectEnabled(this, true, "CompanionEqualizer", audioSessionId)
                }
            }
            if (_bassBoostEnabled.value) {
                companionBassBoost = runCatching {
                    BassBoost(0, audioSessionId).apply {
                        if (strengthSupported) setStrength(_bassBoostStrength.value.coerceIn(0, 1000).toShort())
                        setEffectEnabled(this, true, "CompanionBassBoost", audioSessionId)
                    }
                }.getOrNull()
            }
            if (_virtualizerEnabled.value) {
                companionVirtualizer = runCatching {
                    Virtualizer(0, audioSessionId).apply {
                        if (strengthSupported) setStrength(_virtualizerStrength.value.coerceIn(0, 1000).toShort())
                        setEffectEnabled(this, true, "CompanionVirtualizer", audioSessionId)
                    }
                }.getOrNull()
            }
            if (_loudnessEnhancerEnabled.value) {
                companionLoudness = runCatching {
                    android.media.audiofx.LoudnessEnhancer(audioSessionId).apply {
                        setTargetGain(_loudnessEnhancerStrength.value.coerceIn(0, MAX_LOUDNESS_GAIN_MB))
                        setEffectEnabled(this, true, "CompanionLoudness", audioSessionId)
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
        return promoteCompanionSessionInternal(audioSessionId)
    }

    private fun promoteCompanionSessionInternal(audioSessionId: Int): Boolean {
        if (audioSessionId == 0 || audioSessionId != companionSessionId) return false
        releaseInternal()
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
        attachedToLiveAudioTrack = true
        equalizer?.let { eq ->
            val range = eq.bandLevelRange
            if (range != null && range.size >= 2) {
                minEqLevel = range[0]
                maxEqLevel = range[1]
            }
            applyBandLevels(_bandLevels.value)
            setEffectEnabled(eq, _isEnabled.value, "Equalizer", audioSessionId)
        }
        applyCurrentEffectStateToAttachedEffects()
        return !_isEnabled.value || equalizer != null
    }

    suspend fun attachToAudioSessionIfNeeded(
        audioSessionId: Int,
        fromLiveAudioTrack: Boolean = false,
        forceReattach: Boolean = false
    ) {
        if (!hasAnyEnabledEffects) {
            Timber.tag(TAG).d(
                "Skipping attachToAudioSession($audioSessionId): all audio effects are disabled"
            )
            releaseIfUnused()
            return
        }

        attachToAudioSession(
            audioSessionId = audioSessionId,
            fromLiveAudioTrack = fromLiveAudioTrack,
            forceReattach = forceReattach
        )
    }
    
    /**
     * Enables or disables the equalizer.
     */
    fun setEnabled(enabled: Boolean) {
        hasRestoredState = true
        _isEnabled.value = enabled
        equalizer?.let { eq ->
            if (enabled) {
                applyBandLevels(_bandLevels.value)
            }
            setEffectEnabled(eq, enabled, "Equalizer", currentAudioSessionId)
            Timber.tag(TAG).d("Equalizer enabled: $enabled")
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
        hasRestoredState = true

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
        hasRestoredState = true
        _currentPresetName.value = preset.name
        _bandLevels.value = preset.bandLevels
        applyBandLevels(preset.bandLevels)
        Timber.tag(TAG).d("Applied preset: ${preset.displayName}")
    }

    /**
     * Sets bass boost enabled state.
     */
    fun setBassBoostEnabled(enabled: Boolean) {
        hasRestoredState = true
        if (!isBassBoostSupportedGlobal) {
            _bassBoostEnabled.value = false
            releaseIfUnused()
            return
        }
        _bassBoostEnabled.value = enabled
        if (enabled && currentAudioSessionId != 0 && bassBoost == null) {
            bassBoost = createBassBoostSync(currentAudioSessionId)
        } else {
            bassBoost?.let { setEffectEnabled(it, enabled, "BassBoost", currentAudioSessionId) }
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
        hasRestoredState = true
        if (!isVirtualizerSupportedGlobal) {
            _virtualizerEnabled.value = false
            releaseIfUnused()
            return
        }
        _virtualizerEnabled.value = enabled
        if (enabled && currentAudioSessionId != 0 && virtualizer == null) {
            virtualizer = createVirtualizerSync(currentAudioSessionId)
        } else {
            virtualizer?.let { setEffectEnabled(it, enabled, "Virtualizer", currentAudioSessionId) }
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
        hasRestoredState = true
        _loudnessEnhancerEnabled.value = enabled
        if (enabled && currentAudioSessionId != 0 && loudnessEnhancer == null) {
            loudnessEnhancer = createLoudnessEnhancer(currentAudioSessionId)
        } else {
            loudnessEnhancer?.let { setEffectEnabled(it, enabled, "LoudnessEnhancer", currentAudioSessionId) }
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
        hasRestoredState = true
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
            applyBandLevels(preset.bandLevels)
            equalizer?.let { setEffectEnabled(it, enabled, "Equalizer", currentAudioSessionId) }
            ensureSecondaryEffectsForSession(currentAudioSessionId)
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
                if (strengthSupported) {
                    setStrength(_bassBoostStrength.value.coerceIn(0, 1000).toShort())
                }
                setEffectEnabled(this, _bassBoostEnabled.value, "BassBoost", currentAudioSessionId)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed applying bass boost state")
        }

        try {
            virtualizer?.apply {
                if (strengthSupported) {
                    setStrength(_virtualizerStrength.value.coerceIn(0, 1000).toShort())
                }
                setEffectEnabled(this, _virtualizerEnabled.value, "Virtualizer", currentAudioSessionId)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed applying virtualizer state")
        }

        try {
            loudnessEnhancer?.apply {
                setTargetGain(_loudnessEnhancerStrength.value.coerceIn(0, MAX_LOUDNESS_GAIN_MB))
                setEffectEnabled(this, _loudnessEnhancerEnabled.value, "LoudnessEnhancer", currentAudioSessionId)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed applying loudness state")
        }
    }
    
    private fun applyBandLevels(levels: List<Int>) {
        val eq = equalizer ?: return
        applyBandLevelsTo(eq, levels, minEqLevel, maxEqLevel)
    }

    private fun applyBandLevelsTo(
        eq: Equalizer,
        levels: List<Int>,
        minLevelMb: Short,
        maxLevelMb: Short
    ) {
        val deviceBandCount = eq.numberOfBands.toInt()
        if (deviceBandCount <= 0 || levels.isEmpty()) return

        // Map UI bands (10) to device bands (typically 5)
        val uiBandCount = levels.size
        if (deviceBandCount >= uiBandCount) {
            levels.forEachIndexed { index, level ->
                applyBandLevelDirectTo(eq, index, level, minLevelMb, maxLevelMb)
            }
        } else {
            val ratio = uiBandCount.toFloat() / deviceBandCount.toFloat()
            for (deviceBand in 0 until deviceBandCount) {
                val startUiBand = (deviceBand * ratio).toInt()
                val endUiBand = ((deviceBand + 1) * ratio).toInt().coerceAtMost(uiBandCount)

                var sum = 0
                var count = 0
                for (uiBand in startUiBand until endUiBand) {
                    sum += levels[uiBand]
                    count++
                }
                val averageLevel = if (count > 0) sum / count else 0
                applyBandLevelDirectTo(eq, deviceBand, averageLevel, minLevelMb, maxLevelMb)
            }
        }
    }

    private fun applyBandLevelDirectTo(
        eq: Equalizer,
        bandIndex: Int,
        normalizedLevel: Int,
        minLevelMb: Short,
        maxLevelMb: Short
    ) {
        if (bandIndex >= eq.numberOfBands) return
        val clamped = normalizedLevel.coerceIn(MIN_LEVEL, MAX_LEVEL)
        val range = maxLevelMb - minLevelMb
        val millibelLevel = (minLevelMb + (clamped + 15) * range / 30).toShort()
        try {
            eq.setBandLevel(bandIndex.toShort(), millibelLevel)
            Timber.tag(TAG).v("Set band $bandIndex to $millibelLevel mB (normalized: $clamped)")
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
        releaseInternal()
    }

    private fun releaseInternal() {
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
        attachedToLiveAudioTrack = false
        Timber.tag(TAG).d("Audio effects released")
    }
}
