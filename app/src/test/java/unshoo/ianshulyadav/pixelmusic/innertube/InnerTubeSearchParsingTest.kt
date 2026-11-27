package unshoo.ianshulyadav.pixelmusic.innertube

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicCardShelfRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicResponsiveListItemRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.response.SearchResponse
import unshoo.ianshulyadav.pixelmusic.innertube.pages.SearchPage
import unshoo.ianshulyadav.pixelmusic.innertube.pages.SearchSummaryPage

class InnerTubeSearchParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    @Test
    fun `decodes SearchResponse with omitted chip isSelected, icon-only buttons, and didYouMeanRenderer`() {
        val raw = """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "header": {
                              "chipCloudRenderer": {
                                "chips": [
                                  {
                                    "chipCloudChipRenderer": {
                                      "text": { "runs": [ { "text": "Songs" } ] }
                                    }
                                  }
                                ]
                              }
                            },
                            "contents": [
                              {
                                "itemSectionRenderer": {
                                  "contents": [
                                    {
                                      "didYouMeanRenderer": {
                                        "correctedQuery": { "runs": [ { "text": "Arijit Singh" } ] },
                                        "didYouMeanEndpoint": {
                                          "searchEndpoint": { "query": "Arijit Singh" }
                                        }
                                      }
                                    }
                                  ]
                                }
                              },
                              {
                                "musicCardShelfRenderer": {
                                  "title": { "runs": [ { "text": "Arijit Singh" } ] },
                                  "subtitle": { "runs": [ { "text": "Artist" } ] },
                                  "thumbnail": {
                                    "croppedSquareThumbnailRenderer": {
                                      "thumbnail": {
                                        "thumbnails": [ { "url": "//yt3.ggpht.com/artist.jpg" } ]
                                      }
                                    }
                                  },
                                  "buttons": [
                                    { "toggleButtonRenderer": { "isToggled": false } },
                                    {
                                      "buttonRenderer": {
                                        "icon": { "iconType": "MUSIC_SHUFFLE" },
                                        "command": { "watchPlaylistEndpoint": { "playlistId": "RDAMPL123" } }
                                      }
                                    }
                                  ],
                                  "onTap": {
                                    "browseEndpoint": {
                                      "browseId": "UC123456"
                                    }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val decoded = json.decodeFromString<SearchResponse>(raw)
        val sections = decoded.contents?.tabbedSearchResultsRenderer?.tabs
            ?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents.orEmpty()

        assertEquals(2, sections.size)
        val didYouMean = sections[0].itemSectionRenderer?.contents?.firstOrNull()?.didYouMeanRenderer
        assertEquals("Arijit Singh", didYouMean?.didYouMeanEndpoint?.searchEndpoint?.query)

        val cardShelf = sections[1].musicCardShelfRenderer
        assertNotNull(cardShelf)
        val artist = SearchSummaryPage.fromMusicCardShelfRenderer(cardShelf!!)
        assertInstanceOf(ArtistItem::class.java, artist)
        val artistItem = artist as ArtistItem
        assertEquals("UC123456", artistItem.id)
        assertEquals("Arijit Singh", artistItem.title)
        assertEquals("https://yt3.ggpht.com/artist.jpg", artistItem.thumbnail)
    }

    @Test
    fun `SearchPage keeps artist, album without playlistId, and song without thumbnail`() {
        val artistRaw = """
            {
              "flexColumns": [
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "The Weeknd" } ] } } }
              ],
              "navigationEndpoint": {
                "browseEndpoint": { "browseId": "UC0WP5P-ufpRfjbN6OWw1iGA" }
              }
            }
        """.trimIndent()
        val albumRaw = """
            {
              "flexColumns": [
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "After Hours" } ] } } }
              ],
              "navigationEndpoint": {
                "browseEndpoint": { "browseId": "MPREb_12345" }
              }
            }
        """.trimIndent()
        val songRaw = """
            {
              "flexColumns": [
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Blinding Lights" } ] } } },
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "The Weeknd" } ] } } }
              ],
              "playlistItemData": { "videoId": "4NRXx6U8ABQ" }
            }
        """.trimIndent()

        val artist = SearchPage.toYTItem(json.decodeFromString<MusicResponsiveListItemRenderer>(artistRaw))
        val album = SearchPage.toYTItem(json.decodeFromString<MusicResponsiveListItemRenderer>(albumRaw))
        val song = SearchPage.toYTItem(json.decodeFromString<MusicResponsiveListItemRenderer>(songRaw))

        assertTrue(artist is ArtistItem && artist.id == "UC0WP5P-ufpRfjbN6OWw1iGA")
        assertTrue(album is AlbumItem && album.browseId == "MPREb_12345" && album.playlistId == "MPREb_12345")
        assertTrue(song is SongItem && song.id == "4NRXx6U8ABQ" && song.title == "Blinding Lights")
    }

    @Test
    fun `SearchSummaryPage keeps playlist card without buttons or header`() {
        val cardRaw = """
            {
              "title": { "runs": [ { "text": "Top Hits" } ] },
              "subtitle": { "runs": [ { "text": "YouTube Music" } ] },
              "onTap": {
                "browseEndpoint": {
                  "browseId": "VLPL999",
                  "browseEndpointContextSupportedConfigs": {
                    "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_PLAYLIST" }
                  }
                }
              }
            }
        """.trimIndent()

        val card = json.decodeFromString<MusicCardShelfRenderer>(cardRaw)
        val parsed = SearchSummaryPage.fromMusicCardShelfRenderer(card)

        assertInstanceOf(PlaylistItem::class.java, parsed)
        val playlist = parsed as PlaylistItem
        assertEquals("PL999", playlist.id)
        assertEquals("Top Hits", playlist.title)
        assertEquals("YouTube Music", playlist.author?.name)
    }
}
