/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package unshoo.ianshulyadav.pixelmusic.innertube.models

import kotlinx.serialization.Serializable

/**
 * Podcast episode row item returned inside YouTube Music podcast show detail pages (`MPSP...`)
 * and podcast episode search/feed shelves.
 */
@Serializable
data class MusicMultiRowListItemRenderer(
    val title: Runs? = null,
    val subtitle: Runs? = null,
    val secondTitle: Runs? = null,
    val description: Runs? = null,
    val thumbnail: ThumbnailRenderer? = null,
    val onTap: NavigationEndpoint? = null,
    val menu: Menu? = null,
    val overlay: MusicResponsiveListItemRenderer.Overlay? = null,
) {
    fun watchEndpoint(): WatchEndpoint? =
        overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.anyWatchEndpoint
            ?: onTap?.anyWatchEndpoint
            ?: menu?.menuRenderer?.items?.firstNotNullOfOrNull {
                it.menuNavigationItemRenderer?.navigationEndpoint?.anyWatchEndpoint
            }
}
