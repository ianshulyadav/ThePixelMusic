package com.unshoo.pixelmusic.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.service.cast.CastController

/**
 * Choosing a speaker.
 *
 * This sheet does one thing: it asks [CastController] what is on the network and
 * which device the music is on. It never loads a queue, never holds a session and
 * never talks to the player — the service owns all of that (see `CastPlayback`).
 *
 * That split is the whole fix. The previous sheet drove a `CastSession` of its
 * own from the UI layer while the ViewModel drove a second one and the service a
 * third, so pressing pause here could fight the transfer logic there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastBottomSheet(
    onDismiss: () -> Unit,
    onExpansionChanged: (Float) -> Unit
) {
    val context = LocalContext.current
    val state by CastController.state.collectAsState()
    val volume by CastController.volume.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Bumped by "Search again": keying the scan on it re-runs the effect and
    // removes the previous router callback instead of stacking a new one.
    var scanKey by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        CastController.ensureStarted(context)
        onDispose { }
    }

    // Scan only while somebody is looking: an active scan every time the app
    // opens is a battery cost with nothing to show for it.
    DisposableEffect(scanKey) {
        val stop = CastController.discover(active = true)
        onDispose { stop() }
    }

    LaunchedEffect(state.connecting) {
        onExpansionChanged(if (state.connecting) 0.5f else 1f)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Cast to a device",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = when {
                    state.connecting -> "Connecting…"
                    state.connectedName != null -> "Playing on ${state.connectedName}"
                    else -> "Choose a speaker on your Wi-Fi network."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(12.dp))

            if (state.connectedName != null) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "Speaker volume",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = volume.coerceIn(0f, 1f),
                    onValueChange = { CastController.setVolume(it) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        CastController.disconnect(resumeHere = true)
                        onDismiss()
                    }) {
                        Text("Stop casting")
                    }
                }
            }

            Spacer(Modifier.size(8.dp))

            if (state.devices.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Looking for devices…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                state.devices.forEach { device ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = device.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = when {
                            device.connected -> {
                                { Text("Connected") }
                            }
                            device.connecting -> {
                                { Text("Connecting…") }
                            }
                            else -> null
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.rounded_cast_24),
                                contentDescription = null,
                                tint = if (device.connected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        },
                        trailingContent = if (device.connected) {
                            {
                                Icon(
                                    painter = painterResource(R.drawable.rounded_music_cast_24),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !device.connected) {
                                CastController.connect(device.id)
                            }
                    )
                }
            }

            if (state.supported && state.devices.isEmpty()) {
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = { scanKey++ },
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape
                ) {
                    Text("Search again")
                }
            }
        }
    }
}
