package com.jerrey.monoicon.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import android.util.Log

/**
 * Renders [Drawable]s into raw ARGB_8888 [Bitmap]s and provides the Pixel
 * legacy-icon AdaptiveIcon wrapper. Mask generation itself lives in
 * [com.jerrey.monoicon.theme.mask.LabMonochromeExtractor].
 *
 * ## Supported render types
 * - [BitmapDrawable] — copies the inner bitmap directly.
 * - [VectorDrawable] — renders via a [Canvas].
 * - [AdaptiveIconDrawable] — renders the whole drawable.
 * - Any other [Drawable] — generic canvas render via [renderToBitmap].
 */
object DrawableConverter {

    private const val TAG = "MonoIcon.Convert"

    // Phase 2.5 性能优化：复用绘制对象，避免每次分配 Canvas/Bitmap 位图分配
    private val reusableCanvas = Canvas()

    /**
     * AOSP android15 `BaseIconFactory.LEGACY_ICON_SCALE`:
     * `0.7f * (1f / (1 + 2 * extraInsetFraction))` = 0.4667.
     * The effective legacy scale is the product of this constant and the
     * per-icon [com.jerrey.monoicon.theme.mask.IconNormalizerCompat] result.
     */
    private val aospLegacyIconScale: Float
        get() = 0.7f / ((AdaptiveIconDrawable.getExtraInsetFraction() * 2f) + 1f)

    /**
     * Wraps a legacy Drawable exactly like AOSP
     * `BaseIconFactory.wrapToAdaptiveIcon()` (android15-release):
     * white `ColorDrawable` background + the icon inset by
     * `IconNormalizer.getScale() * LEGACY_ICON_SCALE`. Shape detection is
     * disabled (the default factory configuration), so the legacy scale
     * branch always applies.
     *
     * The returned AdaptiveIconDrawable owns an isolated foreground wrapper;
     * the source Drawable is never used directly by the generated mask path.
     */
    fun wrapAospLegacyIcon(drawable: Drawable, iconBitmapSize: Int): AdaptiveIconDrawable? {
        return try {
            val source = cloneForPixelLegacy(drawable) ?: return null
            val normalized = com.jerrey.monoicon.theme.mask.IconNormalizerCompat.getScale(
                source, iconBitmapSize,
            )
            val scale = normalized * aospLegacyIconScale
            val foreground = wrapIntoSquareDrawable(source, scale)
            AdaptiveIconDrawable(ColorDrawable(android.graphics.Color.WHITE), foreground).apply {
                setBounds(0, 0, 1, 1)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "wrapAospLegacyIcon failed: ${t.message}", t)
            null
        }
    }

    /** AOSP's aspect-ratio-preserving square wrapper. */
    private fun wrapIntoSquareDrawable(drawable: Drawable, scale: Float): Drawable {
        val width = drawable.intrinsicWidth.toFloat()
        val height = drawable.intrinsicHeight.toFloat()
        val scaledWidth: Float
        val scaledHeight: Float
        if (height <= width || width <= 0f) {
            scaledWidth = scale
            scaledHeight = if (width <= height || height <= 0f) {
                scale
            } else {
                (height / width) * scale
            }
        } else {
            scaledWidth = (width / height) * scale
            scaledHeight = scale
        }
        val horizontal = ((1f - scaledWidth) / 2f).coerceAtLeast(0f)
        val vertical = ((1f - scaledHeight) / 2f).coerceAtLeast(0f)
        return InsetDrawable(drawable, horizontal, vertical, horizontal, vertical)
    }

    /**
     * Makes a private Drawable for the wrapper. ConstantState is preferred;
     * BitmapDrawable gets a copied bitmap when no state is available.
     */
    private fun cloneForPixelLegacy(drawable: Drawable): Drawable? {
        drawable.constantState?.let { return it.newDrawable().mutate() }
        if (drawable is BitmapDrawable) {
            val source = drawable.bitmap ?: return null
            val copy = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            synchronized(reusableCanvas) {
                reusableCanvas.setBitmap(copy)
                reusableCanvas.drawBitmap(source, 0f, 0f, null)
                reusableCanvas.setBitmap(null)
            }
            return BitmapDrawable(null, copy)
        }
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val oldBounds = Rect(drawable.bounds)
        return try {
            synchronized(reusableCanvas) {
                reusableCanvas.setBitmap(copy)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(reusableCanvas)
                reusableCanvas.setBitmap(null)
            }
            BitmapDrawable(null, copy)
        } catch (t: Throwable) {
            copy.recycle()
            null
        } finally {
            drawable.bounds = oldBounds
        }
    }

    /**
     * Renders [drawable] to a raw ARGB_8888 [Bitmap] **without** any mask
     * generation. The bitmap is then fed into
     * [com.jerrey.monoicon.theme.mask.LabMonochromeExtractor.extractFromBitmap].
     *
     * @param drawable The drawable to render.
     * @return A raw ARGB_8888 bitmap, or `null` on failure.
     */
    fun toRawBitmap(drawable: Drawable): Bitmap? {
        try {
            return when (drawable) {
                is BitmapDrawable -> copyBitmapDrawable(drawable)
                is VectorDrawable -> renderToBitmap(drawable)
                is AdaptiveIconDrawable -> renderToBitmap(drawable)
                else -> renderToBitmap(drawable)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "toRawBitmap failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Generic canvas render of any [Drawable] to a square-normalized
     * ARGB_8888 bitmap. Never throws — failures return `null`.
     *
     * @param drawable The drawable to render (any type).
     * @return The rendered bitmap, or `null` on failure.
     */
    fun renderToBitmap(drawable: Drawable): Bitmap? {
        return try {
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)

            // Square-normalize: apps can ship non-square adaptive icon layers
            // (e.g. bilibili background 108×132, foreground 108×108). Rendering
            // at that non-square intrinsic stretches the square foreground layer
            // into the taller rect (AdaptiveIconDrawable scales layers to fill
            // the bounds with unequal scale factors). Rendering at a square size
            // makes the scale factors equal so the foreground stays undistorted;
            // the background (usually a solid color) absorbs the non-uniform
            // scaling.
            val size = maxOf(width, height)

            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            synchronized(reusableCanvas) {
                reusableCanvas.setBitmap(bitmap)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(reusableCanvas)
                reusableCanvas.setBitmap(null)
            }
            bitmap
        } catch (e: Throwable) {
            Log.e(TAG, "renderToBitmap failed for ${drawable.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * Retrieves the native monochrome layer from an [AdaptiveIconDrawable]
     * via reflection.
     *
     * [AdaptiveIconDrawable.getMonochrome] is a hidden API (added in API 33);
     * since `minSdk` is 35 the platform always provides it. If reflection fails
     * (vendor ROM changed the member), returns `null` silently.
     *
     * @param drawable The adaptive icon to query.
     * @return The monochrome [Drawable], or `null` if unavailable.
     */
    fun getMonochromeLayer(drawable: AdaptiveIconDrawable): Drawable? {
        return try {
            val method = AdaptiveIconDrawable::class.java.getDeclaredMethod("getMonochrome")
            method.isAccessible = true
            method.invoke(drawable) as? Drawable
        } catch (t: Throwable) {
            Log.d(TAG, "getMonochromeLayer failed (no-op): ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * Renders an APK-provided monochrome layer with Pixel Launcher's native
     * inset compensation. AdaptiveIconDrawable expands its foreground by the
     * extra-inset fraction; Pixel applies the inverse inset before caching the
     * ALPHA_8 mask so the final glyph keeps the APK author's intended scale.
     */
    fun renderPixelNativeMonochrome(drawable: Drawable, size: Int): Bitmap? {
        return try {
            val targetSize = size.coerceAtLeast(1)
            val inset = InsetDrawable(
                drawable,
                -AdaptiveIconDrawable.getExtraInsetFraction(),
            )
            // Pixel draws the native monochrome Drawable straight into an
            // ALPHA_8 bitmap. This deliberately ignores the layer's RGB
            // channels: a black, opaque monochrome glyph is still a valid
            // mask and must not collapse to zero alpha.
            val raw = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ALPHA_8)
            synchronized(reusableCanvas) {
                reusableCanvas.setBitmap(raw)
                inset.setBounds(0, 0, targetSize, targetSize)
                inset.draw(reusableCanvas)
                reusableCanvas.setBitmap(null)
            }
            raw
        } catch (e: Throwable) {
            Log.e(TAG, "renderPixelNativeMonochrome failed: ${e.message}", e)
            null
        }
    }

    /**
     * Copies the inner bitmap of a [BitmapDrawable].
     *
     * Returns a mutable ARGB_8888 copy so later stages never mutate the
     * drawable's own bitmap. If the inner bitmap is null, returns null.
     */
    private fun copyBitmapDrawable(drawable: BitmapDrawable): Bitmap? {
        val inner = drawable.bitmap ?: return null
        // Copying into a fresh ARGB_8888 bitmap is safe even if `inner` is
        // immutable or shared with other drawables.
        val copy = Bitmap.createBitmap(inner.width, inner.height, Bitmap.Config.ARGB_8888)
        synchronized(reusableCanvas) {
            reusableCanvas.setBitmap(copy)
            reusableCanvas.drawBitmap(inner, 0f, 0f, null)
            reusableCanvas.setBitmap(null)
        }
        return copy
    }
}
