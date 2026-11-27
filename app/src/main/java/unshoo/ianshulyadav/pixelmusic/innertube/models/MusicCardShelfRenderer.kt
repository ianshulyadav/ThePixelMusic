/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicCardShelfRenderer(
    val title: Runs = Runs(emptyList()),
    val subtitle: Runs = Runs(emptyList()),
    val thumbnail: ThumbnailRenderer = ThumbnailRenderer(),
    val header: Header? = null,
    val contents: List<Content>? = null,
    val buttons: List<Button> = emptyList(),
    val onTap: NavigationEndpoint = NavigationEndpoint(),
    val subtitleBadges: List<Badges>? = null,
) {
    @Serializable
    data class Header(
        val musicCardShelfHeaderBasicRenderer: MusicCardShelfHeaderBasicRenderer = MusicCardShelfHeaderBasicRenderer(),
    ) {
        @Serializable
        data class MusicCardShelfHeaderBasicRenderer(
            val title: Runs = Runs(emptyList()),
        )
    }

    @Serializable
    data class Content(
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
    )
}
