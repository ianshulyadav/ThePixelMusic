package com.unshoo.pixelmusic.presentation.components.subcomps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.utils.OfflineAudioResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * True when an online-catalog song has a downloaded copy on device.
 * Pure on-device songs are not marked: the badge only answers "is this online song offline?".
 * Cheap (no disk I/O) so it is safe to call inside lazy list items.
 */
val Song.isDownloadedOnlineSong: Boolean
    get() = !isLocal && (isYouTube || id.startsWith("youtube_")) &&
        (path.isNotBlank() || contentUriString.startsWith("file://") || contentUriString.startsWith("content://"))

private val Song.onlineYoutubeId: String?
    get() = if (isLocal) null else youtubeId ?: id.removePrefix("youtube_").takeIf { it != id }

/**
 * Same as [isDownloadedOnlineSong] but also knows about downloads that the [Song] row itself
 * does not carry (e.g. playlist rows, or files saved into a custom download folder).
 */
@Composable
fun rememberIsDownloaded(song: Song): Boolean {
    if (song.isDownloadedOnlineSong) return true
    val ytId = remember(song.id, song.youtubeId, song.isLocal) { song.onlineYoutubeId } ?: return false
    val downloadedIds by OfflineAudioResolver.downloadedIds.collectAsState()
    return ytId in downloadedIds
}

@Composable
fun rememberIsPlaylistDownloaded(songs: List<Song>, expectedSongCount: Int): Boolean {
    if (songs.isEmpty() || songs.size < expectedSongCount) return false
    val downloadedIds by OfflineAudioResolver.downloadedIds.collectAsState()
    val context = LocalContext.current
    var isDownloaded by remember(songs) { mutableStateOf(false) }
    LaunchedEffect(songs, downloadedIds) {
        isDownloaded = withContext(Dispatchers.IO) {
            songs.all { song ->
                song.isLocal || song.onlineYoutubeId?.let(downloadedIds::contains) == true ||
                    (OfflineAudioResolver.readableAudioPath(context, song.path)
                        ?: OfflineAudioResolver.readableAudioPath(context, song.contentUriString)) != null
            }
        }
    }
    return isDownloaded
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadedBadge(
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    ringColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    ringWidth: Dp = 1.dp,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialShapes.Cookie9Sided.toShape()
    val downloadedLabel = stringResource(R.string.downloaded_badge_label)
    Box(
        modifier = modifier
            .size(size + ringWidth * 2)
            .semantics { contentDescription = downloadedLabel }
            .background(ringColor, shape)
            .padding(ringWidth)
            .background(colors.primary, shape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.58f)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = w * 0.24f
            val tick = Path().apply {
                moveTo(w * 0.10f, h * 0.53f)
                lineTo(w * 0.40f, h * 0.82f)
                lineTo(w * 0.92f, h * 0.22f)
            }
            drawPath(
                path = tick,
                color = colors.onPrimary,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/** Convenience: the badge pinned to the bottom-end corner of the enclosing artwork Box. */
@Composable
fun BoxScope.ArtworkDownloadedBadge(
    ringColor: Color,
    size: Dp = 12.dp,
) {
    val overhang = size * 0.35f
    DownloadedBadge(
        modifier = Modifier.align(Alignment.BottomEnd).offset(x = overhang, y = overhang),
        size = size,
        ringColor = ringColor,
    )
}
