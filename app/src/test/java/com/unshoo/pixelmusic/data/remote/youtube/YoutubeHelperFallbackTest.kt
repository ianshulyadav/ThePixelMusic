package com.unshoo.pixelmusic.data.remote.youtube

import android.content.Context
import com.unshoo.pixelmusic.data.addons.AddonManager
import com.unshoo.pixelmusic.data.addons.UnifiedSourceId
import com.unshoo.pixelmusic.data.database.youtube.AppDatabase
import com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver
import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceType
import com.unshoo.pixelmusic.data.model.youtube.Song
import com.unshoo.pixelmusic.data.preferences.StreamingAudioQuality
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.remote.saavn.SaavnAudioQuality
import com.unshoo.pixelmusic.data.remote.saavn.SaavnDownloadUrl
import com.unshoo.pixelmusic.data.remote.saavn.SaavnService
import com.unshoo.pixelmusic.data.remote.saavn.SaavnSong
import com.unshoo.pixelmusic.data.remote.saavn.SaavnStreamResult
import com.unshoo.pixelmusic.presentation.viewmodel.ConnectivityStateHolder
import com.unshoo.pixelmusic.utils.OfflineAudioResolver
import dagger.hilt.android.EntryPointAccessors
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

class YoutubeHelperFallbackTest {

    private lateinit var tempDir: java.nio.file.Path
    private lateinit var context: Context
    private lateinit var prefsRepo: UserPreferencesRepository
    private lateinit var connectivity: ConnectivityStateHolder

    private var saavnEnabledFlow = MutableStateFlow(true)
    private var isOnlineFlow = MutableStateFlow(true)
    private var isMeteredFlow = MutableStateFlow(true)

    private val saavnUrl = "https://aac.saavncdn.com/123/test_320.mp4"
    private val ytUrl = "https://rr1.googlevideo.com/videoplayback?expire=4102444800&itag=251"
    private val losslessUrl = "https://tracks.monochrome.st/track/998877"

    @BeforeEach
    fun setUp() {
        YoutubeHelper.invalidateAllStreamCaches()
        tempDir = Files.createTempDirectory("yt-helper-fallback-test")

        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns tempDir.toFile()
        every { context.cacheDir } returns tempDir.toFile()

        saavnEnabledFlow = MutableStateFlow(true)
        isOnlineFlow = MutableStateFlow(true)
        isMeteredFlow = MutableStateFlow(true)

        connectivity = mockk(relaxed = true)
        every { connectivity.isOnline } returns isOnlineFlow
        every { connectivity.isMeteredNetwork } returns isMeteredFlow
        every { connectivity.linkDownstreamBandwidthKbps } returns MutableStateFlow(0)
        every { connectivity.isNetworkRecentlyUnstable } returns MutableStateFlow(false)

        prefsRepo = mockk(relaxed = true)
        every { prefsRepo.forceHighQualityOnMobileFlow } returns flowOf(false)
        every { prefsRepo.streamingAudioQualityMobileFlow } returns flowOf(StreamingAudioQuality.AUTO)
        every { prefsRepo.streamingAudioQualityWifiFlow } returns flowOf(StreamingAudioQuality.AUTO)
        every { prefsRepo.enableSaavnStreamingFlow } returns saavnEnabledFlow
        every { prefsRepo.saavnAudioQualityFlow } returns flowOf(SaavnAudioQuality.AUTO)

        val entryPoint = object : YoutubeHelperEntryPoint {
            override fun connectivityStateHolder(): ConnectivityStateHolder = connectivity
            override fun userPreferencesRepository(): UserPreferencesRepository = prefsRepo
        }

        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(context, YoutubeHelperEntryPoint::class.java)
        } returns entryPoint

        mockkObject(OfflineAudioResolver)
        every { OfflineAudioResolver.readableAudioPath(any(), any()) } returns null

        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getInstance(any()) } throws IllegalStateException("No Room DB in unit test")

        mockkObject(AddonManager)
        coEvery { AddonManager.readUnifiedOrder(any<Context>()) } returns UnifiedSourceId.DEFAULT_BUILTIN_ORDER

        mockkObject(LosslessStreamResolver)
        coEvery { LosslessStreamResolver.isEnabled(any()) } returns true
        coEvery { LosslessStreamResolver.hasAnyActiveLosslessSource(any<Context>()) } returns true
        coEvery { LosslessStreamResolver.getUnifiedSongSourceOverride(any(), any()) } returns null
        every { LosslessStreamResolver.isFailedForTrack(any()) } returns false
        every { LosslessStreamResolver.resultFor(any()) } returns null
        every { LosslessStreamResolver.isLosslessUri(any()) } answers {
            val u = firstArg<String?>().orEmpty()
            u.contains("monochrome.st/track/") || u.endsWith(".flac")
        }
        every { LosslessStreamResolver.isFreshUri(any()) } returns true
        every { LosslessStreamResolver.markUriFresh(any(), any()) } returns Unit

        mockkObject(SaavnService)
        mockkObject(YoutubeHelper)
        coEvery {
            YoutubeHelper.getSongUrlFromYoutube(any(), any(), any(), any(), any(), any())
        } returns Triple(ytUrl, "audio/opus", 160_000)
    }

    @AfterEach
    fun tearDown() {
        YoutubeHelper.invalidateAllStreamCaches()
        unmockkAll()
        tempDir.toFile().deleteRecursively()
    }

    private fun testSong(videoId: String = "dQw4w9WgXcQ") = Song(
        youtubeId = videoId,
        title = "Never Gonna Give You Up",
        artist = "Rick Astley",
        album = "Whenever You Need Somebody",
        duration = "213",
    )

    private fun saavnResult(url: String = saavnUrl) = SaavnStreamResult(
        url = url,
        bitrateKbps = 320,
        quality = "320kbps",
        saavnSong = SaavnSong(id = "saavn1", name = "Never Gonna Give You Up"),
    )

    @Test
    fun `next song preload resolves lossless first when available`() = runTest {
        coEvery { LosslessStreamResolver.resolve(any(), any(), any(), any()) } returns LosslessStreamResolver.Result(
            mediaId = "dQw4w9WgXcQ",
            uri = losslessUrl,
            mimeType = "audio/flac",
            codecs = "flac",
            bitrate = 1_411_200,
            sampleRate = 44_100,
            bitDepth = 16,
            contentLength = 30_000_000L,
            label = "FLAC",
            source = AudioSourceType.MONOCHROME,
        )

        val resolved = YoutubeHelper.getSongPlayerUrl(
            context = context,
            song = testSong(),
            allowLocal = false,
            allowLossless = true,
            isNextInQueue = true,
        )

        assertEquals(losslessUrl, resolved)
        coVerify(exactly = 0) { SaavnService.resolveStream(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { YoutubeHelper.getSongUrlFromYoutube(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `next song preload falls back to JioSaavn when lossless unavailable and JioSaavn enabled`() = runTest {
        coEvery { LosslessStreamResolver.resolve(any(), any(), any(), any()) } returns null
        coEvery { SaavnService.resolveStream(any(), any(), any(), any(), any(), any(), any(), any()) } returns saavnResult()

        val resolved = YoutubeHelper.getSongPlayerUrl(
            context = context,
            song = testSong(),
            allowLocal = false,
            allowLossless = true,
            isNextInQueue = true,
        )

        assertEquals(saavnUrl, resolved)
        coVerify(exactly = 1) { LosslessStreamResolver.resolve(any(), any(), any(), any()) }
        coVerify(exactly = 1) { SaavnService.resolveStream(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { YoutubeHelper.getSongUrlFromYoutube(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `next song preload skips JioSaavn and falls back to YouTube when JioSaavn disabled`() = runTest {
        saavnEnabledFlow.value = false
        coEvery { LosslessStreamResolver.resolve(any(), any(), any(), any()) } returns null

        val resolved = YoutubeHelper.getSongPlayerUrl(
            context = context,
            song = testSong(),
            allowLocal = false,
            allowLossless = true,
            isNextInQueue = true,
        )

        assertEquals(ytUrl, resolved)
        coVerify(exactly = 1) { LosslessStreamResolver.resolve(any(), any(), any(), any()) }
        coVerify(exactly = 0) { SaavnService.resolveStream(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { YoutubeHelper.getSongUrlFromYoutube(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `next song preload falls back to YouTube when both lossless and JioSaavn return null`() = runTest {
        saavnEnabledFlow.value = true
        coEvery { LosslessStreamResolver.resolve(any(), any(), any(), any()) } returns null
        coEvery { SaavnService.resolveStream(any(), any(), any(), any(), any(), any(), any(), any()) } returns null

        val resolved = YoutubeHelper.getSongPlayerUrl(
            context = context,
            song = testSong(),
            allowLocal = false,
            allowLossless = true,
            isNextInQueue = true,
        )

        assertEquals(ytUrl, resolved)
        coVerify(exactly = 1) { LosslessStreamResolver.resolve(any(), any(), any(), any()) }
        coVerify(exactly = 1) { SaavnService.resolveStream(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { YoutubeHelper.getSongUrlFromYoutube(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `lookahead non-lossless cache upgrades to lossless when track becomes next in queue`() = runTest {
        saavnEnabledFlow.value = false
        isMeteredFlow.value = false

        // 1. Deeper lookahead slot resolves without lossless -> warms YouTube URL
        val lookaheadResolved = YoutubeHelper.getSongPlayerUrl(
            context = context,
            song = testSong("AbCdEfGh123"),
            allowLocal = false,
            allowLossless = false,
            isNextInQueue = false,
        )
        assertEquals(ytUrl, lookaheadResolved)
        coVerify(exactly = 0) { LosslessStreamResolver.resolve(any(), any(), any(), any()) }
        coVerify(exactly = 1) { YoutubeHelper.getSongUrlFromYoutube(any(), any(), any(), any(), any(), any()) }

        // 2. Same track advances to nextIndex -> must still check lossless and upgrade!
        coEvery { LosslessStreamResolver.resolve(any(), any(), any(), any()) } returns LosslessStreamResolver.Result(
            mediaId = "AbCdEfGh123",
            uri = losslessUrl,
            mimeType = "audio/flac",
            codecs = "flac",
            bitrate = 1_411_200,
            sampleRate = 44_100,
            bitDepth = 16,
            contentLength = 30_000_000L,
            label = "FLAC",
            source = AudioSourceType.MONOCHROME,
        )

        val nextResolved = YoutubeHelper.getSongPlayerUrl(
            context = context,
            song = testSong("AbCdEfGh123"),
            allowLocal = false,
            allowLossless = true,
            isNextInQueue = true,
        )
        assertEquals(losslessUrl, nextResolved)
        coVerify(exactly = 1) { LosslessStreamResolver.resolve(any(), any(), any(), any()) }
        // No second YouTube Innertube fetch was performed
        coVerify(exactly = 1) { YoutubeHelper.getSongUrlFromYoutube(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `lookahead fallback is reused without second YouTube call when next song lossless unavailable`() = runTest {
        saavnEnabledFlow.value = false
        isMeteredFlow.value = false

        val lookaheadResolved = YoutubeHelper.getSongPlayerUrl(
            context = context,
            song = testSong("ZyXwVuTs456"),
            allowLocal = false,
            allowLossless = false,
            isNextInQueue = false,
        )
        assertEquals(ytUrl, lookaheadResolved)
        coVerify(exactly = 1) { YoutubeHelper.getSongUrlFromYoutube(any(), any(), any(), any(), any(), any()) }

        coEvery { LosslessStreamResolver.resolve(any(), any(), any(), any()) } returns null

        val nextResolved = YoutubeHelper.getSongPlayerUrl(
            context = context,
            song = testSong("ZyXwVuTs456"),
            allowLocal = false,
            allowLossless = true,
            isNextInQueue = true,
        )
        assertEquals(ytUrl, nextResolved)
        coVerify(exactly = 1) { LosslessStreamResolver.resolve(any(), any(), any(), any()) }
        // Cached YouTube fallback is reused once lossless confirms unavailable
        coVerify(exactly = 1) { YoutubeHelper.getSongUrlFromYoutube(any(), any(), any(), any(), any(), any()) }

        // A third lookup for the same next song must not re-probe Lossless or YouTube
        val repeatNextResolved = YoutubeHelper.getSongPlayerUrl(
            context = context,
            song = testSong("ZyXwVuTs456"),
            allowLocal = false,
            allowLossless = true,
            isNextInQueue = true,
        )
        assertEquals(ytUrl, repeatNextResolved)
        coVerify(exactly = 1) { LosslessStreamResolver.resolve(any(), any(), any(), any()) }
        coVerify(exactly = 1) { YoutubeHelper.getSongUrlFromYoutube(any(), any(), any(), any(), any(), any()) }
    }
}
