package com.unshoo.pixelmusic.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unshoo.pixelmusic.data.model.Playlist
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.presentation.components.PlaylistCover
import com.unshoo.pixelmusic.presentation.components.bounceClick
import com.unshoo.pixelmusic.presentation.viewmodel.PlayerViewModel
import com.unshoo.pixelmusic.ui.theme.ShapeCache

@Composable
internal fun ExploreLibraryPlaylistCard(
    playlist: Playlist,
    playerViewModel: PlayerViewModel,
    onClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val needsCollage = playlist.coverImageUri.isNullOrBlank() && playlist.coverColorArgb == null
    val previewSongIds = remember(playlist.songIds, needsCollage) {
        if (needsCollage) playlist.songIds.take(4) else emptyList()
    }
    var playlistSongs by remember(previewSongIds) { mutableStateOf(emptyList<Song>()) }
    if (previewSongIds.isNotEmpty()) {
        LaunchedEffect(playerViewModel, previewSongIds) {
            playlistSongs = playerViewModel.getSongs(previewSongIds)
        }
    }

    val dominantColor = playlist.coverColorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.secondaryContainer
    val cardBgColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val isDarkTheme = isSystemInDarkTheme()
    val blendedBgColor = remember(dominantColor, cardBgColor, isDarkTheme) {
        val blendFraction = if (isDarkTheme) 0.18f else 0.35f
        androidx.compose.ui.graphics.lerp(cardBgColor, dominantColor, blendFraction)
    }

    val cardShape = ShapeCache.smooth20
    val thumbShape = ShapeCache.smooth14

    val accent = remember(dominantColor) {
        Brush.horizontalGradient(listOf(dominantColor.copy(alpha = 0.22f), Color.Transparent))
    }

    Card(
        modifier = Modifier
            .width(260.dp)
            .height(120.dp)
            .bounceClick(scaleDown = 0.96f) { onClick() },
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = blendedBgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(accent)) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(thumbShape)
                ) {
                    PlaylistCover(
                        playlist = playlist,
                        playlistSongs = playlistSongs,
                        modifier = Modifier.fillMaxSize(),
                        size = 96.dp
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(28.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                            .clickable(enabled = playlist.songIds.isNotEmpty(), onClick = onPlayClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.2).sp
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val countText = if (playlist.displaySongCount != null) {
                            "${playlist.displaySongCount} songs"
                        } else {
                            "${playlist.songIds.size} songs"
                        }
                        Text(
                            text = countText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }

                    val sourceLabel = if (playlist.source == "YOUTUBE") "YouTube" else "Library"
                    val badgeBg = if (playlist.source == "YOUTUBE") {
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    }
                    val badgeText = if (playlist.source == "YOUTUBE") {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }

                    Box(
                        modifier = Modifier
                            .background(badgeBg, shape = RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = badgeText
                        )
                    }
                }
            }
        }
    }
}
