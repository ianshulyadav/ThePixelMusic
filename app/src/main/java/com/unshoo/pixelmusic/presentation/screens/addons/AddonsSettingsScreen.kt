@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.unshoo.pixelmusic.presentation.screens.addons

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.unshoo.pixelmusic.data.addons.AddonCheckReport
import com.unshoo.pixelmusic.data.addons.AddonSettingOption
import com.unshoo.pixelmusic.data.addons.AddonSettingSpec
import com.unshoo.pixelmusic.data.addons.AddonSettingType
import com.unshoo.pixelmusic.data.addons.CatalogAddonEntry
import com.unshoo.pixelmusic.data.addons.InstalledAddon
import com.unshoo.pixelmusic.presentation.viewmodel.AddonsSettingsViewModel
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddonsSettingsScreen(
    navController: NavController,
    viewModel: AddonsSettingsViewModel = hiltViewModel(),
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val busyTags by viewModel.busyTags.collectAsStateWithLifecycle()
    val toast by viewModel.toastMessage.collectAsStateWithLifecycle()
    val catalogDialog by viewModel.catalogDialog.collectAsStateWithLifecycle()
    val checkReports by viewModel.checkReports.collectAsStateWithLifecycle()

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeToast()
        }
    }

    var linkInput by rememberSaveable { mutableStateOf("") }
    var configureAddonId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeConfirmAddon by remember { mutableStateOf<InstalledAddon?>(null) }

    val fileImporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val fileName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "module.arch"
            viewModel.importFromFileUri(uri, fileName)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 1. Add Module (Directly at top so user can add right away) ───────────────────
        AddonSubsection(title = "Add Module") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "A module is a service someone else runs. PixelMusic only asks it for songs, lyrics or audio streams, and it can't touch anything else in the app.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Add from a link
                    Text(
                        text = "Add from a link",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = { linkInput = it },
                        placeholder = { Text("https://...", fontFamily = GoogleSansRounded) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )

                    val isInstalling = "install" in busyTags
                    Button(
                        onClick = {
                            if (linkInput.isNotBlank()) {
                                viewModel.installFromUrl(linkInput.trim())
                                linkInput = ""
                            }
                        },
                        enabled = linkInput.isNotBlank() && !isInstalling,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        if (isInstalling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Check the link", fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(4.dp))

                    // Add from a file
                    Text(
                        text = "Add from a file",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "A module someone sent you as a .zip or .arch. A look works straight from the file. One that plays songs or lyrics still needs its service online.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val isImporting = "import" in busyTags
                    FilledTonalButton(
                        onClick = { fileImporter.launch(arrayOf("*/*")) },
                        enabled = !isImporting,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Outlined.FileOpen, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Choose a .zip or .arch", fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(4.dp))

                    // From the catalog
                    Text(
                        text = "From the catalog",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Nothing in the catalog yet. Paste a module's link above to add it.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── 2. Installed Modules List (Matches User Reference Screenshots 5 & 6) ─────────
        AddonSubsection(title = "Installed modules (${state.installedAddons.size})") {
            if (state.installedAddons.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = "No modules installed yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Paste a module URL or choose a .zip / .arch file above.",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.installedAddons.forEach { addon ->
                        CleanInstalledAddonCard(
                            addon = addon,
                            report = checkReports[addon.id],
                            isBusy = "test_${addon.id}" in busyTags || "refresh_${addon.id}" in busyTags,
                            onToggleEnabled = { viewModel.setAddonEnabled(addon.id, it) },
                            onCheckServer = { viewModel.testAddon(addon.id) },
                            onConfigure = { configureAddonId = addon.id },
                            onRemove = { removeConfirmAddon = addon },
                        )
                    }
                }
            }
        }

        // ── 3. Engine & Speed (Moved down to the bottom and overhauled) ───────────────────
        AddonSubsection(title = "Engine & Speed") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Setting 1: Enable audio addons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Extension, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable audio addons",
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = GoogleSansRounded,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Allow installed HTTP audio addons, Spine JS modules, and .arch packages to resolve Hi-Res / Lossless streams.",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = GoogleSansRounded,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = state.addonsEnabled,
                            onCheckedChange = viewModel::setAddonsEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Setting 2: Fast hedged concurrent waves
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Refresh, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Fast hedged concurrent waves",
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = GoogleSansRounded,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Races up to 3 ordered sources per wave with a 120 ms stagger so slow or cold servers never stall playback.",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = GoogleSansRounded,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = state.fastConcurrent,
                            onCheckedChange = viewModel::setFastConcurrent,
                            enabled = state.addonsEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Setting 3: Per-source timeout (Overhauled with clean selectable options)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Timer, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(22.dp))
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Per-source timeout",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Maximum wait per addon/module before advancing to the next fallback.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = GoogleSansRounded,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Line 1: Default (6.5 s) full width
                        val isDefaultSelected = state.perAddonTimeoutMs == 6500L
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setPerAddonTimeoutMs(6500L) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isDefaultSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 10.dp)
                            ) {
                                Text(
                                    text = "Default (6.5 s)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = if (isDefaultSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isDefaultSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Line 2: All other 4 options (equal-width columns, never scatters on high DPI)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                3500L to "3.5 s",
                                5000L to "5.0 s",
                                10000L to "10 s",
                                15000L to "15 s"
                            ).forEach { (ms, label) ->
                                val isSelected = state.perAddonTimeoutMs == ms
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { viewModel.setPerAddonTimeoutMs(ms) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontFamily = GoogleSansRounded,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
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

    // Configure dialog
    configureAddonId?.let { id ->
        val addon = state.installedAddons.firstOrNull { it.id == id }
        if (addon != null) {
            AddonSettingsBottomSheet(
                addon = addon,
                onDismiss = { configureAddonId = null },
                onSaveSetting = { key, value ->
                    viewModel.updateAddonSetting(addon.id, key, value)
                },
            )
        }
    }

    // Catalog picker dialog
    catalogDialog?.let { catalog ->
        CatalogPickerDialog(
            catalog = catalog,
            onDismiss = { /* dismiss */ },
            onInstallOne = { entry -> viewModel.installCatalogItem(entry, catalog.indexUrl) },
            onInstallAll = { entries -> viewModel.installAllCatalogItems(entries, catalog.indexUrl) },
        )
    }

    // Remove Confirmation Dialog
    removeConfirmAddon?.let { addon ->
        AlertDialog(
            onDismissRequest = { removeConfirmAddon = null },
            title = {
                Text(
                    text = "Uninstall ${addon.name}?",
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = "This removes the module and its saved settings from your app.",
                    fontFamily = GoogleSansRounded,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeAddon(addon.id)
                        removeConfirmAddon = null
                    },
                ) {
                    Text(
                        text = "Uninstall",
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirmAddon = null }) {
                    Text("Cancel", fontFamily = GoogleSansRounded)
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// CLEAN INSTALLED ADDON CARD (Matches User Reference Screenshots 5 & 6)
// ---------------------------------------------------------------------------------------------

@Composable
private fun CleanInstalledAddonCard(
    addon: InstalledAddon,
    report: AddonCheckReport?,
    isBusy: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onCheckServer: () -> Unit,
    onConfigure: () -> Unit,
    onRemove: () -> Unit,
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
                // Monogram avatar
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
                            imageVector = if (report.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
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

            // Action row: Check Server, Configure, Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onCheckServer,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Check server", fontFamily = GoogleSansRounded, style = MaterialTheme.typography.labelMedium)
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

                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Uninstall",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonSubsection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = GoogleSansRounded,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        content()
    }
}

@Composable
private fun CatalogPickerDialog(
    catalog: com.unshoo.pixelmusic.data.addons.AddonDiscoverResult.Catalog,
    onDismiss: () -> Unit,
    onInstallOne: (CatalogAddonEntry) -> Unit,
    onInstallAll: (List<CatalogAddonEntry>) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = catalog.title.ifBlank { "Addon Catalog" },
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(catalog.entries, key = { it.id }) { entry ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = entry.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = GoogleSansRounded,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = { onInstallOne(entry) },
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Add", fontFamily = GoogleSansRounded)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onInstallAll(catalog.entries) },
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Install all", fontFamily = GoogleSansRounded)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", fontFamily = GoogleSansRounded)
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// OVERHAULED SETTINGS SHEET WITH DIRECT OPTION SELECTING (INSTEAD OF MANUAL TYPING)
// ---------------------------------------------------------------------------------------------

@Composable
fun AddonSettingsBottomSheet(
    addon: InstalledAddon,
    onDismiss: () -> Unit,
    onSaveSetting: (key: String, value: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(
                    text = "${addon.name} Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Select quality and stream options for this module",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(addon.settingsSchema, key = { it.key }) { spec ->
                    SettingItemEditor(
                        spec = spec,
                        initialValue = addon.settingsValues[spec.key] ?: spec.defaultValue,
                        onSave = { onSaveSetting(spec.key, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingItemEditor(
    spec: AddonSettingSpec,
    initialValue: String,
    onSave: (String) -> Unit
) {
    var currentValue by remember(spec.key, initialValue) { mutableStateOf(initialValue) }
    var showCustomInput by remember(spec.key) { mutableStateOf(false) }

    val isToggle = spec.type == AddonSettingType.TOGGLE || currentValue.lowercase() in listOf("true", "false")

    // Derive options: either from spec or from standard patterns (quality, atmos, format)
    val candidateOptions = remember(spec) {
        if (spec.options.isNotEmpty()) {
            spec.options.map { it.value to it.label }
        } else {
            val keyLower = spec.key.lowercase()
            val labelLower = spec.label.lowercase()
            when {
                keyLower.contains("quality") || labelLower.contains("quality") || keyLower.contains("stream") -> {
                    listOf(
                        "lossless" to "Lossless FLAC (16-bit / 44.1 kHz)",
                        "flac_high" to "Hi-Res FLAC (24-bit / 96 kHz)",
                        "flac_max" to "Hi-Res FLAC (24-bit / 192 kHz)",
                        "high" to "High (320 kbps MP3/AAC)",
                        "low" to "Low (128 kbps Data Saver)"
                    )
                }
                keyLower.contains("atmos") || labelLower.contains("atmos") || keyLower.contains("spatial") -> {
                    listOf(
                        "stereo" to "Stereo standard",
                        "atmos" to "Dolby Atmos / Spatial Audio"
                    )
                }
                keyLower.contains("format") || labelLower.contains("format") || keyLower.contains("codec") -> {
                    listOf(
                        "flac" to "FLAC (Bit-perfect)",
                        "alac" to "ALAC (Apple Lossless)",
                        "aac" to "AAC (Standard)",
                        "mp3" to "MP3 (High compatibility)",
                        "opus" to "Opus (Low bitrate)"
                    )
                }
                else -> emptyList()
            }
        }
    }

    if (isToggle) {
        // Toggle rendered as clean surfaceContainer card, free of outer divider containers
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = spec.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (spec.description.isNotBlank()) {
                        Text(
                            text = spec.description,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                val isChecked = currentValue.equals("true", ignoreCase = true)
                Switch(
                    checked = isChecked,
                    onCheckedChange = {
                        val newVal = it.toString()
                        currentValue = newVal
                        onSave(newVal)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    } else {
        // Option/Text setting: Section header followed by free standalone cards (no container-in-container)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    text = spec.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (spec.description.isNotBlank()) {
                    Text(
                        text = spec.description,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (candidateOptions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidateOptions.forEach { (optVal, optLabel) ->
                        val isSelected = currentValue.equals(optVal, ignoreCase = true)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    currentValue = optVal
                                    showCustomInput = false
                                    onSave(optVal)
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        currentValue = optVal
                                        showCustomInput = false
                                        onSave(optVal)
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = optLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = GoogleSansRounded,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = optVal.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = GoogleSansRounded,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Optional custom input fallback
                    val isCustom = candidateOptions.none { it.first.equals(currentValue, ignoreCase = true) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCustomInput = !showCustomInput }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (showCustomInput || isCustom) "Custom value:" else "Use custom value...",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (showCustomInput || isCustom) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = currentValue,
                                onValueChange = {
                                    currentValue = it
                                    onSave(it)
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = currentValue,
                        onValueChange = {
                            currentValue = it
                            onSave(it)
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }
    }
}
