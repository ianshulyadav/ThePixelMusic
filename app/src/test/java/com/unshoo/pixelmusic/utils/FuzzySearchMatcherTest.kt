package com.unshoo.pixelmusic.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuzzySearchMatcherTest {

    @Test
    fun `matches mistyped artist and song names`() {
        assertTrue(FuzzySearchMatcher.scoreFields("arjit singh", "Arijit Singh" to 3) > 0)
        assertTrue(FuzzySearchMatcher.scoreFields("blinding lihgts", "Blinding Lights" to 3, "The Weeknd" to 2) > 0)
        assertTrue(FuzzySearchMatcher.scoreFields("arijiit", "Arijit Singh" to 3) > 0)
        assertTrue(FuzzySearchMatcher.scoreFields("talyor swft", "Taylor Swift" to 3) > 0)
    }

    @Test
    fun `matches diacritics apostrophes and compact spacing`() {
        assertTrue(FuzzySearchMatcher.scoreFields("beyonce", "Beyoncé" to 3) > 0)
        assertTrue(FuzzySearchMatcher.scoreFields("dont stop", "Don't Stop Me Now" to 3) > 0)
        assertTrue(FuzzySearchMatcher.scoreFields("theweeknd", "The Weeknd" to 3) > 0)
        assertTrue(FuzzySearchMatcher.scoreFields("blindinglights", "Blinding Lights" to 3) > 0)
    }

    @Test
    fun `matches cross-field song and mistyped artist query`() {
        val score = FuzzySearchMatcher.scoreFields(
            "arjit tum hi ho",
            "Tum Hi Ho" to 3,
            "Arijit Singh" to 2,
            "Aashiqui 2" to 1,
        )
        assertTrue(score > 0)
    }

    @Test
    fun `ranks exact title match ahead of artist-only match and rejects unrelated query`() {
        val titleScore = FuzzySearchMatcher.scoreFields("starboy", "Starboy" to 3, "The Weeknd" to 2)
        val artistScore = FuzzySearchMatcher.scoreFields("starboy", "Blinding Lights" to 3, "Starboy" to 2)
        val unrelated = FuzzySearchMatcher.scoreFields("bohemian rhapsody", "Starboy" to 3, "The Weeknd" to 2)

        assertTrue(titleScore > artistScore)
        assertEquals(0, unrelated)
    }
}
