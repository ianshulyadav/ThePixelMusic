/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class SearchSuggestionsSectionRenderer(
    val contents: List<Content> = emptyList(),
) {
    @Serializable
    data class Content(
        val searchSuggestionRenderer: SearchSuggestionRenderer? = null,
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
    ) {
        @Serializable
        data class SearchSuggestionRenderer(
            val suggestion: Runs = Runs(emptyList()),
            val navigationEndpoint: NavigationEndpoint = NavigationEndpoint(),
        )
    }
}
