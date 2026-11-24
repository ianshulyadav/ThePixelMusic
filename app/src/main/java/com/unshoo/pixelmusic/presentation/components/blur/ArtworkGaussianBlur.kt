package com.unshoo.pixelmusic.presentation.components.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Optimised Gaussian blur for full-screen artwork backdrops (full player sheet, lyrics sheet).
 *
 * Why not `Modifier.blur(60.dp)`: a RenderEffect blur runs on the GPU over the *rendered layer*,
 * i.e. the whole screen at device resolution, and it is re-executed every frame the layer is
 * invalidated. The player background changes alpha on every frame of the sheet drag, so the old
 * approach blurred ~2.5M pixels per frame (dropped frames at 120Hz), and it simply did nothing on
 * Android < 12.
 *
 * Here the artwork is decoded at [SOURCE_SIZE_PX] (64px), blurred once with a true separable
 * Gaussian kernel on a background thread, cached, and then drawn upscaled with bilinear filtering.
 * Upscaling an already-blurred 64px image is visually indistinguishable from a large-radius blur
 * of the full-resolution image (all fine detail is gone either way), while per-frame cost drops
 * to a single textured quad. Works on every API level.
 */
object ArtworkGaussianBlur {

    /** Edge length the artwork is decoded/blurred at. 64px upscaled == ~60-80dp blur on phones. */
    const val SOURCE_SIZE_PX = 64

    /** Default kernel sigma in source pixels; ~5px at 64px is a soft, colour-rich "atmosphere". */
    const val DEFAULT_SIGMA = 5f

    // ~16KB per entry (64*64*4); 24 entries covers the queue neighbourhood + lyrics sheet.
    private val cache = LruCache<String, ImageBitmap>(24)

    private fun key(model: String, sigma: Float) = "$model#${(sigma * 10).roundToInt()}"

    fun cached(model: String?, sigma: Float = DEFAULT_SIGMA): ImageBitmap? =
        model?.let { cache.get(key(it, sigma)) }

    /**
     * Decodes [model] small, blurs it and caches the result. Safe to call repeatedly (e.g. to
     * pre-warm the next track); returns the cached bitmap immediately on a hit.
     */
    suspend fun load(context: Context, model: String, sigma: Float = DEFAULT_SIGMA): ImageBitmap? {
        cache.get(key(model, sigma))?.let { return it }
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(SOURCE_SIZE_PX * 2, SOURCE_SIZE_PX * 2)
            // Software bitmap: we need pixel access, and a hardware bitmap would force a GPU readback.
            .allowHardware(false)
            .memoryCacheKey("blur_src:$model")
            .build()
        val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable ?: return null
        val source = (drawable as? BitmapDrawable)?.bitmap ?: return null
        val blurred = withContext(Dispatchers.Default) { blur(source, sigma) } ?: return null
        val image = blurred.asImageBitmap().also { it.prepareToDraw() }
        cache.put(key(model, sigma), image)
        return image
    }

    /** Center-crops [source] to a square of [SOURCE_SIZE_PX] and applies a separable Gaussian. */
    fun blur(source: Bitmap, sigma: Float): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null
        val size = SOURCE_SIZE_PX
        val side = min(source.width, source.height)
        val cropped = Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side,
            side
        )
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        gaussianBlurArgb(pixels, size, size, sigma)
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).apply {
            if (pixels.all { it ushr 24 == 255 }) setHasAlpha(false)
        }
    }

    /**
     * In-place separable Gaussian blur of packed ARGB pixels (two 1D passes, clamp-to-edge).
     * Cost is O(w * h * kernel) — ~0.2M multiply-adds for a 64px image, well under 1ms.
     */
    fun gaussianBlurArgb(pixels: IntArray, width: Int, height: Int, sigma: Float) {
        if (sigma <= 0f || width <= 0 || height <= 0) return
        val kernel = gaussianKernel(sigma)
        val radius = kernel.size / 2
        val tmp = IntArray(pixels.size)
        // Horizontal pass: pixels -> tmp
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in -radius..radius) {
                    val sx = min(width - 1, max(0, x + k))
                    val c = pixels[row + sx]
                    val w = kernel[k + radius]
                    a += (c ushr 24) * w
                    r += ((c shr 16) and 0xFF) * w
                    g += ((c shr 8) and 0xFF) * w
                    b += (c and 0xFF) * w
                }
                tmp[row + x] = pack(a, r, g, b)
            }
        }
        // Vertical pass: tmp -> pixels
        for (x in 0 until width) {
            for (y in 0 until height) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in -radius..radius) {
                    val sy = min(height - 1, max(0, y + k))
                    val c = tmp[sy * width + x]
                    val w = kernel[k + radius]
                    a += (c ushr 24) * w
                    r += ((c shr 16) and 0xFF) * w
                    g += ((c shr 8) and 0xFF) * w
                    b += (c and 0xFF) * w
                }
                pixels[y * width + x] = pack(a, r, g, b)
            }
        }
    }

    /** Normalised 1D Gaussian kernel covering ±3σ. */
    fun gaussianKernel(sigma: Float): FloatArray {
        val radius = max(1, (sigma * 3f).roundToInt())
        val kernel = FloatArray(radius * 2 + 1)
        val twoSigmaSq = 2f * sigma * sigma
        var sum = 0f
        for (i in -radius..radius) {
            val v = exp(-(i * i) / twoSigmaSq)
            kernel[i + radius] = v
            sum += v
        }
        for (i in kernel.indices) kernel[i] /= sum
        return kernel
    }

    private fun pack(a: Float, r: Float, g: Float, b: Float): Int {
        fun ch(v: Float) = v.roundToInt().coerceIn(0, 255)
        return (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}

/**
 * Draws a pre-blurred bitmap center-cropped to fill the layer with bilinear filtering.
 * [alphaProvider] is read in the draw phase only, so animating it never recomposes.
 */
@Composable
fun BlurredArtworkImage(
    image: ImageBitmap,
    modifier: Modifier = Modifier,
    alphaProvider: () -> Float = { 1f },
) {
    Canvas(modifier) { drawCropped(image, alphaProvider()) }
}

private fun DrawScope.drawCropped(image: ImageBitmap, alpha: Float) {
    if (alpha <= 0f || size.width <= 0f || size.height <= 0f) return
    val srcW = image.width.toFloat()
    val srcH = image.height.toFloat()
    // Center-crop: pick the source rect with the destination's aspect ratio.
    val dstRatio = size.width / size.height
    val (cropW, cropH) = if (srcW / srcH > dstRatio) (srcH * dstRatio) to srcH else srcW to (srcW / dstRatio)
    drawImage(
        image = image,
        srcOffset = IntOffset(((srcW - cropW) / 2f).roundToInt(), ((srcH - cropH) / 2f).roundToInt()),
        srcSize = IntSize(cropW.roundToInt().coerceAtLeast(1), cropH.roundToInt().coerceAtLeast(1)),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha.coerceIn(0f, 1f),
        filterQuality = FilterQuality.Low, // bilinear: smooth upscale, no extra blur pass
    )
}
