/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class GridRenderer(
    val header: Header? = null,
    val items: List<Item> = emptyList(),
    val continuations: List<Continuation>? = null,
) {
    @Serializable
    data class Header(
        val gridHeaderRenderer: GridHeaderRenderer = GridHeaderRenderer(),
    ) {
        @Serializable
        data class GridHeaderRenderer(
            val title: Runs = Runs(emptyList()),
        )
    }

    @Serializable
    data class Item(
        val musicNavigationButtonRenderer: MusicNavigationButtonRenderer? = null,
        val musicTwoRowItemRenderer: MusicTwoRowItemRenderer? = null,
    )
}
