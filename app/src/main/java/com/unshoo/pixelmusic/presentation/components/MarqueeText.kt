package com.unshoo.pixelmusic.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints

@Composable
fun AutoScrollingTextOnDemand(
    text: String,
    style: TextStyle,
    gradientEdgeColor: Color,
    expansionFractionProvider: () -> Float,
    modifier: Modifier = Modifier,
    canScroll: Boolean = true
) {
    var overflow by remember(text, style) { mutableStateOf(false) }
    val canStart by remember(text, style) { derivedStateOf { expansionFractionProvider() > 0.99f && overflow } }

    // Usamos un Text "medidor" sólo la primera composición para detectar overflow.
    if (!canStart) {
        Text(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            onTextLayout = { res: TextLayoutResult -> overflow = res.hasVisualOverflow },
            modifier = modifier
        )
    } else {
        AutoScrollingText(
            text = text,
            style = style,
            textAlign = TextAlign.Start,
            gradientEdgeColor = gradientEdgeColor,
            modifier = modifier,
            canScroll = canScroll
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AutoScrollingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    textAlign: TextAlign? = null,
    gradientEdgeColor: Color,
    gradientWidth: Dp = 24.dp,
    canScroll: Boolean = true
) {
    // An endless marquee re-renders (through an offscreen layer) on every vsync for as
    // long as it is on screen - e.g. the mini player title, even while paused. Low-end
    // devices scroll a couple of times and then settle so the UI can go idle.
    val context = LocalContext.current
    val performanceModeEnabled = com.unshoo.pixelmusic.ui.theme.LocalPerformanceModeEnabled.current
    val marqueeIterations = remember {
        if (com.unshoo.pixelmusic.utils.DevicePerformanceUtils.isLowEndDevice(context)) {
            LOW_END_MARQUEE_ITERATIONS
        } else {
            // Was Int.MAX_VALUE: an endless marquee kept the app redrawing at 120 Hz forever
            // (~40% RenderThread CPU while idle on a Galaxy S24).
            HIGH_END_MARQUEE_ITERATIONS
        }
    }
    Box(
        modifier = modifier
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val gradientWidthPx = gradientWidth.toPx()
                // Right fade-out: Always visible for overflow
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(gradientEdgeColor, Color.Transparent),
                        startX = size.width - gradientWidthPx,
                        endX = size.width
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
      // Keyed on the text so each new song title scrolls again from the start.
      androidx.compose.runtime.key(text) {
        Text(
            text = text,
            style = style,
            textAlign = textAlign,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (canScroll && !performanceModeEnabled) {
                        Modifier.basicMarquee(
                            iterations = marqueeIterations,
                            spacing = MarqueeSpacing(gradientWidth + 6.dp),
                            velocity = 25.dp,
                            initialDelayMillis = 2000
                        )
                    } else Modifier
                )
        )
      }
    }
}

private const val LOW_END_MARQUEE_ITERATIONS = 2
private const val HIGH_END_MARQUEE_ITERATIONS = 4
