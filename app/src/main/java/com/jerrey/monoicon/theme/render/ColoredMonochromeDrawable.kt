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
 * fixed ARGB color (Phase 6.0).
 *
 * ## Rendering (pixel-bake)
 * Each pixel's RGB is replaced with [color] while alpha is kept
 * from the mask. This is done once at construction by copying the
 * pixel array — the resulting tinted bitmap is drawn directly with
 * no per-frame PorterDuff overhead.
 *
 * ## Launcher tint resistance
 * The HyperOS launcher may call [setTint] / [setTintList] after
 * [setIconDrawable]. This drawable ignores those calls — the color
 * baked into the bitmap at construction time is always rendered.
 *
 * ## Bitmap ownership
 * The tinted bitmap is a **new** [Bitmap] created in the constructor.
 * The caller's mask bitmap is NOT retained.
 */
class ColoredMonochromeDrawable(
    mask: Bitmap,
    color: Int,
) : Drawable() {

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

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
