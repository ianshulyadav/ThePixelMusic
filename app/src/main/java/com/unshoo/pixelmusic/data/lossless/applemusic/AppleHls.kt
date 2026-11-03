/*
 * Apple Music HLS helper for Widevine playback.
 * Adapted from Mochy-Music (GPL-3.0).
 */

package com.unshoo.pixelmusic.data.lossless.applemusic

import java.util.Base64

internal object AppleHls {

    const val WidevineKeyFormat = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

    class WidevineKey(
        val uri: String,
        val pssh: ByteArray,
        val keyId: ByteArray,
    )

    fun segmentCount(media: String): Int = media.lines().count { it.isNotBlank() && !it.startsWith("#") }

    private val WidevineSystemId = byteArrayOf(
        0xed.toByte(), 0xef.toByte(), 0x8b.toByte(), 0xa9.toByte(), 0x79, 0xd6.toByte(), 0x4a, 0xce.toByte(),
        0xa3.toByte(), 0xc8.toByte(), 0x27, 0xdc.toByte(), 0xd5.toByte(), 0x1d, 0x21, 0xed.toByte(),
    )

    /** The stream's key: its URI as written (what Apple's license server expects) and the key id */
    fun legacyKey(media: String): Pair<String, ByteArray>? {
        val line = media.lines().firstOrNull { l ->
            l.startsWith("#EXT-X-KEY") &&
                (attribute(l, "KEYFORMAT") == WidevineKeyFormat || attribute(l, "URI")?.startsWith("data:") == true)
        } ?: return null
        val uri = attribute(line, "URI") ?: return null
        val payload = runCatching { Base64.getDecoder().decode(uri.substringAfter(',')) }.getOrNull() ?: return null
        val keyId = when {
            payload.size == 16 -> payload
            payload.size > 32 -> keyIdOf(payload)
            else -> return null
        }
        return uri to keyId
    }

    /** A Widevine PSSH box (version 0) whose data names [keyId], AES-CTR */
    fun psshFor(keyId: ByteArray): ByteArray {
        val data = byteArrayOf(0x08, 0x01, 0x12, keyId.size.toByte()) + keyId
        val size = 32 + data.size
        return java.nio.ByteBuffer.allocate(size)
            .putInt(size)
            .put("pssh".toByteArray(Charsets.ISO_8859_1))
            .putInt(0)
            .put(WidevineSystemId)
            .putInt(data.size)
            .put(data)
            .array()
    }

    /** The playlist ExoPlayer opens: absolute URIs, and only the Widevine key as a standard PSSH box */
    fun rewriteLegacy(media: String, base: String, keyUri: String, pssh: ByteArray): String {
        val psshUri = "data:text/plain;base64," + Base64.getEncoder().encodeToString(pssh)
        return media.lines().mapNotNull { line ->
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    if (attribute(line, "URI") != keyUri) null
                    else {
                        """#EXT-X-KEY:METHOD=${playerMethod(attribute(line, "METHOD"))},URI="$psshUri",KEYFORMAT="$WidevineKeyFormat",KEYFORMATVERSIONS="1""""
                    }
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val uri = attribute(line, "URI")
                    if (uri == null) line else line.replace("URI=\"$uri\"", "URI=\"${resolve(base, uri)}\"")
                }
                line.isNotBlank() && !line.startsWith("#") -> resolve(base, line.trim())
                else -> line
            }
        }.joinToString("\n")
    }

    internal fun playerMethod(method: String?): String = when (method) {
        "SAMPLE-AES", "SAMPLE-AES-CENC", "SAMPLE-AES-CTR" -> method
        else -> "SAMPLE-AES-CTR"
    }

    internal fun keyIdOf(pssh: ByteArray): ByteArray {
        val dataStart = 32
        for (i in dataStart until pssh.size - 17) {
            if (pssh[i] == 0x12.toByte() && pssh[i + 1] == 0x10.toByte()) return pssh.copyOfRange(i + 2, i + 18)
        }
        return pssh.copyOfRange(minOf(dataStart, pssh.size), pssh.size)
    }

    internal fun attribute(line: String, name: String): String? {
        val match = Regex("""(?:^|[:,])$name=("([^"]*)"|[^,]*)""").find(line) ?: return null
        return match.groupValues[2].ifEmpty { match.groupValues[1] }
    }

    private fun resolve(base: String, uri: String): String =
        if (uri.startsWith("http")) uri else base.substringBeforeLast('/') + "/" + uri
}

internal fun ByteArray.indexOf(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

