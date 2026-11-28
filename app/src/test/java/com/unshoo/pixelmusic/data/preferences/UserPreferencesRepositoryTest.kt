package com.unshoo.pixelmusic.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class UserPreferencesRepositoryTest {

    @Test
    fun `clearPreferencesExceptKeys preserves initial setup completion`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setInitialSetupDone(true)
            repository.setNavBarStyle("compact")

            repository.clearPreferencesExceptKeys(emptySet())

            assertTrue(repository.initialSetupDoneFlow.first())
            assertEquals(NavBarStyle.DEFAULT, repository.navBarStyleFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `importPreferencesFromBackup clearExisting preserves initial setup completion`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setInitialSetupDone(true)
            repository.setNavBarStyle("compact")

            repository.importPreferencesFromBackup(
                entries = listOf(
                    PreferenceBackupEntry(
                        key = "nav_bar_style",
                        type = "string",
                        stringValue = "restored"
                    )
                ),
                clearExisting = true
            )

            assertTrue(repository.initialSetupDoneFlow.first())
            assertEquals("restored", repository.navBarStyleFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `navBarCornerRadiusFlow clamps values outside the supported UI range`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setNavBarCornerRadius(-1)
            assertEquals(MIN_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())

            repository.setNavBarCornerRadius(999)
            assertEquals(MAX_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())

            repository.importPreferencesFromBackup(
                entries = listOf(
                    PreferenceBackupEntry(
                        key = "nav_bar_corner_radius",
                        type = "int",
                        intValue = -1
                    )
                ),
                clearExisting = false
            )
            assertEquals(MIN_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `setPerformanceModeEnabled preserves unrelated preferences and dynamically overrides loading tweaks`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setAudioOffloadEnabled(false)
            repository.setAlbumArtQuality(AlbumArtQuality.HIGH)
            repository.setAlbumArtCacheLimitMb(300)
            repository.setShowPlayerFileInfo(true)

            val baselineTweaks = repository.fullPlayerLoadingTweaksFlow.first()
            assertEquals(98, baselineTweaks.contentAppearThresholdPercent)
            assertEquals(0, baselineTweaks.contentCloseThresholdPercent)

            repository.setPerformanceModeEnabled(true)

            assertTrue(repository.performanceModeEnabledFlow.first())
            // User's unrelated saved preferences stay untouched
            assertFalse(repository.audioOffloadEnabledFlow.first())
            assertEquals(AlbumArtQuality.HIGH, repository.albumArtQualityFlow.first())
            assertEquals(300, repository.albumArtCacheLimitMbFlow.first())
            assertTrue(repository.showPlayerFileInfoFlow.first())

            // Deferred loading tweaks dynamically engage while Performance Mode is on
            val perfTweaks = repository.fullPlayerLoadingTweaksFlow.first()
            assertTrue(perfTweaks.delayAll)
            assertTrue(perfTweaks.transparentPlaceholders)
            assertEquals(100, perfTweaks.contentAppearThresholdPercent)
            assertEquals(10, perfTweaks.contentCloseThresholdPercent)

            // Disabling restores the user's saved baseline tweaks cleanly
            repository.setPerformanceModeEnabled(false)
            assertFalse(repository.performanceModeEnabledFlow.first())
            assertEquals(baselineTweaks, repository.fullPlayerLoadingTweaksFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `showScrollbarFlow defaults to true and persists toggled state`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            assertTrue(repository.showScrollbarFlow.first())

            repository.setShowScrollbar(false)
            assertFalse(repository.showScrollbarFlow.first())

            repository.setShowScrollbar(true)
            assertTrue(repository.showScrollbarFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `seekerAnimation preferences default disabled at default duration and clamp bounds`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            assertFalse(repository.seekerAnimationEnabledFlow.first())
            assertEquals(DEFAULT_SEEKER_ANIMATION_DURATION_MS, repository.seekerAnimationDurationMsFlow.first())

            repository.setSeekerAnimationEnabled(true)
            assertTrue(repository.seekerAnimationEnabledFlow.first())

            repository.setSeekerAnimationEnabled(false)
            assertFalse(repository.seekerAnimationEnabledFlow.first())

            repository.setSeekerAnimationDurationMs(50)
            assertEquals(MIN_SEEKER_ANIMATION_DURATION_MS, repository.seekerAnimationDurationMsFlow.first())

            repository.setSeekerAnimationDurationMs(5000)
            assertEquals(MAX_SEEKER_ANIMATION_DURATION_MS, repository.seekerAnimationDurationMsFlow.first())

            repository.setSeekerAnimationDurationMs(750)
            assertEquals(750, repository.seekerAnimationDurationMsFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `longPressSeek preferences default enabled at 2x speed and clamp bounds`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            assertTrue(repository.longPressSeekEnabledFlow.first())
            assertEquals(DEFAULT_LONG_PRESS_SEEK_SPEED, repository.longPressSeekSpeedFlow.first())

            repository.setLongPressSeekEnabled(false)
            assertFalse(repository.longPressSeekEnabledFlow.first())

            repository.setLongPressSeekEnabled(true)
            assertTrue(repository.longPressSeekEnabledFlow.first())

            repository.setLongPressSeekSpeed(0.25f)
            assertEquals(MIN_LONG_PRESS_SEEK_SPEED, repository.longPressSeekSpeedFlow.first())

            repository.setLongPressSeekSpeed(12f)
            assertEquals(MAX_LONG_PRESS_SEEK_SPEED, repository.longPressSeekSpeedFlow.first())

            repository.setLongPressSeekSpeed(3.0f)
            assertEquals(3.0f, repository.longPressSeekSpeedFlow.first())

            assertEquals(
                com.unshoo.pixelmusic.data.preferences.LongPressSeekMode.SPEED_BASED,
                repository.longPressSeekModeFlow.first()
            )
            assertEquals(
                com.unshoo.pixelmusic.data.preferences.DEFAULT_LONG_PRESS_SEEK_TIME_RATE_SECONDS,
                repository.longPressSeekTimeRateSecondsFlow.first()
            )

            repository.setLongPressSeekMode(com.unshoo.pixelmusic.data.preferences.LongPressSeekMode.TIME_BASED)
            assertEquals(
                com.unshoo.pixelmusic.data.preferences.LongPressSeekMode.TIME_BASED,
                repository.longPressSeekModeFlow.first()
            )

            repository.setLongPressSeekTimeRateSeconds(1)
            assertEquals(
                com.unshoo.pixelmusic.data.preferences.MIN_LONG_PRESS_SEEK_TIME_RATE_SECONDS,
                repository.longPressSeekTimeRateSecondsFlow.first()
            )

            repository.setLongPressSeekTimeRateSeconds(120)
            assertEquals(
                com.unshoo.pixelmusic.data.preferences.MAX_LONG_PRESS_SEEK_TIME_RATE_SECONDS,
                repository.longPressSeekTimeRateSecondsFlow.first()
            )

            repository.setLongPressSeekTimeRateSeconds(15)
            assertEquals(15, repository.longPressSeekTimeRateSecondsFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `groupAlbumsByNameFlow defaults to false and updates rescan flag according to parameter`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            assertFalse(repository.groupAlbumsByNameFlow.first())
            assertFalse(repository.artistSettingsRescanRequiredFlow.first())

            repository.setGroupAlbumsByName(enabled = true, markRescanRequired = false)
            assertTrue(repository.groupAlbumsByNameFlow.first())
            assertFalse(repository.artistSettingsRescanRequiredFlow.first())

            repository.setGroupAlbumsByName(enabled = false)
            assertFalse(repository.groupAlbumsByNameFlow.first())
            assertTrue(repository.artistSettingsRescanRequiredFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
