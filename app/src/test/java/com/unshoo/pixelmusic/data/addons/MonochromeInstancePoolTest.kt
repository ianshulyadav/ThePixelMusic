package com.unshoo.pixelmusic.data.addons

import androidx.datastore.preferences.core.preferencesOf
import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceType
import com.unshoo.pixelmusic.data.lossless.constants.LosslessStreamingEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.MonochromeEnabledKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MonochromeInstancePoolTest {

    @BeforeEach
    @AfterEach
    fun resetTracker() {
        AddonHealthTracker.clearAll()
    }

    @Test
    fun `html parked mirror enters cooldown while working monochrome server resolves flac`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url.startsWith("https://wolf.qqdl.site") -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html")
                        .body("<html><body>Redirecting...</body></html>".toResponseBody("text/html".toMediaType()))
                        .build()

                    url.startsWith("https://tracks.monochrome.st/search/tracks") -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json")
                        .body(
                            """
                            {"tracks":[{"id":"998877","title":"Blinding Lights","artistNames":["The Weeknd"],"duration":200040,"playable":true}]}
                            """.trimIndent().toResponseBody("application/json".toMediaType())
                        )
                        .build()

                    else -> error("Unexpected probe URL: $url")
                }
            })
            .build()

        val stream = MonochromeInstancePool.resolve(
            httpClient = client,
            title = "Blinding Lights",
            artists = listOf("The Weeknd"),
            album = null,
            durationMs = 200_040L,
            customInstancesRaw = "https://wolf.qqdl.site\nhttps://tracks.monochrome.st",
        )

        assertNotNull(stream)
        assertEquals(AudioSourceType.MONOCHROME, stream!!.source)
        assertEquals("https://tracks.monochrome.st/track/998877", stream.uri)
        assertFalse(AddonHealthTracker.isAvailable("mono:https://wolf.qqdl.site"))
    }

    @Test
    fun `rate limited mirror is deprioritized behind healthy custom instances`() {
        AddonHealthTracker.noteOriginRateLimit("https://tracks.monochrome.st/track/1", "60")

        val active = MonochromeInstancePool.activeInstances("https://custom.mono.example")

        assertEquals("https://custom.mono.example", active.first())
        assertFalse(active.contains("https://tracks.monochrome.st"))
    }

    @Test
    fun `cancelling coroutine interrupts monochrome resolve and cancels active okhttp calls`() = runBlocking {
        val started = CountDownLatch(1)
        val sawCancel = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                started.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < deadline) {
                    if (chain.call().isCanceled()) {
                        sawCancel.set(true)
                        throw java.io.IOException("Canceled")
                    }
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        sawCancel.set(true)
                        throw java.io.IOException("Interrupted")
                    }
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(504)
                    .message("Timeout")
                    .body("".toResponseBody())
                    .build()
            })
            .build()

        val job = async(Dispatchers.IO) {
            runInterruptible {
                MonochromeInstancePool.resolve(
                    httpClient = client,
                    title = "Blinding Lights",
                    artists = listOf("The Weeknd"),
                    album = null,
                    durationMs = 200_040L,
                    customInstancesRaw = "https://tracks.monochrome.st",
                )
            }
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        val cancelStartedAt = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelStartedAt)

        assertTrue(elapsedMs < 1_000L, "Expected fast cancellation (<1s), took ${elapsedMs}ms")
        assertTrue(sawCancel.get(), "Expected OkHttp call to observe cancellation")
    }

    @Test
    fun `monochrome defaults to enabled when lossless master is on and explicit key is unset`() {
        val masterOnUnset = preferencesOf(LosslessStreamingEnabledKey to true)
        val masterOnExplicitOff = preferencesOf(
            LosslessStreamingEnabledKey to true,
            MonochromeEnabledKey to false,
        )
        val masterOffUnset = preferencesOf(LosslessStreamingEnabledKey to false)

        assertTrue(AddonManager.isBuiltInEnabled(AudioSourceType.MONOCHROME, masterOnUnset))
        assertFalse(AddonManager.isBuiltInEnabled(AudioSourceType.MONOCHROME, masterOnExplicitOff))
        assertFalse(AddonManager.isBuiltInEnabled(AudioSourceType.MONOCHROME, masterOffUnset))
    }
}
