package com.unshoo.pixelmusic.presentation.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unshoo.pixelmusic.data.discord.DiscordPresence
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

private val Blurple = Color(0xFF5865F2)
private val BlurpleDark = Color(0xFF3C45A5)

/** Accounts-screen card: link Discord to show "Listening to PixelMusic" on your profile. */
@Composable
fun DiscordAccountCard(modifier: Modifier = Modifier) {
    val state by DiscordPresence.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cardShape = AbsoluteSmoothCornerShape(28.dp, 60)
    val connected = state.enabled && (state.viaApp || (state.linked && state.status == "Connected"))

    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(BlurpleDark, Blurple)), cardShape),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.18f), AbsoluteSmoothCornerShape(14.dp, 60)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Headphones, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Discord",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = if (state.enabled) state.status
                        else "Show what you're listening to on your profile",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFD6D6),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (state.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!connected && !state.busy) {
                        Button(
                            onClick = { context.findActivity()?.let(DiscordPresence::connect) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = BlurpleDark,
                            ),
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { Text("Reconnect") }
                    }
                    OutlinedButton(
                        onClick = { DiscordPresence.disconnect() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) { Text("Disconnect") }
                }
            } else {
                Button(
                    onClick = { context.findActivity()?.let(DiscordPresence::connect) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = BlurpleDark,
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Connect Discord", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
