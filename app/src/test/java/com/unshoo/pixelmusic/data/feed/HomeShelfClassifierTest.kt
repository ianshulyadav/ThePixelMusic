package com.unshoo.pixelmusic.data.feed

import com.unshoo.pixelmusic.data.feed.HomeShelfClassifier.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.BrowseEndpoint
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.YTItem
import unshoo.ianshulyadav.pixelmusic.innertube.pages.HomePage

class HomeShelfClassifierTest {

    private fun song(id: String, isVideo: Boolean = false) = SongItem(
        id = id, title = "Song $id", artists = emptyList(), thumbnail = "https://x/$id.jpg", isVideo = isVideo
    )

    private fun album(id: String) = AlbumItem(
        browseId = "MPRE$id", playlistId = "OLAK$id", title = "Album $id", artists = null, thumbnail = "https://x/a.jpg"
    )

    private fun playlist(id: String) = PlaylistItem(
        id = id, title = "Mix $id", author = null, songCountText = null, thumbnail = null,
        playEndpoint = null, shuffleEndpoint = null, radioEndpoint = null
    )

    private fun section(
        title: String,
        items: List<YTItem>,
        isListShelf: Boolean = false,
        label: String? = null,
        thumbnail: String? = null,
        moreBrowseId: String? = null,
    ) = HomePage.Section(
        title = title,
        label = label,
        thumbnail = thumbnail,
        endpoint = moreBrowseId?.let { BrowseEndpoint(browseId = it) },
        items = items,
        isListShelf = isListShelf,
    )

    @Test
    fun `localized quick picks shelf is found by its list layout`() {
        val shelf = section("Selección rápida", (1..8).map { song("q$it") }, isListShelf = true)
        assertEquals(Kind.QUICK_PICKS, HomeShelfClassifier.classify(shelf))
    }

    @Test
    fun `localized new releases shelf is found by its browse destination`() {
        val shelf = section("Neuerscheinungen", (1..6).map { album("$it") }, moreBrowseId = "FEmusic_new_releases_albums")
        assertEquals(Kind.NEW_RELEASES, HomeShelfClassifier.classify(shelf))
    }

    @Test
    fun `music video shelf is detected from item metadata, not the title`() {
        val shelf = section("おすすめのミュージック ビデオ", (1..6).map { song("v$it", isVideo = true) })
        assertEquals(Kind.MUSIC_VIDEOS, HomeShelfClassifier.classify(shelf))
    }

    @Test
    fun `album and mix shelves classify by item type`() {
        assertEquals(Kind.ALBUMS, HomeShelfClassifier.classify(section("Álbumes para ti", (1..5).map { album("$it") })))
        assertEquals(
            Kind.MIXES,
            HomeShelfClassifier.classify(section("Mezclas", listOf(playlist("RDTMAK1"), playlist("RDTMAK2"), playlist("RDAMPL3"))))
        )
    }

    @Test
    fun `listen again is recognised by the user strapline and avatar`() {
        val shelf = section("Volver a escuchar", listOf(song("1"), album("2")), label = "Ada", thumbnail = "https://avatar")
        assertEquals(Kind.LISTEN_AGAIN, HomeShelfClassifier.classify(shelf))
    }

    @Test
    fun `english titles still work when structural markers are missing`() {
        assertEquals(Kind.QUICK_PICKS, HomeShelfClassifier.classify(section("Quick picks", (1..4).map { song("$it") })))
        assertEquals(Kind.NEW_RELEASES, HomeShelfClassifier.classify(section("New releases", (1..4).map { album("$it") })))
    }

    @Test
    fun `only the first list shelf of songs is quick picks`() {
        val first = section("A", (1..4).map { song("a$it") }, isListShelf = true)
        val second = section("B", (1..4).map { song("b$it") }, isListShelf = true)
        val kinds = HomeShelfClassifier.classifyAll(listOf(first, second)).map { it.second }
        assertEquals(listOf(Kind.QUICK_PICKS, Kind.OTHER), kinds)
    }

    @Test
    fun `radio mix ids`() {
        assertTrue(HomeShelfClassifier.isRadioMixId("RDTMAK5uy_abc"))
        assertFalse(HomeShelfClassifier.isRadioMixId("PL1234"))
    }
}
