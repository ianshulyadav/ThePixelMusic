package com.unshoo.pixelmusic.presentation.components

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unshoo.pixelmusic.data.preferences.dataStore
import com.unshoo.pixelmusic.ui.theme.findActivity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** UI-only preference keys stored in the shared "settings" DataStore. */
object UiEffectPreferenceKeys {
    /** Frosted, dynamic-colour tinted backdrop blur for the bottom navigation bar. */
    val NAV_BAR_FROSTED_BLUR: Preferences.Key<Boolean> = booleanPreferencesKey("nav_bar_frosted_blur")

    /** Hide the system bars while the full player is expanded. */
    val FULL_PLAYER_FULLSCREEN: Preferences.Key<Boolean> = booleanPreferencesKey("full_player_fullscreen")

    /** Now Playing layout: false = Classic (unified sheet player, default), true = Expressive. */
    val NOW_PLAYING_EXPRESSIVE_LAYOUT: Preferences.Key<Boolean> = booleanPreferencesKey("now_playing_expressive_layout")
}

@Composable
fun rememberBooleanPreference(
    context: Context,
    key: Preferences.Key<Boolean>,
    default: Boolean
): State<Boolean> {
    val flow = remember(context, key, default) {
        context.dataStore.data.map { it[key] ?: default }.distinctUntilChanged()
    }
    return flow.collectAsStateWithLifecycle(initialValue = default)
}

suspend fun Context.setBooleanPreference(key: Preferences.Key<Boolean>, value: Boolean) {
    dataStore.edit { it[key] = value }
}

/**
 * Hides the status bar while [enabled] (transient bars on swipe) and restores
 * them when disabled or when leaving composition.
 */
@Composable
fun FullscreenSystemBarsEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        val window = view.context.findActivity()?.window
        if (!enabled || window == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Status bar only: hiding the navigation bar changes the bottom inset, which resizes
        // the player sheet's container mid-animation and can leave the sheet stuck half-open.
        controller.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

/**
 * A recorded snapshot of some content (usually the main screen) that floating chrome such as
 * the navigation bar can draw blurred behind itself.
 *
 * The source content is recorded into a [GraphicsLayer] (a RenderNode); consumers draw that
 * same node, so they always show the latest frame without re-recording themselves.
 */
@Stable
class BackdropSource internal constructor(val layer: GraphicsLayer) {
    /** Snapshot state so consumers' draw passes re-run whenever the source moves or re-attaches. */
    internal var positionInRoot by androidx.compose.runtime.mutableStateOf<Offset?>(null)
}

val LocalBackdropSource = staticCompositionLocalOf<BackdropSource?> { null }

/** Height of the bottom strip captured for frosted chrome (nav bar + search button + margin). */
private val BackdropCaptureHeight = 220.dp

@Composable
fun rememberBackdropSource(): BackdropSource {
    val layer = rememberGraphicsLayer()
    return remember(layer) { BackdropSource(layer) }
}

/** Records this node's content into [source] while still drawing it normally. */
fun Modifier.backdropSource(source: BackdropSource?, enabled: Boolean = true): Modifier {
    if (source == null || !enabled) return this
    return this
        .onGloballyPositioned { source.positionInRoot = it.positionInRoot() }
        .drawWithContent {
            // Only the strip behind the bottom chrome is ever sampled, so record just that:
            // everything above is clipped away and HWUI skips re-drawing it a second time.
            // (Recording the whole screen every frame doubled the draw work while scrolling.)
            val captureTop = (size.height - BackdropCaptureHeight.toPx()).coerceAtLeast(0f)
            source.layer.record {
                clipRect(top = captureTop) {
                    this@drawWithContent.drawContent()
                }
            }
            drawContent()
        }
}

/**
 * Records this node's whole content into [source] (no strip clipping). Used for the player
 * cover art, which only redraws when the artwork changes, so recording is effectively free.
 */
fun Modifier.fullBackdropSource(source: BackdropSource?): Modifier {
    if (source == null) return this
    return this
        .onGloballyPositioned { source.positionInRoot = it.positionInRoot() }
        .drawWithContent {
            source.layer.record { this@drawWithContent.drawContent() }
            drawContent()
        }
}

/** Cover-art backdrop that the full player's top controls blur behind themselves. */
val LocalCoverBackdropSource = staticCompositionLocalOf<BackdropSource?> { null }

val FrostedBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Material 3 Expressive style frosted glass: draws the [source] content found behind this node,
 * blurred, then a translucent dynamic-colour [tint] on top. Children are drawn unblurred.
 * Clip the node to its shape (e.g. Surface or Modifier.clip) for rounded glass.
 */
@Composable
fun Modifier.frostedBackdrop(
    source: BackdropSource?,
    tint: Color,
    blurRadius: Dp = 44.dp,
    enabled: Boolean = true
): Modifier {
    if (!enabled) return this
    val blurLayer = rememberGraphicsLayer()
    val holder = remember { CoordinatesHolder() }
    return this
        .onGloballyPositioned { holder.positionInRoot = it.positionInRoot() }
        .drawBehind {
            val src = source
            // Both reads are snapshot state: any move/re-attach of either side redraws the glass.
            val srcPos = src?.positionInRoot
            val ownPos = holder.positionInRoot
            if (FrostedBlurSupported && src != null && srcPos != null && ownPos != null) {
                // Where this node sits inside the recorded source content.
                val offset: Offset = ownPos - srcPos
                // Blur a quarter-resolution copy and scale it back up: a heavy frosted blur looks
                // identical, but the GPU blurs 1/16 of the pixels (full-res blur cost frames).
                val down = 4f
                val radiusPx = blurRadius.toPx() / down
                if (holder.radiusPx != radiusPx || holder.effect == null) {
                    holder.radiusPx = radiusPx
                    // Frosted, not vivid: heavy blur chained with desaturation so bright
                    // artwork behind the bar can't glow through in saturated colour.
                    holder.effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val desaturate = android.graphics.ColorMatrix().apply { setSaturation(0.35f) }
                        android.graphics.RenderEffect.createChainEffect(
                            android.graphics.RenderEffect.createColorFilterEffect(
                                android.graphics.ColorMatrixColorFilter(desaturate)
                            ),
                            android.graphics.RenderEffect.createBlurEffect(
                                radiusPx, radiusPx, android.graphics.Shader.TileMode.CLAMP
                            )
                        ).asComposeRenderEffect()
                    } else {
                        BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
                    }
                }
                blurLayer.renderEffect = holder.effect
                blurLayer.clip = true
                val smallSize = androidx.compose.ui.unit.IntSize(
                    kotlin.math.ceil(size.width / down).toInt().coerceAtLeast(1),
                    kotlin.math.ceil(size.height / down).toInt().coerceAtLeast(1)
                )
                blurLayer.record(size = smallSize) {
                    scale(1f / down, 1f / down, pivot = Offset.Zero) {
                        translate(-offset.x, -offset.y) {
                            drawLayer(src.layer)
                        }
                    }
                }
                scale(down, down, pivot = Offset.Zero) {
                    drawLayer(blurLayer)
                }
                drawRect(tint)
            } else {
                // No blur available: fall back to a nearly opaque tint so content stays legible.
                drawRect(tint.copy(alpha = maxOf(tint.alpha, 0.94f)))
            }
        }
}

private class CoordinatesHolder {
    var positionInRoot by androidx.compose.runtime.mutableStateOf<Offset?>(null)
    var radiusPx: Float = -1f
    var effect: RenderEffect? = null
}

/** Convenience entry points used from MainActivity (keeps its import list untouched). */
object PixelGlass {
    @Composable
    fun Modifier.navGlass(source: BackdropSource?): Modifier =
        frostedBackdrop(source = source, tint = frostedGlassTint())

    fun Modifier.navGlassSource(source: BackdropSource?, enabled: Boolean): Modifier =
        backdropSource(source, enabled)
}
