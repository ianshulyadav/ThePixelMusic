package com.unshoo.pixelmusic.presentation.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.model.PlaybackQueueHistoryEntry

@Composable
internal fun QueueHistorySection(
    entries: List<PlaybackQueueHistoryEntry>,
    onReplay: (PlaybackQueueHistoryEntry) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (entries.isEmpty()) return
    var showMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.home_queue_history_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = pluralStringResource(R.plurals.home_queue_history_count, entries.size, entries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                FilledTonalIconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.home_queue_history_options))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_queue_history_clear)) },
                        onClick = {
                            showMenu = false
                            confirmClear = true
                        },
                    )
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardWidth = (maxWidth - 32.dp).coerceIn(1.dp, 280.dp)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = { it.id }, contentType = { "queue_history" }) { entry ->
                    QueueHistoryCard(entry, cardWidth, { onReplay(entry) }, { onRemove(entry.id) })
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.home_queue_history_clear)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) {
                    Text(stringResource(R.string.home_queue_history_clear_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun QueueHistoryCard(
    entry: PlaybackQueueHistoryEntry,
    width: Dp,
    onReplay: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val firstTrack = entry.firstTrack
    val relativeTime = DateUtils.getRelativeTimeSpanString(
        entry.savedAtEpochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()

    Card(
        modifier = Modifier.width(width),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 72.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val artwork = firstTrack.artworkUri?.takeIf(String::isNotBlank)
            val coverShape = RoundedCornerShape(12.dp)
            if (artwork != null) {
                SmartImage(
                    model = artwork,
                    contentDescription = null,
                    shape = coverShape,
                    targetSize = SmartImageIntermediateTargetSize,
                    modifier = Modifier.size(48.dp),
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp).clip(coverShape).background(colors.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = colors.onSecondaryContainer)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = firstTrack.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.unknown_song_title),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.home_queue_history_track_count, entry.items.size, entry.items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = relativeTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalIconButton(onClick = onReplay) {
                Icon(Icons.Rounded.PlayArrow, stringResource(R.string.home_queue_history_replay))
            }
            FilledTonalIconButton(onClick = onRemove) {
                Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.home_queue_history_remove))
            }
        }
    }
}
