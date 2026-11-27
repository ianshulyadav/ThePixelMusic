/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicNavigationButtonRenderer(
    val buttonText: Runs = Runs(emptyList()),
    val solid: Solid? = null,
    val iconStyle: IconStyle? = null,
    val clickCommand: NavigationEndpoint = NavigationEndpoint(),
) {
    @Serializable
    data class Solid(
        val leftStripeColor: Long = 0L,
    )

    @Serializable
    data class IconStyle(
        val icon: Icon = Icon(),
    )
}
