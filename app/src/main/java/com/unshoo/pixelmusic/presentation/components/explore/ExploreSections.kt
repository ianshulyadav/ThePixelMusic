@file:OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class
)

package com.unshoo.pixelmusic.presentation.components.explore

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.data.model.Genre
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.presentation.components.SmartImage
import com.unshoo.pixelmusic.presentation.components.SmartImageCompactListTargetSize
import com.unshoo.pixelmusic.presentation.components.SmartImageExploreCardTargetSize
import com.unshoo.pixelmusic.presentation.model.ExploreAlbumItemUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreArtistItemUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreChartsUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreChipUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreHeroItemUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreItemUiModel
import com.unshoo.pixelmusic.presentation.model.ExplorePlaylistItemUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreSectionUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreSongItemUiModel
import com.unshoo.pixelmusic.ui.theme.Shapes
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

// ============================================================================
//  Material 3 Expressive Motion & Press Modifiers
// ============================================================================

/**
 * Phase-deferred spring press scale modifier.
 *
 * Reads animated state strictly within [graphicsLayer] to avoid composition-phase
 * invalidations and preserve smooth vertical scrolling. Respects Compose touch-slop
 * by observing the supplied [MutableInteractionSource].
 */
@Composable
fun Modifier.expressivePress(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
    bouncy: Boolean = true
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (bouncy) {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        } else {
            spring(stiffness = Spring.StiffnessMedium)
        },
        label = "expressivePressScale"
    )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Computes concentric inner corner radius for nested containers:
 * R_inner = max(0, R_outer - padding).
 */
fun concentricCornerRadius(outerRadius: Dp, padding: Dp): Dp =
    (outerRadius - padding).coerceAtLeast(0.dp)

// ============================================================================
//  Material 3 Expressive Progress & Loading Indicators
// ============================================================================

/**
 * Safely clamps progress values into [0.0f, 1.0f] and treats NaN as 0.0f.
 */
fun safeProgressClamp(value: Float): Float {
    return if (value.isNaN()) 0.0f else value.coerceIn(0.0f, 1.0f)
}

/**
 * Material 3 Expressive Linear Wavy Progress Indicator with bound-safety clamping.
 */
@Composable
fun ExploreLinearWavyProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    stroke: Stroke = WavyProgressIndicatorDefaults.linearIndicatorStroke,
    trackStroke: Stroke = WavyProgressIndicatorDefaults.linearTrackStroke,
    gapSize: Dp = WavyProgressIndicatorDefaults.LinearIndicatorTrackGapSize,
    stopSize: Dp = WavyProgressIndicatorDefaults.LinearTrackStopIndicatorSize,
    amplitude: (Float) -> Float = { 1.0f },
    wavelength: Dp = WavyProgressIndicatorDefaults.LinearDeterminateWavelength
) {
    LinearWavyProgressIndicator(
        progress = { safeProgressClamp(progress()) },
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        stroke = stroke,
        trackStroke = trackStroke,
        gapSize = gapSize,
        stopSize = stopSize,
        amplitude = amplitude,
        wavelength = wavelength
    )
}

/**
 * Indeterminate Material 3 Expressive Linear Wavy Progress Indicator.
 */
@Composable
fun ExploreLinearWavyProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    stroke: Stroke = WavyProgressIndicatorDefaults.linearIndicatorStroke,
    trackStroke: Stroke = WavyProgressIndicatorDefaults.linearTrackStroke,
    gapSize: Dp = WavyProgressIndicatorDefaults.LinearIndicatorTrackGapSize,
    wavelength: Dp = WavyProgressIndicatorDefaults.LinearIndeterminateWavelength
) {
    LinearWavyProgressIndicator(
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        stroke = stroke,
        trackStroke = trackStroke,
        gapSize = gapSize,
        wavelength = wavelength
    )
}

/**
 * Material 3 Expressive Circular Wavy Progress Indicator (48dp standard size).
 */
@Composable
fun ExploreCircularWavyProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    stroke: Stroke = WavyProgressIndicatorDefaults.circularIndicatorStroke,
    trackStroke: Stroke = WavyProgressIndicatorDefaults.circularTrackStroke,
    gapSize: Dp = WavyProgressIndicatorDefaults.CircularIndicatorTrackGapSize
) {
    CircularWavyProgressIndicator(
        modifier = modifier.size(48.dp),
        color = color,
        trackColor = trackColor,
        stroke = stroke,
        trackStroke = trackStroke,
        gapSize = gapSize
    )
}

/**
 * Material 3 Expressive Polygon Shape-Morphing Loading Indicator.
 */
@Composable
fun ExploreLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    LoadingIndicator(
        modifier = modifier.size(48.dp),
        color = color
    )
}

/**
 * Material 3 Expressive Contained Shape-Morphing Loading Indicator.
 */
@Composable
fun ExploreContainedLoadingIndicator(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    indicatorColor: Color = MaterialTheme.colorScheme.primary,
    containerShape: Shape = Shapes.large
) {
    ContainedLoadingIndicator(
        modifier = modifier,
        containerColor = containerColor,
        indicatorColor = indicatorColor,
        containerShape = containerShape
    )
}

// ============================================================================
//  Material 3 Expressive Shape Morphing Buttons & Button Groups
// ============================================================================

fun resolveButtonCornerRadius(isPressed: Boolean, restRadius: Float = 24.0f, morphDelta: Float = 4.0f): Float {
    return if (isPressed) (restRadius - morphDelta).coerceAtLeast(0.0f) else restRadius
}

/**
 * Expressive Shape-Morphing Button using official M3 [ButtonShapes] or spring corner morphing.
 */
@Composable
fun ExpressiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    restRadius: Dp = 24.dp,
    morphDelta: Dp = 4.dp,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedRadius by animateDpAsState(
        targetValue = if (isPressed) (restRadius - morphDelta).coerceAtLeast(0.dp) else restRadius,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "expressive_button_shape"
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .expressivePress(interactionSource, pressedScale = 0.96f)
            .hapticPress(interactionSource),
        enabled = enabled,
        shape = RoundedCornerShape(animatedRadius),
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content
        )
    }
}

/**
 * Expressive Tonal Shape-Morphing Button.
 */
@Composable
fun ExpressiveTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    restRadius: Dp = 20.dp,
    morphDelta: Dp = 4.dp,
    content: @Composable RowScope.() -> Unit
) {
    ExpressiveButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = containerColor,
        contentColor = contentColor,
        restRadius = restRadius,
        morphDelta = morphDelta,
        content = content
    )
}

fun resolveButtonGroupCornerRadius(index: Int, selectedIndex: Int): Float {
    return if (index == selectedIndex) 24.0f else 10.0f
}

/**
 * Expressive Button Group for paired actions or category segmentation.
 */
@Composable
fun ExpressiveButtonGroup(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { index, title ->
            val isSelected = index == selectedIndex
            val cornerRadius = resolveButtonGroupCornerRadius(index, selectedIndex).dp
            val interaction = remember { MutableInteractionSource() }

            Surface(
                onClick = { onSelect(index) },
                modifier = Modifier
                    .weight(1f)
                    .expressivePress(interaction, pressedScale = 0.95f)
                    .hapticPress(interaction),
                shape = RoundedCornerShape(cornerRadius),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                interactionSource = interaction
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Expressive Card Container using M3 Expressive shapes with touch-down press feedback.
 */
@Composable
fun ExpressiveCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier
            .expressivePress(interaction, pressedScale = 0.97f)
            .hapticPress(interaction),
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interaction,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    )
}

// ============================================================================
//  Section Header — title + optional "see all"
// ============================================================================

@Composable
fun ExploreSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onAction != null && actionLabel != null) {
            val interaction = remember { MutableInteractionSource() }
            Surface(
                onClick = onAction,
                modifier = Modifier
                    .expressivePress(interaction, pressedScale = 0.94f)
                    .hapticPress(interaction),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.primary,
                interactionSource = interaction
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
//  Hero Carousel / Spotlight
// ============================================================================

val HeroCardShape = AbsoluteSmoothCornerShape(
    cornerRadiusTL = 28.dp,
    cornerRadiusTR = 28.dp,
    cornerRadiusBL = 28.dp,
    cornerRadiusBR = 28.dp,
    smoothnessAsPercentTL = 60,
    smoothnessAsPercentTR = 60,
    smoothnessAsPercentBL = 60,
    smoothnessAsPercentBR = 60
)

@Composable
fun ExploreHeroCarouselUiModels(
    items: List<ExploreHeroItemUiModel>,
    currentSongId: String?,
    onItemClick: (ExploreHeroItemUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val rowState = rememberLazyListState()

    LazyRow(
        state = rowState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> "hero_ui_${item.id}" },
            contentType = { _, _ -> "explore_hero" }
        ) { _, item ->
            ExploreHeroCard(
                title = item.title,
                subtitle = item.subtitle ?: "",
                artworkUrl = item.artworkUrl,
                badgeText = item.badgeText,
                isCurrent = item.id == currentSongId,
                onClick = { onItemClick(item) },
                onLongClick = { onItemClick(item) }
            )
        }
    }
}

@Composable
fun ExploreHeroCarousel(
    songs: List<Song>,
    currentSongId: String?,
    onSongClick: (Song) -> Unit,
    onSongLongClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) return
    val rowState = rememberLazyListState()

    LazyRow(
        state = rowState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(
            items = songs,
            key = { _, song -> "hero_${song.id}" },
            contentType = { _, _ -> "explore_hero" }
        ) { _, song ->
            ExploreHeroCard(
                title = song.title,
                subtitle = song.displayArtist,
                artworkUrl = song.albumArtUriString,
                badgeText = null,
                isCurrent = song.id == currentSongId,
                onClick = { onSongClick(song) },
                onLongClick = { onSongLongClick(song) }
            )
        }
    }
}

@Composable
private fun ExploreHeroCard(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    badgeText: String?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = modifier
            .width(258.dp)
            .height(300.dp)
            .expressivePress(interaction, pressedScale = 0.965f)
            .hapticPress(interaction)
            .clip(HeroCardShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        SmartImage(
            model = artworkUrl,
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            shape = HeroCardShape,
            contentScale = ContentScale.Crop,
            targetSize = SmartImageExploreCardTargetSize
        )

        // Gradient scrim ensuring WCAG AA contrast ratio regardless of artwork colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.42f to Color.Transparent,
                        0.72f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black.copy(alpha = 0.86f)
                    )
                )
        )

        if (pressed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            if (badgeText != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Text(
                        text = badgeText.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .size(40.dp),
            shape = CircleShape,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Black.copy(alpha = 0.32f)
            },
            contentColor = if (isCurrent) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                Color.White
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ============================================================================
//  Quick Picks — 2-column pill grid
// ============================================================================

@Composable
fun ExploreQuickPicksUiModels(
    items: List<ExploreSongItemUiModel>,
    currentSongId: String?,
    onItemClick: (ExploreSongItemUiModel) -> Unit,
    onItemLongClick: (ExploreSongItemUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val rows = remember(items) { items.chunked(2) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { rowSongs ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowSongs.forEach { item ->
                    ExploreQuickPickPill(
                        title = item.title,
                        artist = item.subtitle ?: "",
                        artworkUrl = item.artworkUrl,
                        isCurrent = item.id == currentSongId,
                        explicit = item.explicit,
                        onClick = { onItemClick(item) },
                        onLongClick = { onItemLongClick(item) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowSongs.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun ExploreQuickPicks(
    songs: List<Song>,
    currentSongId: String?,
    onSongClick: (Song) -> Unit,
    onSongLongClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) return
    val rows = remember(songs) { songs.chunked(2) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { rowSongs ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowSongs.forEach { song ->
                    ExploreQuickPickPill(
                        title = song.title,
                        artist = song.displayArtist,
                        artworkUrl = song.albumArtUriString,
                        isCurrent = song.id == currentSongId,
                        explicit = false,
                        onClick = { onSongClick(song) },
                        onLongClick = { onSongLongClick(song) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowSongs.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ExploreQuickPickPill(
    title: String,
    artist: String,
    artworkUrl: String?,
    isCurrent: Boolean,
    explicit: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val outerRadius = 14.dp
    val padding = 6.dp
    val artRadius = concentricCornerRadius(outerRadius, padding) // 8.dp concentric corner

    Surface(
        modifier = modifier
            .expressivePress(interaction, pressedScale = 0.95f)
            .hapticPress(interaction)
            .clip(RoundedCornerShape(outerRadius))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(outerRadius),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartImage(
                model = artworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(artRadius)),
                shape = RoundedCornerShape(artRadius),
                targetSize = SmartImageCompactListTargetSize
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (explicit) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = "E",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ============================================================================
//  Albums & New Releases — square art cards
// ============================================================================

val MadeForYouShape = AbsoluteSmoothCornerShape(
    cornerRadiusTL = 20.dp,
    cornerRadiusTR = 20.dp,
    cornerRadiusBL = 20.dp,
    cornerRadiusBR = 20.dp,
    smoothnessAsPercentTL = 60,
    smoothnessAsPercentTR = 60,
    smoothnessAsPercentBL = 60,
    smoothnessAsPercentBR = 60
)

@Composable
fun ExploreAlbumsRow(
    albums: List<ExploreAlbumItemUiModel>,
    onAlbumClick: (ExploreAlbumItemUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    if (albums.isEmpty()) return
    val rowState = rememberLazyListState()

    LazyRow(
        state = rowState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(
            items = albums,
            key = { _, album -> "album_${album.id}" },
            contentType = { _, _ -> "explore_album" }
        ) { _, album ->
            ExploreSquareCard(
                title = album.title,
                subtitle = album.subtitle ?: album.year ?: "",
                artworkUrl = album.artworkUrl,
                isCurrent = false,
                onClick = { onAlbumClick(album) },
                onLongClick = { onAlbumClick(album) }
            )
        }
    }
}

@Composable
fun ExploreMadeForYou(
    songs: List<Song>,
    currentSongId: String?,
    onSongClick: (Song) -> Unit,
    onSongLongClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) return
    val rowState = rememberLazyListState()

    LazyRow(
        state = rowState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(
            items = songs,
            key = { _, song -> "mfy_${song.id}" },
            contentType = { _, _ -> "explore_made_for_you" }
        ) { _, song ->
            ExploreSquareCard(
                title = song.title,
                subtitle = song.displayArtist,
                artworkUrl = song.albumArtUriString,
                isCurrent = song.id == currentSongId,
                onClick = { onSongClick(song) },
                onLongClick = { onSongLongClick(song) }
            )
        }
    }
}

@Composable
internal fun ExploreSquareCard(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(132.dp)
    ) {
        val interaction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .expressivePress(interaction, pressedScale = 0.94f)
                .hapticPress(interaction)
                .clip(MadeForYouShape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            SmartImage(
                model = artworkUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                shape = MadeForYouShape,
                targetSize = SmartImageExploreCardTargetSize
            )
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(28.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================================================================
//  Charts Section (Top Songs & Albums with Segmented Toggle)
// ============================================================================

@Composable
fun ExploreChartsSection(
    charts: ExploreChartsUiModel,
    currentSongId: String?,
    onTrackClick: (ExploreSongItemUiModel) -> Unit,
    onAlbumClick: (ExploreAlbumItemUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedChartTab by remember { mutableIntStateOf(0) } // 0: Top Songs, 1: Top Albums

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ExploreSectionHeader(title = "Top Charts")

        ExpressiveButtonGroup(
            items = listOf("Top Songs", "Top Albums"),
            selectedIndex = selectedChartTab,
            onSelect = { selectedChartTab = it }
        )

        if (selectedChartTab == 0) {
            if (charts.tracks.isEmpty()) {
                ExploreEmptyHint(text = "No chart tracks available")
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    charts.tracks.take(5).forEachIndexed { index, track ->
                        ExploreRankedSongRow(
                            rank = index + 1,
                            item = track,
                            isCurrent = track.id == currentSongId,
                            onClick = { onTrackClick(track) },
                            onLongClick = { onTrackClick(track) }
                        )
                    }
                }
            }
        } else {
            if (charts.collections.isEmpty()) {
                ExploreEmptyHint(text = "No chart albums available")
            } else {
                ExploreAlbumsRow(
                    albums = charts.collections,
                    onAlbumClick = onAlbumClick
                )
            }
        }
    }
}

@Composable
private fun ExploreRankedSongRow(
    rank: Int,
    item: ExploreSongItemUiModel,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .expressivePress(interaction, pressedScale = 0.975f, bouncy = false)
            .hapticPress(interaction)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .width(28.dp)
                    .padding(start = 4.dp),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(8.dp))
            SmartImage(
                model = item.artworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                targetSize = SmartImageCompactListTargetSize
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.subtitle ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ============================================================================
//  Artists For You
// ============================================================================

@Composable
fun ExploreArtistsRow(
    artists: List<ExploreArtistItemUiModel>,
    onArtistClick: (ExploreArtistItemUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    if (artists.isEmpty()) return
    val rowState = rememberLazyListState()

    LazyRow(
        state = rowState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(
            items = artists,
            key = { _, artist -> "artist_${artist.id}" },
            contentType = { _, _ -> "explore_artist" }
        ) { _, artist ->
            val interaction = remember { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .width(104.dp)
                    .expressivePress(interaction, pressedScale = 0.94f)
                    .hapticPress(interaction)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onArtistClick(artist) }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmartImage(
                    model = artist.artworkUrl,
                    contentDescription = artist.name,
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape),
                    shape = CircleShape,
                    targetSize = SmartImageExploreCardTargetSize
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ============================================================================
//  Genre & Filter Chips
// ============================================================================

@Composable
fun ExploreChipsRow(
    chips: List<ExploreChipUiModel>,
    onChipClick: (ExploreChipUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    if (chips.isEmpty()) return
    val rowState = rememberLazyListState()

    LazyRow(
        state = rowState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(
            items = chips,
            key = { _, chip -> "chip_${chip.id}" },
            contentType = { _, _ -> "explore_chip" }
        ) { _, chip ->
            val interaction = remember { MutableInteractionSource() }
            Surface(
                onClick = { onChipClick(chip) },
                modifier = Modifier
                    .expressivePress(interaction, pressedScale = 0.94f)
                    .hapticPress(interaction),
                shape = RoundedCornerShape(20.dp),
                color = if (chip.isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (chip.isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                interactionSource = interaction
            ) {
                Text(
                    text = chip.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (chip.isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
fun ExploreGenreChips(
    genres: List<Genre>,
    onGenreClick: (Genre) -> Unit,
    modifier: Modifier = Modifier
) {
    if (genres.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val containers = remember(scheme) {
        listOf(
            scheme.primaryContainer to scheme.onPrimaryContainer,
            scheme.secondaryContainer to scheme.onSecondaryContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer,
            scheme.surfaceContainerHighest to scheme.onSurface
        )
    }

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        genres.forEachIndexed { index, genre ->
            val (container, content) = containers[index % containers.size]
            val interaction = remember { MutableInteractionSource() }
            Surface(
                onClick = { onGenreClick(genre) },
                modifier = Modifier
                    .tiltOnPress(interaction, maxDegrees = 4f)
                    .hapticPress(interaction),
                shape = RoundedCornerShape(14.dp),
                color = container,
                contentColor = content,
                interactionSource = interaction
            ) {
                Text(
                    text = genre.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

// ============================================================================
//  Dynamic Shelves
// ============================================================================

@Composable
fun ExploreDynamicShelf(
    shelf: ExploreSectionUiModel,
    currentSongId: String?,
    onItemClick: (ExploreItemUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    if (shelf.items.isEmpty()) return
    val rowState = rememberLazyListState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ExploreSectionHeader(
            title = shelf.title,
            subtitle = shelf.label
        )

        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(
                items = shelf.items,
                key = { _, item -> "shelf_${shelf.id}_${item.id}" },
                contentType = { _, _ -> "explore_shelf_item" }
            ) { _, item ->
                when (item) {
                    is ExploreSongItemUiModel -> {
                        ExploreSquareCard(
                            title = item.title,
                            subtitle = item.subtitle ?: "",
                            artworkUrl = item.artworkUrl,
                            isCurrent = item.id == currentSongId,
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemClick(item) }
                        )
                    }
                    is ExploreAlbumItemUiModel -> {
                        ExploreSquareCard(
                            title = item.title,
                            subtitle = item.subtitle ?: item.year ?: "",
                            artworkUrl = item.artworkUrl,
                            isCurrent = false,
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemClick(item) }
                        )
                    }
                    is ExplorePlaylistItemUiModel -> {
                        ExploreSquareCard(
                            title = item.title,
                            subtitle = item.subtitle ?: item.songCountText ?: "",
                            artworkUrl = item.artworkUrl,
                            isCurrent = false,
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemClick(item) }
                        )
                    }
                    is ExploreArtistItemUiModel -> {
                        val interaction = remember { MutableInteractionSource() }
                        Column(
                            modifier = Modifier
                                .width(104.dp)
                                .expressivePress(interaction, pressedScale = 0.94f)
                                .hapticPress(interaction)
                                .combinedClickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    onClick = { onItemClick(item) }
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            SmartImage(
                                model = item.artworkUrl,
                                contentDescription = item.name,
                                modifier = Modifier
                                    .size(104.dp)
                                    .clip(CircleShape),
                                shape = CircleShape,
                                targetSize = SmartImageExploreCardTargetSize
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    else -> {
                        ExploreSquareCard(
                            title = item.id,
                            subtitle = "",
                            artworkUrl = item.thumbnail,
                            isCurrent = false,
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemClick(item) }
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
//  Compact Song Row
// ============================================================================

@Composable
fun ExploreSongRow(
    item: ExploreSongItemUiModel,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val outerRadius = 16.dp
    val padding = 8.dp
    val artRadius = concentricCornerRadius(outerRadius, padding) // 8.dp concentric

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .expressivePress(interaction, pressedScale = 0.975f, bouncy = false)
            .hapticPress(interaction)
            .clip(RoundedCornerShape(outerRadius))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(outerRadius),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartImage(
                model = item.artworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(artRadius)),
                shape = RoundedCornerShape(artRadius),
                targetSize = SmartImageCompactListTargetSize
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.subtitle ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun ExploreSongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExploreSongRow(
        item = ExploreSongItemUiModel(
            id = song.id,
            title = song.title,
            subtitle = song.displayArtist,
            artworkUrl = song.albumArtUriString,
            durationText = null,
            nativeSong = song
        ),
        isCurrent = isCurrent,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
    )
}

// ============================================================================
//  Recently Played Pills
// ============================================================================

@Composable
fun ExploreRecentlyPlayedPills(
    songs: List<Song>,
    currentSongId: String?,
    onSongClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) return
    val rowState = rememberLazyListState()

    LazyRow(
        state = rowState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(
            items = songs,
            key = { _, song -> "recent_${song.id}" },
            contentType = { _, _ -> "explore_recent_pill" }
        ) { _, song ->
            val interaction = remember { MutableInteractionSource() }
            val outerRadius = 14.dp
            val padding = 6.dp
            val artRadius = concentricCornerRadius(outerRadius, padding) // 8.dp concentric

            Surface(
                onClick = { onSongClick(song) },
                modifier = Modifier
                    .width(148.dp)
                    .expressivePress(interaction, pressedScale = 0.945f)
                    .hapticPress(interaction),
                shape = RoundedCornerShape(outerRadius),
                color = if (song.id == currentSongId) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                interactionSource = interaction
            ) {
                Row(
                    modifier = Modifier.padding(padding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmartImage(
                        model = song.albumArtUriString,
                        contentDescription = null,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(artRadius)),
                        shape = RoundedCornerShape(artRadius),
                        targetSize = SmartImageCompactListTargetSize
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song.displayArtist,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
//  Skeleton Shimmer
// ============================================================================

@Composable
fun ExploreSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            repeat(2) {
                ShimmerBox(
                    modifier = Modifier
                        .width(258.dp)
                        .height(300.dp),
                    shape = HeroCardShape
                )
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(2) {
                        ShimmerBox(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            repeat(3) {
                ShimmerBox(
                    modifier = Modifier
                        .width(132.dp)
                        .height(132.dp),
                    shape = MadeForYouShape
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            repeat(3) {
                ShimmerBox(
                    modifier = Modifier
                        .height(38.dp)
                        .width(92.dp),
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }
    }
}

@Composable
fun ExploreEmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
