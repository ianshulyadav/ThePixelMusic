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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
        HomeSectionId.MOST_LISTENED -> R.string.home_section_most_listened
        HomeSectionId.LISTENING_STATS -> R.string.home_section_listening_stats
        HomeSectionId.QUEUE_HISTORY -> R.string.home_queue_history_title
    }
)

fun homeSectionIcon(section: HomeSectionId): ImageVector = when (section) {
    HomeSectionId.QUICK_PICKS -> Icons.Rounded.Bolt
    HomeSectionId.YOUR_MIX -> Icons.Rounded.Shuffle
    HomeSectionId.DAILY_MIX -> Icons.Rounded.AutoAwesome
    HomeSectionId.RECENTLY_PLAYED -> Icons.Rounded.History
    HomeSectionId.RECENTLY_ADDED -> Icons.Rounded.LibraryAdd
    HomeSectionId.MOST_LISTENED -> Icons.Rounded.Album
    HomeSectionId.LISTENING_STATS -> Icons.Rounded.BarChart
    HomeSectionId.QUEUE_HISTORY -> Icons.AutoMirrored.Rounded.QueueMusic
}

/** Sections that have settings on the Options tab. */
private fun HomeSectionId.hasOptions(): Boolean =
    this == HomeSectionId.MOST_LISTENED || this == HomeSectionId.DAILY_MIX ||
        this == HomeSectionId.QUICK_PICKS || this == HomeSectionId.YOUR_MIX

/** LazyColumn key of the Options card for a section (used to jump straight to it). */
private fun HomeSectionId.optionsKey(): String = "opt_" + storageKey

@Composable
fun CustomizeHomeScreen(
    onBackClick: () -> Unit,
    onRegenerateDailyMix: () -> Unit,
    settingsViewModel: com.unshoo.pixelmusic.presentation.viewmodel.SettingsViewModel =
        androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
) {
    val settingsState by settingsViewModel.uiState.collectAsState()
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
    // Section whose gear was tapped: the Options tab scrolls to (and highlights) its card.
    var focusedOptions by remember { mutableStateOf<HomeSectionId?>(null) }
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
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomInset + DoneButtonClearance),
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
                                            IconButton(onClick = {
                                                focusedOptions = section
                                                selectedTab = 1
                                            }) {
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
                    settingsState = settingsState,
                    focusedSection = focusedOptions,
                    bottomPadding = bottomInset + DoneButtonClearance,
                    onRangeChange = { scope.launch { HomeLayoutPreferences.setMostListenedRange(context, it) } },
                    onKindChange = { scope.launch { HomeLayoutPreferences.setMostListenedKind(context, it) } },
                    onRegenerateDailyMix = onRegenerateDailyMix,
                    onShowGreetingChange = { scope.launch { HomeLayoutPreferences.setShowGreeting(context, it) } },
                    onCustomGreetingChange = { scope.launch { HomeLayoutPreferences.setCustomGreeting(context, it) } },
                    onQuickPicksModeChange = settingsViewModel::setQuickPicksDisplayMode,
                    onQuickPicksSourceChange = settingsViewModel::setDiscover,
                    onCollageEnabledChange = settingsViewModel::setCollageEnabled,
                    onCollagePatternChange = settingsViewModel::setCollagePattern,
                    onCollageAutoRotateChange = settingsViewModel::setCollageAutoRotate,
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
                // BUGFIX: sat behind the mini player whenever a song was loaded. Keep it above it.
                .padding(
                    end = 20.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                        com.unshoo.pixelmusic.presentation.components.MiniPlayerHeight + 28.dp
                )
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

private val DoneButtonClearance = com.unshoo.pixelmusic.presentation.components.MiniPlayerHeight + 112.dp

@Composable
private fun HomeOptionsTab(
    config: HomeLayoutConfig,
    settingsState: com.unshoo.pixelmusic.presentation.viewmodel.SettingsUiState,
    focusedSection: HomeSectionId?,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onRangeChange: (MostListenedRange) -> Unit,
    onKindChange: (MostListenedKind) -> Unit,
    onRegenerateDailyMix: () -> Unit,
    onShowGreetingChange: (Boolean) -> Unit,
    onCustomGreetingChange: (String) -> Unit,
    onQuickPicksModeChange: (com.unshoo.pixelmusic.data.preferences.QuickPicksDisplayMode) -> Unit,
    onQuickPicksSourceChange: (com.unshoo.pixelmusic.data.preferences.QuickPicks) -> Unit,
    onCollageEnabledChange: (Boolean) -> Unit,
    onCollagePatternChange: (com.unshoo.pixelmusic.data.preferences.CollagePattern) -> Unit,
    onCollageAutoRotateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val optionsListState = rememberLazyListState()
    val cardKeys = listOf(
        "opt_greeting",
        HomeSectionId.QUICK_PICKS.optionsKey(),
        HomeSectionId.YOUR_MIX.optionsKey(),
        HomeSectionId.MOST_LISTENED.optionsKey(),
        HomeSectionId.DAILY_MIX.optionsKey()
    )
    LaunchedEffect(focusedSection) {
        val index = focusedSection?.let { cardKeys.indexOf(it.optionsKey()) } ?: -1
        if (index >= 0) optionsListState.animateScrollToItem(index)
    }
    LazyColumn(
        state = optionsListState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "opt_greeting") {
            OptionsCard(title = stringResource(R.string.customize_home_greeting), icon = Icons.Rounded.WavingHand) {
                ToggleRow(
                    title = stringResource(R.string.customize_home_greeting_show),
                    subtitle = stringResource(R.string.customize_home_greeting_show_desc),
                    checked = config.showGreeting,
                    onCheckedChange = onShowGreetingChange
                )
                AnimatedVisibility(
                    visible = config.showGreeting,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    var text by remember(config.customGreeting) { mutableStateOf(config.customGreeting) }
                    androidx.compose.material3.OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it.take(80)
                            onCustomGreetingChange(text)
                        },
                        label = { Text(stringResource(R.string.customize_home_greeting_custom)) },
                        placeholder = { Text(stringResource(R.string.customize_home_greeting_custom_hint)) },
                        supportingText = { Text(stringResource(R.string.customize_home_greeting_custom_desc)) },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        item(key = HomeSectionId.QUICK_PICKS.optionsKey()) {
            OptionsCard(
                title = homeSectionLabel(HomeSectionId.QUICK_PICKS),
                icon = homeSectionIcon(HomeSectionId.QUICK_PICKS),
                highlighted = focusedSection == HomeSectionId.QUICK_PICKS
            ) {
                OptionLabel(stringResource(R.string.settings_quick_picks_mode_title))
                ChipRow(
                    options = listOf(
                        com.unshoo.pixelmusic.data.preferences.QuickPicksDisplayMode.M3_MULTI_BROWSE to stringResource(R.string.settings_quick_picks_mode_multi_browse),
                        com.unshoo.pixelmusic.data.preferences.QuickPicksDisplayMode.M3_HERO to stringResource(R.string.settings_quick_picks_mode_hero),
                        com.unshoo.pixelmusic.data.preferences.QuickPicksDisplayMode.CARD to stringResource(R.string.settings_quick_picks_mode_square),
                        com.unshoo.pixelmusic.data.preferences.QuickPicksDisplayMode.CARD_CLASSIC to stringResource(R.string.settings_quick_picks_mode_classic),
                        com.unshoo.pixelmusic.data.preferences.QuickPicksDisplayMode.UNCONTAINED to stringResource(R.string.settings_quick_picks_mode_uncontained),
                        com.unshoo.pixelmusic.data.preferences.QuickPicksDisplayMode.LIST to stringResource(R.string.settings_quick_picks_mode_list)
                    ),
                    selected = settingsState.quickPicksDisplayMode,
                    onSelect = onQuickPicksModeChange
                )
                Spacer(Modifier.height(6.dp))
                OptionLabel(stringResource(R.string.settings_set_quick_picks_title))
                ChipRow(
                    options = listOf(
                        com.unshoo.pixelmusic.data.preferences.QuickPicks.QUICK_PICKS to stringResource(R.string.customize_home_quick_picks_discover),
                        com.unshoo.pixelmusic.data.preferences.QuickPicks.LAST_LISTEN to stringResource(R.string.customize_home_quick_picks_last_listen)
                    ),
                    selected = settingsState.discover,
                    onSelect = onQuickPicksSourceChange
                )
            }
        }
        item(key = HomeSectionId.YOUR_MIX.optionsKey()) {
            OptionsCard(
                title = homeSectionLabel(HomeSectionId.YOUR_MIX),
                icon = homeSectionIcon(HomeSectionId.YOUR_MIX),
                highlighted = focusedSection == HomeSectionId.YOUR_MIX
            ) {
                ToggleRow(
                    title = stringResource(R.string.setcat_show_collage_title),
                    subtitle = stringResource(R.string.setcat_show_collage_subtitle),
                    checked = settingsState.collageEnabled,
                    onCheckedChange = onCollageEnabledChange
                )
                AnimatedVisibility(
                    visible = settingsState.collageEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OptionLabel(stringResource(R.string.setcat_collage_pattern_label))
                        ChipRow(
                            options = com.unshoo.pixelmusic.data.preferences.CollagePattern.entries.map { it to it.label },
                            selected = settingsState.collagePattern,
                            onSelect = onCollagePatternChange
                        )
                        ToggleRow(
                            title = stringResource(R.string.setcat_auto_rotate_patterns_title),
                            subtitle = stringResource(R.string.setcat_auto_rotate_patterns_subtitle),
                            checked = settingsState.collageAutoRotate,
                            onCheckedChange = onCollageAutoRotateChange
                        )
                    }
                }
            }
        }
        item(key = HomeSectionId.MOST_LISTENED.optionsKey()) {
            OptionsCard(
                title = homeSectionLabel(HomeSectionId.MOST_LISTENED),
                icon = homeSectionIcon(HomeSectionId.MOST_LISTENED),
                highlighted = focusedSection == HomeSectionId.MOST_LISTENED
            ) {
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
        item(key = HomeSectionId.DAILY_MIX.optionsKey()) {
            OptionsCard(
                title = homeSectionLabel(HomeSectionId.DAILY_MIX),
                icon = homeSectionIcon(HomeSectionId.DAILY_MIX),
                highlighted = focusedSection == HomeSectionId.DAILY_MIX
            ) {
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
private fun OptionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun OptionsCard(
    title: String,
    icon: ImageVector,
    highlighted: Boolean = false,
    content: @Composable () -> Unit
) {
    // Briefly pulse the card the user jumped to from a section's gear button.
    val highlightAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            highlightAlpha.snapTo(1f)
            kotlinx.coroutines.delay(900)
            highlightAlpha.animateTo(0f, androidx.compose.animation.core.tween(700))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f * highlightAlpha.value))
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

/**
 * Single-choice selector as a Material 3 Expressive *connected button group*: options sit in
 * joined rows (outer corners round, inner corners tight) and the selected one morphs to a
 * fully rounded, filled shape with a check mark. Long labels get two per row, short labels three.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val perRow = if (options.all { it.second.length <= 11 }) 3 else 2
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(perRow).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween)
            ) {
                rowOptions.forEachIndexed { index, (value, label) ->
                    val isSelected = value == selected
                    val shapes = when {
                        rowOptions.size == 1 -> androidx.compose.material3.ToggleButtonDefaults.shapes()
                        index == 0 -> androidx.compose.material3.ButtonGroupDefaults.connectedLeadingButtonShapes()
                        index == rowOptions.lastIndex -> androidx.compose.material3.ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> androidx.compose.material3.ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                    androidx.compose.material3.ToggleButton(
                        checked = isSelected,
                        onCheckedChange = { onSelect(value) },
                        shapes = shapes,
                        colors = androidx.compose.material3.ToggleButtonDefaults.toggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            checkedContainerColor = MaterialTheme.colorScheme.primary,
                            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { role = androidx.compose.ui.semantics.Role.RadioButton }
                    ) {
                        androidx.compose.animation.AnimatedVisibility(visible = isSelected) {
                            Row {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                // Keep a lone last option the same width as the others.
                repeat(perRow - rowOptions.size) { Spacer(Modifier.weight(1f)) }
            }
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
