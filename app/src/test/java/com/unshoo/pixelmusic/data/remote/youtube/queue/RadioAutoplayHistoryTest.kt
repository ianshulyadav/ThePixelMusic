package com.unshoo.pixelmusic.data.remote.youtube.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioAutoplayHistoryTest {
    @Test
    fun followsNewestAcceptedSongOnlyOnceWhenStationEnds() {
        val history = RadioAutoplayHistory("seed0000001")
        assertTrue(history.record("track000001"))
        assertTrue(history.record("track000002"))
        assertFalse(history.record("track000001"))

        assertEquals("track000002", history.nextSeed())
        assertEquals("track000001", history.nextSeed())
        assertNull(history.nextSeed())
    }

    @Test
    fun rejectedOrInvalidSongsCannotBecomeSeeds() {
        val history = RadioAutoplayHistory("seed0000001")
        assertFalse(history.record("not-a-video-id"))
        assertNull(history.nextSeed())
    }

    @Test
    fun manualReseedStartsAFreshStation() {
        val history = RadioAutoplayHistory("seed0000001")
        history.record("track000001")
        history.reset("seed0000002")

        assertNull(history.nextSeed())
        assertTrue(history.record("track000001"))
        assertEquals("track000001", history.nextSeed())
    }
}
