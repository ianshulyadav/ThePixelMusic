package unshoo.ianshulyadav.pixelmusic.innertube

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicTwoRowItemRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.ThumbnailRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.pages.LibraryPage

class PlaylistThumbnailRendererTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `ThumbnailRenderer resolves croppedSquare and animated backup thumbnails`() {
        val cropped = json.decodeFromString<ThumbnailRenderer>(
            """
            {
              "croppedSquareThumbnailRenderer": {
                "thumbnail": {
                  "thumbnails": [
                    { "url": "//yt3.ggpht.com/small_sq.jpg" },
                    { "url": "//yt3.ggpht.com/custom_sq.jpg?sqp=1&rs=2" }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val animated = json.decodeFromString<ThumbnailRenderer>(
            """
            {
              "musicAnimatedThumbnailRenderer": {
                "backupRenderer": {
                  "thumbnail": {
                    "thumbnails": [
                      { "url": "https://yt3.ggpht.com/backup_cover.jpg" }
                    ]
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            "https://yt3.ggpht.com/custom_sq.jpg?sqp=1&rs=2",
            cropped.getThumbnailUrl(),
        )
        assertEquals(
            "https://yt3.ggpht.com/backup_cover.jpg",
            animated.getThumbnailUrl(),
        )
    }

    @Test
    fun `LibraryPage fromMusicTwoRowItemRenderer preserves playlists with croppedSquare thumbnail`() {
        val renderer = json.decodeFromString<MusicTwoRowItemRenderer>(
            """
            {
              "title": { "runs": [ { "text": "Bhajan" } ] },
              "subtitle": { "runs": [ { "text": "Playlist" }, { "text": " • " }, { "text": "18 songs" } ] },
              "thumbnailRenderer": {
                "croppedSquareThumbnailRenderer": {
                  "thumbnail": {
                    "thumbnails": [
                      { "url": "https://yt3.ggpht.com/bhajan_cover=w544-h544-l90-rj?sqp=token&rs=sig" }
                    ]
                  }
                }
              },
              "navigationEndpoint": {
                "browseEndpoint": {
                  "browseId": "VLPL1234567890",
                  "browseEndpointContextSupportedConfigs": {
                    "browseEndpointContextMusicConfig": {
                      "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val parsed = LibraryPage.fromMusicTwoRowItemRenderer(renderer)
        assertNotNull(parsed)
        val playlist = assertInstanceOf(PlaylistItem::class.java, parsed)
        assertEquals("Bhajan", playlist.title)
        assertEquals("PL1234567890", playlist.id)
        assertEquals(
            "https://yt3.ggpht.com/bhajan_cover=w544-h544-l90-rj?sqp=token&rs=sig",
            playlist.thumbnail,
        )
    }
}
