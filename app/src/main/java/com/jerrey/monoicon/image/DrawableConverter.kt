package com.jerrey.monoicon.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.util.Log

/**
 * Converts a [Drawable] into a monochrome alpha-mask [Bitmap].
 *
 * The output bitmap is **ARGB_8888** with:
 * - `RGB = white (0xFFFFFFFF)`
 * - `Alpha = original drawable's alpha channel`
 *
 * The launcher tints the final drawable black
 * ([android.graphics.drawable.Drawable.setTint] with 0xFF000000),
 * so only the alpha channel determines the final icon shape.
 * Filling RGB with white is the most compatible baseline; it will be
 * overwritten by the launcher's black tint regardless.
 *
 * ## Supported types (Phase 2.1 scope)
 * - [AdaptiveIconDrawable] — renders the **whole** drawable
 *   (background + foreground + mask). This is intentional: some apps put
 *   meaningful visuals in the background layer, so foreground-only is a
 *   runtime-verified fallback, not the initial choice.
 * - [BitmapDrawable] — copies the inner bitmap directly.
 * - [VectorDrawable] — renders via a [Canvas].
 *
 * Any other [Drawable] subtype returns `null` (caller falls back to the
 * original icon). Support will be extended gradually after the module
 * is confirmed working.
 */
object DrawableConverter {

    private const val TAG = "MonoIcon.Convert"

    /**
     * Converts [drawable] into a monochrome alpha-mask [Bitmap], or returns
     * `null` if the drawable type is not (yet) supported or conversion fails.
     *
     * The returned bitmap is ARGB_8888, RGB = white, alpha preserved.
     *
     * @param drawable The drawable to convert. Must not be recycled.
     * @return An ARGB_8888 alpha-mask bitmap, or `null` on failure.
     */
    fun toBitmap(drawable: Drawable): Bitmap? {
        try {
            val rendered = when (drawable) {
                is BitmapDrawable -> copyBitmapDrawable(drawable)
                is VectorDrawable -> renderToBitmap(drawable)
                is AdaptiveIconDrawable ->
                    // Initial scope: render the whole adaptive icon. The mask
                    // is applied by AdaptiveIconDrawable itself during draw().
                    // Runtime-verification point: if whole-render produces
                    // wrong masks, switch to foreground-only rendering.
                    renderToBitmap(drawable)
                else -> {
                    Log.d(TAG, "Unsupported drawable type: ${drawable.javaClass.simpleName}")
                    null
                }
            } ?: return null

            return toWhiteAlphaMask(rendered)
        } catch (e: Throwable) {
            Log.e(TAG, "toBitmap failed for ${drawable.javaClass.simpleName}: ${e.message}", e)
            return null
        }
    }

    // ── Per-type converters ─────────────────────────────────────────

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
        val canvas = Canvas(copy)
        canvas.drawBitmap(inner, 0f, 0f, null)
        return copy
    }

    /**
     * Renders [drawable] onto a fresh ARGB_8888 bitmap via a [Canvas].
     *
     * The drawable is laid out to its full intrinsic bounds and drawn.
     * Alpha is naturally preserved by the draw operation.
     */
    private fun renderToBitmap(drawable: Drawable): Bitmap {
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)

        return bitmap
    }

    /**
     * Rewrites every pixel of [bitmap] to RGB = white while preserving
     * each pixel's alpha channel.
     *
     * This is the "alpha mask" transformation: shape (alpha) is kept,
     * color is neutralized. It is NOT grayscale — color information is
     * discarded in favor of a pure alpha-defined silhouette, which is
     * exactly what the launcher's monochrome path consumes.
     *
     * @param bitmap Input ARGB_8888 bitmap (not recycled).
     * @return A new ARGB_8888 bitmap, RGB = 0xFFFFFF, alpha preserved.
     */
    fun toWhiteAlphaMask(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val a = (pixels[i] ushr 24) and 0xFF
            pixels[i] = (a shl 24) or 0x00FFFFFF
        }

        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }
}
