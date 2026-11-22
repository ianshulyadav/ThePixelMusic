package com.unshoo.pixelmusic.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window-level layout decisions shared by the app shell and the screens.
 *
 * Portrait phones keep the original bottom navigation bar. A landscape window that is at
 * least [NavigationRailMinWidth] wide (every phone held sideways, tablets, foldables)
 * swaps it for a navigation rail on the start edge, and the screens switch to their
 * landscape layouts.
 */
@Immutable
data class AppWindowLayout(
    /** True when the bottom navigation bar is replaced by the side navigation rail. */
    val useNavigationRail: Boolean = false,
    /** Window is wider than it is tall. */
    val isLandscape: Boolean = false,
    /** Window height is below the compact-height breakpoint (phones held sideways). */
    val isCompactHeight: Boolean = false,
    val windowWidth: Dp = 0.dp,
    val windowHeight: Dp = 0.dp
) {
    /** Space the main tab screens leave on their start edge for the rail. */
    val navigationRailInset: Dp get() = if (useNavigationRail) NavigationRailWidth else 0.dp

    /** Landscape screens that should split into two panes (header | list). */
    val useTwoPane: Boolean get() = useNavigationRail
}

/** Width of the rail column (the pill itself is narrower and centred inside it). */
val NavigationRailWidth = 88.dp
private val NavigationRailMinWidth = 560.dp
private val CompactHeightBreakpoint = 480.dp

/** Max width of single-column content (settings, forms) in landscape. */
val LandscapeReadableContentWidth = 680.dp

/** Mini player width when it floats at the bottom end in landscape. */
val LandscapeMiniPlayerMaxWidth = 420.dp

val LocalAppWindowLayout = staticCompositionLocalOf { AppWindowLayout() }

@Composable
fun rememberAppWindowLayout(): AppWindowLayout {
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp
    val height = configuration.screenHeightDp
    val orientation = configuration.orientation
    return remember(width, height, orientation) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE || width > height
        AppWindowLayout(
            useNavigationRail = landscape && width.dp >= NavigationRailMinWidth,
            isLandscape = landscape,
            isCompactHeight = height.dp < CompactHeightBreakpoint,
            windowWidth = width.dp,
            windowHeight = height.dp
        )
    }
}

/**
 * Centers [content] in a column no wider than [maxWidth] when the window is in landscape;
 * a no-op in portrait. Used for list/settings screens that look stretched edge to edge.
 */
@Composable
fun LandscapeReadableWidth(
    modifier: Modifier = Modifier,
    maxWidth: Dp = LandscapeReadableContentWidth,
    content: @Composable BoxScope.() -> Unit
) {
    val layout = LocalAppWindowLayout.current
    if (!layout.isLandscape) {
        Box(modifier = modifier.fillMaxSize(), content = content)
        return
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = maxWidth)
                .fillMaxWidth(),
            content = content
        )
    }
}

/** Start padding that keeps main-tab content clear of the navigation rail. */
@Composable
fun Modifier.navigationRailInsetPadding(): Modifier {
    val inset = LocalAppWindowLayout.current.navigationRailInset
    return if (inset > 0.dp) this.padding(start = inset) else this
}

/**
 * Max height of the collapsing hero header on album/artist/playlist pages. In landscape the
 * window is only ~360dp tall, so a 300dp header would leave almost no room for the list;
 * cap it to just over half the window height there.
 */
@Composable
fun rememberDetailHeaderMaxHeight(portraitHeight: Dp): Dp {
    val layout = LocalAppWindowLayout.current
    return if (layout.isLandscape && layout.windowHeight > 0.dp) {
        (layout.windowHeight * 0.55f).coerceIn(150.dp, portraitHeight)
    } else {
        portraitHeight
    }
}
