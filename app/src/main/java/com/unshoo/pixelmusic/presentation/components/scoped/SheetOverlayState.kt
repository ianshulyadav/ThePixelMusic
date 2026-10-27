package com.unshoo.pixelmusic.presentation.components.scoped

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.max

internal data class SheetOverlayState(
    val internalIsKeyboardVisible: Boolean,
    val actuallyShowSheetContent: Boolean,
    val isQueueVisible: Boolean,
    // Draw-phase providers: callers read these inside graphicsLayer {} so the
    // per-frame queue offset never triggers recomposition, only layer redraws.
    val queueVisualOpenFractionProvider: () -> Float,
    val bottomSheetOpenFractionProvider: () -> Float,
    val queueScrimAlphaProvider: () -> Float
)

@Composable
internal fun rememberSheetOverlayState(
    density: Density,
    showPlayerContentArea: Boolean,
    hideMiniPlayer: Boolean,
    showQueueSheet: Boolean,
    queueHiddenOffsetPx: Float,
    screenHeightPx: Float,
    castSheetOpenFraction: Float,
    // Draw-phase providers over the REAL queue offset. Deriving the visual open
    // fraction from the same Animatable that drives the sheet position means the
    // scrim and full-player depth track the finger 1:1 during drags and stay
    // perfectly in sync with the settle animation — no second tween that can drift.
    queueOffsetProvider: () -> Float = { 0f },
    queueHiddenOffsetProvider: () -> Float = { queueHiddenOffsetPx }
): SheetOverlayState {
    var internalIsKeyboardVisible by remember { mutableStateOf(false) }

    val imeInsets = WindowInsets.ime
    LaunchedEffect(imeInsets, density) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .collectLatest { isVisible ->
                if (internalIsKeyboardVisible != isVisible) {
                    internalIsKeyboardVisible = isVisible
                }
            }
    }

    val shouldShowSheet by remember(showPlayerContentArea, hideMiniPlayer) {
        derivedStateOf { showPlayerContentArea && !hideMiniPlayer }
    }

    val actuallyShowSheetContent = shouldShowSheet

    val isQueueVisible by remember(showQueueSheet, queueHiddenOffsetPx, screenHeightPx) {
        derivedStateOf {
            showQueueSheet &&
                queueHiddenOffsetPx > 0f &&
                screenHeightPx > 0f
        }
    }

    // Position-driven open fraction: 1f fully open, 0f fully hidden. Backed by
    // snapshot state and exposed as a provider so consumers can read it in the
    // draw phase without recomposing on every drag frame.
    val queueVisualOpenFractionState = remember(
        showQueueSheet,
        queueOffsetProvider,
        queueHiddenOffsetProvider
    ) {
        derivedStateOf {
            val hiddenOffset = queueHiddenOffsetProvider()
            if (hiddenOffset <= 0f) {
                if (showQueueSheet) 1f else 0f
            } else {
                (1f - (queueOffsetProvider() / hiddenOffset)).coerceIn(0f, 1f)
            }
        }
    }

    val bottomSheetOpenFractionState = remember(queueVisualOpenFractionState, castSheetOpenFraction) {
        derivedStateOf { max(queueVisualOpenFractionState.value, castSheetOpenFraction) }
    }

    val queueScrimAlphaState = remember(queueVisualOpenFractionState) {
        derivedStateOf { (queueVisualOpenFractionState.value * 0.45f).coerceIn(0f, 0.45f) }
    }

    return SheetOverlayState(
        internalIsKeyboardVisible = internalIsKeyboardVisible,
        actuallyShowSheetContent = actuallyShowSheetContent,
        isQueueVisible = isQueueVisible,
        queueVisualOpenFractionProvider = { queueVisualOpenFractionState.value },
        bottomSheetOpenFractionProvider = { bottomSheetOpenFractionState.value },
        queueScrimAlphaProvider = { queueScrimAlphaState.value }
    )
}
