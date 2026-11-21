package com.unshoo.pixelmusic.presentation.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.Animatable
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.unshoo.pixelmusic.BottomNavItem
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.preferences.NavBarStyle
import com.unshoo.pixelmusic.presentation.components.scoped.CustomNavigationBarItem
import com.unshoo.pixelmusic.presentation.navigation.Screen
import com.unshoo.pixelmusic.presentation.navigation.navigateToTopLevelSafely
import com.unshoo.pixelmusic.ui.theme.PixelMusicTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Rounded-square search button (reference design). */
private val SearchButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)

internal val NavBarContentHeight = 90.dp
internal val NavBarCompactContentHeight = 64.dp
internal val NavBarContentHeightFullWidth = NavBarContentHeight
private val FloatingPillContentHeight = 76.dp
private val FloatingPillCompactContentHeight = 62.dp
private val MainScreenBottomGradientExtraHeight = MiniPlayerHeight + MiniPlayerBottomSpacer + 8.dp
internal val MaxNavigationBarBottomInset = 96.dp

internal fun sanitizeNavigationBarBottomInset(systemNavBarInset: Dp): Dp {
    if (!systemNavBarInset.value.isFinite()) return 0.dp
    return systemNavBarInset.coerceIn(0.dp, MaxNavigationBarBottomInset)
}

internal fun calculatePlayerSheetCollapsedTargetY(
    containerHeightPx: Float,
    collapsedContentHeightPx: Float,
    bottomMarginPx: Float,
    bottomSpacerPx: Float
): Float {
    val safeContainerHeightPx = containerHeightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeCollapsedContentHeightPx = collapsedContentHeightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeBottomMarginPx = bottomMarginPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeBottomSpacerPx = bottomSpacerPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val maxTargetY = (safeContainerHeightPx - safeCollapsedContentHeightPx).coerceAtLeast(0f)

    return (safeContainerHeightPx - safeCollapsedContentHeightPx - safeBottomMarginPx - safeBottomSpacerPx)
        .coerceIn(0f, maxTargetY)
}

internal fun resolveNavBarContentHeight(compactMode: Boolean, heightOffset: Dp = 0.dp): Dp =
    (if (compactMode) NavBarCompactContentHeight else NavBarContentHeight) + heightOffset

internal fun resolveNavBarContentHeight(navBarStyle: String, compactMode: Boolean, heightOffset: Dp = 0.dp): Dp =
    when (navBarStyle) {
        NavBarStyle.FLOATING_PILL ->
            FloatingPillContentHeight + heightOffset
        else -> resolveNavBarContentHeight(compactMode, heightOffset)
    }

internal fun resolveMainScreenBottomGradientHeight(compactMode: Boolean, heightOffset: Dp = 0.dp): Dp =
    resolveNavBarContentHeight(compactMode, heightOffset) + MainScreenBottomGradientExtraHeight

internal fun resolveNavBarSurfaceHeight(
    navBarStyle: String,
    systemNavBarInset: Dp,
    compactMode: Boolean,
    heightOffset: Dp = 0.dp
): Dp {
    val contentHeight = resolveNavBarContentHeight(navBarStyle, compactMode, heightOffset)
    return if (navBarStyle == NavBarStyle.FULL_WIDTH) {
        contentHeight + systemNavBarInset
    } else {
        contentHeight
    }
}

internal fun resolveFloatingNavBarBottomPadding(systemNavBarInset: Dp): Dp =
    if (systemNavBarInset > 0.dp) (systemNavBarInset - 2.dp).coerceAtLeast(0.dp) else 2.dp

internal fun resolveNavBarOccupiedHeight(
    systemNavBarInset: Dp,
    compactMode: Boolean
): Dp = resolveNavBarOccupiedHeight(NavBarStyle.DEFAULT, systemNavBarInset, compactMode, 0.dp)

internal fun resolveNavBarOccupiedHeight(
    navBarStyle: String,
    systemNavBarInset: Dp,
    compactMode: Boolean,
    heightOffset: Dp = 0.dp
): Dp {
    val contentHeight = resolveNavBarContentHeight(navBarStyle, compactMode, heightOffset)
    return if (navBarStyle == NavBarStyle.FULL_WIDTH) {
        contentHeight + systemNavBarInset
    } else {
        contentHeight + resolveFloatingNavBarBottomPadding(systemNavBarInset)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveFloatingPillNavigationBar(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRoute: String?,
    modifier: Modifier = Modifier,
    frostedBlur: Boolean = false,
    onSearchIconDoubleTap: () -> Unit = {}
) {
    // The expressive pill maintains its full intended proportions and is never shrunken by compact mode.
    val pillItemHeight = 52.dp
    val searchButtonSize = 64.dp
    val pillIconSize = 24.dp
    val selectionContainerWidth = 54.dp
    val selectionContainerHeight = 32.dp
    val selectionContainerShape = CircleShape
    val latestCurrentRoute by rememberUpdatedState(currentRoute)
    val latestNavigationEnabled by rememberUpdatedState(currentRoute != null)
    val backdropSource = LocalBackdropSource.current
    val glassTint = frostedGlassTint()
    var lastSearchTapTimestamp by remember { mutableStateOf(0L) }

    val haptic = LocalHapticFeedback.current

    val searchItem = navItems.find { it.screen.route == Screen.Search.route }
    val mainItems = navItems.filter { it.screen.route != Screen.Search.route }

    val selectedIndex = mainItems.indexOfFirst { it.screen.route == latestCurrentRoute }

    val motionScheme = MaterialTheme.motionScheme

    val baseContainerColor = MaterialTheme.colorScheme.primaryContainer
    val surfaceColor = MaterialTheme.colorScheme.surface
    val solidTintedColor = remember(baseContainerColor, surfaceColor) {
        Color(
            red = (baseContainerColor.red * 0.45f) + (surfaceColor.red * 0.55f),
            green = (baseContainerColor.green * 0.45f) + (surfaceColor.green * 0.55f),
            blue = (baseContainerColor.blue * 0.45f) + (surfaceColor.blue * 0.55f),
            alpha = 1f
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tomato-style Material You Expressive Floating Pill Navigation Bar
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier
                .weight(1f)
                .height(64.dp)
                .then(
                    if (frostedBlur) {
                        Modifier
                            .clip(CircleShape)
                            .frostedBackdrop(source = backdropSource, tint = glassTint)
                    } else {
                        Modifier.shadow(elevation = 6.dp, shape = CircleShape)
                    }
                ),
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = if (frostedBlur) Color.Transparent else solidTintedColor
            ),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
        ) {
            // One shared selection container that springs ("jumps") between tabs with a
            // slight jelly stretch, matching the bounce of the other navigation bar styles.
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
            ) {
                val itemCount = mainItems.size.coerceAtLeast(1)
                val itemWidth = maxWidth / itemCount
                val targetIndex = selectedIndex.coerceAtLeast(0).toFloat()
                val indicatorPosition = remember { Animatable(targetIndex) }
                var hasPositionedIndicator by remember { mutableStateOf(false) }
                LaunchedEffect(targetIndex) {
                    if (!hasPositionedIndicator) {
                        indicatorPosition.snapTo(targetIndex)
                        hasPositionedIndicator = true
                    } else {
                        indicatorPosition.animateTo(
                            targetIndex,
                            androidx.compose.animation.core.spring(
                                dampingRatio = 0.62f,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                            )
                        )
                    }
                }
                val indicatorVisibility by animateFloatAsState(
                    targetValue = if (selectedIndex >= 0) 1f else 0f,
                    animationSpec = tween(durationMillis = 140),
                    label = "pill_indicator_visibility"
                )
                val indicatorColor = if (frostedBlur) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
                if (indicatorVisibility > 0.005f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(selectionContainerWidth)
                            .height(selectionContainerHeight)
                            .graphicsLayer {
                                val pos = indicatorPosition.value
                                val travel = kotlin.math.abs(pos - targetIndex).coerceAtMost(1f)
                                val itemWidthPx = itemWidth.toPx()
                                translationX = pos * itemWidthPx + (itemWidthPx - size.width) / 2f
                                // Stretch along the travel direction mid-jump, settle round.
                                scaleX = (1f + 0.38f * travel) * (0.6f + 0.4f * indicatorVisibility)
                                scaleY = (1f - 0.12f * travel) * (0.6f + 0.4f * indicatorVisibility)
                                alpha = indicatorVisibility
                            }
                            .clip(selectionContainerShape)
                            .background(indicatorColor, selectionContainerShape)
                    )
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    mainItems.forEachIndexed { index, item ->
                        val isSelected = selectedIndex == index
                        val itemInteractionSource = remember { MutableInteractionSource() }
                        val isPressed by itemInteractionSource.collectIsPressedAsState()

                        val animContentColor by animateColorAsState(
                            targetValue = if (isSelected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            animationSpec = motionScheme.defaultEffectsSpec(),
                            label = "pill_content_" + index
                        )
                        val iconScale by animateFloatAsState(
                            targetValue = when {
                                isPressed -> 0.86f
                                isSelected -> 1.08f
                                else -> 1.0f
                            },
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                            ),
                            label = "pill_icon_bounce_" + index
                        )

                        Box(
                            modifier = Modifier
                                .weight(1.0f)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = itemInteractionSource,
                                    indication = null,
                                    enabled = latestNavigationEnabled
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (latestCurrentRoute != item.screen.route) {
                                        navController.navigateToTopLevelSafely(item.screen.route)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val iconRes = if (isSelected && item.selectedIconResId != null && item.selectedIconResId != 0) {
                                item.selectedIconResId
                            } else {
                                item.iconResId
                            }
                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = item.label,
                                tint = animContentColor,
                                modifier = Modifier
                                    .size(pillIconSize)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    }
                            )
                        }
                    }
                }
            }
        }

        // Disconnected Search FAB — M3 Expressive floating action button
        // Uses the solid app theme-based primary color (matching Library header title)
        if (searchItem != null) {
            val isSearchSelected = latestCurrentRoute == searchItem.screen.route
            val searchInteractionSource = remember { MutableInteractionSource() }
            val isSearchPressed by searchInteractionSource.collectIsPressedAsState()
            val isSearchHovered by searchInteractionSource.collectIsHoveredAsState()

            val fabScale by animateFloatAsState(
                targetValue = when {
                    isSearchPressed -> 0.92f
                    isSearchHovered -> 1.06f
                    isSearchSelected -> 1.03f
                    else -> 1.0f
                },
                animationSpec = motionScheme.fastSpatialSpec(),
                label = "search_fab_scale"
            )

            val searchIconBounce by animateFloatAsState(
                targetValue = if (isSearchSelected) 1.15f else 1.0f,
                animationSpec = motionScheme.fastSpatialSpec(),
                label = "search_icon_bounce"
            )

            val searchIconRes = if (isSearchSelected && searchItem.selectedIconResId != null && searchItem.selectedIconResId != 0) {
                searchItem.selectedIconResId
            } else {
                searchItem.iconResId
            }

            val searchColor by animateColorAsState(
                targetValue = if (isSearchSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                animationSpec = motionScheme.defaultEffectsSpec(),
                label = "search_fab_bg"
            )

            val searchContentColor by animateColorAsState(
                targetValue = if (isSearchSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                animationSpec = motionScheme.defaultEffectsSpec(),
                label = "search_fab_content"
            )

            Surface(
                onClick = {
                    if (!latestNavigationEnabled) return@Surface

                    val now = SystemClock.elapsedRealtime()
                    val isDoubleTap = now - lastSearchTapTimestamp <= 350L
                    lastSearchTapTimestamp = now

                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                    if (!isSearchSelected) {
                        navController.navigateToTopLevelSafely(searchItem.screen.route)
                    } else if (isDoubleTap) {
                        lastSearchTapTimestamp = 0L
                        onSearchIconDoubleTap()
                    }
                },
                modifier = Modifier
                    .size(searchButtonSize)
                    .graphicsLayer {
                        scaleX = fabScale
                        scaleY = fabScale
                    },
                shape = SearchButtonShape,
                color = searchColor,
                contentColor = searchContentColor,
                tonalElevation = if (isSearchHovered) 4.dp else 2.dp,
                shadowElevation = if (isSearchHovered) 8.dp else 6.dp,
                interactionSource = searchInteractionSource
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        painter = painterResource(id = searchIconRes),
                        contentDescription = searchItem.label,
                        modifier = Modifier
                            .size(26.dp)
                            .graphicsLayer {
                                scaleX = searchIconBounce
                                scaleY = searchIconBounce
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerInternalNavigationItemsRow(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRoute: String?,
    modifier: Modifier = Modifier,
    navBarStyle: String,
    compactMode: Boolean,
    bottomBarPadding: Dp,
    onSearchIconDoubleTap: () -> Unit
) {
    val navBarInsetPadding = sanitizeNavigationBarBottomInset(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    )
    val innerRowPadding = (navBarInsetPadding - bottomBarPadding).coerceAtLeast(0.dp)
    val latestCurrentRoute by rememberUpdatedState(currentRoute)
    val latestOnSearchIconDoubleTap by rememberUpdatedState(onSearchIconDoubleTap)
    val latestNavigationEnabled by rememberUpdatedState(currentRoute != null)

    val rowModifier = if (navBarStyle == NavBarStyle.FULL_WIDTH) {
        modifier
            .fillMaxWidth()
            .padding(top = 0.dp, bottom = innerRowPadding, start = 12.dp, end = 12.dp)
    } else {
        modifier
            .padding(start = 10.dp, end = 10.dp, bottom = innerRowPadding)
            .fillMaxWidth()
    }
    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val scope = rememberCoroutineScope()
        var lastSearchTapTimestamp by remember { mutableStateOf(0L) }
        navItems.forEach { item ->
            val isSelected = currentRoute != null && currentRoute == item.screen.route
            val baseContainerColor = MaterialTheme.colorScheme.primaryContainer
            val surfaceColor = MaterialTheme.colorScheme.surface
            val solidTintedColor = remember(baseContainerColor, surfaceColor) {
                Color(
                    red = (baseContainerColor.red * 0.45f) + (surfaceColor.red * 0.55f),
                    green = (baseContainerColor.green * 0.45f) + (surfaceColor.green * 0.55f),
                    blue = (baseContainerColor.blue * 0.45f) + (surfaceColor.blue * 0.55f),
                    alpha = 1f
                )
            }
            val indicatorColorFromTheme = if (navBarStyle == NavBarStyle.FULL_WIDTH) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                solidTintedColor
            }
            val selectedIconColor = if (navBarStyle == NavBarStyle.FULL_WIDTH) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.primary
            }
            val unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
            val selectedTextColor = MaterialTheme.colorScheme.onSurface
            val unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant

            val iconPainterResId = if (isSelected && item.selectedIconResId != null && item.selectedIconResId != 0) {
                item.selectedIconResId
            } else {
                item.iconResId
            }
            val label = item.label
            val iconLambda: @Composable () -> Unit = remember(iconPainterResId, label) {
                {
                    Icon(
                        painter = painterResource(id = iconPainterResId),
                        contentDescription = label
                    )
                }
            }
            val selectedIconLambda: @Composable () -> Unit = remember(iconPainterResId, label) {
                {
                    Icon(
                        painter = painterResource(id = iconPainterResId),
                        contentDescription = label
                    )
                }
            }
            val labelLambda: (@Composable () -> Unit)? = if (compactMode) {
                null
            } else {
                remember(label) {
                    { Text(label) }
                }
            }
            val onClickLambda: () -> Unit = remember(item.screen.route, navController, scope) {
                click@{
                    if (!latestNavigationEnabled) {
                        lastSearchTapTimestamp = 0L
                        return@click
                    }

                    val itemRoute = item.screen.route
                    val isSearchTab = itemRoute == Screen.Search.route
                    val isAlreadySelected = latestCurrentRoute == itemRoute

                    if (isSearchTab) {
                        val now = SystemClock.elapsedRealtime()
                        val isDoubleTap = now - lastSearchTapTimestamp <= 350L
                        lastSearchTapTimestamp = now

                        if (!isAlreadySelected) {
                            if (!navController.navigateToTopLevelSafely(itemRoute)) {
                                lastSearchTapTimestamp = 0L
                                return@click
                            }
                        }

                        if (isDoubleTap) {
                            lastSearchTapTimestamp = 0L
                            if (isAlreadySelected) {
                                latestOnSearchIconDoubleTap()
                            } else {
                                scope.launch {
                                    delay(160L)
                                    latestOnSearchIconDoubleTap()
                                }
                            }
                        }
                    } else if (!isAlreadySelected) {
                        lastSearchTapTimestamp = 0L
                        navController.navigateToTopLevelSafely(itemRoute)
                    } else {
                        lastSearchTapTimestamp = 0L
                    }
                }
            }
            CustomNavigationBarItem(
                modifier = Modifier.weight(1f),
                selected = isSelected,
                onClick = onClickLambda,
                enabled = currentRoute != null,
                compactMode = compactMode,
                icon = iconLambda,
                selectedIcon = selectedIconLambda,
                label = labelLambda,
                contentDescription = label,
                alwaysShowLabel = true,
                selectedIconColor = selectedIconColor,
                unselectedIconColor = unselectedIconColor,
                selectedTextColor = selectedTextColor,
                unselectedTextColor = unselectedTextColor,
                indicatorColor = indicatorColorFromTheme
            )
        }
    }
}

@Composable
fun PlayerInternalNavigationBar(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRoute: String?,
    modifier: Modifier = Modifier,
    navBarStyle: String,
    compactMode: Boolean,
    bottomBarPadding: Dp = 0.dp,
    frostedBlur: Boolean = false,
    onSearchIconDoubleTap: () -> Unit = {}
) {
    if (navBarStyle == NavBarStyle.FLOATING_PILL) {
        ExpressiveFloatingPillNavigationBar(
            navController = navController,
            navItems = navItems,
            currentRoute = currentRoute,
            modifier = modifier,
            frostedBlur = frostedBlur,
            onSearchIconDoubleTap = onSearchIconDoubleTap
        )
    } else {
        PlayerInternalNavigationItemsRow(
            navController = navController,
            navItems = navItems,
            currentRoute = currentRoute,
            navBarStyle = navBarStyle,
            compactMode = compactMode,
            bottomBarPadding = bottomBarPadding,
            onSearchIconDoubleTap = onSearchIconDoubleTap,
            modifier = modifier
        )
    }
}

/** Dynamic-colour tint for frosted glass chrome (M3 Expressive): surface tinted toward primary. */
@Composable
internal fun frostedGlassTint(songTintStrength: Float = 0.14f): Color {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.surfaceContainerHigh, scheme.primaryContainer, songTintStrength) {
        // Frosted (milky) rather than clear glass: denser tint over a heavy blur.
        lerp(scheme.surfaceContainerHigh, scheme.primaryContainer, songTintStrength).copy(alpha = 0.80f)
    }
}

/** Thin light rim that sells the glass edge. */
@Composable
internal fun frostedGlassRim(): Color =
    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

// -----------------------------------------------------------------------------
// Compose Previews
// -----------------------------------------------------------------------------

@Composable
private fun ExpressiveFloatingPillPreviewWrapper(
    frosted: Boolean,
    compact: Boolean = false
) {
    val navController = rememberNavController()
    val navItems = remember {
        persistentListOf(
            BottomNavItem("Home", R.drawable.rounded_home_24, R.drawable.home_24_rounded_filled, Screen.Home),
            BottomNavItem("Explore", R.drawable.rounded_album_24, R.drawable.rounded_album_24, Screen.Explore),
            BottomNavItem("Search", R.drawable.rounded_search_24, R.drawable.rounded_search_24, Screen.Search),
            BottomNavItem("Library", R.drawable.rounded_library_music_24, R.drawable.round_library_music_24, Screen.Library)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        ExpressiveFloatingPillNavigationBar(
            navController = navController,
            navItems = navItems,
            currentRoute = Screen.Home.route,
            frostedBlur = frosted
        )
    }
}

@Preview(name = "Expressive Floating Pill - Light Solid", showBackground = true)
@Composable
fun ExpressiveFloatingPillLightPreview() {
    PixelMusicTheme(darkTheme = false) {
        ExpressiveFloatingPillPreviewWrapper(frosted = false)
    }
}

@Preview(name = "Expressive Floating Pill - Dark Frosted", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ExpressiveFloatingPillDarkFrostedPreview() {
    PixelMusicTheme(darkTheme = true) {
        ExpressiveFloatingPillPreviewWrapper(frosted = true)
    }
}

@Preview(name = "Expressive Floating Pill - Compact Mode", showBackground = true)
@Composable
fun ExpressiveFloatingPillCompactPreview() {
    PixelMusicTheme(darkTheme = false) {
        ExpressiveFloatingPillPreviewWrapper(frosted = false)
    }
}
