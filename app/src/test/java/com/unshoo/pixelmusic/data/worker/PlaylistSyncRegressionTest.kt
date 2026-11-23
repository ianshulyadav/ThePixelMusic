package com.unshoo.pixelmusic.data.worker

import com.google.common.truth.Truth.assertThat
import com.unshoo.pixelmusic.data.model.youtube.Playlist
import com.unshoo.pixelmusic.data.model.youtube.PlaylistInfo
import com.unshoo.pixelmusic.data.model.youtube.PlaylistSongCrossRef
import com.unshoo.pixelmusic.data.model.youtube.Song
import com.unshoo.pixelmusic.utils.LocalAudioDuplicateMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

class PlaylistSyncRegressionTest {
    @Test
    fun onlyExhaustedPaginationIsComplete() = runBlocking {
        assertThat(collectPlaylistPages(null, 2) { error("Unexpected page request") }).isTrue()
        val requests = mutableListOf<String>()
        assertThat(collectPlaylistPages("a", 2) { token ->
            requests += token
            Result.success(if (token == "a") "b" else null)
        }).isTrue()
        assertThat(requests).containsExactly("a", "b").inOrder()
        assertThat(collectPlaylistPages("a", 2) { Result.failure(IOException()) }).isFalse()
        assertThat(collectPlaylistPages("a", 2) { Result.success(it) }).isFalse()
        assertThat(collectPlaylistPages("a", 1) { Result.success("b") }).isFalse()
    }

    @Test
    fun largePlaylistKeepsRepeatedPositionsAndSkipsMissingRows() {
        val songs = (1..5000).map { Song(youtubeId = it.toString()) }
        val ids = listOf("5000", "1", "1", "missing") + songs.map { it.youtubeId }
        val refs = ids.mapIndexed { index, id -> PlaylistSongCrossRef("p", id, index) }.reversed()
        val playlist = Playlist(PlaylistInfo(id = "p"), songs, refs)
        assertThat(playlist.songs.map { it.youtubeId }).containsExactlyElementsIn(ids.filter { it != "missing" }).inOrder()
    }

    @Test
    fun localMatcherKeepsNonLatinTitlesDistinct() {
        val matcher = LocalAudioDuplicateMatcher
        assertThat(matcher.calculateStringSimilarity("तुम ही हो", "दिल झूम")).isLessThan(0.8)
        assertThat(matcher.calculateStringSimilarity("तुम ही हो (Official Audio)", "तुम ही हो")).isEqualTo(1.0)
        assertThat(matcher.calculateStringSimilarity("!!!", "???")).isEqualTo(0.0)
    }
}
