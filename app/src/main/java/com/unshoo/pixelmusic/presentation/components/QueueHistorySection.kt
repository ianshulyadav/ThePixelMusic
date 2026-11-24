package com.unshoo.pixelmusic.presentation.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.rotate
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
            val cardWidth = (maxWidth - 32.dp).coerceIn(1.dp, 312.dp)
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
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val tile = RoundedCornerShape(14.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier.weight(1f).aspectRatio(0.9f)
                        .clip(tile).background(colors.primaryContainer).padding(12.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp).clip(CircleShape)
                            .background(colors.primary.copy(alpha = 0.22f)).padding(4.dp),
                        tint = colors.onPrimaryContainer,
                    )
                    Column(modifier = Modifier.align(Alignment.BottomStart)) {
                        Text(
                            text = entry.items.size.toString(),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            color = colors.onPrimaryContainer,
                        )
                        Text(
                            text = stringResource(R.string.home_queue_history_tracks),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onPrimaryContainer,
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f).aspectRatio(0.9f)
                        .clip(tile).background(colors.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    val covers = remember(entry) {
                        buildList {
                            firstTrack.artworkUri?.takeIf(String::isNotBlank)?.let(::add)
                            for (item in entry.items) {
                                if (size >= 3) break
                                item.artworkUri?.takeIf { it.isNotBlank() && it !in this }?.let(::add)
                            }
                        }
                    }
                    if (covers.isEmpty()) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp),
                            tint = colors.onSecondaryContainer,
                        )
                    } else {
                        covers.asReversed().forEachIndexed { index, cover ->
                            val depth = covers.lastIndex - index
                            SmartImage(
                                model = cover,
                                contentDescription = null,
                                shape = RoundedCornerShape(14.dp),
                                targetSize = SmartImageIntermediateTargetSize,
                                modifier = Modifier.size(64.dp)
                                    .offset(x = (-6 * depth).dp, y = (-5 * depth).dp)
                                    .rotate(-9f * depth),
                            )
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(R.string.home_queue_history_starts_with),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = firstTrack.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.unknown_song_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = firstTrack.artist?.takeIf(String::isNotBlank) ?: stringResource(R.string.unknown_artist),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(relativeTime, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onReplay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_queue_history_replay), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalIconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.home_queue_history_remove))
                }
            }
        }
    }
}
