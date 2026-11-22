package com.unshoo.pixelmusic.presentation.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.github.AppAnnouncement
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import timber.log.Timber

/**
 * Full-screen page for an announcement the team publishes from
 * www.pixelmusic.me/wishes/admin.html. Shown once per user on the first launch after
 * publishing (see AnnouncementsRepository.takePopupAnnouncement).
 */
@Composable
fun AppAnnouncementDialog(
    announcement: AppAnnouncement,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val actionShape = AbsoluteSmoothCornerShape(20.dp, 60)
    val scroll = rememberScrollState()
    val surface = MaterialTheme.colorScheme.surface
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 0 → 1 over the first ~220dp of scrolling; drives the badge collapse.
    val collapse = with(density) { (scroll.value / 220.dp.toPx()).coerceIn(0f, 1f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(surface)
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                        0.5f to MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f),
                        1f to surface,
                    ),
                ),
        ) {
            // Scrolling content. It fades out at the top and bottom edges instead of being cut off.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdges(top = topInset + 72.dp, bottom = 40.dp)
                    .verticalScroll(scroll)
                    .padding(top = topInset + 64.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnnouncementBadge(
                        modifier = Modifier.graphicsLayer {
                            val s = 1f - 0.35f * collapse
                            scaleX = s
                            scaleY = s
                            alpha = 1f - collapse
                            translationY = scroll.value * 0.35f // gentle parallax
                        },
                    )
                    Spacer(Modifier.height(28.dp))

                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Text(
                            text = stringResource(R.string.app_announcement_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                    }
                    Spacer(Modifier.height(18.dp))

                    Text(
                        text = announcement.title,
                        fontFamily = GoogleSansRounded,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    if (announcement.publishedAt > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = DateUtils.getRelativeTimeSpanString(
                                announcement.publishedAt,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ).toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (announcement.message.isNotBlank()) {
                        Spacer(Modifier.height(24.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = AbsoluteSmoothCornerShape(28.dp, 60),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = announcement.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(22.dp),
                            )
                        }
                    }
                    // Room so the last lines can scroll above the pinned buttons.
                    Spacer(Modifier.height(170.dp + bottomInset))
                }
            }

            // Top bar: soft scrim that appears as content scrolls under it, plus the close button.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topInset + 72.dp)
                    .graphicsLayer { alpha = collapse }
                    .background(Brush.verticalGradient(listOf(surface, surface.copy(alpha = 0.85f), Color.Transparent))),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                FilledTonalIconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.app_announcement_close))
                }
            }

            // Actions pinned to the bottom over a gradient, so text scrolls softly behind them.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, surface.copy(alpha = 0.92f), surface)))
                    .navigationBarsPadding()
                    .padding(top = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val url = announcement.buttonUrl
                    val label = announcement.buttonLabel
                    if (url != null && label != null) {
                        Button(
                            onClick = {
                                openHttpsLink(context, url)
                                onDismiss()
                            },
                            shape = actionShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    FilledTonalButton(
                        onClick = onDismiss,
                        shape = actionShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    ) {
                        Text(stringResource(R.string.app_announcement_close), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** Fades content out over [top] and [bottom] instead of a hard clip. */
private fun Modifier.fadingEdges(top: androidx.compose.ui.unit.Dp, bottom: androidx.compose.ui.unit.Dp): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val topPx = top.toPx()
            val bottomPx = bottom.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    (topPx / size.height).coerceIn(0f, 0.5f) to Color.Black,
                    (1f - bottomPx / size.height).coerceIn(0.5f, 1f) to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/**
 * Material 3 Expressive badge: a slowly turning 12-sided cookie in the tertiary color with a
 * gently pulsing 9-sided cookie holding the megaphone in front.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnnouncementBadge(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "announcementBadge")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val tilt by transition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "tilt",
    )
    val outer = MaterialShapes.Cookie12Sided.toShape()
    val inner = MaterialShapes.Cookie9Sided.toShape()

    Box(modifier = modifier.size(156.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(156.dp)
                .graphicsLayer { rotationZ = spin }
                .clip(outer)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .size(110.dp)
                .graphicsLayer { scaleX = pulse; scaleY = pulse; rotationZ = -spin * 0.5f }
                .clip(inner)
                .background(MaterialTheme.colorScheme.primary),
        )
        Icon(
            imageVector = Icons.Rounded.Campaign,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .size(54.dp)
                .graphicsLayer { rotationZ = tilt },
        )
    }
}

/** Opens [url] in the browser; only https links are allowed. */
private fun openHttpsLink(context: Context, url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull()
    if (uri?.scheme?.lowercase() != "https") {
        Timber.w("Refusing to open non-https announcement link: %s", url)
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Timber.w("No browser available for %s", url)
    }
}
