package com.unshoo.pixelmusic.data.worker

import com.google.common.truth.Truth.assertThat
import com.unshoo.pixelmusic.data.database.AlbumEntity
import com.unshoo.pixelmusic.data.database.SongEntity
import org.junit.Test

class AlbumGroupingUtilsTest {

    @Test
    fun `resolveAlbumArtist prefers embedded album artist`() {
        val resolved = resolveAlbumArtist(
            rawAlbumArtist = null,
            metadataAlbumArtist = "The Weeknd"
        )

        assertThat(resolved).isEqualTo("The Weeknd")
    }

    @Test
    fun `buildAlbumGroupingKey ignores per-track artist when title and art match`() {
        val soloTrack = testSong(
            artistName = "The Weeknd",
            albumArtist = null,
            albumArtUriString = "content://art/hurry-up",
            parentDirectoryPath = "/music/The Weeknd & Justice - Hurry Up Tomorrow"
        )
        val collabTrack = testSong(
            artistName = "The Weeknd, Justice",
            albumArtist = null,
            albumArtUriString = "content://art/hurry-up",
            parentDirectoryPath = "/music/The Weeknd & Justice - Hurry Up Tomorrow"
        )

        assertThat(buildAlbumGroupingKey(soloTrack)).isEqualTo(buildAlbumGroupingKey(collabTrack))
    }

    @Test
    fun `buildAlbumGroupingKey keeps same-titled albums apart when directories differ`() {
        val firstAlbum = testSong(
            artistName = "Artist A",
            albumArtist = null,
            albumArtUriString = null,
            parentDirectoryPath = "/music/Artist A/Greatest Hits"
        )
        val secondAlbum = testSong(
            artistName = "Artist B",
            albumArtist = null,
            albumArtUriString = null,
            parentDirectoryPath = "/music/Artist B/Greatest Hits"
        )

        assertThat(buildAlbumGroupingKey(firstAlbum)).isNotEqualTo(buildAlbumGroupingKey(secondAlbum))
    }

    @Test
    fun `buildAlbumGroupingKey ignores reused artwork for local same-titled albums`() {
        val firstAlbum = testSong(
            artistName = "Artist A",
            albumArtist = null,
            albumArtUriString = "pixelmusic_local_art://song/10",
            parentDirectoryPath = "/music/Artist A/Feels",
            albumName = "Unknown Album",
            albumId = 10L
        )
        val secondAlbum = testSong(
            artistName = "Artist B",
            albumArtist = null,
            albumArtUriString = "pixelmusic_local_art://song/10",
            parentDirectoryPath = "/music/Artist B/Feels",
            albumName = "Unknown Album",
            albumId = 11L
        )

        assertThat(buildAlbumGroupingKey(firstAlbum)).isNotEqualTo(buildAlbumGroupingKey(secondAlbum))
    }

    @Test
    fun `buildAlbumGroupingKeys keeps media fallback even when artwork exists`() {
        val album = com.unshoo.pixelmusic.data.database.AlbumEntity(
            id = 77L,
            title = "Unknown Album",
            artistName = "",
            artistId = 0L,
            albumArtUriString = "pixelmusic_local_art://song/10",
            songCount = 1,
            dateAdded = 0L,
            year = 0
        )

        val keys = buildAlbumGroupingKeys(album)

        assertThat(keys).contains(
            AlbumGroupingKey(
                normalizedTitle = "unknown album",
                identity = "media:77"
            )
        )
    }

    @Test
    fun `chooseAlbumDisplayArtist prefers dominant track artist when grouping is off`() {
        val songs = listOf(
            testSong(artistName = "The Weeknd", albumArtist = "The Weeknd & Justice"),
            testSong(artistName = "The Weeknd", albumArtist = "The Weeknd & Justice"),
            testSong(artistName = "The Weeknd, Justice", albumArtist = "The Weeknd & Justice")
        )

        val displayArtist = chooseAlbumDisplayArtist(
            songs = songs,
            preferAlbumArtist = false
        )

        assertThat(displayArtist).isEqualTo("The Weeknd")
    }

    @Test
    fun `chooseAlbumDisplayArtist prefers album artist when grouping is on`() {
        val songs = listOf(
            testSong(artistName = "The Weeknd", albumArtist = "The Weeknd & Justice"),
            testSong(artistName = "The Weeknd, Justice", albumArtist = "The Weeknd & Justice")
        )

        val displayArtist = chooseAlbumDisplayArtist(
            songs = songs,
            preferAlbumArtist = true
        )

        assertThat(displayArtist).isEqualTo("The Weeknd & Justice")
    }

    @Test
    fun `buildAlbumGroupingKey merges tracks with different artists when groupByAlbumNameOnly is enabled`() {
        val track1 = testSong(
            artistName = "Blues Image",
            albumArtist = "Blues Image",
            parentDirectoryPath = "/music/mixtape/disc1",
            albumName = "70's Mixtape Vol. 1",
            albumId = 101L
        )
        val track2 = testSong(
            artistName = "Elton John",
            albumArtist = "Elton John",
            parentDirectoryPath = "/music/mixtape/disc2",
            albumName = " 70's Mixtape Vol. 1 ",
            albumId = 202L
        )

        assertThat(buildAlbumGroupingKey(track1, groupByAlbumNameOnly = true))
            .isEqualTo(buildAlbumGroupingKey(track2, groupByAlbumNameOnly = true))
    }

    @Test
    fun `buildAlbumGroupingKey keeps unknown albums separate even when groupByAlbumNameOnly is enabled`() {
        val track1 = testSong(
            artistName = "Artist A",
            albumArtist = null,
            parentDirectoryPath = "/music/folderA",
            albumName = "Unknown Album",
            albumId = 101L
        )
        val track2 = testSong(
            artistName = "Artist B",
            albumArtist = null,
            parentDirectoryPath = "/music/folderB",
            albumName = "Unknown Album",
            albumId = 202L
        )

        assertThat(buildAlbumGroupingKey(track1, groupByAlbumNameOnly = true))
            .isNotEqualTo(buildAlbumGroupingKey(track2, groupByAlbumNameOnly = true))
    }

    @Test
    fun `buildAlbumGroupingKeys maps split albums to single name key when groupByAlbumNameOnly is enabled`() {
        val album1 = AlbumEntity(
            id = 10L,
            title = "70's Mixtape Vol. 1",
            artistName = "Blues Image",
            artistId = 1L,
            albumArtUriString = null,
            songCount = 1,
            dateAdded = 100L,
            year = 1970,
            albumArtist = "Blues Image"
        )
        val album2 = album1.copy(
            id = 20L,
            artistName = "Elton John",
            artistId = 2L,
            albumArtist = "Elton John"
        )

        val keys1 = buildAlbumGroupingKeys(album1, groupByAlbumNameOnly = true)
        val keys2 = buildAlbumGroupingKeys(album2, groupByAlbumNameOnly = true)

        assertThat(keys1).containsExactly(
            AlbumGroupingKey(
                normalizedTitle = "70's mixtape vol. 1",
                identity = "name"
            )
        )
        assertThat(keys2).isEqualTo(keys1)
    }

    @Test
    fun `chooseAlbumDisplayArtist returns Various Artists and resolves 0L id for conflicting compilation artists`() {
        val songs = listOf(
            testSong(artistName = "Blues Image", albumArtist = "Blues Image", albumId = 1L),
            testSong(artistName = "Cherie Currie", albumArtist = "Cherie Currie", albumId = 2L),
            testSong(artistName = "Eddie Money", albumArtist = "Eddie Money", albumId = 3L),
            testSong(artistName = "Elton John", albumArtist = "Elton John", albumId = 4L)
        )

        val displayArtist = chooseAlbumDisplayArtist(
            songs = songs,
            preferAlbumArtist = true,
            groupByAlbumNameOnly = true
        )
        val artistId = resolveAlbumDisplayArtistId(
            displayArtist = displayArtist,
            songs = songs,
            artistNameToId = mapOf("Blues Image" to 10L, "Elton John" to 20L),
            artistDelimiters = listOf(",", "&")
        )

        assertThat(displayArtist).isEqualTo("Various Artists")
        assertThat(artistId).isEqualTo(0L)
    }

    @Test
    fun `chooseAlbumDisplayArtist preserves dominant shared artist when groupByAlbumNameOnly is enabled`() {
        val songs = listOf(
            testSong(artistName = "Daft Punk", albumArtist = "Daft Punk"),
            testSong(artistName = "Daft Punk", albumArtist = "Daft Punk"),
            testSong(artistName = "Daft Punk feat. Julian Casablancas", albumArtist = "Daft Punk")
        )

        val displayArtist = chooseAlbumDisplayArtist(
            songs = songs,
            preferAlbumArtist = true,
            groupByAlbumNameOnly = true
        )

        assertThat(displayArtist).isEqualTo("Daft Punk")
    }

    private fun testSong(
        artistName: String,
        albumArtist: String?,
        albumArtUriString: String? = null,
        parentDirectoryPath: String = "/music/default",
        albumName: String = "Hurry Up Tomorrow",
        albumId: Long = 42L
    ): SongEntity {
        return SongEntity(
            id = albumId,
            title = "Track",
            artistName = artistName,
            artistId = 1L,
            albumArtist = albumArtist,
            albumName = albumName,
            albumId = albumId,
            contentUriString = "content://media/$albumId",
            albumArtUriString = albumArtUriString,
            duration = 180_000L,
            genre = null,
            filePath = "$parentDirectoryPath/track.flac",
            parentDirectoryPath = parentDirectoryPath
        )
    }
}
