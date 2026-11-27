/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Menu(
    val menuRenderer: MenuRenderer = MenuRenderer(),
) {
    @Serializable
    data class MenuRenderer(
        val items: List<Item>? = null,
        val topLevelButtons: List<TopLevelButton>? = null,
    ) {
        @Serializable
        data class Item(
            val menuNavigationItemRenderer: MenuNavigationItemRenderer? = null,
            val menuServiceItemRenderer: MenuServiceItemRenderer? = null,
            val toggleMenuServiceItemRenderer: ToggleMenuServiceRenderer? = null,
        ) {
            @Serializable
            data class MenuNavigationItemRenderer(
                val text: Runs = Runs(emptyList()),
                val icon: Icon = Icon(),
                val navigationEndpoint: NavigationEndpoint = NavigationEndpoint(),
            )

            @Serializable
            data class MenuServiceItemRenderer(
                val text: Runs = Runs(emptyList()),
                val icon: Icon = Icon(),
                val serviceEndpoint: NavigationEndpoint = NavigationEndpoint(),
            )
            @Serializable
            data class ToggleMenuServiceRenderer(
                val defaultIcon: Icon = Icon(),
                val defaultServiceEndpoint: DefaultServiceEndpoint = DefaultServiceEndpoint(),
            )
        }

        @Serializable
        data class TopLevelButton(
            val buttonRenderer: ButtonRenderer? = null,
        ) {
            @Serializable
            data class ButtonRenderer(
                val icon: Icon = Icon(),
                val navigationEndpoint: NavigationEndpoint = NavigationEndpoint(),
            )
        }
    }
}
