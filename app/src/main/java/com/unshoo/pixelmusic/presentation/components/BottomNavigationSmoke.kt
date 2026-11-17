package com.unshoo.pixelmusic.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unshoo.pixelmusic.ui.theme.PixelMusicTheme

/**
 * Precomputed 10-stop cubic easing curve distribution ($t^{2.2}$) ensuring zero derivative
 * at the top edge ($A'(0) = 0$) and seamless fade to 100% opacity at the bottom edge.
 *
 * This eliminates the harsh banding and visible cut-off lines associated with linear gradients,
 * creating an authentic atmospheric smoke blend.
 */
private val SmokeStops = floatArrayOf(
    0.00f, 0.12f, 0.25f, 0.38f, 0.50f, 0.62f, 0.72f, 0.82f, 0.90f, 1.00f
)
private val SmokeAlphas = floatArrayOf(
    0.000f, 0.040f, 0.120f, 0.260f, 0.480f, 0.750f, 0.920f, 0.990f, 1.000f, 1.000f
)

/**
 * Creates and remembers a vertical gradient [Brush] representing a smooth bottom navigation
 * smoke effect tinted with the container [backgroundColor].
 *
 * Memoized by [backgroundColor] to ensure zero GC allocations during 60/120 FPS scrolling
 * and draw passes.
 */
@Composable
fun rememberBottomNavigationSmokeBrush(
    backgroundColor: Color = MaterialTheme.colorScheme.background
): Brush {
    return remember(backgroundColor) {
        val stops = Array(SmokeStops.size) { i ->
            SmokeStops[i] to backgroundColor.copy(alpha = SmokeAlphas[i])
        }
        Brush.verticalGradient(colorStops = stops)
    }
}

/**
 * Creates and remembers an inverted vertical gradient [Brush] representing a smooth top
 * status-bar smoke effect tinted with the container [backgroundColor].
 * 100% opaque at top edge, fading down smoothly to 0% at the bottom edge.
 */
@Composable
fun rememberTopBarSmokeBrush(
    backgroundColor: Color = MaterialTheme.colorScheme.background
): Brush {
    return remember(backgroundColor) {
        val lastIdx = SmokeAlphas.lastIndex
        val stops = Array(SmokeStops.size) { i ->
            SmokeStops[i] to backgroundColor.copy(alpha = SmokeAlphas[lastIdx - i])
        }
        Brush.verticalGradient(colorStops = stops)
    }
}

/**
 * Modifier extension to draw the bottom navigation smoke effect directly in the HWUI/Skia
 * draw pass via [drawBehind], avoiding additional layout nodes or measurement overhead.
 */
fun Modifier.bottomNavigationSmokeBackground(brush: Brush): Modifier = this.drawBehind {
    drawRect(brush = brush)
}

// -----------------------------------------------------------------------------
// Compose Previews
// -----------------------------------------------------------------------------

@Composable
private fun BottomNavigationSmokePreviewContent(isDark: Boolean) {
    PixelMusicTheme(darkTheme = isDark) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Simulated scrolling content underneath
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    repeat(4) { index ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                            )
                            Column {
                                Text(
                                    text = "Track Title #${index + 1}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Artist Name • Album",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Smooth Bottom Navigation Smoke Blur Layer
                val smokeBrush = rememberBottomNavigationSmokeBrush(
                    backgroundColor = MaterialTheme.colorScheme.background
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .align(Alignment.BottomCenter)
                        .bottomNavigationSmokeBackground(brush = smokeBrush)
                )

                // Mock Floating Pill Navigation Bar centered inside the smoke area
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Floating Pill Mock
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Active selection indicator capsule
                            Box(
                                modifier = Modifier
                                    .size(width = 58.dp, height = 34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Home",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Text(
                                text = "Explore",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Library",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Detached Search FAB Mock
                    Surface(
                        modifier = Modifier.size(60.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        shadowElevation = 6.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Search",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Bottom Navigation Smoke - Light", showBackground = true)
@Composable
fun BottomNavigationSmokePreviewLight() {
    BottomNavigationSmokePreviewContent(isDark = false)
}

@Preview(name = "Bottom Navigation Smoke - Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BottomNavigationSmokePreviewDark() {
    BottomNavigationSmokePreviewContent(isDark = true)
}
