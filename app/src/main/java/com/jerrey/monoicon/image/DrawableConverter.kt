package com.jerrey.monoicon.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.util.Log

/**
 * Converts a [Drawable] into a monochrome silhouette [Bitmap].
 *
 * The output bitmap is **ARGB_8888**:
 * - `Alpha` derived from per-pixel luminance (dark → opaque, light → transparent)
 * - `RGB` filled black (0xFF000000)
 *
 * The `setIconDrawable` hook path (the actual Phase 2 entry point) has NO
 * launcher-applied black tint, so the mask must carry its own color.
 * A black silhouette on a transparent background is self-sufficient and
 * directly displayable by the launcher.
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

    // Phase 2.5 性能优化：复用绘制对象，避免每次分配 Canvas/Bitmap 位图分配
    private val reusableCanvas = Canvas()

    /**
     * Converts [drawable] into a monochrome silhouette [Bitmap], or returns
     * `null` if the drawable type is not (yet) supported or conversion fails.
     *
     * @param drawable The drawable to convert. Must not be recycled.
     * @return An ARGB_8888 black silhouette bitmap, or `null` on failure.
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

            // Phase 2.3b: 用亮度掩码（替代纯 alpha，避免不透明图标退化为实心方块）
            return toLuminanceMask(rendered)
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
        synchronized(reusableCanvas) {
            reusableCanvas.setBitmap(copy)
            reusableCanvas.drawBitmap(inner, 0f, 0f, null)
            reusableCanvas.setBitmap(null)
        }
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
        synchronized(reusableCanvas) {
            reusableCanvas.setBitmap(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(reusableCanvas)
            reusableCanvas.setBitmap(null)
        }

        return bitmap
    }

    /**
     * Derives a monochrome silhouette mask from [bitmap] using **luminance**.
     *
     * This is the Phase 2.3b strategy (user-approved, replacing the pure-alpha
     * approach which degenerated to a solid white square on opaque icons):
     *
     * - **Alpha** is derived from per-pixel luminance:
     *   - dark pixels (low luminance) → opaque (foreground shape kept)
     *   - light pixels (high luminance) → transparent (background dropped)
     * - **RGB** is filled black (0xFF000000), because the `setIconDrawable`
     *   hook path has NO launcher-applied black tint (unlike the
     *   `getMonochrome` path). The mask must therefore be self-sufficient.
     * - Pixels that were fully transparent in the source stay transparent.
     *
     * This is NOT a grayscale output — the result is a black silhouette whose
     * alpha defines the recognizable icon shape. It is a runtime verification
     * point: if some icons are inverted (dark background + light glyph), the
     * luminance direction may need adjustment per-icon.
     *
     * @param bitmap Input ARGB_8888 bitmap (not recycled).
     * @return A new ARGB_8888 black silhouette mask, alpha from luminance.
     */
    fun toLuminanceMask(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val a = (color ushr 24) and 0xFF
            if (a == 0) {
                // 源像素完全透明 → 保持透明
                pixels[i] = 0x00000000
                continue
            }

            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF

            // 亮度 (Rec. 601 系数)，0-255
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

            // 深色 → 不透明（前景），浅色 → 透明（背景）
            val luminanceAlpha = 255 - luminance

            // 与源 alpha 结合，尊重原始半透明
            val finalAlpha = luminanceAlpha * a / 255

            // RGB 填黑：setIconDrawable 路径无 launcher tint，需自带颜色
            pixels[i] = (finalAlpha shl 24) or 0x00000000
        }

        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }
}
