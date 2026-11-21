package com.unshoo.pixelmusic.presentation.components

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp as lerpFloat
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/** Visual treatment of the [QuickPicksMaterialCarousel] cards. */
enum class QuickPicksCarouselLook {
    /** Full-bleed artwork with a frosted-glass (blurred artwork) info panel. */
    FROSTED,
    /** Inset artwork on a container tinted with the album's own colour. */
    ALBUM_TINT,
}

private val CardCorner = 28.dp
private val CarouselHeight = 300.dp

/**
 * Quick Picks carousel with Apple Music–style motion:
 *  - a centred, focused card with neighbours peeking on both sides,
 *  - continuous depth while dragging (neighbours shrink, dim and recede; artwork parallaxes
 *    against the card frame), driven every frame from the pager offset (no recomposition),
 *  - a soft critically-damped spring snap, one page per fling,
 *  - spring press-down feedback and a light haptic tick when a new card settles.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickPicksMaterialCarousel(
    songs: List<Song>,
    look: QuickPicksCarouselLook,
    activePlayingSongId: String?,
    onSongClick: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) return
    val pagerState = rememberPagerState { songs.size }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Haptic tick when a new card becomes the settled one (like the iOS picker detent).
    androidx.compose.runtime.LaunchedEffect(pagerState) {
        androidx.compose.runtime.snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .drop(1) // not on first composition
            .collect { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val pageWidth = (maxWidth * 0.74f).coerceAtMost(340.dp)
        val sidePadding = (maxWidth - pageWidth) / 2
        val flingBehavior = PagerDefaults.flingBehavior(
            state = pagerState,
            pagerSnapDistance = PagerSnapDistance.atMost(1),
            snapAnimationSpec = spring(dampingRatio = 0.86f, stiffness = 320f),
        )
        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(pageWidth),
            contentPadding = PaddingValues(horizontal = sidePadding),
            pageSpacing = 4.dp,
            flingBehavior = flingBehavior,
            beyondViewportPageCount = 1,
            key = { songs[it].id },
            modifier = Modifier
                .fillMaxWidth()
                .height(CarouselHeight)
        ) { page ->
            val song = songs[page]
            CarouselPage(
                song = song,
                page = page,
                pagerState = pagerState,
                look = look,
                isPlaying = song.id == activePlayingSongId,
                onClick = {
                    if (pagerState.currentPage != page) {
                        scope.launch { pagerState.animateScrollToPage(page, animationSpec = spring(dampingRatio = 0.86f, stiffness = 320f)) }
                    } else {
                        onSongClick(song)
                    }
                },
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onDownload = onDownload,
            )
        }
    }
}

/** Signed distance of [page] from the centre, in pages (0 = centred). */
private fun PagerState.offsetFor(page: Int): Float =
    (currentPage - page) + currentPageOffsetFraction

@Composable
private fun CarouselPage(
    song: Song,
    page: Int,
    pagerState: PagerState,
    look: QuickPicksCarouselLook,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onDownload: (Song) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "qp_press"
    )
    val shape = remember { RoundedCornerShape(CardCorner) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Read the pager offset inside the layer block: runs per frame in the draw phase,
                // so dragging never recomposes the cards.
                val offset = pagerState.offsetFor(page)
                val distance = offset.absoluteValue.coerceIn(0f, 1f)
                val depth = lerpFloat(1f, 0.84f, distance)
                scaleX = depth * pressScale
                scaleY = depth * pressScale
                alpha = lerpFloat(1f, 0.55f, distance)
                // Neighbours tuck slightly toward the centre card and tilt away, like a cover flow.
                translationX = offset * size.width * 0.06f
                rotationY = (offset * -6f).coerceIn(-6f, 6f)
                cameraDistance = 14f * density
                shadowElevation = lerpFloat(18.dp.toPx(), 2.dp.toPx(), distance)
                this.shape = shape
                clip = false
            }
            .padding(vertical = 8.dp)
    ) {
        when (look) {
            QuickPicksCarouselLook.FROSTED -> FrostedCard(song, page, pagerState, isPlaying, shape, interaction, onClick, onPlayNext, onAddToQueue, onDownload)
            QuickPicksCarouselLook.ALBUM_TINT -> AlbumTintCard(song, page, pagerState, isPlaying, shape, interaction, onClick, onPlayNext, onAddToQueue, onDownload)
        }
    }
}

/** Artwork that parallaxes against its frame as the card moves (Apple Music's "window" effect). */
@Composable
private fun ParallaxArtwork(
    song: Song,
    page: Int,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 0.dp,
) {
    SmartImage(
        model = song.albumArtUriString,
        contentDescription = if (blurRadius > 0.dp) null else song.title,
        contentScale = ContentScale.Crop,
        targetSize = SmartImageCardTargetSize,
        placeholderResId = 0,
        errorResId = 0,
        modifier = modifier
            .graphicsLayer {
                val offset = pagerState.offsetFor(page).coerceIn(-1f, 1f)
                scaleX = 1.14f
                scaleY = 1.14f
                translationX = offset * size.width * 0.10f
            }
            .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrostedCard(
    song: Song,
    page: Int,
    pagerState: PagerState,
    isPlaying: Boolean,
    shape: RoundedCornerShape,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onDownload: (Song) -> Unit,
) {
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val panelFraction = 0.30f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(BorderStroke(0.75.dp, Color.White.copy(alpha = 0.14f)), shape)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        ParallaxArtwork(song, page, pagerState, Modifier.fillMaxSize())

        // Frosted panel: the same artwork, heavily blurred, revealed only in the bottom band so
        // the glass shows the cover's own colours behind it (RenderEffect blur on Android 12+).
        if (supportsBlur) {
            ParallaxArtwork(
                song, page, pagerState,
                blurRadius = 28.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(top = size.height * (1f - panelFraction)) { this@drawWithContent.drawContent() }
                    }
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(panelFraction)
                .background(
                    if (supportsBlur) Color.Black.copy(alpha = 0.22f)
                    else Color.Black.copy(alpha = 0.55f)
                )
                .background(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent))
                )
        ) {
            // Specular hairline on the top edge of the glass.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.75.dp)
                    .background(Color.White.copy(alpha = 0.28f))
            )
            CardInfo(
                song = song,
                isPlaying = isPlaying,
                titleColor = Color.White,
                subtitleColor = Color.White.copy(alpha = 0.78f),
                accent = Color.White,
                chipColor = Color.White.copy(alpha = 0.18f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 18.dp, end = 10.dp)
            )
        }

        CardOverflowMenu(
            song = song,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onDownload = onDownload,
            containerColor = Color.Black.copy(alpha = 0.32f),
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTintCard(
    song: Song,
    page: Int,
    pagerState: PagerState,
    isPlaying: Boolean,
    shape: RoundedCornerShape,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onDownload: (Song) -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val albumColor = rememberAlbumVibrantColor(song.albumArtUriString, MaterialTheme.colorScheme.primary)
    // Material-style tonal container from the album colour: deep in dark theme, soft in light.
    val container = remember(albumColor, isDark) {
        if (isDark) lerp(albumColor, Color(0xFF0B0D12), 0.58f) else lerp(albumColor, Color.White, 0.72f)
    }
    val onContainer = if (container.luminance() > 0.5f) Color(0xFF14161B) else Color.White
    val accent = remember(albumColor, isDark) {
        if (isDark) lerp(albumColor, Color.White, 0.35f) else lerp(albumColor, Color.Black, 0.35f)
    }
    val artShape = remember { RoundedCornerShape(CardCorner - 10.dp) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(lerp(container, albumColor, 0.18f), container))
            )
            .border(BorderStroke(0.75.dp, accent.copy(alpha = 0.22f)), shape)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .shadow(8.dp, artShape, clip = false, ambientColor = albumColor, spotColor = albumColor)
                .clip(artShape)
        ) {
            ParallaxArtwork(song, page, pagerState, Modifier.fillMaxSize())
            CardOverflowMenu(
                song = song,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onDownload = onDownload,
                containerColor = Color.Black.copy(alpha = 0.30f),
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)
        ) {
            CardInfo(
                song = song,
                isPlaying = isPlaying,
                titleColor = onContainer,
                subtitleColor = onContainer.copy(alpha = 0.72f),
                accent = accent,
                chipColor = accent.copy(alpha = 0.18f),
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = CircleShape,
                color = accent,
                contentColor = container,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun CardInfo(
    song: Song,
    isPlaying: Boolean,
    titleColor: Color,
    subtitleColor: Color,
    accent: Color,
    chipColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GoogleSansRounded,
                    color = titleColor
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (isPlaying) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = CircleShape, color = chipColor) {
                    EqualizerAnimation(
                        modifier = Modifier
                            .padding(horizontal = 7.dp, vertical = 5.dp)
                            .width(14.dp)
                            .height(12.dp),
                        isPlaying = true,
                        color = accent,
                        barCount = 3
                    )
                }
            }
        }
        Text(
            text = song.displayArtist,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = subtitleColor
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CardOverflowMenu(
    song: Song,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(shape = CircleShape, color = containerColor, contentColor = contentColor, modifier = Modifier.size(34.dp)) {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Options", modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(16.dp)) {
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                onClick = { expanded = false; onPlayNext(song) }
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                onClick = { expanded = false; onAddToQueue(song) }
            )
            DropdownMenuItem(
                text = { Text("Download") },
                leadingIcon = { Icon(Icons.Rounded.Download, null) },
                onClick = { expanded = false; onDownload(song) }
            )
        }
    }
}
