package com.unshoo.pixelmusic.data.addons

import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceType
import com.unshoo.pixelmusic.presentation.viewmodel.PlaybackStreamReconciler
import com.unshoo.pixelmusic.presentation.viewmodel.ConfirmedAudioFormat
import com.unshoo.pixelmusic.presentation.viewmodel.OfferedStream
import com.unshoo.pixelmusic.utils.AudioMetaUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonSubsystemTest {

    @Test
    fun unifiedOrder_placesInstalledAddonsFirstByDefault_andPreservesCustomOrder() {
        val addonA = InstalledAddon(
            id = "tidomeow",
            protocol = AddonProtocol.HTTP_ADDON,
            name = "Tidomeow",
        )
        val addonB = InstalledAddon(
            id = "spine-qobuz",
            protocol = AddonProtocol.SPINE_JS,
            name = "Spine Qobuz",
        )

        val defaultOrder = UnifiedSourceId.buildUnifiedOrder(
            savedUnifiedCsv = null,
            legacyBuiltInCsv = null,
            addons = listOf(addonA, addonB),
        )
        assertEquals(
            listOf(
                "addon:tidomeow",
                "addon:spine-qobuz",
                "builtin:MONOCHROME",
                "builtin:TIDAL",
                "builtin:QOBUZ",
                "builtin:DEEZER",
                "builtin:APPLE",
                "builtin:JIOSAAVN",
                "builtin:YOUTUBE",
            ),
            defaultOrder,
        )

        // User drags Monochrome above Tidomeow, then installs a third addon C -> C prepends while custom order stays intact
        val addonC = InstalledAddon(
            id = "arch-hires",
            protocol = AddonProtocol.ARCH_PACKAGE,
            name = "Arch HiRes",
        )
        val customSaved = "builtin:MONOCHROME,addon:tidomeow,builtin:QOBUZ,addon:spine-qobuz,builtin:YOUTUBE"
        val merged = UnifiedSourceId.buildUnifiedOrder(
            savedUnifiedCsv = customSaved,
            legacyBuiltInCsv = null,
            addons = listOf(addonA, addonB, addonC),
        )
        assertEquals("addon:arch-hires", merged[0])
        assertEquals("builtin:MONOCHROME", merged[1])
        assertEquals("addon:tidomeow", merged[2])
        assertEquals("builtin:QOBUZ", merged[3])
        assertEquals("addon:spine-qobuz", merged[4])
        assertEquals("builtin:YOUTUBE", merged.last())
    }

    @Test
    fun unifiedSongSourceOverride_supportsAddonsBuiltInsAndLegacyEntries() {
        val legacyRaw = "dQw4w9WgXcQ=TIDAL;abc123XYZ=YOUTUBE"
        assertEquals("builtin:TIDAL", UnifiedSongSourceOverride.get(legacyRaw, "youtube_dQw4w9WgXcQ"))
        assertEquals("builtin:YOUTUBE", UnifiedSongSourceOverride.get(legacyRaw, "youtube://abc123XYZ"))

        val updated = UnifiedSongSourceOverride.withOverride(
            raw = legacyRaw,
            mediaId = "dQw4w9WgXcQ",
            sourceId = "addon:tidomeow",
        )
        assertEquals("addon:tidomeow", UnifiedSongSourceOverride.get(updated, "dQw4w9WgXcQ"))

        val cleared = UnifiedSongSourceOverride.withOverride(
            raw = updated,
            mediaId = "dQw4w9WgXcQ",
            sourceId = null,
        )
        assertNull(UnifiedSongSourceOverride.get(cleared, "dQw4w9WgXcQ"))
        assertEquals("builtin:YOUTUBE", UnifiedSongSourceOverride.get(cleared, "abc123XYZ"))
    }

    @Test
    fun installerUrlHelpers_preserveTokenizedRootPaths() {
        assertEquals(
            "https://tidomeow.example.org/7d8f5e42bea241d7b68ed8e0716fc29c",
            AddonInstaller.stripManifestSuffix(
                "https://tidomeow.example.org/7d8f5e42bea241d7b68ed8e0716fc29c/manifest.json"
            ),
        )
        assertEquals(
            "https://addon.example.org/manifest.json",
            AddonInstaller.normalizeInputUrl("stremio://addon.example.org/manifest.json#section"),
        )
    }

    @Test
    fun trackMatcher_prioritizesExactIsrc_andRejectsLiveOrKaraokeMismatches() {
        val studioCandidate = AddonTrackCandidate(
            id = "100",
            title = "Get Lucky",
            artists = listOf("Daft Punk", "Pharrell Williams"),
            durationMs = 369_000L,
            isrc = "USQX91300108",
        )
        val liveVariant = AddonTrackCandidate(
            id = "101",
            title = "Get Lucky (Live in Paris)",
            artists = listOf("Daft Punk"),
            durationMs = 369_000L,
        )
        val karaokeVariant = AddonTrackCandidate(
            id = "102",
            title = "Get Lucky (Karaoke Version)",
            artists = listOf("Party Tyme Karaoke"),
            durationMs = 369_000L,
        )

        val ranked = AddonTrackMatcher.rankCandidates(
            wantedTitle = "Get Lucky (Official Audio)",
            wantedArtists = listOf("Daft Punk feat. Pharrell Williams"),
            wantedAlbum = "Random Access Memories",
            wantedDurationMs = 369_600L,
            wantedIsrc = "USQX91300108",
            candidates = listOf(karaokeVariant, liveVariant, studioCandidate),
        )
        assertEquals(1, ranked.size)
        assertEquals("100", ranked.first().candidate.id)
        assertTrue(ranked.first().score > 1.0)
    }

    @Test
    fun dolbyAtmosAndAddonAttribution_formatAndReconcileCleanly() {
        assertEquals(
            "DOLBY ATMOS • 768 kbps",
            AudioMetaUtils.formatAudioMetaLabel(
                mimeType = "audio/eac3-joc",
                bitrate = 768_000,
                sampleRate = 48_000,
                bitDepth = null,
                formatTag = "DOLBY ATMOS",
            ),
        )
        assertEquals(
            "DOLBY ATMOS",
            AudioMetaUtils.formatShareCardAudioTag(
                mimeType = "audio/eac3-joc",
                bitDepth = null,
                sampleRate = 48_000,
                formatTag = "DOLBY ATMOS",
            ),
        )

        val reconciled = PlaybackStreamReconciler.reconcile(
            mediaId = "youtube_20115564",
            confirmed = ConfirmedAudioFormat(
                sampleMimeType = "audio/flac",
                containerMimeType = null,
                bitrate = 2_304_000,
                sampleRate = 96_000,
                channelCount = 2,
                bitDepth = 24,
            ),
            offered = OfferedStream(
                mimeType = "audio/flac",
                bitrate = 2_304_000,
                sampleRate = 96_000,
                bitDepth = 24,
                sourceName = "Tidomeow",
                labelSaysHiRes = true,
                isAtmos = false,
            ),
            previous = null,
        )
        assertEquals("Tidomeow", reconciled.sourceName)
        assertTrue(reconciled.formatTag?.contains("HI-RES") == true)
        assertFalse(reconciled.mimeType.isNullOrBlank())
        assertEquals(AudioSourceType.ADDON, UnifiedSourceId.builtInTypeOrNull("ADDON"))
    }
}
