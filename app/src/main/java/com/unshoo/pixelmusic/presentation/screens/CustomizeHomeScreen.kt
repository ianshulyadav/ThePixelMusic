package com.unshoo.pixelmusic.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.preferences.HomeLayoutConfig
import com.unshoo.pixelmusic.data.preferences.HomeLayoutPreferences
import com.unshoo.pixelmusic.data.preferences.HomeSectionId
import com.unshoo.pixelmusic.data.preferences.MostListenedKind
import com.unshoo.pixelmusic.data.preferences.MostListenedRange
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val RowShape = RoundedCornerShape(36.dp)

@Composable
fun homeSectionLabel(section: HomeSectionId): String = stringResource(
    when (section) {
        HomeSectionId.QUICK_PICKS -> R.string.home_section_quick_picks
        HomeSectionId.YOUR_MIX -> R.string.home_section_your_mix
        HomeSectionId.DAILY_MIX -> R.string.home_section_daily_mix
        HomeSectionId.RECENTLY_PLAYED -> R.string.home_section_recently_played
        HomeSectionId.RECENTLY_ADDED -> R.string.home_section_recently_added
        HomeSectionId.ARTIST_RELEASES -> R.string.home_section_artist_releases
        HomeSectionId.MOST_LISTENED -> R.string.home_section_most_listened
        HomeSectionId.LISTENING_STATS -> R.string.home_section_listening_stats
    }
)

fun homeSectionIcon(section: HomeSectionId): ImageVector = when (section) {
    HomeSectionId.QUICK_PICKS -> Icons.Rounded.Bolt
    HomeSectionId.YOUR_MIX -> Icons.Rounded.Shuffle
    HomeSectionId.DAILY_MIX -> Icons.Rounded.AutoAwesome
    HomeSectionId.RECENTLY_PLAYED -> Icons.Rounded.History
    HomeSectionId.RECENTLY_ADDED -> Icons.Rounded.LibraryAdd
    HomeSectionId.ARTIST_RELEASES -> Icons.Rounded.NewReleases
    HomeSectionId.MOST_LISTENED -> Icons.Rounded.Album
    HomeSectionId.LISTENING_STATS -> Icons.Rounded.BarChart
}

/** Sections that have settings on the Options tab. */
private fun HomeSectionId.hasOptions(): Boolean =
    this == HomeSectionId.MOST_LISTENED || this == HomeSectionId.DAILY_MIX

@Composable
fun CustomizeHomeScreen(
    onBackClick: () -> Unit,
    onRegenerateDailyMix: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val stored by remember(context) { HomeLayoutPreferences.configFlow(context) }
        .collectAsState(initial = null)

    // Local editable copy; persisted on Done (and on every add/remove so nothing is lost).
    var sections by remember { mutableStateOf<List<HomeSectionId>?>(null) }
    LaunchedEffect(stored) {
        if (sections == null && stored != null) sections = stored!!.sections
    }
    var selectedTab by remember { mutableStateOf(0) }
    val config = stored ?: HomeLayoutConfig()
    val current = sections ?: config.sections

    fun persist(list: List<HomeSectionId>) {
        sections = list
        scope.launch { HomeLayoutPreferences.setSections(context, list) }
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState = listState) { from, to ->
        val keys = current.map { it.storageKey }
        val fromIdx = keys.indexOf(from.key as? String)
        val toIdx = keys.indexOf(to.key as? String)
        if (fromIdx >= 0 && toIdx >= 0) {
            sections = current.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: close · title · reset
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(Icons.Rounded.Close, MaterialTheme.colorScheme.surfaceContainerHighest, onClick = onBackClick)
                Text(
                    text = stringResource(R.string.customize_home_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                CircleIconButton(Icons.Rounded.RestartAlt, MaterialTheme.colorScheme.secondaryContainer) {
                    sections = HomeSectionId.DEFAULT_ORDER
                    scope.launch { HomeLayoutPreferences.reset(context) }
                }
            }

            // Items / Options segmented tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SegmentTab(stringResource(R.string.customize_home_tab_items), selectedTab == 0, Modifier.weight(1f)) { selectedTab = 0 }
                SegmentTab(stringResource(R.string.customize_home_tab_options), selectedTab == 1, Modifier.weight(1f)) { selectedTab = 1 }
            }

            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            if (selectedTab == 0) {
                val hidden = HomeSectionId.entries.filter { it !in current }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomInset + 112.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "header_on_home") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(
                                text = stringResource(R.string.customize_home_on_home),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = current.size.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(current, key = { it.storageKey }) { section ->
                        ReorderableItem(reorderState, key = section.storageKey) { isDragging ->
                            val elevationColor by animateColorAsState(
                                if (isDragging) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer,
                                label = "dragColor"
                            )
                            SectionRow(
                                section = section,
                                container = elevationColor,
                                dragHandle = {
                                    Box(
                                        modifier = Modifier
                                            .draggableHandle(
                                                onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                                onDragStopped = { sections?.let { persist(it) } }
                                            )
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.DragIndicator, contentDescription = null)
                                    }
                                },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (section.hasOptions()) {
                                            IconButton(onClick = { selectedTab = 1 }) {
                                                Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.customize_home_tab_options))
                                            }
                                        }
                                        CircleIconButton(Icons.Rounded.Remove, MaterialTheme.colorScheme.surfaceContainerLowest) {
                                            persist(current - section)
                                        }
                                    }
                                }
                            )
                        }
                    }
                    if (hidden.isNotEmpty()) {
                        item(key = "header_add") {
                            Text(
                                text = stringResource(R.string.customize_home_add_section),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp)
                            )
                        }
                        items(hidden, key = { "add_" + it.storageKey }) { section ->
                            SectionRow(
                                section = section,
                                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                                dragHandle = { Spacer(Modifier.width(4.dp)) },
                                trailing = {
                                    CircleIconButton(Icons.Rounded.Add, MaterialTheme.colorScheme.primaryContainer) {
                                        persist(current + section)
                                    }
                                }
                            )
                        }
                    }
                }
            } else {
                HomeOptionsTab(
                    config = config,
                    bottomPadding = bottomInset + 112.dp,
                    onRangeChange = { scope.launch { HomeLayoutPreferences.setMostListenedRange(context, it) } },
                    onKindChange = { scope.launch { HomeLayoutPreferences.setMostListenedKind(context, it) } },
                    onRegenerateDailyMix = onRegenerateDailyMix,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        ExtendedFloatingActionButton(
            onClick = {
                sections?.let { persist(it) }
                onBackClick()
            },
            icon = { Icon(Icons.Rounded.Check, contentDescription = null) },
            text = { Text(stringResource(R.string.customize_home_done)) },
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp)
        )
    }
}

@Composable
private fun SectionRow(
    section: HomeSectionId,
    container: androidx.compose.ui.graphics.Color,
    dragHandle: @Composable () -> Unit,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dragHandle()
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(homeSectionIcon(section), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = homeSectionLabel(section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

@Composable
private fun HomeOptionsTab(
    config: HomeLayoutConfig,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onRangeChange: (MostListenedRange) -> Unit,
    onKindChange: (MostListenedKind) -> Unit,
    onRegenerateDailyMix: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "opt_most_listened") {
            OptionsCard(title = homeSectionLabel(HomeSectionId.MOST_LISTENED), icon = homeSectionIcon(HomeSectionId.MOST_LISTENED)) {
                Text(
                    text = stringResource(R.string.customize_home_most_listened_range),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChipRow(
                    options = listOf(
                        MostListenedRange.TODAY to stringResource(R.string.customize_home_range_today),
                        MostListenedRange.WEEK to stringResource(R.string.customize_home_range_week),
                        MostListenedRange.MONTH to stringResource(R.string.customize_home_range_month),
                        MostListenedRange.YEAR to stringResource(R.string.customize_home_range_year),
                        MostListenedRange.ALL_TIME to stringResource(R.string.customize_home_range_all)
                    ),
                    selected = config.mostListenedRange,
                    onSelect = onRangeChange
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.customize_home_most_listened_kind),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChipRow(
                    options = listOf(
                        MostListenedKind.SONGS to stringResource(R.string.customize_home_kind_songs),
                        MostListenedKind.ALBUMS to stringResource(R.string.customize_home_kind_albums),
                        MostListenedKind.ARTISTS to stringResource(R.string.customize_home_kind_artists)
                    ),
                    selected = config.mostListenedKind,
                    onSelect = onKindChange
                )
            }
        }
        item(key = "opt_daily_mix") {
            OptionsCard(title = homeSectionLabel(HomeSectionId.DAILY_MIX), icon = homeSectionIcon(HomeSectionId.DAILY_MIX)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(onClick = onRegenerateDailyMix)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.customize_home_daily_mix_regenerate),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.customize_home_daily_mix_regenerate_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun OptionsCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val bg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "chipBg"
            )
            val fg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                label = "chipFg"
            )
            Text(
                text = label,
                color = fg,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun SegmentTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
        label = "segBg"
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        label = "segFg"
    )
    val corner by animateDpAsState(if (selected) 26.dp else 18.dp, label = "segCorner")
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(corner))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CircleIconButton(
    icon: ImageVector,
    container: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
    }
}
