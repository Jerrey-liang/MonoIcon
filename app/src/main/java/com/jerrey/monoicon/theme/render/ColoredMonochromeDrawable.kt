package com.jerrey.monoicon.theme.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.content.res.ColorStateList

/**
 * A [Drawable] that renders an alpha-mask [Bitmap] filled with a
 * fixed ARGB color (Phase 6.0 / 6.3).
 *
 * ## Rendering (pixel-bake)
 * Each pixel's RGB is replaced with [color] while alpha is kept
 * from the mask. The tinted bitmap is drawn directly.
 *
 * ## [ConstantState] support
 * HyperOS [FolderPreviewIconView.refreshIconDrawable] copies the
 * drawable via [getConstantState] → [newDrawable]. This drawable
 * provides a full [ConstantState] so folder icon copies are
 * correctly cloned rather than producing null → invisible icons.
 *
 * ## Launcher tint resistance
 * [setTintList] is intentionally a no-op.
 */
class ColoredMonochromeDrawable(
    mask: Bitmap,
    color: Int,
) : Drawable() {

    /** The source mask (retained for ConstantState copy). */
    private val sourceMask: Bitmap = mask
    private val sourceColor: Int = color

    /** Pre-tinted bitmap — mask alpha + color RGB. */
    private val bitmap: Bitmap
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect: Rect

    init {
        val w = mask.width
        val h = mask.height
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)

        // Bake color into RGB channels, preserve mask alpha
        val rgb = color and 0x00FFFFFF
        for (i in pixels.indices) {
            val a = (pixels[i] shr 24) and 0xFF
            if (a > 0) {
                pixels[i] = (a shl 24) or rgb
            } else {
                pixels[i] = 0x00000000
            }
        }

        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        srcRect = Rect(0, 0, w, h)
    }

    override fun draw(canvas: Canvas) {
        if (bitmap.isRecycled) return
        canvas.drawBitmap(bitmap, srcRect, bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    /**
     * Intentional no-op — the color is fixed at construction.
     * The launcher may try to tint this drawable after setIconDrawable;
     * silently ignore to preserve the app-specific color.
     */
    override fun setTintList(tint: ColorStateList?) {
        // no-op — Phase 6.0: launcher tint resistance
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // no-op — the baked color controls all rendering
    }

    override fun getIntrinsicWidth(): Int = bitmap.width

    override fun getIntrinsicHeight(): Int = bitmap.height

    override fun getConstantState(): ConstantState = ColoredMonoState(sourceMask, sourceColor)

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /**
     * [ConstantState] for [ColoredMonochromeDrawable].
     * [newDrawable] creates a fresh pre-tinted bitmap copy so each
     * cloned drawable is independent (same behavior as BitmapDrawable).
     */
    private class ColoredMonoState(
        private val mask: Bitmap,
        private val color: Int,
    ) : ConstantState() {
        override fun newDrawable(): Drawable = ColoredMonochromeDrawable(mask, color)
        override fun getChangingConfigurations(): Int = 0
    }
}
