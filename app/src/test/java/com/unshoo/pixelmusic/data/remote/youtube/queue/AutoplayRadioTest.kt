package com.unshoo.pixelmusic.data.remote.youtube.queue

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import unshoo.ianshulyadav.pixelmusic.innertube.models.WatchEndpoint

class AutoplayRadioTest {

    /** Fake radio pages: (seed video id, continuation) -> songs + next continuation. */
    private class FakePages : AutoplayRadio.RadioPages<String> {
        val pages = HashMap<Pair<String, String?>, Pair<List<String>, String?>>()
        val failing = HashSet<String>()
        val asked = ArrayList<Pair<String, String?>>()

        fun with(seed: String, continuation: String?, ids: List<String>, next: String?) = apply {
            pages[seed to continuation] = ids to next
        }

        override suspend fun page(endpoint: WatchEndpoint, continuation: String?): AutoplayRadio.Page<String> {
            val seed = endpoint.videoId!!
            asked += seed to continuation
            if (seed in failing) error("offline")
            val (ids, next) = pages[seed to continuation] ?: (emptyList<String>() to null)
            return AutoplayRadio.Page(ids, next, endpoint)
        }
    }

    private fun radio(pages: FakePages) = AutoplayRadio(pages) { it }

    @Test
    fun followsTheRadioPageAfterPageAndNeverRepeatsASong() = runBlocking {
        val pages = FakePages()
            .with("a", null, listOf("a", "b", "c"), "t1")
            .with("a", "t1", listOf("c", "d", "e"), "t2")
            .with("a", "t2", listOf("f", "g"), "t3")
        val radio = radio(pages)

        assertEquals(listOf("b", "c", "d"), radio.nextBatch("a", 3) { true })
        // the list ended on a song this radio sent: the same radio carries on
        assertEquals(listOf("f", "g"), radio.nextBatch("d", 3) { true })
        assertEquals(listOf("a" to null, "a" to "t1", "a" to "t2"), pages.asked.take(3))
    }

    @Test
    fun aRadioWithoutMorePagesHandsOverToTheRadioOfItsNewestSong() = runBlocking {
        val pages = FakePages()
            .with("a", null, listOf("b", "c"), null)
            .with("c", null, listOf("b", "d", "e"), null)
        val radio = radio(pages)

        assertEquals(listOf("b", "c", "d", "e"), radio.nextBatch("a", 10) { true })
        assertEquals(listOf("a" to null, "c" to null, "e" to null, "d" to null), pages.asked)
    }

    @Test
    fun aSongOfTheUsersOwnListStartsOver() = runBlocking {
        val pages = FakePages()
            .with("a", null, listOf("b"), "t")
            .with("x", null, listOf("b", "y"), null)
        val radio = radio(pages)

        assertEquals(listOf("b"), radio.nextBatch("a", 1) { true })
        // "x" was not sent by the radio: its own radio, with the memory cleared
        assertEquals(listOf("b", "y"), radio.nextBatch("x", 2) { true })
    }

    @Test
    fun skipsRejectedSongsWithoutRememberingThemAndSurvivesFailedPages() = runBlocking {
        val pages = FakePages()
            .with("a", null, listOf("hidden", "b", "c"), null)
            .with("b", null, listOf("hidden", "d"), null)
        pages.failing += "c"
        val radio = radio(pages)

        // the radio of "c" fails: the radio of "b" takes over
        assertEquals(listOf("b", "c", "d"), radio.nextBatch("a", 5) { it != "hidden" })
        assertEquals(listOf("a" to null, "c" to null, "b" to null), pages.asked.take(3))
    }

    @Test
    fun resumeCarriesOnTheRadioTheUserStarted() = runBlocking {
        val pages = FakePages()
            .with("a", "t1", listOf("c", "d", "e"), null)
        val radio = radio(pages)
        radio.resume(AutoplayRadio.radioEndpoint("a"), "t1", listOf("a", "b", "c"))

        assertEquals(listOf("d", "e"), radio.nextBatch("b", 5) { true })
        assertEquals("a" to "t1", pages.asked.first())
    }
}
