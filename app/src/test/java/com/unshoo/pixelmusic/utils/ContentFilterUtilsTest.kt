package com.unshoo.pixelmusic.utils

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ContentFilterUtils Live and Forgotten Favorites Tests")
class ContentFilterUtilsTest {

    @Test
    fun testLivePerformanceDetection() {
        assertTrue(ContentFilterUtils.isLivePerformance("Live"))
        assertTrue(ContentFilterUtils.isLivePerformance("Live Performance"))
        assertTrue(ContentFilterUtils.isLivePerformance("Live Performances"))
        assertTrue(ContentFilterUtils.isLivePerformance("Hotel California (Live)"))
        assertTrue(ContentFilterUtils.isLivePerformance("Bohemian Rhapsody [Live at Wembley]"))
        assertTrue(ContentFilterUtils.isLivePerformance("Comfortably Numb - Live"))
        assertTrue(ContentFilterUtils.isLivePerformance("Taylor Swift - The Eras Tour (Live)"))
        assertTrue(ContentFilterUtils.isLivePerformance("Live Session at Electric Lady"))
        assertTrue(ContentFilterUtils.isLivePerformance("Live in Paris"))
        assertTrue(ContentFilterUtils.isLivePerformance("Live from London"))

        // False positives should NOT match
        assertFalse(ContentFilterUtils.isLivePerformance("Live Your Life"))
        assertFalse(ContentFilterUtils.isLivePerformance("Live and Let Die"))
        assertFalse(ContentFilterUtils.isLivePerformance("Long Live"))
        assertFalse(ContentFilterUtils.isLivePerformance("Alive"))
        assertFalse(ContentFilterUtils.isLivePerformance("Relive the Memories"))
        assertFalse(ContentFilterUtils.isLivePerformance("Live It Up"))
    }

    @Test
    fun testBlockedExploreSection() {
        assertTrue(ContentFilterUtils.isBlockedExploreSection("Live performances"))
        assertTrue(ContentFilterUtils.isBlockedExploreSection("Live performance"))
        assertTrue(ContentFilterUtils.isBlockedExploreSection("Forgotten favorites"))
        assertTrue(ContentFilterUtils.isBlockedExploreSection("Forgotten favourites"))
        assertTrue(ContentFilterUtils.isBlockedExploreSection("Forgotten fav"))
        assertTrue(ContentFilterUtils.isBlockedExploreSection("Vergessene Favoriten"))
        assertTrue(ContentFilterUtils.isBlockedExploreSection("Favoritos olvidados"))
        assertTrue(ContentFilterUtils.isBlockedExploreSection("Favoris oubliés"))
        assertTrue(ContentFilterUtils.isBlockedExploreSection("Long listens"))
        assertTrue(ContentFilterUtils.isBlockedExploreSection("Local music"))

        assertFalse(ContentFilterUtils.isBlockedExploreSection("Quick picks"))
        assertFalse(ContentFilterUtils.isBlockedExploreSection("Listen again"))
        assertFalse(ContentFilterUtils.isBlockedExploreSection("New releases"))
        assertFalse(ContentFilterUtils.isBlockedExploreSection("Mixed for you"))
        assertFalse(ContentFilterUtils.isBlockedExploreSection("Similar to Taylor Swift"))
    }

    @Test
    fun testBlockedExploreCategory() {
        assertTrue(ContentFilterUtils.isBlockedExploreCategory("Live performances"))
        assertTrue(ContentFilterUtils.isBlockedExploreCategory("Forgotten favorites"))
        assertTrue(ContentFilterUtils.isBlockedExploreCategory("Forgotten"))
        assertTrue(ContentFilterUtils.isBlockedExploreCategory("Live"))

        assertFalse(ContentFilterUtils.isBlockedExploreCategory("All"))
        assertFalse(ContentFilterUtils.isBlockedExploreCategory("Relax"))
        assertFalse(ContentFilterUtils.isBlockedExploreCategory("Workout"))
        assertFalse(ContentFilterUtils.isBlockedExploreCategory("Focus"))
        assertFalse(ContentFilterUtils.isBlockedExploreCategory("Energize"))
    }
}
