package com.unshoo.pixelmusic.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import kotlin.math.abs
import kotlin.math.roundToInt

import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unshoo.pixelmusic.data.preferences.DEFAULT_SEEKER_ANIMATION_DURATION_MS
import com.unshoo.pixelmusic.data.preferences.sanitizeSeekerAnimationDurationMs
import dagger.hilt.android.EntryPointAccessors

/** Global seeker transition setting provided from app preferences. */
data class SeekerAnimationConfig(
    val enabled: Boolean = false,
    val durationMs: Int = DEFAULT_SEEKER_ANIMATION_DURATION_MS,
)

val LocalSeekerAnimationConfig = compositionLocalOf { SeekerAnimationConfig() }

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WavySliderExpressive(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    onValueCommit: ((Float) -> Unit)? = null,
    activeTrackColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    inactiveTrackColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
    thumbColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,

    isPlaying: Boolean = true,
    strokeWidth: Dp = 5.dp,
    thumbRadius: Dp = 8.dp,
    trackEdgePadding: Dp = thumbRadius,
    wavelength: Dp = WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
    waveSpeed: Dp = WavyProgressIndicatorDefaults.LinearDeterminateWavelength / 2f, // Slower wave as requested

    waveAmplitudeWhenPlaying: Dp = 4.dp,
    thumbLineHeightWhenInteracting: Dp = 24.dp,
    semanticsLabel: String? = null,
    semanticsProgressStep: Float = 0.01f,
    transitionKey: Any? = null
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val entryPoint = remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, SmartImageEntryPoint::class.java)
    }
    val userPreferencesRepository = entryPoint.userPreferencesRepository()
    val performanceModeEnabled by userPreferencesRepository.performanceModeEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val seekerAnimationConfig = LocalSeekerAnimationConfig.current

    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
    val trackEdgePaddingPx = with(density) { trackEdgePadding.coerceAtLeast(0.dp).toPx() }
    val thumbLineHeightPx = with(density) { thumbLineHeightWhenInteracting.toPx() }

    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
    }

    val normalizedValue = if (valueRange.endInclusive == valueRange.start) 0f
        else ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)

    val safeSemanticsStep = semanticsProgressStep.coerceIn(0.005f, 0.25f)
    val semanticNormalizedValue = remember(normalizedValue, safeSemanticsStep) {
        ((normalizedValue / safeSemanticsStep).roundToInt() * safeSemanticsStep).coerceIn(0f, 1f)
    }
    val semanticSliderValue = remember(semanticNormalizedValue, valueRange) {
        valueRange.start + semanticNormalizedValue * (valueRange.endInclusive - valueRange.start)
    }
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val latestOnValueCommit by rememberUpdatedState(onValueCommit)
    var isPointerSeeking by remember { mutableStateOf(false) }
    val isInteracting = isPointerSeeking

    val thumbInteractionFraction by animateFloatAsState(
        targetValue = if (isInteracting) 1f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "ThumbInteractionAnim"
    )
    val animatedAmplitude by animateFloatAsState(
        targetValue = if (enabled && isPlaying && !isInteracting && !performanceModeEnabled) 1f else 0f,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "amplitude"
    )

    // Keep visual progress interpolation out of composition:
    // update Animatable off composition, then consume .value only inside draw lambdas.
    val progressAnimatable = remember { Animatable(normalizedValue.coerceIn(0f, 1f)) }
    val latestTarget by rememberUpdatedState(normalizedValue.coerceIn(0f, 1f))
    var lastObservedTransitionKey by remember { mutableStateOf(transitionKey) }
    var isTransitionGlideActive by remember { mutableStateOf(false) }
    var isTimedGlideActive by remember { mutableStateOf(false) }
    var glideStartValue by remember { mutableFloatStateOf(normalizedValue.coerceIn(0f, 1f)) }
    var glideStartNanos by remember { mutableLongStateOf(0L) }
    var glideDeadlineNanos by remember { mutableLongStateOf(0L) }
    var lastProgressUpdateNanos by remember { mutableLongStateOf(0L) }
    val isLongPressSeeking =
        com.unshoo.pixelmusic.presentation.components.player.LocalLongPressSeekConfig.current.isActivelySeeking
    LaunchedEffect(
        normalizedValue,
        transitionKey,
        isInteracting,
        enabled,
        performanceModeEnabled,
        seekerAnimationConfig,
        isLongPressSeeking
    ) {
        val target = normalizedValue.coerceIn(0f, 1f)
        val keyChanged = transitionKey != lastObservedTransitionKey
        lastObservedTransitionKey = transitionKey

        val current = progressAnimatable.value
        val mode = resolveSliderProgressAnimationMode(
            enabled = enabled,
            isInteracting = isInteracting,
            performanceModeEnabled = performanceModeEnabled,
            keyChanged = keyChanged,
            isTransitionGlideActive = isTransitionGlideActive,
            currentProgress = current,
            targetProgress = target,
            seekerAnimationEnabled = seekerAnimationConfig.enabled,
            seekerAnimationDurationMs = seekerAnimationConfig.durationMs,
            isLongPressSeeking = isLongPressSeeking,
        )

        val nowNanos = System.nanoTime()
        val intervalMs = if (lastProgressUpdateNanos == 0L) {
            180
        } else {
            ((nowNanos - lastProgressUpdateNanos) / 1_000_000L).coerceIn(1L, 250L).toInt()
        }
        lastProgressUpdateNanos = nowNanos

        when (mode) {
            SliderProgressAnimationMode.SNAP -> {
                isTransitionGlideActive = false
                isTimedGlideActive = false
                progressAnimatable.snapTo(target)
            }
            SliderProgressAnimationMode.SPRING_TRANSITION -> {
                isTransitionGlideActive = true
                isTimedGlideActive = false
                try {
                    progressAnimatable.animateTo(
                        targetValue = target,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = TRACK_SWITCH_SPRING_STIFFNESS,
                            visibilityThreshold = PROGRESS_VISIBILITY_THRESHOLD,
                        ),
                    )
                    isTransitionGlideActive = false
                } finally {
                    lastProgressUpdateNanos = System.nanoTime()
                }
            }
            SliderProgressAnimationMode.TIMED_TRANSITION -> {
                val durationMs = sanitizeSeekerAnimationDurationMs(seekerAnimationConfig.durationMs)
                val firstFrameNanos = withFrameNanos { it }
                if (keyChanged || !isTimedGlideActive || firstFrameNanos >= glideDeadlineNanos) {
                    glideStartValue = current
                    glideStartNanos = firstFrameNanos
                    glideDeadlineNanos = firstFrameNanos + durationMs.toLong() * 1_000_000L
                }
                isTransitionGlideActive = true
                isTimedGlideActive = true
                val totalNanos = (glideDeadlineNanos - glideStartNanos).coerceAtLeast(1_000_000L)
                try {
                    while (true) {
                        val frameNanos = withFrameNanos { it }
                        val rawFraction = ((frameNanos - glideStartNanos).toDouble() / totalNanos.toDouble())
                            .toFloat()
                            .coerceIn(0f, 1f)
                        val easedFraction = FastOutSlowInEasing.transform(rawFraction)
                        val nextValue = glideStartValue + (latestTarget - glideStartValue) * easedFraction
                        progressAnimatable.snapTo(nextValue)
                        if (rawFraction >= 1f) break
                    }
                    progressAnimatable.snapTo(latestTarget)
                    isTransitionGlideActive = false
                    isTimedGlideActive = false
                } finally {
                    lastProgressUpdateNanos = System.nanoTime()
                }
            }
            SliderProgressAnimationMode.LINEAR_TICK -> {
                isTimedGlideActive = false
                val durationMs = ((intervalMs * 9) / 10).coerceAtLeast(16)
                try {
                    progressAnimatable.animateTo(
                        targetValue = target,
                        animationSpec = tween(durationMillis = durationMs, easing = LinearEasing),
                    )
                } finally {
                    lastProgressUpdateNanos = System.nanoTime()
                }
            }
        }
    }

    val containerHeight = max(WavyProgressIndicatorDefaults.LinearContainerHeight, max(thumbRadius * 2, thumbLineHeightWhenInteracting))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(containerHeight)
            .clearAndSetSemantics {
                if (!semanticsLabel.isNullOrBlank()) {
                    contentDescription = semanticsLabel
                }
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = semanticSliderValue,
                    range = valueRange.start..valueRange.endInclusive,
                    steps = 0
                )
                if (enabled) {
                    setProgress { requested ->
                        val coerced = requested.coerceIn(valueRange.start, valueRange.endInclusive)
                        latestOnValueChange(coerced)
                        latestOnValueCommit?.invoke(coerced)
                            ?: latestOnValueChangeFinished?.invoke()
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        LinearWavyProgressIndicator(
            progress = { progressAnimatable.value },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = trackEdgePadding.coerceAtLeast(0.dp))
                // Decorative layer: avoid duplicate semantics updates from the visual track.
                .clearAndSetSemantics { },
            color = activeTrackColor,
            trackColor = inactiveTrackColor,
            stroke = stroke,
            trackStroke = stroke,
            gapSize = thumbRadius + 4.dp,
            stopSize = 3.dp,
            amplitude = { progress -> if (performanceModeEnabled) 0f else if (progress > 0f) animatedAmplitude else 0f },
            wavelength = wavelength,
            waveSpeed = if (performanceModeEnabled) 0.dp else waveSpeed
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val edgePaddingPx = trackEdgePaddingPx.coerceIn(0f, size.width / 2f)
            val trackStart = edgePaddingPx
            val trackEnd = size.width - edgePaddingPx
            val trackWidth = (trackEnd - trackStart).coerceAtLeast(0f)
            val thumbY = size.height / 2
            val renderedProgress = progressAnimatable.value

            fun lerp(start: Float, stop: Float, fraction: Float): Float {
                return start + (stop - start) * fraction
            }

            val currentWidth = lerp(thumbRadiusPx * 2f, strokeWidthPx * 1.2f, thumbInteractionFraction)
            val currentHeight = lerp(thumbRadiusPx * 2f, thumbLineHeightPx, thumbInteractionFraction)
            val rawThumbX = trackStart + (trackWidth * renderedProgress)
            val minThumbCenter = (currentWidth / 2f).coerceAtMost(size.width / 2f)
            val maxThumbCenter = (size.width - currentWidth / 2f).coerceAtLeast(minThumbCenter)
            val thumbX = rawThumbX.coerceIn(minThumbCenter, maxThumbCenter)
            
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(
                    thumbX - currentWidth / 2f,
                    thumbY - currentHeight / 2f
                ),
                size = Size(currentWidth, currentHeight),
                cornerRadius = CornerRadius(currentWidth / 2f)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled, valueRange, trackEdgePaddingPx, transitionKey) {
                    if (!enabled) return@pointerInput

                    fun valueForX(rawX: Float): Float {
                        val edgePadding = trackEdgePaddingPx.coerceIn(0f, size.width / 2f)
                        val trackStart = edgePadding
                        val trackEnd = size.width - edgePadding
                        val trackWidth = (trackEnd - trackStart).coerceAtLeast(1f)
                        val normalized = ((rawX - trackStart) / trackWidth).coerceIn(0f, 1f)
                        return valueRange.start +
                            normalized * (valueRange.endInclusive - valueRange.start)
                    }

                    awaitEachGesture {
                        try {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            isPointerSeeking = true
                            down.consume()
                            var latestGestureValue = valueForX(down.position.x)
                            latestOnValueChange(latestGestureValue)

                            var pointerId = down.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: event.changes.firstOrNull { it.pressed }
                                    ?: break

                                pointerId = change.id
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }

                                if (change.position != change.previousPosition) {
                                    change.consume()
                                    latestGestureValue = valueForX(change.position.x)
                                    latestOnValueChange(latestGestureValue)
                                }
                            }

                            latestOnValueCommit?.invoke(latestGestureValue)
                                ?: latestOnValueChangeFinished?.invoke()
                        } finally {
                            isPointerSeeking = false
                        }
                    }
                }
        )
    }
}

internal enum class SliderProgressAnimationMode {
    SNAP,
    LINEAR_TICK,
    SPRING_TRANSITION,
    TIMED_TRANSITION,
}

internal fun resolveSliderProgressAnimationMode(
    enabled: Boolean,
    isInteracting: Boolean,
    performanceModeEnabled: Boolean,
    keyChanged: Boolean,
    isTransitionGlideActive: Boolean,
    currentProgress: Float,
    targetProgress: Float,
    seekerAnimationEnabled: Boolean = false,
    seekerAnimationDurationMs: Int = DEFAULT_SEEKER_ANIMATION_DURATION_MS,
    isLongPressSeeking: Boolean = false,
): SliderProgressAnimationMode {
    if (!enabled || isInteracting || performanceModeEnabled) {
        return SliderProgressAnimationMode.SNAP
    }
    val delta = abs(targetProgress - currentProgress)
    if (isLongPressSeeking) {
        return if (keyChanged || delta <= SUB_PIXEL_PROGRESS_STEP) {
            SliderProgressAnimationMode.SNAP
        } else {
            SliderProgressAnimationMode.LINEAR_TICK
        }
    }
    if (!seekerAnimationEnabled) {
        if (keyChanged ||
            targetProgress < currentProgress - BACKWARD_JUMP_THRESHOLD ||
            delta > LEGACY_SNAP_JUMP_THRESHOLD ||
            delta <= SUB_PIXEL_PROGRESS_STEP
        ) {
            return SliderProgressAnimationMode.SNAP
        }
        return SliderProgressAnimationMode.LINEAR_TICK
    }
    if (isTransitionGlideActive ||
        (keyChanged && delta > SUB_PIXEL_PROGRESS_STEP) ||
        targetProgress < currentProgress - BACKWARD_JUMP_THRESHOLD ||
        delta > JUMP_ANIMATION_THRESHOLD
    ) {
        val clampedDurationMs = sanitizeSeekerAnimationDurationMs(seekerAnimationDurationMs)
        return if (clampedDurationMs == DEFAULT_SEEKER_ANIMATION_DURATION_MS) {
            SliderProgressAnimationMode.SPRING_TRANSITION
        } else {
            SliderProgressAnimationMode.TIMED_TRANSITION
        }
    }
    if (delta <= SUB_PIXEL_PROGRESS_STEP) {
        return SliderProgressAnimationMode.SNAP
    }
    return SliderProgressAnimationMode.LINEAR_TICK
}

/** ~2 px on a typical phone-width track: below this, steady forward interpolation is invisible. */
internal const val SUB_PIXEL_PROGRESS_STEP = 0.0025f
internal const val BACKWARD_JUMP_THRESHOLD = 0.005f
internal const val JUMP_ANIMATION_THRESHOLD = 0.02f
internal const val LEGACY_SNAP_JUMP_THRESHOLD = 0.1f
private const val TRACK_SWITCH_SPRING_STIFFNESS = 240f
private const val PROGRESS_VISIBILITY_THRESHOLD = 0.0005f
