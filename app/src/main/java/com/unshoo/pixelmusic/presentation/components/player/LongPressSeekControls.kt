package com.unshoo.pixelmusic.presentation.components.player

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.data.preferences.DEFAULT_LONG_PRESS_SEEK_SPEED
import com.unshoo.pixelmusic.data.preferences.DEFAULT_LONG_PRESS_SEEK_TIME_RATE_SECONDS
import com.unshoo.pixelmusic.data.preferences.LongPressSeekMode

@Immutable
data class LongPressSeekConfig(
    val enabled: Boolean = true,
    val mode: LongPressSeekMode = LongPressSeekMode.SPEED_BASED,
    val speedMultiplier: Float = DEFAULT_LONG_PRESS_SEEK_SPEED,
    val timeRateSeconds: Int = DEFAULT_LONG_PRESS_SEEK_TIME_RATE_SECONDS,
    val isActivelySeeking: Boolean = false,
    val onHoldForwardStart: () -> Unit = {},
    val onHoldBackwardStart: () -> Unit = {},
    val onHoldEnd: () -> Unit = {},
)

val LocalLongPressSeekConfig = compositionLocalOf { LongPressSeekConfig() }

/**
 * Shared modifier for Previous / Next transport buttons:
 * - Short tap invokes [onTap] (track skip / previous)
 * - Holding past the system long-press timeout activates continuous speed seek via [onHoldStart]
 *   with zero polling timers on the UI thread, restoring cleanly via [onHoldEnd] on release/cancel.
 */
@Composable
internal fun Modifier.transportHoldToSeek(
    interactionSource: MutableInteractionSource,
    indication: Indication?,
    enabled: Boolean = true,
    clickLabel: String? = null,
    longPressEnabled: Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onTapHaptic: (() -> Unit)? = null,
    onTap: () -> Unit,
): Modifier {
    if (!longPressEnabled) {
        return this.clickable(
            interactionSource = interactionSource,
            indication = indication,
            enabled = enabled,
            onClickLabel = clickLabel,
            role = Role.Button,
            onClick = {
                onTapHaptic?.invoke()
                onTap()
            },
        )
    }

    val haptics = LocalHapticFeedback.current
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnHoldStart by rememberUpdatedState(onHoldStart)
    val latestOnHoldEnd by rememberUpdatedState(onHoldEnd)
    val latestOnTapHaptic by rememberUpdatedState(onTapHaptic)

    return this
        .semantics(mergeDescendants = false) {
            role = Role.Button
            if (!enabled) {
                disabled()
            }
            onClick(label = clickLabel) {
                if (enabled) {
                    latestOnTapHaptic?.invoke()
                    latestOnTap()
                    true
                } else {
                    false
                }
            }
        }
        .indication(interactionSource = interactionSource, indication = indication)
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val press = PressInteraction.Press(down.position)
                interactionSource.tryEmit(press)
                var interactionClosed = false
                var holdActive = false

                try {
                    val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
                    val slopLimit = maxOf(viewConfiguration.touchSlop * 2f, 24.dp.toPx())
                    var releasedNormally = false
                    var canceled = false

                    // Wait for quick release or sheet/pager drag cancellation before starting hold.
                    withTimeoutOrNull(longPressTimeoutMs) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                            if (change == null) {
                                canceled = true
                                return@withTimeoutOrNull
                            }
                            if (change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                releasedNormally = true
                                return@withTimeoutOrNull
                            }
                            if (
                                change.isConsumed ||
                                !change.pressed ||
                                (change.position - down.position).getDistance() > slopLimit
                            ) {
                                canceled = true
                                return@withTimeoutOrNull
                            }
                        }
                    }

                    if (releasedNormally) {
                        interactionSource.tryEmit(PressInteraction.Release(press))
                        interactionClosed = true
                        latestOnTapHaptic?.invoke()
                        latestOnTap()
                        return@awaitEachGesture
                    }
                    if (canceled) {
                        interactionSource.tryEmit(PressInteraction.Cancel(press))
                        interactionClosed = true
                        return@awaitEachGesture
                    }

                    // Long press active: engage continuous speed seek once, then await lift cleanly.
                    holdActive = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    latestOnHoldStart()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                        if (change == null || change.changedToUpIgnoreConsumed() || !change.pressed) {
                            change?.takeIf { it.changedToUpIgnoreConsumed() }?.consume()
                            interactionSource.tryEmit(PressInteraction.Release(press))
                            interactionClosed = true
                            break
                        }
                        if (change.isConsumed) {
                            interactionSource.tryEmit(PressInteraction.Cancel(press))
                            interactionClosed = true
                            break
                        }
                        event.changes.forEach { pointer ->
                            if (pointer.pressed) pointer.consume()
                        }
                    }
                } finally {
                    if (holdActive) {
                        latestOnHoldEnd()
                    }
                    if (!interactionClosed) {
                        interactionSource.tryEmit(PressInteraction.Cancel(press))
                    }
                }
            }
        }
}
