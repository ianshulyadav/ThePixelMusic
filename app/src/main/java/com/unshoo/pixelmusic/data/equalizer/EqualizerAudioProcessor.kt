package com.unshoo.pixelmusic.data.equalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-app EQ / bass boost / virtualizer / loudness, applied to decoded PCM inside ExoPlayer's
 * audio pipeline (one instance per player). See [AudioEffectsDsp] for why this replaces the
 * platform audio effects.
 *
 * - 10 peaking bands (RBJ biquads, ~1 octave wide) at [AudioEffectsDsp.BAND_FREQUENCIES_HZ].
 * - Bass boost: low shelf at 100 Hz, up to +12 dB.
 * - Virtualizer: mid/side stereo widening (stereo input only).
 * - Loudness: make-up gain up to +10 dB.
 * - Automatic headroom plus a peak limiter so boosts don't clip.
 *
 * Handles 16-bit and float PCM. Filters run in double precision so the 31 Hz band stays stable.
 */
@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {

    private companion object {
        const val BAND_Q = 1.41
        const val BASS_SHELF_HZ = 100.0
        const val BASS_MAX_DB = 12.0
        const val LIMIT_CEILING = 0.98
        const val LIMIT_RELEASE_SEC = 0.08
    }

    private var appliedParams: AudioEffectsDsp.Params? = null
    private var appliedSampleRate = 0
    private var appliedChannels = 0

    // Active filters: coefficients [b0, b1, b2, a1, a2] per filter.
    private var coeffs: Array<DoubleArray> = emptyArray()
    // Transposed direct form II state: [filter][channel * 2 + {0,1}]
    private var state: Array<DoubleArray> = emptyArray()

    private var preGain = 1.0
    private var sideGain = 1.0
    private var widen = false
    private var processing = false

    private var limiterGain = 1.0
    private var releaseCoef = 0.0
    private var frame = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!supported || inputAudioFormat.channelCount <= 0) return AudioFormat.NOT_SET
        // Always active for supported PCM so effects can be switched on/off live without
        // reconfiguring the sink; when nothing is enabled the data is copied through untouched.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)

        val format = inputAudioFormat
        val params = AudioEffectsDsp.params
        if (params !== appliedParams ||
            format.sampleRate != appliedSampleRate ||
            format.channelCount != appliedChannels
        ) {
            rebuild(params, format.sampleRate, format.channelCount)
        }

        if (!processing && limiterGain >= 0.9999) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val channels = format.channelCount
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = remaining / (bytesPerSample * channels)
        if (frame.size != channels) frame = DoubleArray(channels)

        repeat(frames) {
            // Read
            for (ch in 0 until channels) {
                frame[ch] = if (isFloat) {
                    inputBuffer.float.toDouble()
                } else {
                    inputBuffer.short / 32768.0
                }
            }

            if (processing) {
                // Filters
                for (f in coeffs.indices) {
                    val c = coeffs[f]
                    val s = state[f]
                    for (ch in 0 until channels) {
                        val x = frame[ch]
                        val i = ch * 2
                        val y = c[0] * x + s[i]
                        s[i] = c[1] * x - c[3] * y + s[i + 1]
                        s[i + 1] = c[2] * x - c[4] * y
                        frame[ch] = y
                    }
                }
                // Stereo widening
                if (widen && channels == 2) {
                    val mid = (frame[0] + frame[1]) * 0.5
                    val side = (frame[0] - frame[1]) * 0.5 * sideGain
                    frame[0] = mid + side
                    frame[1] = mid - side
                }
                for (ch in 0 until channels) frame[ch] *= preGain
            }

            // Peak limiter (instant attack, smooth release)
            var peak = 0.0
            for (ch in 0 until channels) peak = max(peak, abs(frame[ch]))
            val target = if (peak * limiterGain > LIMIT_CEILING) LIMIT_CEILING / peak else 1.0
            limiterGain = if (target < limiterGain) target else limiterGain + (1.0 - limiterGain) * releaseCoef

            // Write
            for (ch in 0 until channels) {
                val v = (frame[ch] * limiterGain).coerceIn(-1.0, 1.0)
                if (isFloat) {
                    out.putFloat(v.toFloat())
                } else {
                    out.putShort((v * 32767.0).toInt().toShort())
                }
            }
        }
        // Skip any trailing partial frame so the input is fully consumed.
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private fun rebuild(params: AudioEffectsDsp.Params, sampleRate: Int, channels: Int) {
        val channelsChanged = channels != appliedChannels || sampleRate != appliedSampleRate
        appliedParams = params
        appliedSampleRate = sampleRate
        appliedChannels = channels
        if (sampleRate <= 0 || channels <= 0) {
            processing = false
            return
        }
        releaseCoef = 1.0 - exp(-1.0 / (LIMIT_RELEASE_SEC * sampleRate))

        val newCoeffs = ArrayList<DoubleArray>()
        var maxBoostDb = 0.0
        val nyquistSafe = sampleRate * 0.45

        if (params.eqEnabled) {
            AudioEffectsDsp.BAND_FREQUENCIES_HZ.forEachIndexed { i, hz ->
                val gainDb = params.bandGainsDb.getOrElse(i) { 0f }.toDouble()
                if (abs(gainDb) >= 0.05 && hz < nyquistSafe) {
                    newCoeffs += peaking(hz.toDouble(), gainDb, BAND_Q, sampleRate.toDouble())
                    maxBoostDb = max(maxBoostDb, gainDb)
                }
            }
        }
        if (params.bassBoostEnabled && params.bassBoostStrength > 0) {
            val gainDb = params.bassBoostStrength.coerceIn(0, 1000) / 1000.0 * BASS_MAX_DB
            newCoeffs += lowShelf(BASS_SHELF_HZ, gainDb, sampleRate.toDouble())
            maxBoostDb += gainDb
        }

        widen = params.virtualizerEnabled && params.virtualizerStrength > 0 && channels == 2
        sideGain = 1.0 + params.virtualizerStrength.coerceIn(0, 1000) / 1000.0 * 0.8

        val loudnessDb = if (params.loudnessEnabled) params.loudnessGainMb.coerceIn(0, 1000) / 100.0 else 0.0
        // Pull back half of the largest boost as headroom; the limiter catches the rest.
        val headroomDb = -maxBoostDb * 0.5
        preGain = 10.0.pow((headroomDb + loudnessDb) / 20.0)

        // Keep filter state for unchanged filter count so live tweaks don't click.
        if (channelsChanged || newCoeffs.size != coeffs.size) {
            state = Array(newCoeffs.size) { DoubleArray(channels * 2) }
        }
        coeffs = newCoeffs.toTypedArray()
        processing = params.anyActive
    }

    /** RBJ cookbook peaking EQ. */
    private fun peaking(f0: Double, gainDb: Double, q: Double, fs: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha / a
        return doubleArrayOf(
            (1.0 + alpha * a) / a0,
            (-2.0 * cosW0) / a0,
            (1.0 - alpha * a) / a0,
            (-2.0 * cosW0) / a0,
            (1.0 - alpha / a) / a0,
        )
    }

    /** RBJ cookbook low shelf, S = 1. */
    private fun lowShelf(f0: Double, gainDb: Double, fs: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cosW0 = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt(2.0)
        val sqrtA2Alpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosW0 + sqrtA2Alpha
        return doubleArrayOf(
            a * ((a + 1) - (a - 1) * cosW0 + sqrtA2Alpha) / a0,
            2 * a * ((a - 1) - (a + 1) * cosW0) / a0,
            a * ((a + 1) - (a - 1) * cosW0 - sqrtA2Alpha) / a0,
            -2 * ((a - 1) + (a + 1) * cosW0) / a0,
            ((a + 1) + (a - 1) * cosW0 - sqrtA2Alpha) / a0,
        )
    }

    private fun clearState() {
        state.forEach { it.fill(0.0) }
        limiterGain = 1.0
    }

    override fun onFlush() {
        clearState()
    }

    override fun onReset() {
        appliedParams = null
        appliedSampleRate = 0
        appliedChannels = 0
        coeffs = emptyArray()
        state = emptyArray()
        processing = false
        limiterGain = 1.0
    }
}
