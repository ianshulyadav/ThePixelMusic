package com.unshoo.pixelmusic.data.remote.youtube.queue

import kotlinx.coroutines.CancellationException
import timber.log.Timber
import unshoo.ianshulyadav.pixelmusic.innertube.models.WatchEndpoint

/**
 * Auto-queue's running radio, ported from PixelMusicDesktop (`sf-services/src/wave/yt.rs`, `AutoplayRadio`).
 *
 * It follows the YouTube Music radio of the song the queue is ending on, page by page (its continuation).
 * When that radio has no more pages, the radio of the newest song it sent whose radio was not followed yet
 * takes over, so it never runs dry and never loops back onto a radio it already played. A song sent once is
 * never sent again. A seed this radio did not send (a song from the user's own list) starts over from that
 * song's radio.
 *
 * Pure logic: the pages come from [RadioPages] (YouTube Music in the app, a fake in tests), and a song's
 * identity is its video id ([idOf]).
 */
class AutoplayRadio<T>(
    private val pages: RadioPages<T>,
    private val idOf: (T) -> String?,
) {
    /** One page of a radio: its songs, the token of the page after it, and the endpoint that token belongs to. */
    data class Page<T>(val items: List<T>, val continuation: String?, val endpoint: WatchEndpoint)

    fun interface RadioPages<T> {
        suspend fun page(endpoint: WatchEndpoint, continuation: String?): Page<T>
    }

    /** The radio followed now, whether its first page came, and the next page's token. */
    private var endpoint: WatchEndpoint? = null
    private var started = false
    private var continuation: String? = null

    /** Video ids handed to the queue (or already in it when the radio was resumed). */
    private val sent = HashSet<String>()

    /** Video ids whose radio was followed. */
    private val seeds = HashSet<String>()

    /** Video ids of the songs sent, newest last: the next seeds. */
    private val pool = ArrayList<String>()

    fun clear() {
        endpoint = null
        started = false
        continuation = null
        sent.clear()
        seeds.clear()
        pool.clear()
    }

    /**
     * Carries on a radio that already filled the queue (a radio/mix started by the user): [queuedIds] are the
     * songs it put there (oldest first), [endpoint]/[nextContinuation] the page after them.
     */
    fun resume(endpoint: WatchEndpoint, nextContinuation: String?, queuedIds: List<String>) {
        clear()
        endpoint.videoId?.let { seeds += it }
        this.endpoint = endpoint
        continuation = nextContinuation?.takeIf { it.isNotBlank() }
        // with no token the radio's first page is asked for again (its songs are skipped as sent)
        started = continuation != null
        for (id in queuedIds) {
            if (sent.add(id)) pool += id
        }
    }

    /**
     * The next songs after [seedId] (the song the queue is ending on): up to [want] songs not sent before that
     * [keep] accepts. Songs [keep] rejects are not remembered as sent.
     */
    suspend fun nextBatch(seedId: String, want: Int, keep: (T) -> Boolean): List<T> {
        if (seedId !in sent || sent.size > MEMORY) {
            clear()
            follow(seedId)
        }
        sent += seedId
        val out = ArrayList<T>()
        repeat(MAX_PAGES) {
            if (out.size >= want) return out
            // the radio followed now is over: the newest song sent whose radio was not followed yet
            if (started && continuation == null) {
                val next = pool.lastOrNull { it !in seeds } ?: return out
                follow(next)
            }
            val current = endpoint ?: return out
            val page = try {
                pages.page(current, continuation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "radio of %s failed", current.videoId)
                // this radio is done for; the next round takes another seed
                started = true
                continuation = null
                return@repeat
            }
            started = true
            endpoint = page.endpoint
            continuation = page.continuation?.takeIf { it.isNotBlank() }
            for (item in page.items) {
                val id = idOf(item) ?: continue
                if (out.size >= want || !keep(item) || !sent.add(id)) continue
                pool += id
                out += item
            }
        }
        return out
    }

    private fun follow(videoId: String) {
        seeds += videoId
        endpoint = radioEndpoint(videoId)
        started = false
        continuation = null
    }

    companion object {
        private const val TAG = "AutoplayRadio"

        /** Radio pages asked for at most per batch. */
        const val MAX_PAGES = 4

        /** Songs remembered as sent before the memory starts over. */
        const val MEMORY = 3000

        fun radioEndpoint(videoId: String) = WatchEndpoint(videoId = videoId, playlistId = "RDAMVM$videoId")
    }
}
