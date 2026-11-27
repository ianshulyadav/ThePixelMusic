/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Tabs(
    val tabs: List<Tab> = emptyList(),
) {
    @Serializable
    data class Tab(
        val tabRenderer: TabRenderer = TabRenderer(),
    ) {
        @Serializable
        data class TabRenderer(
            val title: String? = null,
            val content: Content? = null,
            val endpoint: NavigationEndpoint? = null,
        ) {
            @Serializable
            data class Content(
                val sectionListRenderer: SectionListRenderer? = null,
                val musicQueueRenderer: MusicQueueRenderer? = null,
            )
        }
    }
}
