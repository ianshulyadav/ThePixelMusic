@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.unshoo.pixelmusic.presentation.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unshoo.pixelmusic.data.addons.AddonManager
import com.unshoo.pixelmusic.data.addons.UnifiedSourceId
import com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver
import com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver.SourceSettings
import com.unshoo.pixelmusic.data.lossless.constants.AudioSourceType
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SwitcherOptionUi(
    val sourceId: String?,
    val title: String,
    val subtitle: String,
    val isAddon: Boolean,
    val badgeNumber: String,
    val isActiveNow: Boolean,
)

@Composable
fun PlayFromSourceSheet(
    song: Song,
    activeSourceName: String? = null,
    colorScheme: ColorScheme? = null,
    onDismiss: () -> Unit,
    onSelectSource: (unifiedSourceId: String?) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val installedAddons by AddonManager.installedAddons.collectAsStateWithLifecycle()

    val videoId = remember(song) {
        song.youtubeId?.takeIf { it.isNotBlank() }
            ?: song.contentUriString.substringAfter("youtube://").takeIf { it.isNotBlank() && !it.startsWith("http") }
            ?: song.id.removePrefix("youtube_")
    }

    var unifiedOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var sourceSettings by remember { mutableStateOf<SourceSettings?>(null) }
    var pinnedSourceId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(videoId, installedAddons) {
        withContext(Dispatchers.IO) {
            AddonManager.ensureLoadedBlocking(context)
            unifiedOrder = AddonManager.readUnifiedOrder(context)
            sourceSettings = LosslessStreamResolver.readSettings(context)
            pinnedSourceId = if (videoId.isNotBlank()) {
                LosslessStreamResolver.getUnifiedSongSourceOverride(context, videoId)
            } else {
                null
            }
        }
    }

    val activeLossless = remember(videoId, activeSourceName) {
        if (videoId.isNotBlank()) LosslessStreamResolver.resultFor(videoId) else null
    }

    val options = remember(unifiedOrder, installedAddons, activeLossless, activeSourceName, sourceSettings) {
        val s = sourceSettings
        val addonMap = installedAddons.associateBy { it.id }
        val orderIds = if (unifiedOrder.isNotEmpty()) {
            unifiedOrder
        } else {
            UnifiedSourceId.buildUnifiedOrder(null, null, installedAddons)
        }

        buildList {
            // 1. Automatic Fallback Chain (Always at top, numbered 'A')
            add(
                SwitcherOptionUi(
                    sourceId = null,
                    title = "Automatic (Unified Chain)",
                    subtitle = "Follows your global Addon & Lossless fallback priority",
                    isAddon = false,
                    badgeNumber = "A",
                    isActiveNow = false,
                )
            )

            // 2. Only show actively enabled addons and built-in modules in priority order
            var priorityCounter = 1
            orderIds.forEach { id ->
                if (UnifiedSourceId.isAddon(id)) {
                    val addonId = UnifiedSourceId.addonIdOrNull(id) ?: return@forEach
                    val addon = addonMap[addonId] ?: return@forEach
                    val masterEnabled = s?.addonsMasterEnabled ?: true
                    if (!masterEnabled || !addon.enabled) return@forEach

                    val active = activeLossless?.sourceId == id ||
                        activeSourceName?.equals(addon.name, ignoreCase = true) == true
                    add(
                        SwitcherOptionUi(
                            sourceId = id,
                            title = addon.name,
                            subtitle = buildString {
                                append(addon.protocol.displayLabel)
                                if (active && activeLossless != null) {
                                    append(" · ${activeLossless.label.removePrefix("${addon.name} · ")}")
                                }
                            },
                            isAddon = true,
                            badgeNumber = "${priorityCounter++}",
                            isActiveNow = active,
                        )
                    )
                } else {
                    val builtIn = UnifiedSourceId.builtInTypeOrNull(id) ?: return@forEach

                    // Only show if activated / on, with YouTube always available as fallback
                    val isEnabled = when (builtIn) {
                        AudioSourceType.YOUTUBE -> true
                        AudioSourceType.MONOCHROME -> (s?.losslessMasterEnabled ?: true) && (s?.monochromeEnabled ?: true)
                        AudioSourceType.APPLE -> (s?.losslessMasterEnabled ?: true) && (s?.appleEnabled ?: true)
                        AudioSourceType.TIDAL -> (s?.losslessMasterEnabled ?: true) && (s?.tidalEnabled ?: false)
                        AudioSourceType.QOBUZ -> (s?.losslessMasterEnabled ?: true) && (s?.qobuzEnabled ?: false)
                        AudioSourceType.DEEZER -> (s?.losslessMasterEnabled ?: true) && (s?.deezerEnabled ?: false)
                        AudioSourceType.JIOSAAVN -> s?.saavnEnabled ?: false
                        else -> false
                    }
                    if (!isEnabled) return@forEach

                    val title = UnifiedSourceId.builtInTitle(builtIn)
                    val active = activeLossless?.sourceId == id ||
                        activeSourceName?.equals(title, ignoreCase = true) == true ||
                        activeSourceName?.equals(builtIn.name, ignoreCase = true) == true
                    add(
                        SwitcherOptionUi(
                            sourceId = id,
                            title = title,
                            subtitle = if (active && activeLossless != null) {
                                activeLossless.label
                            } else {
                                UnifiedSourceId.builtInSubtitle(builtIn)
                            },
                            isAddon = false,
                            badgeNumber = "${priorityCounter++}",
                            isActiveNow = active,
                        )
                    )
                }
            }
        }
    }

    val effectiveScheme = colorScheme ?: MaterialTheme.colorScheme

    MaterialTheme(
        colorScheme = effectiveScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = effectiveScheme.surfaceContainerLow,
            dragHandle = {
                Surface(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = effectiveScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    shape = CircleShape
                ) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp))
                }
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Play from…",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = effectiveScheme.onSurface
                    )
                    Text(
                        text = "${song.title} — ${song.displayArtist}",
                        style = MaterialTheme.typography.bodySmall,
                        color = effectiveScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(options, key = { it.sourceId ?: "auto_default" }) { opt ->
                        val isSelected = pinnedSourceId == opt.sourceId
                        // Extra round capsule pills matching earlier preferred design
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = when {
                                isSelected -> effectiveScheme.primaryContainer
                                opt.isActiveNow -> effectiveScheme.secondaryContainer.copy(alpha = 0.65f)
                                else -> effectiveScheme.surfaceContainerHigh
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(999.dp))
                                .clickable {
                                    Toast.makeText(
                                        context,
                                        if (opt.sourceId == null) {
                                            "Using automatic unified chain"
                                        } else {
                                            "Switching to ${opt.title}…"
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    onSelectSource(opt.sourceId)
                                },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Circular numbering badge (NO icons, clean numbering)
                                Surface(
                                    shape = CircleShape,
                                    color = when {
                                        isSelected -> effectiveScheme.primary
                                        opt.isActiveNow -> effectiveScheme.primary
                                        opt.sourceId == null -> effectiveScheme.tertiaryContainer
                                        opt.isAddon -> effectiveScheme.primaryContainer
                                        else -> effectiveScheme.surfaceContainerHighest
                                    },
                                    modifier = Modifier.size(38.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = opt.badgeNumber,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontFamily = GoogleSansRounded,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center,
                                            color = when {
                                                isSelected -> effectiveScheme.onPrimary
                                                opt.isActiveNow -> effectiveScheme.onPrimary
                                                opt.sourceId == null -> effectiveScheme.onTertiaryContainer
                                                opt.isAddon -> effectiveScheme.onPrimaryContainer
                                                else -> effectiveScheme.onSurface
                                            }
                                        )
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = opt.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontFamily = GoogleSansRounded,
                                            fontWeight = FontWeight.SemiBold,
                                            color = effectiveScheme.onSurface,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (opt.isActiveNow) {
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = effectiveScheme.primary,
                                            ) {
                                                Text(
                                                    text = "PLAYING",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = effectiveScheme.onPrimary,
                                                    fontFamily = GoogleSansRounded,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        } else if (opt.isAddon) {
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = effectiveScheme.tertiaryContainer,
                                            ) {
                                                Text(
                                                    text = "ADDON",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = effectiveScheme.onTertiaryContainer,
                                                    fontFamily = GoogleSansRounded,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = opt.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = effectiveScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        onSelectSource(opt.sourceId)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
