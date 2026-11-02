@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.unshoo.pixelmusic.presentation.screens.lossless

import com.unshoo.pixelmusic.presentation.screens.AiApiKeyItem
import com.unshoo.pixelmusic.presentation.screens.SettingsItem
import com.unshoo.pixelmusic.BuildConfig

import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.unshoo.pixelmusic.data.addons.AddonCheckReport
import com.unshoo.pixelmusic.data.addons.AddonHealthTracker
import com.unshoo.pixelmusic.data.addons.InstalledAddon
import com.unshoo.pixelmusic.data.addons.MonochromeInstancePool
import com.unshoo.pixelmusic.data.addons.UnifiedSourceId
import com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver
import com.unshoo.pixelmusic.data.lossless.SourceCheckResult
import com.unshoo.pixelmusic.data.lossless.SourceCheckService
import com.unshoo.pixelmusic.data.lossless.SourceCheckStatus
import com.unshoo.pixelmusic.data.lossless.applemusic.AppleMusicAudioProvider
import com.unshoo.pixelmusic.data.lossless.applemusic.AppleMusicProvider
import com.unshoo.pixelmusic.data.lossless.audiosource.AudioSourceConfig
import com.unshoo.pixelmusic.data.lossless.constants.AppleMusicQuality
import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceType
import com.unshoo.pixelmusic.data.lossless.constants.DeezerAudioQuality
import com.unshoo.pixelmusic.data.lossless.constants.QobuzAudioQuality
import com.unshoo.pixelmusic.data.lossless.qobuz.QobuzToken
import com.unshoo.pixelmusic.data.lossless.constants.TidalAudioQuality
import com.unshoo.pixelmusic.data.lossless.constants.TidalSubscriptionStatus
import com.unshoo.pixelmusic.data.lossless.pool.PoolAccountManager
import com.unshoo.pixelmusic.presentation.navigation.Screen
import com.unshoo.pixelmusic.presentation.navigation.navigateSafely
import com.unshoo.pixelmusic.presentation.model.SettingsCategory
import com.unshoo.pixelmusic.presentation.utils.LocalAppHapticsConfig
import com.unshoo.pixelmusic.presentation.utils.performAppCompatHapticFeedback
import com.unshoo.pixelmusic.presentation.viewmodel.LosslessSourcesUiState
import com.unshoo.pixelmusic.presentation.viewmodel.LosslessSourcesViewModel
import com.unshoo.pixelmusic.presentation.viewmodel.PoolCounts
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class LosslessSubScreen {
    HUB,
    APPLE_MUSIC,
    MONOCHROME,
    ARCHIVETUNE_POOL,
    JIOSAAVN,
    ADDONS
}

@Composable
fun LosslessSourcesSettings(
    navController: NavController,
    viewModel: LosslessSourcesViewModel = hiltViewModel(),
    onSubNavChange: (onSubBack: (() -> Unit)?) -> Unit = {},
    onResetScroll: () -> Unit = {}
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val poolCounts by viewModel.poolCounts.collectAsStateWithLifecycle()
    val poolReport by viewModel.poolStatusReport.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val checkResults by viewModel.checkResults.collectAsStateWithLifecycle()
    val poolCheck by viewModel.poolCheckResult.collectAsStateWithLifecycle()
    val message by viewModel.lastMessage.collectAsStateWithLifecycle()
    val installedAddons by viewModel.installedAddons.collectAsStateWithLifecycle()
    val checkReports by viewModel.checkReports.collectAsStateWithLifecycle()

    var activeSubScreen by remember { mutableStateOf(LosslessSubScreen.HUB) }
    var configureAddon by remember { mutableStateOf<InstalledAddon?>(null) }
    var showOrderBottomSheet by remember { mutableStateOf(false) }
    var showPoolUrlDialog by remember { mutableStateOf(false) }
    var showTidalInstancesDialog by remember { mutableStateOf(false) }
    var showQobuzTokensDialog by remember { mutableStateOf(false) }
    var showQobuzInstancesDialog by remember { mutableStateOf(false) }
    var showAppleTokensDialog by remember { mutableStateOf(false) }
    var showMonochromeServersDialog by remember { mutableStateOf(false) }
    var removeConfirmAddon by remember { mutableStateOf<InstalledAddon?>(null) }
    var resultDialog by remember { mutableStateOf<Pair<String, SourceCheckResult>?>(null) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(activeSubScreen) {
        onResetScroll()
        if (activeSubScreen != LosslessSubScreen.HUB) {
            onSubNavChange {
                activeSubScreen = LosslessSubScreen.HUB
                onResetScroll()
            }
        } else {
            onSubNavChange(null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onSubNavChange(null)
        }
    }

    BackHandler(enabled = activeSubScreen != LosslessSubScreen.HUB) {
        activeSubScreen = LosslessSubScreen.HUB
        onResetScroll()
    }

    AnimatedContent(
        targetState = activeSubScreen,
        transitionSpec = {
            if (targetState != LosslessSubScreen.HUB) {
                (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "LosslessScreenTransition"
    ) { subScreen ->
        when (subScreen) {
            LosslessSubScreen.HUB -> {
                ModulesHubScreen(
                    navController = navController,
                    state = state,
                    poolCounts = poolCounts,
                    installedAddons = installedAddons,
                    checkReports = checkReports,
                    busy = busy,
                    onOpenSubScreen = { activeSubScreen = it },
                    onOpenOrderSheet = { showOrderBottomSheet = true },
                    onToggleLossless = viewModel::setLosslessEnabled,
                    onToggleAddon = viewModel::setAddonEnabled,
                    onTestAddon = viewModel::testAddon,
                    onRefreshAddon = viewModel::refreshAddon,
                    onConfigureAddon = { configureAddon = it },
                    onRemoveAddon = { removeConfirmAddon = it },
                    onClearCache = viewModel::clearResolvedCache
                )
            }

            LosslessSubScreen.APPLE_MUSIC -> {
                AppleMusicModuleScreen(
                    state = state,
                    poolCounts = poolCounts,
                    isBusy = AudioSourceType.APPLE.name in busy,
                    checkResult = checkResults[AudioSourceType.APPLE],
                    onToggleEnabled = viewModel::setAppleEnabled,
                    onSelectQuality = viewModel::setAppleQuality,
                    onCheckServer = { viewModel.checkSource(AudioSourceType.APPLE) },
                    onSignInWeb = { navController.navigateSafely(Screen.AppleMusicLogin.route) },
                    onSignOut = viewModel::signOutApple,
                    onOpenTokensDialog = { showAppleTokensDialog = true },
                    onOpenPoolSettings = { activeSubScreen = LosslessSubScreen.ARCHIVETUNE_POOL }
                )
            }

            LosslessSubScreen.MONOCHROME -> {
                MonochromeModuleScreen(
                    state = state,
                    isBusy = AudioSourceType.MONOCHROME.name in busy,
                    checkResult = checkResults[AudioSourceType.MONOCHROME],
                    onToggleEnabled = viewModel::setMonochromeEnabled,
                    onCheckServer = { viewModel.checkSource(AudioSourceType.MONOCHROME) },
                    onOpenServersDialog = { showMonochromeServersDialog = true }
                )
            }

            LosslessSubScreen.ARCHIVETUNE_POOL -> {
                ArchiveTunePoolScreen(
                    state = state,
                    poolCounts = poolCounts,
                    poolReport = poolReport,
                    poolCheck = poolCheck,
                    busy = busy,
                    checkResults = checkResults,
                    onTogglePoolEnabled = viewModel::setPoolEnabled,
                    onSaveApiKey = viewModel::savePoolApiKey,
                    onOpenPoolUrlDialog = { showPoolUrlDialog = true },
                    onCheckFeed = { viewModel.checkPool() },
                    onFetchHealth = { viewModel.fetchPoolStatus() },
                    onToggleTidal = viewModel::setTidalEnabled,
                    onSelectTidalQuality = viewModel::setTidalQuality,
                    onToggleTidalAccountFirst = viewModel::setTidalAccountFirst,
                    onSignInTidal = { navController.navigateSafely(Screen.TidalLogin.route) },
                    onSignOutTidal = viewModel::signOutTidal,
                    onOpenTidalInstances = { showTidalInstancesDialog = true },
                    onCheckTidal = { viewModel.checkSource(AudioSourceType.TIDAL) },
                    onToggleQobuz = viewModel::setQobuzEnabled,
                    onSelectQobuzQuality = viewModel::setQobuzQuality,
                    onSignInQobuz = { navController.navigateSafely(Screen.QobuzLogin.route) },
                    onOpenQobuzTokens = { showQobuzTokensDialog = true },
                    onOpenQobuzInstances = { showQobuzInstancesDialog = true },
                    onCheckQobuz = { viewModel.checkSource(AudioSourceType.QOBUZ) },
                    onToggleDeezer = viewModel::setDeezerEnabled,
                    onSelectDeezerQuality = viewModel::setDeezerQuality,
                    onSignInDeezer = { navController.navigateSafely(Screen.DeezerLogin.route) },
                    onCheckDeezer = { viewModel.checkSource(AudioSourceType.DEEZER) }
                )
            }

            LosslessSubScreen.JIOSAAVN -> {
                JioSaavnModuleScreen(
                    state = state,
                    isBusy = AudioSourceType.JIOSAAVN.name in busy,
                    checkResult = checkResults[AudioSourceType.JIOSAAVN],
                    onToggleEnabled = viewModel::setSaavnEnabled,
                    onCheckServer = { viewModel.checkSource(AudioSourceType.JIOSAAVN) }
                )
            }

            LosslessSubScreen.ADDONS -> {
                com.unshoo.pixelmusic.presentation.screens.addons.AddonsSettingsScreen(
                    navController = navController,
                    onBackClick = {
                        activeSubScreen = LosslessSubScreen.HUB
                        onResetScroll()
                    }
                )
            }
        }
    }

    configureAddon?.let { addon ->
        com.unshoo.pixelmusic.presentation.screens.addons.AddonSettingsBottomSheet(
            addon = addon,
            onDismiss = { configureAddon = null },
            onSaveSetting = { key, value ->
                viewModel.updateAddonSetting(addon.id, key, value)
            }
        )
    }

    if (showOrderBottomSheet) {
        ProviderOrderBottomSheet(
            state = state,
            poolCounts = poolCounts,
            installedAddons = installedAddons,
            onDismiss = { showOrderBottomSheet = false },
            onToggleMonochrome = viewModel::setMonochromeEnabled,
            onToggleApple = viewModel::setAppleEnabled,
            onToggleTidal = viewModel::setTidalEnabled,
            onToggleQobuz = viewModel::setQobuzEnabled,
            onToggleDeezer = viewModel::setDeezerEnabled,
            onToggleSaavn = viewModel::setSaavnEnabled,
            onToggleAddon = viewModel::setAddonEnabled,
            onSaveOrder = viewModel::saveUnifiedOrder
        )
    }

    removeConfirmAddon?.let { addon ->
        AlertDialog(
            onDismissRequest = { removeConfirmAddon = null },
            title = { Text("Uninstall ${addon.name}?", fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold) },
            text = { Text("This removes the module and its saved settings from your app.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeAddon(addon.id)
                        removeConfirmAddon = null
                    }
                ) {
                    Text("Uninstall", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirmAddon = null }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showMonochromeServersDialog) {
        MonochromeServersDialog(
            currentCustomInstances = state.monochromeCustomInstances,
            onDismiss = { showMonochromeServersDialog = false },
            onSave = {
                viewModel.saveMonochromeCustomInstances(it)
                showMonochromeServersDialog = false
            }
        )
    }

    if (showPoolUrlDialog) {
        PoolUrlSimpleDialog(
            currentUrl = state.poolBaseUrl,
            effectiveUrl = state.poolEffectiveBaseUrl,
            onDismiss = { showPoolUrlDialog = false },
            onSave = { viewModel.savePoolBaseUrl(it); showPoolUrlDialog = false }
        )
    }

    if (showTidalInstancesDialog) {
        TidalInstancesSimpleDialog(
            instances = state.tidalInstances,
            onDismiss = { showTidalInstancesDialog = false },
            onSave = { viewModel.setTidalInstances(it); showTidalInstancesDialog = false }
        )
    }

    if (showQobuzInstancesDialog) {
        QobuzInstancesSimpleDialog(
            instances = state.qobuzInstances,
            onDismiss = { showQobuzInstancesDialog = false },
            onSave = { viewModel.setQobuzInstances(it); showQobuzInstancesDialog = false }
        )
    }

    if (showQobuzTokensDialog) {
        QobuzTokensSimpleDialog(
            tokens = state.qobuzTokens,
            onDismiss = { showQobuzTokensDialog = false },
            onAdd = { viewModel.addQobuzTokens(it) },
            onRemove = { viewModel.removeQobuzToken(it) }
        )
    }

    if (showAppleTokensDialog) {
        AppleTokensSimpleDialog(
            mediaToken = state.appleMediaUserToken,
            devToken = state.appleDevToken,
            busy = AudioSourceType.APPLE.name in busy,
            onDismiss = { showAppleTokensDialog = false },
            onSave = { media, dev -> viewModel.saveAppleTokens(media, dev); showAppleTokensDialog = false }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 1. MODULES HUB SCREEN
// ---------------------------------------------------------------------------------------------

@Composable
private fun ModulesHubScreen(
    navController: NavController,
    state: LosslessSourcesUiState,
    poolCounts: PoolCounts,
    installedAddons: List<InstalledAddon>,
    checkReports: Map<String, AddonCheckReport>,
    busy: Set<String> = emptySet(),
    onOpenSubScreen: (LosslessSubScreen) -> Unit,
    onOpenOrderSheet: () -> Unit,
    onToggleLossless: (Boolean) -> Unit,
    onToggleAddon: (String, Boolean) -> Unit,
    onTestAddon: (String) -> Unit,
    onRefreshAddon: (String) -> Unit,
    onConfigureAddon: (InstalledAddon) -> Unit,
    onRemoveAddon: (InstalledAddon) -> Unit,
    onClearCache: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Master Toggle Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(50.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.HighQuality,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Lossless Audio",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (state.losslessEnabled) "Lossless streams enabled" else "Fallback to standard stream",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.losslessEnabled,
                    onCheckedChange = onToggleLossless,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // Provider Fallback Order Card
        val activeSourcesPreview = remember(state.sourceOrder, state.monochromeEnabled, state.appleEnabled, state.saavnEnabled) {
            val list = mutableListOf<String>()
            if (state.monochromeEnabled) list.add("Monochrome")
            if (state.appleEnabled) list.add("Apple Music")
            if (state.poolApiKey.isNotBlank() || state.poolBuildTimeKeyPresent) {
                list.add("ArchiveTune")
            }
            if (state.saavnEnabled) list.add("JioSaavn")
            list.add("YouTube")
            list.take(3).joinToString(" -> ") + if (list.size > 3) "..." else ""
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable { onOpenOrderSheet() },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.SwapVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Playback Provider Order",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = activeSourcesPreview,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // "Find more sources" Card (Solid Material 3, opens Addons subscreen directly)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .clickable {
                    onOpenSubScreen(LosslessSubScreen.ADDONS)
                },
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add more sources",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Add a module from the catalog or from a link",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Section Title: MODULES
        Text(
            text = "MODULES",
            style = MaterialTheme.typography.labelLarge,
            fontFamily = GoogleSansRounded,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
        )

        // 1. Installed Addons (addon First, with full options matching Image 4!)
        installedAddons.forEach { addon ->
            val report = checkReports[addon.id]
            val isBusy = "addon_${addon.id}" in busy
            InstalledAddonModuleCard(
                addon = addon,
                report = report,
                isBusy = isBusy,
                onToggleEnabled = { onToggleAddon(addon.id, it) },
                onTest = { onTestAddon(addon.id) },
                onRefresh = { onRefreshAddon(addon.id) },
                onConfigure = { onConfigureAddon(addon) },
                onRemove = { onRemoveAddon(addon) }
            )
        }

        // 2. Monochrome
        ModuleCard(
            icon = Icons.Rounded.GraphicEq,
            iconContainerColor = Color(0xFF1E1E1E),
            iconColor = Color.White,
            title = "Monochrome",
            subtitle = if (state.monochromeEnabled) "Ready · FLAC up to 24-bit / 192 kHz" else "Off · No account needed",
            pillTag = null,
            versionTag = "Built in · v2.1",
            onClick = { onOpenSubScreen(LosslessSubScreen.MONOCHROME) }
        )

        // 3. Apple Music
        val appleSubtitle = when {
            state.appleMediaUserToken.isNotBlank() -> "Account connected · ALAC Lossless / AAC"
            else -> "Not signed in"
        }
        ModuleCard(
            icon = Icons.Rounded.MusicNote,
            iconContainerColor = Color(0xFFFC3C44),
            iconColor = Color.White,
            title = "Apple Music",
            subtitle = appleSubtitle,
            pillTag = null,
            versionTag = "Built in · v1.0",
            onClick = { onOpenSubScreen(LosslessSubScreen.APPLE_MUSIC) }
        )

        // 4. ArchiveTune Pool (Houses Tidal, Qobuz, Deezer, Apple Music)
        val isPoolConfigured = state.poolApiKey.isNotBlank() || state.poolBuildTimeKeyPresent
        val poolSubtitle = if (isPoolConfigured) {
            "${poolCounts.total} pooled accounts (${poolCounts.apple} Apple, ${poolCounts.tidal} Tidal, ${poolCounts.qobuz} Qobuz, ${poolCounts.deezer} Deezer)"
        } else {
            "Multi-source pool · Tidal, Qobuz, Deezer, Apple"
        }
        ModuleCard(
            icon = Icons.Outlined.Cloud,
            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
            iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            title = "ArchiveTune Pool",
            subtitle = poolSubtitle,
            pillTag = "Multi-Source",
            versionTag = if (isPoolConfigured) "Configured · API v2.0" else "Key required",
            onClick = { onOpenSubScreen(LosslessSubScreen.ARCHIVETUNE_POOL) }
        )

        // 5. JioSaavn (Lossless category before YouTube)
        ModuleCard(
            icon = Icons.Rounded.GraphicEq,
            iconContainerColor = Color(0xFF2BC5B4),
            iconColor = Color.White,
            title = "JioSaavn",
            subtitle = if (state.saavnEnabled) "On · No account needed" else "Off · No account needed",
            pillTag = null,
            versionTag = "Built in · v1.0",
            onClick = { onOpenSubScreen(LosslessSubScreen.JIOSAAVN) }
        )

        Spacer(Modifier.height(8.dp))

        // Cache reset button
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onClearCache() },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Clear resolved streams cache",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// INSTALLED ADDON MODULE CARD (Matches Image 4 with Test, Settings, Refresh, Delete)
// ---------------------------------------------------------------------------------------------

@Composable
private fun InstalledAddonModuleCard(
    addon: InstalledAddon,
    report: AddonCheckReport?,
    isBusy: Boolean = false,
    onToggleEnabled: (Boolean) -> Unit,
    onTest: () -> Unit,
    onRefresh: () -> Unit,
    onConfigure: () -> Unit,
    onRemove: () -> Unit
) {
    val initial = addon.name.firstOrNull()?.uppercaseChar()?.toString() ?: "M"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Monogram avatar (Matches Addons screen)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(46.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = initial,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = addon.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (addon.enabled) "On · No account needed" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.width(8.dp))

                Switch(
                    checked = addon.enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }

            // Tag badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = "${addon.protocol.displayLabel} v${addon.version}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (addon.settingsSchema.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = "${addon.settingsSchema.size} setting(s)",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // Rich 2-line quality status banner (Image 3 style)
            if (report != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (report.ok) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (report.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (report.ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = report.summary,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = GoogleSansRounded,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Action row: Check server, Configure, Delete (Exact match to Addons screen)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val addonSpinTransition = rememberInfiniteTransition(label = "addon_card_spin_${addon.id}")
                val addonSpinAngle by addonSpinTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "addon_card_spin_angle_${addon.id}"
                )

                FilledTonalButton(
                    onClick = onTest,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .then(if (isBusy) Modifier.graphicsLayer { rotationZ = addonSpinAngle } else Modifier)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isBusy) "Checking…" else "Check server", fontFamily = GoogleSansRounded, style = MaterialTheme.typography.labelMedium)
                }

                if (addon.settingsSchema.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = onConfigure,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Configure", fontFamily = GoogleSansRounded, style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 2. PROVIDER ORDER BOTTOM SHEET
// ---------------------------------------------------------------------------------------------

private data class OrderItemData(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconBg: Color,
    val iconColor: Color,
    val isEnabled: Boolean,
    val onToggle: (Boolean) -> Unit,
    val monogram: String? = null
)

@Composable
private fun ProviderOrderBottomSheet(
    state: LosslessSourcesUiState,
    poolCounts: PoolCounts,
    installedAddons: List<InstalledAddon>,
    onDismiss: () -> Unit,
    onToggleMonochrome: (Boolean) -> Unit,
    onToggleApple: (Boolean) -> Unit,
    onToggleTidal: (Boolean) -> Unit,
    onToggleQobuz: (Boolean) -> Unit,
    onToggleDeezer: (Boolean) -> Unit,
    onToggleSaavn: (Boolean) -> Unit,
    onToggleAddon: (String, Boolean) -> Unit,
    onSaveOrder: (List<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val view = LocalView.current
    val haptics = LocalAppHapticsConfig.current
    val isPoolConfigured = state.poolApiKey.isNotBlank() || state.poolBuildTimeKeyPresent || poolCounts.total > 0
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    val initialItems = remember(
        installedAddons,
        state.monochromeEnabled,
        state.appleEnabled,
        state.tidalEnabled,
        state.qobuzEnabled,
        state.deezerEnabled,
        state.saavnEnabled,
        isPoolConfigured,
        state.tidalSignedIn,
        state.qobuzTokens,
        state.deezerSignedIn,
        state.unifiedOrder
    ) {
        val candidates = mutableListOf<OrderItemData>()

        // 1. Newly added modules / Addons first
        installedAddons.forEach { addon ->
            candidates.add(
                OrderItemData(
                    id = UnifiedSourceId.forAddon(addon.id),
                    title = addon.name,
                    subtitle = "${addon.protocol.displayLabel} · v${addon.version}",
                    icon = Icons.Outlined.Extension,
                    iconBg = primaryContainer,
                    iconColor = onPrimaryContainer,
                    isEnabled = addon.enabled,
                    onToggle = { onToggleAddon(addon.id, it) },
                    monogram = addon.name.firstOrNull()?.uppercaseChar()?.toString() ?: "M"
                )
            )
        }

        // 2. Monochrome
        candidates.add(
            OrderItemData(
                id = UnifiedSourceId.MONOCHROME,
                title = "Monochrome",
                subtitle = "Hi-Res FLAC up to 24-bit / 192 kHz",
                icon = Icons.Rounded.GraphicEq,
                iconBg = Color(0xFF1E1E1E),
                iconColor = Color.White,
                isEnabled = state.monochromeEnabled,
                onToggle = onToggleMonochrome
            )
        )

        // 3. Apple Music
        candidates.add(
            OrderItemData(
                id = UnifiedSourceId.APPLE,
                title = "Apple Music",
                subtitle = "ALAC Lossless / AAC stream",
                icon = Icons.Rounded.MusicNote,
                iconBg = Color(0xFFFC3C44),
                iconColor = Color.White,
                isEnabled = state.appleEnabled,
                onToggle = onToggleApple
            )
        )

        // 4. ArchiveTune Pool sources: ONLY display if pool configured OR personal account signed in
        if (isPoolConfigured || state.tidalSignedIn) {
            candidates.add(
                OrderItemData(
                    id = UnifiedSourceId.TIDAL,
                    title = "Tidal",
                    subtitle = if (state.tidalSignedIn) "Signed In · FLAC / Hi-Res" else "Pooled Account · FLAC",
                    icon = Icons.Rounded.GraphicEq,
                    iconBg = Color(0xFF00E5FF),
                    iconColor = Color.Black,
                    isEnabled = state.tidalEnabled,
                    onToggle = onToggleTidal
                )
            )
        }

        if (isPoolConfigured || state.qobuzTokens.isNotEmpty()) {
            candidates.add(
                OrderItemData(
                    id = UnifiedSourceId.QOBUZ,
                    title = "Qobuz",
                    subtitle = if (state.qobuzTokens.isNotEmpty()) "Tokens ready · Studio Master" else "Pooled Account · Hi-Res",
                    icon = Icons.Rounded.GraphicEq,
                    iconBg = Color(0xFF2B5CE6),
                    iconColor = Color.White,
                    isEnabled = state.qobuzEnabled,
                    onToggle = onToggleQobuz
                )
            )
        }

        if (isPoolConfigured || state.deezerSignedIn) {
            candidates.add(
                OrderItemData(
                    id = UnifiedSourceId.DEEZER,
                    title = "Deezer",
                    subtitle = if (state.deezerSignedIn) "ARL ready · CD FLAC" else "Pooled Account · FLAC",
                    icon = Icons.Rounded.GraphicEq,
                    iconBg = Color(0xFFA238FF),
                    iconColor = Color.White,
                    isEnabled = state.deezerEnabled,
                    onToggle = onToggleDeezer
                )
            )
        }

        // 5. JioSaavn
        candidates.add(
            OrderItemData(
                id = UnifiedSourceId.JIOSAAVN,
                title = "JioSaavn",
                subtitle = "AAC up to 320 kbps · Indian Catalog",
                icon = Icons.Rounded.GraphicEq,
                iconBg = Color(0xFF2BC5B4),
                iconColor = Color.White,
                isEnabled = state.saavnEnabled,
                onToggle = onToggleSaavn
            )
        )

        // 6. YouTube Music (Terminal fallback)
        candidates.add(
            OrderItemData(
                id = UnifiedSourceId.YOUTUBE,
                title = "YouTube Music",
                subtitle = "Opus / AAC terminal fallback",
                icon = Icons.Rounded.PlayArrow,
                iconBg = Color(0xFFFF0000),
                iconColor = Color.White,
                isEnabled = true,
                onToggle = { }
            )
        )

        if (state.unifiedOrder.isNotEmpty()) {
            val orderMap = state.unifiedOrder.withIndex().associate { it.value to it.index }
            candidates.sortedBy { orderMap[it.id] ?: (100 + candidates.indexOf(it)) }
        } else {
            candidates
        }
    }

    var orderList by remember(initialItems) { mutableStateOf(initialItems) }
    val listState = rememberLazyListState()

    val reorderableState = rememberReorderableLazyListState(
        onMove = { from, to ->
            orderList = orderList.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            performAppCompatHapticFeedback(view, haptics, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            onSaveOrder(orderList.map { it.id })
        },
        lazyListState = listState
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Playback Provider Order",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Drag a handle to change priority. Songs play from the first available active source.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                itemsIndexed(orderList, key = { _, item -> item.id }) { index, item ->
                    ReorderableItem(reorderableState, key = item.id) { isDragging ->
                        val elevation = if (isDragging) 8.dp else 0.dp
                        val bgColor = if (isDragging) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = bgColor,
                            shadowElevation = elevation
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = item.iconBg,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (item.monogram != null) {
                                            Text(
                                                text = item.monogram,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontFamily = GoogleSansRounded,
                                                fontWeight = FontWeight.Bold,
                                                color = item.iconColor
                                            )
                                        } else {
                                            Icon(
                                                imageVector = item.icon,
                                                contentDescription = null,
                                                tint = item.iconColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontFamily = GoogleSansRounded,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = GoogleSansRounded,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

                                if (item.id != UnifiedSourceId.YOUTUBE) {
                                    Switch(
                                        checked = item.isEnabled,
                                        onCheckedChange = item.onToggle,
                                        modifier = Modifier.scale(0.85f),
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .draggableHandle(
                                            onDragStarted = {
                                                performAppCompatHapticFeedback(view, haptics, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 3. ARCHIVETUNE POOL MODULE SCREEN (Houses Tidal, Qobuz, Deezer when API key entered)
// ---------------------------------------------------------------------------------------------

@Composable
private fun ArchiveTunePoolScreen(
    state: LosslessSourcesUiState,
    poolCounts: PoolCounts,
    poolReport: PoolAccountManager.PoolStatusReport?,
    poolCheck: SourceCheckResult?,
    busy: Set<String>,
    checkResults: Map<AudioSourceType, SourceCheckResult>,
    onTogglePoolEnabled: (Boolean) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onOpenPoolUrlDialog: () -> Unit,
    onCheckFeed: () -> Unit,
    onFetchHealth: () -> Unit,
    onToggleTidal: (Boolean) -> Unit,
    onSelectTidalQuality: (TidalAudioQuality) -> Unit,
    onToggleTidalAccountFirst: (Boolean) -> Unit,
    onSignInTidal: () -> Unit,
    onSignOutTidal: () -> Unit,
    onOpenTidalInstances: () -> Unit,
    onCheckTidal: () -> Unit,
    onToggleQobuz: (Boolean) -> Unit,
    onSelectQobuzQuality: (QobuzAudioQuality) -> Unit,
    onSignInQobuz: () -> Unit,
    onOpenQobuzTokens: () -> Unit,
    onOpenQobuzInstances: () -> Unit,
    onCheckQobuz: () -> Unit,
    onToggleDeezer: (Boolean) -> Unit,
    onSelectDeezerQuality: (DeezerAudioQuality) -> Unit,
    onSignInDeezer: () -> Unit,
    onCheckDeezer: () -> Unit
) {
    val isKeyEntered = state.poolApiKey.isNotBlank() || state.poolBuildTimeKeyPresent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Master Toggle for ArchiveTune Pool
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use ArchiveTune Pool",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (state.poolEnabled) "Lossless playback routed through pooled instances" else "Pool instances disabled",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = state.poolEnabled,
                    onCheckedChange = onTogglePoolEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // Header Summary Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ArchiveTune Pool",
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${poolCounts.total} pooled accounts (Tidal: ${poolCounts.tidal}, Qobuz: ${poolCounts.qobuz}, Deezer: ${poolCounts.deezer}, Apple: ${poolCounts.apple})",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceBadge(label = "Multi-Source")
                    SourceBadge(label = "Tidal · Qobuz · Deezer")
                }
            }
        }

        // Pool API Key (Using authentic AiApiKeyItem with rounded container)
        AiApiKeyItem(
            apiKey = state.poolApiKey,
            onApiKeySave = onSaveApiKey,
            title = "Pool API key",
            subtitle = buildString {
                append("Read key from the pool dashboard (atp_…), sent as Authorization: Bearer. ")
                append(
                    if (state.poolBuildTimeKeyPresent) {
                        "A build-time key is baked in; a key entered here overrides it."
                    } else {
                        "No build-time key in this build — paste yours here (or set SOURCE_PROVIDER_KEY in local.properties)."
                    }
                )
            },
            shape = RoundedCornerShape(20.dp)
        )

        // Pool URL Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onOpenPoolUrlDialog() },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (state.poolBaseUrl.isNotBlank()) state.poolBaseUrl else "Default Pool URL active (Tap to override)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Source Pool Health & Feed Card with responsive buttons
        PoolHealthAndFeedCard(
            poolCounts = poolCounts,
            poolReport = poolReport,
            poolCheck = poolCheck,
            busy = busy,
            onCheckFeed = onCheckFeed,
            onFetchHealth = onFetchHealth,
            onViewDetails = null
        )

        // Conditional display of pooled sources
        if (isKeyEntered) {
            Text(
                text = "POOLED SOURCES",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
            )

            // Pooled Tidal Card
            PooledTidalCard(
                state = state,
                poolCount = poolCounts.tidal,
                isBusy = AudioSourceType.TIDAL.name in busy,
                checkResult = checkResults[AudioSourceType.TIDAL],
                onToggleEnabled = onToggleTidal,
                onSelectQuality = onSelectTidalQuality,
                onToggleAccountFirst = onToggleTidalAccountFirst,
                onSignIn = onSignInTidal,
                onSignOut = onSignOutTidal,
                onOpenInstances = onOpenTidalInstances,
                onCheck = onCheckTidal
            )

            // Pooled Qobuz Card
            PooledQobuzCard(
                state = state,
                poolCount = poolCounts.qobuz,
                isBusy = AudioSourceType.QOBUZ.name in busy,
                checkResult = checkResults[AudioSourceType.QOBUZ],
                onToggleEnabled = onToggleQobuz,
                onSelectQuality = onSelectQobuzQuality,
                onSignIn = onSignInQobuz,
                onOpenTokens = onOpenQobuzTokens,
                onOpenInstances = onOpenQobuzInstances,
                onCheck = onCheckQobuz
            )

            // Pooled Deezer Card
            PooledDeezerCard(
                state = state,
                poolCount = poolCounts.deezer,
                isBusy = AudioSourceType.DEEZER.name in busy,
                checkResult = checkResults[AudioSourceType.DEEZER],
                onToggleEnabled = onToggleDeezer,
                onSelectQuality = onSelectDeezerQuality,
                onSignIn = onSignInDeezer,
                onCheck = onCheckDeezer
            )

            // Pooled Apple Music Summary Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFC3C44),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Apple Music Pool",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${poolCounts.apple} active pooled account(s). Provides fallback streams for Apple Music.",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // Explanatory card when API key is not entered
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Enter API Key to Unlock Sources",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Enter your ArchiveTune Pool API key above to unlock Tidal, Qobuz, Deezer, and pooled Apple Music instances. Once unlocked, all source accounts and their quality controls will become fully configurable here.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PoolHealthAndFeedCard(
    poolCounts: PoolCounts,
    poolReport: PoolAccountManager.PoolStatusReport?,
    poolCheck: SourceCheckResult?,
    busy: Set<String>,
    onCheckFeed: () -> Unit,
    onFetchHealth: () -> Unit,
    onViewDetails: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Source Pool Health",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Live status of pooled accounts",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Accounts breakdown
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val services = listOf(
                    "tidal" to "Tidal",
                    "qobuz" to "Qobuz",
                    "deezer" to "Deezer",
                    "apple" to "Apple Music"
                )
                services.forEach { (key, name) ->
                    val cachedCount = when (key) {
                        "tidal" -> poolCounts.tidal
                        "qobuz" -> poolCounts.qobuz
                        "deezer" -> poolCounts.deezer
                        "apple" -> poolCounts.apple
                        else -> 0
                    }
                    val cat = poolReport?.categories?.firstOrNull { it.service.equals(key, ignoreCase = true) }
                    val alive = cat?.alive ?: 0
                    val prem = cat?.premium ?: 0
                    val dead = cat?.dead ?: 0

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val dotColor = if (alive > 0 || cachedCount > 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(96.dp)
                        )
                        Text(
                            text = "$cachedCount cached",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (poolReport != null) {
                            Spacer(Modifier.width(6.dp))
                            val parts = buildList {
                                if (alive > 0) add("$alive alive" + if (prem > 0) " ($prem prem)" else "")
                                if (dead > 0) add("$dead dead")
                                if (alive == 0 && dead == 0) add("offline")
                            }
                            Text(
                                text = "· " + parts.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (alive > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (poolCheck != null) {
                Text(
                    text = "${poolCheck.status.name}: ${poolCheck.summary.lineSequence().firstOrNull() ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // Balanced Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val feedSpinTransition = rememberInfiniteTransition(label = "pool_feed_spin")
                val feedSpinAngle by feedSpinTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "pool_feed_spin_angle"
                )
                val isFeedBusy = "pool" in busy
                val isHealthBusy = "pool_status" in busy

                FilledTonalButton(
                    onClick = onCheckFeed,
                    enabled = !isFeedBusy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (isFeedBusy) Icons.Outlined.Refresh else Icons.Outlined.Cloud,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .then(if (isFeedBusy) Modifier.graphicsLayer { rotationZ = feedSpinAngle } else Modifier)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isFeedBusy) "Checking…" else "Check feed", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                FilledTonalButton(
                    onClick = onFetchHealth,
                    enabled = !isHealthBusy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .then(if (isHealthBusy) Modifier.graphicsLayer { rotationZ = feedSpinAngle } else Modifier)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isHealthBusy) "Refreshing…" else "Refresh health", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (onViewDetails != null) {
                    OutlinedButton(
                        onClick = onViewDetails,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Details", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 4. ARCHIVETUNE POOLED SOURCE CARDS (Tidal, Qobuz, Deezer)
// ---------------------------------------------------------------------------------------------

@Composable
private fun PooledTidalCard(
    state: LosslessSourcesUiState,
    poolCount: Int,
    isBusy: Boolean = false,
    checkResult: SourceCheckResult? = null,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectQuality: (TidalAudioQuality) -> Unit,
    onToggleAccountFirst: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onOpenInstances: () -> Unit,
    onCheck: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF00E5FF),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = Color.Black, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tidal",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$poolCount pooled account(s) active",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.tidalEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text(
                text = "STREAM QUALITY",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            QualityOptionsSelector(
                options = listOf(
                    QualityOptionItem(TidalAudioQuality.HI_RES_LOSSLESS, "Hi-Res Lossless (up to 24-bit / 192 kHz)", "Highest available studio master stream"),
                    QualityOptionItem(TidalAudioQuality.FLAC, "Lossless FLAC (16-bit / 44.1 kHz)", "Bit-perfect CD quality audio"),
                    QualityOptionItem(TidalAudioQuality.AAC_320, "High AAC (320 kbps)", "High quality AAC stream")
                ),
                selectedKey = state.tidalQuality,
                onSelect = onSelectQuality
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Try account before public instances",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Use your personal signed-in Tidal account first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.tidalAccountFirst,
                    onCheckedChange = onToggleAccountFirst,
                    modifier = Modifier.scale(0.85f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.tidalSignedIn) {
                    OutlinedButton(onClick = onSignOut, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Text("Sign out", fontFamily = GoogleSansRounded, maxLines = 1)
                    }
                } else {
                    FilledTonalButton(onClick = onSignIn, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.Login, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sign in", fontFamily = GoogleSansRounded, maxLines = 1)
                    }
                }

                OutlinedButton(onClick = onOpenInstances, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Text("Instances", fontFamily = GoogleSansRounded, maxLines = 1)
                }

                val tidalSpinTransition = rememberInfiniteTransition(label = "tidal_card_spin")
                val tidalSpinAngle by tidalSpinTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "tidal_card_spin_angle"
                )

                FilledTonalButton(
                    onClick = onCheck,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .then(if (isBusy) Modifier.graphicsLayer { rotationZ = tidalSpinAngle } else Modifier)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isBusy) "Checking…" else "Check", fontFamily = GoogleSansRounded, maxLines = 1)
                }
            }

            if (checkResult != null) {
                val isOk = checkResult.healthy
                val bg = if (isOk) Color(0xFF1B5E20).copy(alpha = 0.14f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                val fg = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = bg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isOk) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = fg,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = checkResult.summary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Medium,
                            color = fg
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PooledQobuzCard(
    state: LosslessSourcesUiState,
    poolCount: Int,
    isBusy: Boolean = false,
    checkResult: SourceCheckResult? = null,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectQuality: (QobuzAudioQuality) -> Unit,
    onSignIn: () -> Unit,
    onOpenTokens: () -> Unit,
    onOpenInstances: () -> Unit,
    onCheck: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF2B5CE6),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Qobuz",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$poolCount pooled account(s) active",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.qobuzEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text(
                text = "STREAM QUALITY",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            QualityOptionsSelector(
                options = listOf(
                    QualityOptionItem(QobuzAudioQuality.MAX, "Max (24-bit / 192 kHz)", "Highest resolution studio master stream"),
                    QualityOptionItem(QobuzAudioQuality.HI_RES, "Hi-Res (24-bit / 96 kHz)", "High fidelity studio quality FLAC"),
                    QualityOptionItem(QobuzAudioQuality.FLAC, "Lossless FLAC", "Bit-perfect CD quality (16-bit / 44.1 kHz)")
                ),
                selectedKey = state.qobuzQuality,
                onSelect = onSelectQuality
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = onSignIn, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Login, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sign in", fontFamily = GoogleSansRounded, maxLines = 1)
                }

                OutlinedButton(onClick = onOpenTokens, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Text("Manual", fontFamily = GoogleSansRounded, maxLines = 1)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onOpenInstances, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Text("API Instances", fontFamily = GoogleSansRounded, maxLines = 1)
                }

                val qobuzSpinTransition = rememberInfiniteTransition(label = "qobuz_card_spin")
                val qobuzSpinAngle by qobuzSpinTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "qobuz_card_spin_angle"
                )

                FilledTonalButton(
                    onClick = onCheck,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .then(if (isBusy) Modifier.graphicsLayer { rotationZ = qobuzSpinAngle } else Modifier)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isBusy) "Checking…" else "Check", fontFamily = GoogleSansRounded, maxLines = 1)
                }
            }

            if (checkResult != null) {
                val isOk = checkResult.healthy
                val bg = if (isOk) Color(0xFF1B5E20).copy(alpha = 0.14f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                val fg = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = bg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isOk) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = fg,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = checkResult.summary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Medium,
                            color = fg
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PooledDeezerCard(
    state: LosslessSourcesUiState,
    poolCount: Int,
    isBusy: Boolean = false,
    checkResult: SourceCheckResult? = null,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectQuality: (DeezerAudioQuality) -> Unit,
    onSignIn: () -> Unit,
    onCheck: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFA238FF),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Deezer",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$poolCount pooled account(s) active",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.deezerEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text(
                text = "STREAM QUALITY",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            QualityOptionsSelector(
                options = listOf(
                    QualityOptionItem(DeezerAudioQuality.FLAC, "Lossless FLAC", "16-bit / 44.1 kHz FLAC streaming"),
                    QualityOptionItem(DeezerAudioQuality.MP3_320, "High (320 kbps)", "320 kbps MP3 high quality stream"),
                    QualityOptionItem(DeezerAudioQuality.MP3_128, "Standard (128 kbps)", "128 kbps standard stream")
                ),
                selectedKey = state.deezerQuality,
                onSelect = onSelectQuality
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = onSignIn, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.Key, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.deezerSignedIn) "Update ARL" else "Sign in / ARL", fontFamily = GoogleSansRounded, maxLines = 1)
                }

                val deezerSpinTransition = rememberInfiniteTransition(label = "deezer_card_spin")
                val deezerSpinAngle by deezerSpinTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "deezer_card_spin_angle"
                )

                FilledTonalButton(
                    onClick = onCheck,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .then(if (isBusy) Modifier.graphicsLayer { rotationZ = deezerSpinAngle } else Modifier)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isBusy) "Checking…" else "Check", fontFamily = GoogleSansRounded, maxLines = 1)
                }
            }

            if (checkResult != null) {
                val isOk = checkResult.healthy
                val bg = if (isOk) Color(0xFF1B5E20).copy(alpha = 0.14f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                val fg = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = bg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isOk) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = fg,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = checkResult.summary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Medium,
                            color = fg
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 5. APPLE MUSIC MODULE SCREEN
// ---------------------------------------------------------------------------------------------

@Composable
private fun AppleMusicModuleScreen(
    state: LosslessSourcesUiState,
    poolCounts: PoolCounts,
    isBusy: Boolean,
    checkResult: SourceCheckResult?,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectQuality: (AppleMusicQuality) -> Unit,
    onCheckServer: () -> Unit,
    onSignInWeb: () -> Unit,
    onSignOut: () -> Unit,
    onOpenTokensDialog: () -> Unit,
    onOpenPoolSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header Card (Matching Image 1)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFC3C44),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Apple Music",
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (state.appleMediaUserToken.isNotBlank()) "Connected" else "Not signed in",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "Plays your Apple Music subscription in PixelMusic, in ALAC Lossless / AAC 256, with every Audio Lab effect.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    
                    Text(
                        text = "Built in · v1.0",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }

                // Full-width Prominent Sign In button (Image 1 style)
                if (state.appleMediaUserToken.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onOpenTokensDialog,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Outlined.Key, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Manage tokens", fontFamily = GoogleSansRounded)
                        }

                        OutlinedButton(
                            onClick = onSignOut,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Sign out", fontFamily = GoogleSansRounded)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSignInWeb,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(
                                "Sign in to Apple Music",
                                fontFamily = GoogleSansRounded,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        TextButton(
                            onClick = onOpenTokensDialog,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Paste custom developer / media-user token", fontFamily = GoogleSansRounded)
                        }
                    }
                }
            }
        }

        // Toggle Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use Apple Music",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (state.appleMediaUserToken.isNotBlank()) "Active in provider fallback order" else "Sign in first, then turn it on here.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = state.appleEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // Quality Options Selector
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "STREAM QUALITY",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                QualityOptionsSelector(
                    options = listOf(
                        QualityOptionItem(
                            key = AppleMusicQuality.LOSSLESS,
                            title = "ALAC Lossless",
                            subtitle = "Apple Lossless Audio Codec (16-bit / 44.1 kHz)"
                        ),
                        QualityOptionItem(
                            key = AppleMusicQuality.AAC,
                            title = "AAC 256 kbps",
                            subtitle = "Apple Music standard stereo stream"
                        )
                    ),
                    selectedKey = state.appleQuality,
                    onSelect = onSelectQuality
                )
            }
        }

        // ArchiveTune Pool Status for Apple Music
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ArchiveTune Apple Music Pool",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${poolCounts.apple} active pooled account(s). Provides fallback streams for Apple Music.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onOpenPoolSettings) {
                    Text("Pool", fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Test Playback Card (Image 2 style)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "TEST PLAYBACK",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Runs every step on this phone with 'Koka Gaani': finding the song, opening the stream, getting a license from this phone's Widevine, and decoding the start of it, muted.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val appleSpinTransition = rememberInfiniteTransition(label = "apple_card_spin")
                val appleSpinAngle by appleSpinTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "apple_card_spin_angle"
                )

                FilledTonalButton(
                    onClick = onCheckServer,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = if (isBusy) Icons.Outlined.Refresh else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .then(if (isBusy) Modifier.graphicsLayer { rotationZ = appleSpinAngle } else Modifier)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isBusy) "Testing…" else "Run the test", fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold)
                }

                if (checkResult != null) {
                    val isOk = checkResult.healthy
                    val bg = if (isOk) Color(0xFF1B5E20).copy(alpha = 0.14f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    val fg = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = bg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isOk) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = fg,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = checkResult.summary,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = GoogleSansRounded,
                                fontWeight = FontWeight.Medium,
                                color = fg
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 6. MONOCHROME MODULE SCREEN (With Custom Servers adding!)
// ---------------------------------------------------------------------------------------------

@Composable
private fun MonochromeModuleScreen(
    state: LosslessSourcesUiState,
    isBusy: Boolean,
    checkResult: SourceCheckResult?,
    onToggleEnabled: (Boolean) -> Unit,
    onCheckServer: () -> Unit,
    onOpenServersDialog: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1E1E1E),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Monochrome",
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (state.monochromeEnabled) "Ready · FLAC up to 24-bit / 192 kHz" else "Off · No account needed",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "Free, bit-perfect Hi-Res FLAC and CD-quality FLAC direct from Monochrome instances, no account needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    
                    Text(
                        text = "Built in · v2.1",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }

                val monoSpinTransition = rememberInfiniteTransition(label = "mono_card_spin")
                val monoSpinAngle by monoSpinTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "mono_card_spin_angle"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onCheckServer,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .then(if (isBusy) Modifier.graphicsLayer { rotationZ = monoSpinAngle } else Modifier)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isBusy) "Checking…" else "Check server", fontFamily = GoogleSansRounded)
                    }

                    OutlinedButton(
                        onClick = onOpenServersDialog,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Outlined.Dns, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add servers", fontFamily = GoogleSansRounded)
                    }
                }

                if (checkResult != null) {
                    val isOk = checkResult.healthy
                    val bg = if (isOk) Color(0xFF1B5E20).copy(alpha = 0.14f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    val fg = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = bg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isOk) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = fg,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = checkResult.summary,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = GoogleSansRounded,
                                fontWeight = FontWeight.Medium,
                                color = fg
                            )
                        }
                    }
                }
            }
        }

        // Toggle Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use Monochrome",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Songs play from Monochrome when it has them, in the order set under Audio Quality.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = state.monochromeEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // Stream Quality Options (Replacing pills with clean selectable cards)
        var selectedMonoQuality by rememberSaveable { mutableStateOf("LOSSLESS") }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "STREAM QUALITY",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                QualityOptionsSelector(
                    options = listOf(
                        QualityOptionItem("LOSSLESS", "Lossless FLAC", "Bit-perfect 16-bit / 44.1 kHz CD quality"),
                        QualityOptionItem("HI_RES", "Hi-Res FLAC", "Studio Master up to 24-bit / 192 kHz"),
                        QualityOptionItem("HIGH", "High", "Standard 320 kbps compressed audio"),
                        QualityOptionItem("LOW", "Low", "Data saver 128 kbps audio")
                    ),
                    selectedKey = selectedMonoQuality,
                    onSelect = { selectedMonoQuality = it }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 7. JIOSAAVN MODULE SCREEN
// ---------------------------------------------------------------------------------------------

@Composable
private fun JioSaavnModuleScreen(
    state: LosslessSourcesUiState,
    isBusy: Boolean,
    checkResult: SourceCheckResult?,
    onToggleEnabled: (Boolean) -> Unit,
    onCheckServer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header Card (Matching Image 6)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF2BC5B4),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "J",
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = GoogleSansRounded,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "JioSaavn",
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (state.saavnEnabled) "On · No account needed" else "Off · No account needed",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "JioSaavn's catalog in AAC up to 320 kbps, no account needed. Strongest on Indian music.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    
                    Text(
                        text = "Built in · v1.0",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }

                val saavnSpinTransition = rememberInfiniteTransition(label = "saavn_card_spin")
                val saavnSpinAngle by saavnSpinTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "saavn_card_spin_angle"
                )

                FilledTonalButton(
                    onClick = onCheckServer,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .then(if (isBusy) Modifier.graphicsLayer { rotationZ = saavnSpinAngle } else Modifier)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isBusy) "Checking…" else "Check server", fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold)
                }

                if (checkResult != null) {
                    val isOk = checkResult.healthy
                    val bg = if (isOk) Color(0xFF1B5E20).copy(alpha = 0.14f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    val fg = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = bg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isOk) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = fg,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = checkResult.summary,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = GoogleSansRounded,
                                fontWeight = FontWeight.Medium,
                                color = fg
                            )
                        }
                    }
                }
            }
        }

        // Toggle Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use JioSaavn",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Songs play from JioSaavn when it has them, in the order set under Audio Quality.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = state.saavnEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // Stream Quality Selector (Vertical cards matching Monochrome & Tidal for High DPI)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "STREAM QUALITY",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                QualityOptionsSelector(
                    options = listOf(
                        QualityOptionItem("320", "AAC 320 kbps", "JioSaavn's best, highest available bitrate"),
                        QualityOptionItem("160", "AAC 160 kbps", "Standard high quality stream"),
                        QualityOptionItem("96", "AAC 96 kbps", "Data saver stream"),
                    ),
                    selectedKey = "320",
                    onSelect = { }
                )
            }
        }

        // Test Playback Card (Image 6 style)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "TEST PLAYBACK",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Runs every step on this phone with 'Espresso': finding the song, opening the stream, and decoding the start of it, muted.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = onCheckServer,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Run the test", fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// HELPER COMPONENTS
// ---------------------------------------------------------------------------------------------

@Composable
private fun ModuleCard(
    icon: ImageVector,
    iconContainerColor: Color,
    iconColor: Color,
    title: String,
    subtitle: String,
    pillTag: String? = null,
    versionTag: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = iconContainerColor,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!pillTag.isNullOrBlank()) {
                        SourceBadge(label = pillTag)
                    }
                    Text(
                        text = versionTag,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun SourceBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = GoogleSansRounded,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun CapabilityPill(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = GoogleSansRounded,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private data class QualityOptionItem<T>(
    val key: T,
    val title: String,
    val subtitle: String
)

@Composable
private fun <T> QualityOptionsSelector(
    options: List<QualityOptionItem<T>>,
    selectedKey: T,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { item ->
            val isSelected = item.key == selectedKey
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(item.key) },
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = GoogleSansRounded,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// DIALOGS
// ---------------------------------------------------------------------------------------------

@Composable
private fun MonochromeServersDialog(
    currentCustomInstances: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(currentCustomInstances) { mutableStateOf(currentCustomInstances) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monochrome Servers", fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Default instances include api.monochrome.tf, tracks.monochrome.st, and official mirrors. Add your custom mirrors below (one URL per line):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Custom Mirror URLs") },
                    placeholder = { Text("https://my-mirror.example.com") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    shape = RoundedCornerShape(14.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun PoolUrlSimpleDialog(
    currentUrl: String,
    effectiveUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Source Pool Base URL", fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Effective fallback URL: $effectiveUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Custom Pool URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun TidalInstancesSimpleDialog(
    instances: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(instances) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tidal Public Instances", fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter instance URLs, one per line:", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("URLs") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun QobuzInstancesSimpleDialog(
    instances: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(instances) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Qobuz API Instances", fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter instance URLs, one per line:", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("URLs") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun QobuzTokensSimpleDialog(
    tokens: List<QobuzToken>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (QobuzToken) -> Unit
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Qobuz Tokens", fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tokens.forEach { token ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(token.label.ifBlank { "Token" }, fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold)
                            Text("${token.token.take(8)}... · app ${token.appId}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onRemove(token) }) { Text("Remove") }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("token,app_id,app_secret[,label]") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (input.isNotBlank()) {
                    onAdd(input.trim())
                    input = ""
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun AppleTokensSimpleDialog(
    mediaToken: String,
    devToken: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var media by remember { mutableStateOf(mediaToken) }
    var dev by remember { mutableStateOf(devToken) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apple Music Tokens", fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste your media-user-token and developer JWT (or leave dev blank to scrape from beta.music.apple.com):",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = media,
                    onValueChange = { media = it },
                    label = { Text("Media User Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dev,
                    onValueChange = { dev = it },
                    label = { Text("Developer Token (JWT)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(media, dev) },
                enabled = !busy
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                else Text("Verify & Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(24.dp)
    )
}

private fun SourceCheckStatus.label(): String =
    when (this) {
        SourceCheckStatus.READY -> "Ready"
        SourceCheckStatus.DEGRADED -> "Degraded"
        SourceCheckStatus.NOT_CONFIGURED -> "Not configured"
        SourceCheckStatus.UNSUPPORTED -> "Unsupported"
        SourceCheckStatus.UNREACHABLE -> "Unreachable"
    }
