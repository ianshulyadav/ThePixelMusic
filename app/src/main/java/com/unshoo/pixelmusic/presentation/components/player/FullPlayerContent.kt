package com.unshoo.pixelmusic.presentation.components.player

import android.annotation.SuppressLint
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.unshoo.pixelmusic.data.model.Lyrics
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.preferences.FullPlayerLoadingTweaks
import com.unshoo.pixelmusic.presentation.components.UiEffectPreferenceKeys
import com.unshoo.pixelmusic.presentation.components.rememberBooleanPreference
import com.unshoo.pixelmusic.presentation.viewmodel.PlayerSheetState
import com.unshoo.pixelmusic.presentation.viewmodel.PlayerViewModel
import kotlinx.collections.immutable.ImmutableList

/**
 * Now Playing entry point. Picks the layout chosen in Settings › Appearance › Now Playing layout:
 *  - Classic (default): the unified-sheet full player ([ClassicFullPlayerContent]).
 *  - Expressive: full-bleed cover with expressive controls ([ExpressiveFullPlayerContent]).
 * Both share the exact same parameters so switching is instant and state-safe.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("StateFlowValueCalledInComposition")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FullPlayerContent(
    currentSong: Song?,
    currentPlaybackQueue: ImmutableList<Song>,
    currentQueueSourceName: String,
    currentMediaItemIndex: Int = -1,
    isShuffleEnabled: Boolean,
    shuffleTransitionInProgress: Boolean,
    repeatMode: Int,
    allowRealtimeUpdates: Boolean = true,
    expansionFractionProvider: () -> Float,
    currentSheetState: PlayerSheetState,
    carouselStyle: String,
    loadingTweaks: FullPlayerLoadingTweaks,
    isSheetDragGestureActive: Boolean = false,
    playerViewModel: PlayerViewModel,
    currentPositionProvider: () -> Long,
    isPlayingProvider: () -> Boolean,
    playWhenReadyProvider: () -> Boolean,
    isFavoriteProvider: () -> Boolean,
    repeatModeProvider: () -> Int,
    isShuffleEnabledProvider: () -> Boolean,
    totalDurationProvider: () -> Long,
    lyricsProvider: () -> Lyrics? = { null },
    isCastConnecting: Boolean = false,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCollapse: () -> Unit,
    onShowQueueClicked: () -> Unit,
    onQueueDragStart: () -> Unit,
    onQueueDrag: (Float) -> Unit,
    onQueueRelease: (Float, Float) -> Unit,
    onShowCastClicked: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onFavoriteToggle: () -> Unit
) {
    val useExpressiveLayout by rememberBooleanPreference(
        context = LocalContext.current,
        key = UiEffectPreferenceKeys.NOW_PLAYING_EXPRESSIVE_LAYOUT,
        default = false
    )
    if (useExpressiveLayout) {
        ExpressiveFullPlayerContent(
            currentSong = currentSong,
            currentPlaybackQueue = currentPlaybackQueue,
            currentQueueSourceName = currentQueueSourceName,
            currentMediaItemIndex = currentMediaItemIndex,
            isShuffleEnabled = isShuffleEnabled,
            shuffleTransitionInProgress = shuffleTransitionInProgress,
            repeatMode = repeatMode,
            allowRealtimeUpdates = allowRealtimeUpdates,
            expansionFractionProvider = expansionFractionProvider,
            currentSheetState = currentSheetState,
            carouselStyle = carouselStyle,
            loadingTweaks = loadingTweaks,
            isSheetDragGestureActive = isSheetDragGestureActive,
            playerViewModel = playerViewModel,
            currentPositionProvider = currentPositionProvider,
            isPlayingProvider = isPlayingProvider,
            playWhenReadyProvider = playWhenReadyProvider,
            isFavoriteProvider = isFavoriteProvider,
            repeatModeProvider = repeatModeProvider,
            isShuffleEnabledProvider = isShuffleEnabledProvider,
            totalDurationProvider = totalDurationProvider,
            lyricsProvider = lyricsProvider,
            isCastConnecting = isCastConnecting,
            onPlayPause = onPlayPause,
            onSeek = onSeek,
            onNext = onNext,
            onPrevious = onPrevious,
            onCollapse = onCollapse,
            onShowQueueClicked = onShowQueueClicked,
            onQueueDragStart = onQueueDragStart,
            onQueueDrag = onQueueDrag,
            onQueueRelease = onQueueRelease,
            onShowCastClicked = onShowCastClicked,
            onShuffleToggle = onShuffleToggle,
            onRepeatToggle = onRepeatToggle,
            onFavoriteToggle = onFavoriteToggle
        )
    } else {
        ClassicFullPlayerContent(
            currentSong = currentSong,
            currentPlaybackQueue = currentPlaybackQueue,
            currentQueueSourceName = currentQueueSourceName,
            currentMediaItemIndex = currentMediaItemIndex,
            isShuffleEnabled = isShuffleEnabled,
            shuffleTransitionInProgress = shuffleTransitionInProgress,
            repeatMode = repeatMode,
            allowRealtimeUpdates = allowRealtimeUpdates,
            expansionFractionProvider = expansionFractionProvider,
            currentSheetState = currentSheetState,
            carouselStyle = carouselStyle,
            loadingTweaks = loadingTweaks,
            isSheetDragGestureActive = isSheetDragGestureActive,
            playerViewModel = playerViewModel,
            currentPositionProvider = currentPositionProvider,
            isPlayingProvider = isPlayingProvider,
            playWhenReadyProvider = playWhenReadyProvider,
            isFavoriteProvider = isFavoriteProvider,
            repeatModeProvider = repeatModeProvider,
            isShuffleEnabledProvider = isShuffleEnabledProvider,
            totalDurationProvider = totalDurationProvider,
            lyricsProvider = lyricsProvider,
            isCastConnecting = isCastConnecting,
            onPlayPause = onPlayPause,
            onSeek = onSeek,
            onNext = onNext,
            onPrevious = onPrevious,
            onCollapse = onCollapse,
            onShowQueueClicked = onShowQueueClicked,
            onQueueDragStart = onQueueDragStart,
            onQueueDrag = onQueueDrag,
            onQueueRelease = onQueueRelease,
            onShowCastClicked = onShowCastClicked,
            onShuffleToggle = onShuffleToggle,
            onRepeatToggle = onRepeatToggle,
            onFavoriteToggle = onFavoriteToggle
        )
    }
}
