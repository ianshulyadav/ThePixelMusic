/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicCarouselShelfRenderer(
    val header: Header? = null,
    val contents: List<Content> = emptyList(),
    val itemSize: String = "",
    val numItemsPerColumn: Int? = null,
) {
    @Serializable
    data class Header(
        val musicCarouselShelfBasicHeaderRenderer: MusicCarouselShelfBasicHeaderRenderer = MusicCarouselShelfBasicHeaderRenderer(),
    ) {
        @Serializable
        data class MusicCarouselShelfBasicHeaderRenderer(
            val strapline: Runs? = null,
            val title: Runs = Runs(emptyList()),
            val thumbnail: ThumbnailRenderer? = null,
            val moreContentButton: Button? = null,
        )
    }

    @Serializable
    data class Content(
        val musicTwoRowItemRenderer: MusicTwoRowItemRenderer? = null,
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
        val musicNavigationButtonRenderer: MusicNavigationButtonRenderer? = null, // navigation button in explore tab
    )
}
