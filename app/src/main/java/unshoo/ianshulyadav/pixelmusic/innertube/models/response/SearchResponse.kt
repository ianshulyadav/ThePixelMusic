/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.models.response

import unshoo.ianshulyadav.pixelmusic.innertube.models.Continuation
import unshoo.ianshulyadav.pixelmusic.innertube.models.ContinuationItemRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicMultiRowListItemRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.MusicResponsiveListItemRenderer
import unshoo.ianshulyadav.pixelmusic.innertube.models.Tabs
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val contents: Contents? = null,
    val continuationContents: ContinuationContents? = null,
) {
    @Serializable
    data class Contents(
        val tabbedSearchResultsRenderer: Tabs? = null,
        val sectionListRenderer: unshoo.ianshulyadav.pixelmusic.innertube.models.SectionListRenderer? = null,
    )

    @Serializable
    data class ContinuationContents(
        val musicShelfContinuation: MusicShelfContinuation? = null,
    ) {
        @Serializable
        data class MusicShelfContinuation(
            val contents: List<Content> = emptyList(),
            val continuations: List<Continuation>? = null,
        ) {
            @Serializable
            data class Content(
                val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
                val musicMultiRowListItemRenderer: MusicMultiRowListItemRenderer? = null,
                val continuationItemRenderer: ContinuationItemRenderer? = null,
            )
        }
    }
}
