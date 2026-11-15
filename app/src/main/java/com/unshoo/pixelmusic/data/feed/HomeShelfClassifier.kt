package com.unshoo.pixelmusic.data.feed

import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.pages.HomePage

/**
 * Classifies YouTube Music `FEmusic_home` shelves by what the backend actually sends, not by
 * their English titles.
 *
 * InnerTube requests carry `hl` = the user's app language, so shelf titles come back localized
 * ("Selección rápida", "Schnellauswahl", "クイック ピック", ...). The previous `title.contains("quick
 * pick")` matching silently failed for every non-English user: quick picks fell back to "every song
 * on the page", new releases and albums-for-you were empty, and music-video shelves leaked into
 * audio shelves. Structural signals are locale-independent:
 *
 *  - Quick picks is the list-style carousel (musicResponsiveListItemRenderer items) of songs.
 *  - New releases links its "more" button to the `FEmusic_new_releases*` browse page.
 *  - Listen again carries the user's avatar/name as the header strapline.
 *  - Video shelves are made of songs whose musicVideoType is not ATV (audio track).
 *  - Mix shelves are auto-generated `RD…` radio playlists.
 *
 * English keywords remain only as a last-resort tie breaker.
 */
object HomeShelfClassifier {

    enum class Kind {
        QUICK_PICKS,
        LISTEN_AGAIN,
        NEW_RELEASES,
        ALBUMS,
        MIXES,
        MUSIC_VIDEOS,
        ARTISTS,
        DAILY_DISCOVER,
        TRENDING,
        OTHER,
    }

    private const val MAJORITY = 0.6

    fun classify(section: HomePage.Section): Kind {
        val items = section.items
        if (items.isEmpty()) return Kind.OTHER
        val title = section.title.lowercase()
        val moreBrowseId = section.endpoint?.browseId.orEmpty()

        // 1. Explicit backend destinations (locale independent).
        if (moreBrowseId.startsWith("FEmusic_new_releases")) return Kind.NEW_RELEASES
        if (moreBrowseId.startsWith("FEmusic_charts") || moreBrowseId.startsWith("FEmusic_trending")) {
            return Kind.TRENDING
        }

        val total = items.size.toDouble()
        val songs = items.filterIsInstance<SongItem>()
        val videoShare = songs.count { it.isMusicVideo } / total
        val songShare = songs.size / total
        val albumShare = items.count { it is AlbumItem } / total
        val playlistItems = items.filterIsInstance<PlaylistItem>()
        val mixShare = playlistItems.count { isRadioMixId(it.id) } / total
        val artistShare = items.count { it is ArtistItem } / total

        // 2. Title keywords that are unambiguous in English; checked before the structural
        //    heuristics only where the structure alone cannot tell two shelves apart.
        if (title.contains("daily discover")) return Kind.DAILY_DISCOVER
        if (hasNewReleaseKeyword(title) && videoShare < MAJORITY) return Kind.NEW_RELEASES

        // 3. Structural classification.
        if (videoShare >= MAJORITY) return Kind.MUSIC_VIDEOS
        if (section.isListShelf && songShare >= MAJORITY) {
            return if (title.contains("trending") || title.contains("top ") || title.contains("chart")) {
                Kind.TRENDING
            } else {
                Kind.QUICK_PICKS
            }
        }
        // "Listen again" puts the signed-in user's avatar + name in the header strapline.
        if (section.thumbnail != null && section.label != null && !section.isListShelf) return Kind.LISTEN_AGAIN
        if (mixShare >= MAJORITY) return Kind.MIXES
        if (albumShare >= MAJORITY) return Kind.ALBUMS
        if (artistShare >= MAJORITY) return Kind.ARTISTS

        // 4. English fallbacks for responses that lack the structural markers above.
        return when {
            title.contains("quick pick") -> Kind.QUICK_PICKS
            title.contains("listen again") -> Kind.LISTEN_AGAIN
            title.contains("video") -> Kind.MUSIC_VIDEOS
            title.contains("mix") -> Kind.MIXES
            title.contains("album") -> Kind.ALBUMS
            title.contains("trending") -> Kind.TRENDING
            else -> Kind.OTHER
        }
    }

    /** Pairs every section with its kind in one pass so callers do not re-classify. */
    fun classifyAll(sections: List<HomePage.Section>): List<Pair<HomePage.Section, Kind>> {
        val classified = sections.map { it to classify(it) }
        // Only the FIRST list-style song shelf is Quick picks; later ones are ordinary song shelves.
        var seenQuickPicks = false
        return classified.map { (section, kind) ->
            if (kind == Kind.QUICK_PICKS) {
                if (seenQuickPicks) section to Kind.OTHER else {
                    seenQuickPicks = true
                    section to kind
                }
            } else section to kind
        }
    }

    fun isRadioMixId(id: String): Boolean =
        id.startsWith("RDTMAK") || id.startsWith("RDAMPL") || id.startsWith("RDCLAK") ||
            id.startsWith("RDAT") || id.startsWith("RDEM") || id.startsWith("RDMM")

    private fun hasNewReleaseKeyword(title: String): Boolean =
        title.contains("new release") || title.contains("new album") || title.contains("latest release") ||
            title.contains("recent release") || title.contains("release radar") || title.contains("released") ||
            title.contains("new for you") || title.contains("novedades") || title.contains("nouveautés") ||
            title.contains("veröffentlichungen") || title.contains("lançamentos")
}
