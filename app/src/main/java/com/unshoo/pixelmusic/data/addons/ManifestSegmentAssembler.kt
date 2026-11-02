package com.unshoo.pixelmusic.data.addons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * Losslessly downloads and reassembles CMAF HLS (`.m3u8`) and MPEG-DASH (`.mpd` / inline MPD)
 * audio manifests served by Tidal/Qobuz/8SPINE/HTTP addons into a single native `.flac` or `.m4a`
 * file on disk (ported and hardened from BitChord's `ManifestFile` + `Mp4Boxes` + `OfflineDash`).
 *
 * Why this is essential:
 *  - Modern Tidal / 8SPINE / Tidomeow addons serve lossless FLAC as CMAF fMP4 fragments (`init` +
 *    `moof`/`mdat` segments) indexed by an `.m3u8` or `.mpd` manifest.
 *  - Without segment assembly, a naive HTTP downloader writes 1.5 KB of `#EXTM3U` or XML text into
 *    `song.flac`, producing an unplayable download.
 *  - For `flac`: extracts the `dfLa` FLAC metadata blocks from `moov/trak/mdia/minf/stbl/stsd/fLaC`,
 *    concatenates every FLAC frame from each fragment's `moof`/`trun` + `mdat`, and rewrites
 *    `STREAMINFO`'s 36-bit total sample count so Media3 and TagLib can seek and tag natively!
 *  - For `m4a`: writes the `init` segment followed by each fragment stripped of per-segment
 *    `styp`/`sidx` boxes.
 */
object ManifestSegmentAssembler {

    data class SegmentPlan(
        val initializationUrl: String,
        val mediaUrls: List<String>,
    )

    data class AssembledFileResult(
        val extension: String,
        val mimeType: String,
    )

    fun isManifestStream(url: String): Boolean {
        if (url.startsWith("${AddonStreamRegistry.INLINE_SCHEME}://", ignoreCase = true)) return true
        return AddonStreamRegistry.containerMimeOf(url) != null
    }

    fun isSegmentedStream(url: String): Boolean = isManifestStream(url)

    suspend fun assembleToFile(
        httpClient: OkHttpClient,
        streamUri: String,
        outputFile: File,
        extraHeaders: Map<String, String> = emptyMap(),
    ): AssembledFileResult {
        val registered = AddonStreamRegistry.get(streamUri)
        val audioMime = registered?.audioMimeType.orEmpty().lowercase(Locale.ROOT)
        val wantsFlac = audioMime.isEmpty() || audioMime.contains("flac")
        val targetExt = if (wantsFlac) "flac" else "m4a"
        val cacheDir = outputFile.parentFile ?: File(System.getProperty("java.io.tmpdir", "/tmp"))
        downloadToFile(
            httpClient = httpClient,
            cacheDir = cacheDir,
            manifestUrl = streamUri,
            headers = extraHeaders,
            targetExtension = targetExt,
            outputFile = outputFile,
        )
        return AssembledFileResult(
            extension = targetExt,
            mimeType = if (wantsFlac) "audio/flac" else "audio/mp4",
        )
    }

    suspend fun downloadToFile(
        httpClient: OkHttpClient,
        cacheDir: File,
        manifestUrl: String,
        headers: Map<String, String>,
        targetExtension: String,
        outputFile: File,
    ) = withContext(Dispatchers.IO) {
        fun fetchBytes(url: String): ByteArray {
            val req = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    header("User-Agent", "Mozilla/5.0 (Linux; Android 14) PixelMusic/2.0")
                }
            }.build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Segment HTTP ${resp.code} for $url")
                return resp.body?.bytes() ?: throw IOException("Empty segment body for $url")
            }
        }

        val registered = AddonStreamRegistry.get(manifestUrl)
        val manifestText: String
        val baseHttpUrl = when {
            registered?.inlineManifestBytes != null -> {
                manifestText = registered.inlineManifestBytes.toString(Charsets.UTF_8)
                (registered.baseOriginUrl ?: "https://manifest.tidal.com/").toHttpUrlOrNull()
            }
            else -> {
                manifestText = fetchBytes(manifestUrl).toString(Charsets.UTF_8)
                manifestUrl.toHttpUrlOrNull()
            }
        } ?: throw IOException("Invalid manifest base URL: $manifestUrl")

        val isDash = registered?.transport == AddonTransport.DASH ||
            manifestUrl.substringBefore('?').endsWith(".mpd", ignoreCase = true) ||
            manifestText.contains("<MPD", ignoreCase = true)

        val plan = if (isDash) {
            parseDashPlan(manifestText) { rel -> baseHttpUrl.resolve(rel)?.toString() ?: rel }
        } else {
            parseHlsPlan(manifestText, fetchText = { u -> fetchBytes(u).toString(Charsets.UTF_8) }) { rel ->
                baseHttpUrl.resolve(rel)?.toString() ?: rel
            }
        }

        val initBytes = fetchBytes(plan.initializationUrl)
        val ctx = coroutineContext
        val mediaSeq = plan.mediaUrls.asSequence().map { segUrl ->
            ctx.ensureActive()
            fetchBytes(segUrl)
        }

        outputFile.outputStream().buffered().use { sink ->
            assembleSegments(cacheDir, targetExtension.lowercase(Locale.ROOT), initBytes, mediaSeq, sink)
            sink.flush()
        }
    }

    internal fun parseHlsPlan(
        playlist: String,
        fetchText: (String) -> String = { error("Master playlist fetch unavailable") },
        resolveUrl: (String) -> String,
    ): SegmentPlan {
        val trimmed = playlist.trimStart()
        if (!trimmed.startsWith("#EXTM3U")) throw IOException("Not an HLS #EXTM3U playlist")
        if (trimmed.contains("#EXT-X-KEY:") && !trimmed.contains("METHOD=NONE")) {
            throw IOException("Encrypted HLS stream cannot be downloaded")
        }

        // If master playlist (`#EXT-X-STREAM-INF`), pick highest BANDWIDTH variant playlist
        if (trimmed.contains("#EXT-X-STREAM-INF")) {
            val lines = trimmed.lines().map { it.trim() }
            var bestBw = -1L
            var bestVariant: String? = null
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val nextUri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
                    if (nextUri != null && bw >= bestBw) {
                        bestBw = bw
                        bestVariant = resolveUrl(nextUri)
                    }
                }
                i++
            }
            val variantUrl = bestVariant ?: throw IOException("HLS master playlist had no variant streams")
            val mediaPlaylist = fetchText(variantUrl)
            val variantHttp = variantUrl.toHttpUrlOrNull()
            return parseHlsPlan(
                playlist = mediaPlaylist,
                fetchText = fetchText,
                resolveUrl = { rel -> variantHttp?.resolve(rel)?.toString() ?: resolveUrl(rel) },
            )
        }

        val initRel = Regex("""#EXT-X-MAP:[^\r\n]*URI="([^"]+)"""").find(trimmed)?.groupValues?.get(1)
            ?: throw IOException("HLS playlist has no #EXT-X-MAP initialization segment")
        val mediaUrls = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map(resolveUrl)
            .toList()
        if (mediaUrls.isEmpty()) throw IOException("HLS playlist contains no media segments")
        return SegmentPlan(initializationUrl = resolveUrl(initRel), mediaUrls = mediaUrls)
    }

    internal fun parseDashPlan(
        mpdXml: String,
        resolveUrl: (String) -> String,
    ): SegmentPlan {
        if (Regex("<(?:[A-Za-z0-9_-]+:)?ContentProtection\\b", RegexOption.IGNORE_CASE).containsMatchIn(mpdXml)) {
            throw IOException("Encrypted DASH manifest cannot be downloaded")
        }

        val baseUrlInMpd = Regex("""<BaseURL>\s*([^<\s]+)\s*</BaseURL>""", RegexOption.IGNORE_CASE)
            .find(mpdXml)?.groupValues?.get(1)?.trim()
        val effectiveResolve: (String) -> String = { rel ->
            if (!baseUrlInMpd.isNullOrBlank()) {
                val resolvedBase = resolveUrl(baseUrlInMpd)
                resolvedBase.toHttpUrlOrNull()?.resolve(rel)?.toString() ?: resolveUrl(rel)
            } else {
                resolveUrl(rel)
            }
        }

        // Pick best Representation (prefer flac, then highest bandwidth)
        val repRegex = Regex("""<Representation\b([^>]*)>([\s\S]*?)</Representation>""", RegexOption.IGNORE_CASE)
        val reps = repRegex.findAll(mpdXml).toList()
        val chosenBody: String
        val chosenRepId: String
        val chosenBandwidth: String
        if (reps.isNotEmpty()) {
            val best = reps.maxByOrNull { m ->
                val attrs = m.groupValues[1]
                val codecs = attr(attrs, "codecs").orEmpty().lowercase(Locale.ROOT)
                val bw = attr(attrs, "bandwidth")?.toLongOrNull() ?: 0L
                val flacBonus = if (codecs.contains("flac")) 100_000_000L else 0L
                flacBonus + bw
            }!!
            chosenRepId = attr(best.groupValues[1], "id").orEmpty()
            chosenBandwidth = attr(best.groupValues[1], "bandwidth").orEmpty()
            chosenBody = if (best.groupValues[2].contains("<SegmentTemplate", ignoreCase = true)) {
                best.groupValues[2]
            } else {
                mpdXml
            }
        } else {
            chosenRepId = ""
            chosenBandwidth = ""
            chosenBody = mpdXml
        }

        val segTplMatch = Regex("""<SegmentTemplate\b([^>]*)(?:/>|>([\s\S]*?)</SegmentTemplate>)""", RegexOption.IGNORE_CASE)
            .find(chosenBody) ?: throw IOException("DASH manifest missing <SegmentTemplate>")
        val tplAttrs = segTplMatch.groupValues[1]
        val tplInner = segTplMatch.groupValues[2]

        fun expandTemplate(tpl: String, number: Long = 0L, time: Long = 0L): String =
            tpl.replace("\$RepresentationID\$", chosenRepId)
                .replace("\$Bandwidth\$", chosenBandwidth)
                .replace(Regex("\\\$Number(?:%0(\\d+)d)?\\\$")) { m ->
                    val width = m.groupValues[1].toIntOrNull()
                    if (width != null) number.toString().padStart(width, '0') else number.toString()
                }
                .replace("\$Time\$", time.toString())

        val initTpl = attr(tplAttrs, "initialization") ?: throw IOException("DASH SegmentTemplate missing initialization")
        val mediaTpl = attr(tplAttrs, "media") ?: throw IOException("DASH SegmentTemplate missing media")
        val startNumber = attr(tplAttrs, "startNumber")?.toLongOrNull() ?: 1L

        val mediaUrls = ArrayList<String>()
        val sElements = Regex("""<S\b([^>]*)/?>""", RegexOption.IGNORE_CASE).findAll(tplInner).toList()
        if (sElements.isNotEmpty()) {
            var num = startNumber
            var currentTime = 0L
            for (s in sElements) {
                val sAttrs = s.groupValues[1]
                attr(sAttrs, "t")?.toLongOrNull()?.let { currentTime = it }
                val d = attr(sAttrs, "d")?.toLongOrNull() ?: 0L
                val r = attr(sAttrs, "r")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                repeat(r + 1) {
                    mediaUrls.add(effectiveResolve(expandTemplate(mediaTpl, number = num, time = currentTime)))
                    num++
                    currentTime += d
                }
            }
        } else {
            val timescale = attr(tplAttrs, "timescale")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
            val segDur = attr(tplAttrs, "duration")?.toDoubleOrNull()?.takeIf { it > 0 }
                ?: throw IOException("DASH SegmentTemplate has neither SegmentTimeline nor duration")
            val mpdDurSec = parseIsoDurationSeconds(attr(mpdXml.substringBefore('>'), "mediaPresentationDuration"))
                ?: throw IOException("DASH MPD missing mediaPresentationDuration")
            val count = kotlin.math.ceil(mpdDurSec * timescale / segDur).toInt().coerceIn(1, 5000)
            for (i in 0 until count) {
                val n = startNumber + i
                mediaUrls.add(effectiveResolve(expandTemplate(mediaTpl, number = n, time = (i * segDur).toLong())))
            }
        }

        if (mediaUrls.isEmpty()) throw IOException("DASH manifest produced zero segments")
        return SegmentPlan(
            initializationUrl = effectiveResolve(expandTemplate(initTpl, number = startNumber)),
            mediaUrls = mediaUrls,
        )
    }

    internal fun assembleSegments(
        cacheDir: File,
        extension: String,
        initSegment: ByteArray,
        mediaSegments: Sequence<ByteArray>,
        sink: OutputStream,
    ) {
        val sampleEntryType = inspectSampleEntryFourCc(initSegment)
        if (extension == "flac" && sampleEntryType == "fLaC") {
            assembleNativeFlac(cacheDir, initSegment, mediaSegments, sink)
        } else {
            // Fragmented MP4 / M4A container
            sink.write(initSegment)
            mediaSegments.forEach { seg ->
                mp4Boxes(seg).filter { it.type != "styp" && it.type != "sidx" }.forEach { box ->
                    sink.write(seg, box.offset, box.size)
                }
            }
        }
    }

    private fun assembleNativeFlac(
        cacheDir: File,
        init: ByteArray,
        mediaSegments: Sequence<ByteArray>,
        sink: OutputStream,
    ) {
        val trak = findBox(init, "moov", "trak") ?: throw IOException("Invalid CMAF init: missing moov/trak")
        val tkhd = childBox(init, trak, "tkhd") ?: throw IOException("Invalid CMAF init: missing tkhd")
        val trackId = readU32(init, tkhd.content + if (init[tkhd.content].toInt() == 1) 20 else 12)
        val mdhd = findBox(init, "moov", "trak", "mdia", "mdhd") ?: throw IOException("Invalid CMAF init: missing mdhd")
        val timescale = readU32(init, mdhd.content + if (init[mdhd.content].toInt() == 1) 20 else 12)
        val stsd = findBox(init, "moov", "trak", "mdia", "minf", "stbl", "stsd")
            ?: throw IOException("Invalid CMAF init: missing stsd")
        val entry = mp4Boxes(init, stsd.content + 8, stsd.end).firstOrNull()
        if (entry?.type != "fLaC") throw IOException("CMAF sample entry is ${entry?.type}, expected fLaC")
        val dfla = mp4Boxes(init, entry.content + 28, entry.end).firstOrNull { it.type == "dfLa" }
            ?: throw IOException("CMAF fLaC sample entry missing dfLa box")
        val blocks = parseFlacMetadataBlocks(init.copyOfRange(dfla.content + 4, dfla.end))

        val trex = findBox(init, "moov", "mvex", "trex")
        val defaultSampleDuration = trex?.let { readU32(init, it.content + 12) } ?: 0L
        val defaultSampleSize = trex?.let { readU32(init, it.content + 16) } ?: 0L

        cacheDir.mkdirs()
        val tempFrames = File.createTempFile("cmaf-flac-", ".frames", cacheDir)
        try {
            var totalTicks = 0L
            tempFrames.outputStream().buffered().use { frameOut ->
                mediaSegments.forEach { seg ->
                    mp4Boxes(seg).filter { it.type == "moof" }.forEach { moof ->
                        val samples = extractMoofSamples(seg, moof, trackId, defaultSampleDuration, defaultSampleSize)
                        for (s in samples) {
                            frameOut.write(seg, s.offset, s.size)
                            totalTicks += s.duration
                        }
                    }
                }
            }
            if (totalTicks <= 0L && tempFrames.length() <= 0L) {
                throw IOException("CMAF manifest segments contained no FLAC audio frames")
            }

            // Rewrite STREAMINFO 36-bit total sample count so seeking works in Media3 & TagLib
            val streamInfo = blocks.first().second
            val sampleRate = ((streamInfo[10].toLong() and 0xFF) shl 12) or
                ((streamInfo[11].toLong() and 0xFF) shl 4) or
                ((streamInfo[12].toLong() and 0xFF) shr 4)
            val totalSamples = if (timescale > 0 && sampleRate > 0) totalTicks * sampleRate / timescale else totalTicks
            streamInfo[13] = ((streamInfo[13].toInt() and 0xF0) or ((totalSamples shr 32).toInt() and 0x0F)).toByte()
            for (b in 0 until 4) {
                streamInfo[14 + b] = (totalSamples shr (8 * (3 - b))).toByte()
            }

            sink.write("fLaC".toByteArray(Charsets.ISO_8859_1))
            blocks.forEachIndexed { idx, (blockType, payload) ->
                val lastFlag = if (idx == blocks.lastIndex) 0x80 else 0
                sink.write(
                    byteArrayOf(
                        (lastFlag or blockType).toByte(),
                        (payload.size shr 16).toByte(),
                        (payload.size shr 8).toByte(),
                        payload.size.toByte(),
                    )
                )
                sink.write(payload)
            }
            tempFrames.inputStream().buffered().use { it.copyTo(sink) }
        } finally {
            tempFrames.delete()
        }
    }

    private fun inspectSampleEntryFourCc(init: ByteArray): String? {
        val stsd = findBox(init, "moov", "trak", "mdia", "minf", "stbl", "stsd") ?: return null
        return mp4Boxes(init, stsd.content + 8, stsd.end).firstOrNull()?.type
    }

    private fun parseFlacMetadataBlocks(dfLaBody: ByteArray): List<Pair<Int, ByteArray>> {
        val list = ArrayList<Pair<Int, ByteArray>>()
        var p = 0
        while (p + 4 <= dfLaBody.size) {
            val isLast = (dfLaBody[p].toInt() and 0x80) != 0
            val type = dfLaBody[p].toInt() and 0x7F
            val len = ((dfLaBody[p + 1].toInt() and 0xFF) shl 16) or
                ((dfLaBody[p + 2].toInt() and 0xFF) shl 8) or
                (dfLaBody[p + 3].toInt() and 0xFF)
            if (p + 4 + len > dfLaBody.size) break
            list.add(type to dfLaBody.copyOfRange(p + 4, p + 4 + len))
            p += 4 + len
            if (isLast) break
        }
        if (list.firstOrNull()?.let { it.first == 0 && it.second.size == 34 } != true) {
            throw IOException("dfLa payload missing 34-byte FLAC STREAMINFO")
        }
        return list
    }

    private data class Mp4Box(val type: String, val offset: Int, val content: Int, val size: Int) {
        val end: Int get() = offset + size
    }

    private data class SampleSlice(val offset: Int, val size: Int, val duration: Long)

    private fun mp4Boxes(data: ByteArray, from: Int = 0, to: Int = data.size): List<Mp4Box> {
        val out = ArrayList<Mp4Box>()
        var pos = from
        while (pos + 8 <= to) {
            val rawSize = readU32(data, pos)
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            val (headerLen, boxSize) = when {
                rawSize == 1L -> {
                    if (pos + 16 > to) break
                    val large = (readU32(data, pos + 8) shl 32) or readU32(data, pos + 12)
                    16 to large.toInt()
                }
                rawSize == 0L -> 8 to (to - pos)
                else -> 8 to rawSize.toInt()
            }
            if (boxSize < headerLen || pos + boxSize > to) break
            out.add(Mp4Box(type, pos, pos + headerLen, boxSize))
            pos += boxSize
        }
        return out
    }

    private fun childBox(data: ByteArray, parent: Mp4Box, type: String): Mp4Box? =
        mp4Boxes(data, parent.content, parent.end).firstOrNull { it.type == type }

    private fun findBox(data: ByteArray, vararg path: String): Mp4Box? {
        var current: Mp4Box? = null
        for ((idx, name) in path.withIndex()) {
            current = if (idx == 0) {
                mp4Boxes(data).firstOrNull { it.type == name }
            } else {
                childBox(data, current ?: return null, name)
            }
            if (current == null) return null
        }
        return current
    }

    private fun extractMoofSamples(
        segment: ByteArray,
        moof: Mp4Box,
        expectedTrackId: Long,
        trexDuration: Long,
        trexSize: Long,
    ): List<SampleSlice> {
        val out = ArrayList<SampleSlice>()
        val trafs = mp4Boxes(segment, moof.content, moof.end).filter { it.type == "traf" }
        val fallbackMdatContent = mp4Boxes(segment, moof.end, segment.size)
            .firstOrNull { it.type == "mdat" }?.content ?: -1

        for (traf in trafs) {
            val tfhd = childBox(segment, traf, "tfhd") ?: continue
            val tfhdFlags = readU24(segment, tfhd.content + 1)
            val trackId = readU32(segment, tfhd.content + 4)
            if (trackId != expectedTrackId) continue

            var cursor = tfhd.content + 8
            var baseDataOffset = moof.offset.toLong()
            if ((tfhdFlags and 0x000001) != 0) {
                baseDataOffset = (readU32(segment, cursor) shl 32) or readU32(segment, cursor + 4)
                cursor += 8
            }
            if ((tfhdFlags and 0x000002) != 0) cursor += 4 // sample_description_index
            val defaultDur = if ((tfhdFlags and 0x000008) != 0) readU32(segment, cursor).also { cursor += 4 } else trexDuration
            val defaultSize = if ((tfhdFlags and 0x000010) != 0) readU32(segment, cursor).also { cursor += 4 } else trexSize

            val truns = mp4Boxes(segment, traf.content, traf.end).filter { it.type == "trun" }
            var runningOffset = if (fallbackMdatContent >= 0) fallbackMdatContent else baseDataOffset.toInt()
            for (trun in truns) {
                val trunFlags = readU24(segment, trun.content + 1)
                val sampleCount = readU32(segment, trun.content + 4).toInt()
                var p = trun.content + 8
                if ((trunFlags and 0x000001) != 0) {
                    val dataOffset = readS32(segment, p)
                    runningOffset = (baseDataOffset + dataOffset).toInt()
                    p += 4
                }
                if ((trunFlags and 0x000004) != 0) p += 4 // first_sample_flags

                val hasDur = (trunFlags and 0x000100) != 0
                val hasSize = (trunFlags and 0x000200) != 0
                val hasFlags = (trunFlags and 0x000400) != 0
                val hasCto = (trunFlags and 0x000800) != 0

                repeat(sampleCount) {
                    val dur = if (hasDur) readU32(segment, p).also { p += 4 } else defaultDur
                    val sz = (if (hasSize) readU32(segment, p).also { p += 4 } else defaultSize).toInt()
                    if (hasFlags) p += 4
                    if (hasCto) p += 4
                    if (sz > 0 && runningOffset >= 0 && runningOffset + sz <= segment.size) {
                        out.add(SampleSlice(runningOffset, sz, dur))
                        runningOffset += sz
                    }
                }
            }
        }
        return out
    }

    private fun readU24(b: ByteArray, pos: Int): Int =
        ((b[pos].toInt() and 0xFF) shl 16) or
            ((b[pos + 1].toInt() and 0xFF) shl 8) or
            (b[pos + 2].toInt() and 0xFF)

    private fun readU32(b: ByteArray, pos: Int): Long =
        ((b[pos].toLong() and 0xFF) shl 24) or
            ((b[pos + 1].toLong() and 0xFF) shl 16) or
            ((b[pos + 2].toLong() and 0xFF) shl 8) or
            (b[pos + 3].toLong() and 0xFF)

    private fun readS32(b: ByteArray, pos: Int): Int =
        ((b[pos].toInt() and 0xFF) shl 24) or
            ((b[pos + 1].toInt() and 0xFF) shl 16) or
            ((b[pos + 2].toInt() and 0xFF) shl 8) or
            (b[pos + 3].toInt() and 0xFF)

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b${Regex.escape(name)}\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.get(1)

    private fun parseIsoDurationSeconds(iso: String?): Double? {
        if (iso.isNullOrBlank()) return null
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""", RegexOption.IGNORE_CASE)
            .find(iso) ?: return null
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return (h * 3600.0 + min * 60.0 + s).takeIf { it > 0.0 }
    }
}
