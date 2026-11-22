package com.unshoo.pixelmusic.presentation.viewmodel

import android.app.AlarmManager
import android.content.Context
import com.unshoo.pixelmusic.data.EotStateHolder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerStateHolderTest {
    @Test
    fun `restarting and clearing the timer releases the previous song collector`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.ALARM_SERVICE) } returns mockk<AlarmManager>()
        val holder = SleepTimerStateHolder(context)
        var collectors = 0
        val songIds = flow<String?> {
            collectors++
            try {
                awaitCancellation()
            } finally {
                collectors--
            }
        }
        holder.initialize(backgroundScope, {}, { null }, songIds, { "" })
        try {
            holder.setEndOfTrackTimer(true, "first")
            runCurrent()
            assertEquals(1, collectors)

            holder.setEndOfTrackTimer(true, "second")
            runCurrent()
            assertEquals(1, collectors)
        } finally {
            holder.onCleared()
            EotStateHolder.setEotTargetSong(null)
        }
        runCurrent()
        assertEquals(0, collectors)
    }
}
