package com.unshoo.pixelmusic.presentation.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.unshoo.pixelmusic.BottomNavItem
import com.unshoo.pixelmusic.presentation.components.scoped.NavSelectionCapsule
import com.unshoo.pixelmusic.presentation.components.scoped.rememberNavIconBounceScale
import com.unshoo.pixelmusic.presentation.navigation.Screen
import com.unshoo.pixelmusic.presentation.navigation.navigateToTopLevelSafely
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val RailPillWidth = 68.dp
private val RailItemHeight = 60.dp
private val RailSearchButtonSize = 60.dp
private val RailSearchShape = RoundedCornerShape(20.dp)

/**
 * Landscape replacement for the bottom navigation bar: a vertical floating pill with the
 * main tabs and the detached search button underneath it, mirroring the portrait
 * floating-pill layout turned on its side. Navigation behaviour (top-level navigation,
 * double-tap on Search to focus the field) matches [PlayerInternalNavigationBar].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppNavigationRail(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRoute: String?,
    onSearchIconDoubleTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latestCurrentRoute by rememberUpdatedState(currentRoute)
    val latestOnSearchDoubleTap by rememberUpdatedState(onSearchIconDoubleTap)
    val navigationEnabled = currentRoute != null
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var lastSearchTap by remember { mutableLongStateOf(0L) }

    val searchItem = navItems.find { it.screen.route == Screen.Search.route }
    val mainItems = navItems.filter { it.screen.route != Screen.Search.route }

    val scheme = MaterialTheme.colorScheme
    val pillColor = remember(scheme.primaryContainer, scheme.surface) {
        Color(
            red = scheme.primaryContainer.red * 0.45f + scheme.surface.red * 0.55f,
            green = scheme.primaryContainer.green * 0.45f + scheme.surface.green * 0.55f,
            blue = scheme.primaryContainer.blue * 0.45f + scheme.surface.blue * 0.55f,
            alpha = 1f
        )
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(NavigationRailWidth)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.width(RailPillWidth),
            color = pillColor,
            shape = RoundedCornerShape(RailPillWidth / 2),
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                mainItems.forEach { item ->
                    RailItem(
                        item = item,
                        selected = currentRoute == item.screen.route,
                        enabled = navigationEnabled,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (latestCurrentRoute != item.screen.route) {
                                navController.navigateToTopLevelSafely(item.screen.route)
                            }
                        }
                    )
                }
            }
        }

        if (searchItem != null) {
            Spacer(Modifier.height(14.dp))
            val selected = currentRoute == searchItem.screen.route
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val motion = MaterialTheme.motionScheme
            val scale by animateFloatAsState(
                targetValue = when {
                    pressed -> 0.92f
                    selected -> 1.03f
                    else -> 1f
                },
                animationSpec = motion.fastSpatialSpec(),
                label = "rail_search_scale"
            )
            val container by animateColorAsState(
                if (selected) scheme.secondaryContainer else scheme.primary,
                animationSpec = motion.defaultEffectsSpec(),
                label = "rail_search_bg"
            )
            val content by animateColorAsState(
                if (selected) scheme.onSecondaryContainer else scheme.onPrimary,
                animationSpec = motion.defaultEffectsSpec(),
                label = "rail_search_fg"
            )
            Surface(
                onClick = {
                    if (!navigationEnabled) return@Surface
                    val now = SystemClock.elapsedRealtime()
                    val isDoubleTap = now - lastSearchTap <= 350L
                    lastSearchTap = now
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (latestCurrentRoute != searchItem.screen.route) {
                        navController.navigateToTopLevelSafely(searchItem.screen.route)
                        if (isDoubleTap) {
                            lastSearchTap = 0L
                            scope.launch {
                                delay(160L)
                                latestOnSearchDoubleTap()
                            }
                        }
                    } else if (isDoubleTap) {
                        lastSearchTap = 0L
                        latestOnSearchDoubleTap()
                    }
                },
                modifier = Modifier
                    .size(RailSearchButtonSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                shape = RailSearchShape,
                color = container,
                contentColor = content,
                shadowElevation = 6.dp,
                interactionSource = interaction
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(searchItem.iconResId),
                        contentDescription = searchItem.label,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RailItem(
    item: BottomNavItem,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val contentColor by animateColorAsState(
        if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(),
        label = "rail_item_fg"
    )
    val labelColor by animateColorAsState(
        if (selected) scheme.onSurface else scheme.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(),
        label = "rail_item_label"
    )
    val bounce = rememberNavIconBounceScale(selected)
    val iconRes = if (selected && item.selectedIconResId != null && item.selectedIconResId != 0) {
        item.selectedIconResId
    } else {
        item.iconResId
    }
    Column(
        modifier = Modifier
            .width(RailPillWidth)
            .height(RailItemHeight)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            NavSelectionCapsule(
                selected = selected,
                color = scheme.secondaryContainer,
                shape = CircleShape,
                modifier = Modifier
                    .width(52.dp)
                    .height(32.dp)
            )
            Icon(
                painter = painterResource(iconRes),
                contentDescription = item.label,
                tint = contentColor,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = bounce
                        scaleY = bounce
                    }
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = item.label,
            color = labelColor,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
