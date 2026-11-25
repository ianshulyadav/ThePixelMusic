package com.unshoo.pixelmusic.presentation.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.unshoo.pixelmusic.MainCoroutineExtension
import com.unshoo.pixelmusic.data.addons.AddonManager
import com.unshoo.pixelmusic.data.lossless.constants.AddonsEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.AppleMusicSourceEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.DeezerEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.LosslessStreamingEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.MonochromeEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.QobuzEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.TidalEnabledKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class LosslessSourcesViewModelTest {

    private val disableAllLatch = CountDownLatch(1)

    @BeforeEach
    fun setUp() {
        mockkObject(AddonManager)
        coEvery { AddonManager.setAllAddonsEnabled(any(), any()) } answers {
            disableAllLatch.countDown()
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(AddonManager)
    }

    @Test
    fun `master lossless toggle enables monochrome on enable and turns off all sources and addons on disable`() = runTest {
        val tempDir = Files.createTempDirectory("lossless-vm-test")
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val context: Context = mockk(relaxed = true)
            every { context.applicationContext } returns context
            every { context.filesDir } returns tempDir.toFile()

            val dataStore = PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { tempDir.resolve("lossless_vm.preferences_pb").toFile() },
            )
            dataStore.edit { prefs ->
                prefs[TidalEnabledKey] = true
                prefs[QobuzEnabledKey] = true
                prefs[DeezerEnabledKey] = true
                prefs[AppleMusicSourceEnabledKey] = true
                prefs[AddonsEnabledKey] = true
            }

            val viewModel = LosslessSourcesViewModel(context = context, dataStore = dataStore)

            // 1. Turning main Lossless switch ON auto-enables Monochrome.
            viewModel.setLosslessEnabled(true)
            advanceUntilIdle()
            val afterEnable = withContext(Dispatchers.Default) {
                dataStore.data.first { it[LosslessStreamingEnabledKey] == true }
            }
            assertEquals(true, afterEnable[MonochromeEnabledKey])

            // 2. Turning main Lossless switch OFF turns off every source and all installed addons.
            viewModel.setLosslessEnabled(false)
            advanceUntilIdle()
            val afterDisable = withContext(Dispatchers.Default) {
                val prefs = dataStore.data.first { it[LosslessStreamingEnabledKey] == false }
                // Wait for IO continuation calling AddonManager.setAllAddonsEnabled cleanly.
                var attempts = 0
                while (disableAllLatch.count > 0 && attempts < 100) {
                    advanceUntilIdle()
                    kotlinx.coroutines.delay(20)
                    attempts++
                }
                assertTrue(disableAllLatch.await(1, TimeUnit.SECONDS))
                prefs
            }
            assertFalse(afterDisable[MonochromeEnabledKey] == true)
            assertFalse(afterDisable[TidalEnabledKey] == true)
            assertFalse(afterDisable[QobuzEnabledKey] == true)
            assertFalse(afterDisable[DeezerEnabledKey] == true)
            assertFalse(afterDisable[AppleMusicSourceEnabledKey] == true)
            assertFalse(afterDisable[AddonsEnabledKey] == true)
            coVerify(atLeast = 1) { AddonManager.setAllAddonsEnabled(any(), false) }

            // 3. Re-enabling main Lossless switch turns ON Monochrome while other disabled sources stay off.
            viewModel.setLosslessEnabled(true)
            advanceUntilIdle()
            val afterReEnable = withContext(Dispatchers.Default) {
                dataStore.data.first { it[LosslessStreamingEnabledKey] == true }
            }
            assertTrue(afterReEnable[MonochromeEnabledKey] == true)
            assertFalse(afterReEnable[TidalEnabledKey] == true)
            assertFalse(afterReEnable[QobuzEnabledKey] == true)
            assertFalse(afterReEnable[DeezerEnabledKey] == true)
            assertFalse(afterReEnable[AppleMusicSourceEnabledKey] == true)
            assertFalse(afterReEnable[AddonsEnabledKey] == true)
        } finally {
            dsScope.cancel()
            tempDir.toFile().deleteRecursively()
        }
    }
}
