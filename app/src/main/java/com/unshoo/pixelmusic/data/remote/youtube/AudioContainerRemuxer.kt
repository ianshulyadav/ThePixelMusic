package com.unshoo.pixelmusic.data.remote.youtube

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer

import java.io.File
import java.nio.ByteBuffer

/**
 * Rewraps YouTube's WebM/Opus downloads into a standard Ogg/Opus file.
 *
 * Android's MediaStore indexes ".webm" as a *video* container, so WebM audio downloads never
 * show up in the music library or in folder scans, and most tag editors refuse them. The
 * Opus bitstream itself is fine, so instead of re-encoding (slow, lossy) we copy the encoded
 * packets as-is into an Ogg container, which every music scanner treats as audio.
 */
internal object AudioContainerRemuxer {

    /**
     * Remuxes [input] (WebM/Matroska with an Opus track) into [output] (Ogg/Opus).
     * Returns true on success; on failure [output] is deleted and [input] is left untouched.
     */
    fun remuxWebmToOgg(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            extractor.setDataSource(input.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_OPUS) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return false

            extractor.selectTrack(trackIndex)
            if (output.exists()) output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            val outTrack = muxer.addTrack(format)
            muxer.start()
            muxerStarted = true

            val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else {
                256 * 1024
            }
            // Direct buffer: MediaExtractor fills it natively without an extra JNI copy per packet.
            val buffer = ByteBuffer.allocateDirect(maxInput)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            muxerStarted = false
            output.exists() && output.length() > 0
        } catch (e: Exception) {
            PixelMusicHelper.printe("WebM→Ogg remux failed for ${input.name}: ${e.message}")
            runCatching { output.delete() }
            false
        } finally {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
