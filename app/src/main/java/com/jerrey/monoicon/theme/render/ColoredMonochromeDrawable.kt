package com.jerrey.monoicon.theme.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.content.res.ColorStateList
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine

/**
 * A [Drawable] that renders an alpha-mask [Bitmap] with the Pixel
 * Launcher themed-icon structure (Phase 6.0 / 6.6):
 *
 * - **Background plate** ([plateColor] = Pixel's
 *   `themed_icon_background_color`): a solid AdaptiveIcon background.
 * - **Plate mask**: the monochrome mask filled with the plate color using
 *   Pixel's `SRC` blend mode.
 * - **Glyph** ([color] = Pixel's `themed_icon_color`): the same mask filled
 *   with the glyph color using `SRC_IN`.
 *
 * When [plateColor] is transparent (engine unavailable), only the glyph
 * is drawn at full bounds (the pre-6.6 behavior).
 *
 * The drawable is built as `AdaptiveIconDrawable(ColorDrawable(bg),
 * LayerDrawable(mask×bg, mask×fg))`, matching Pixel's themed adaptive icon
 * path. The HyperOS hooks and animation compatibility paths still receive
 * this outer Drawable type.
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
    plateColor: Int = 0,
) : Drawable() {

    /** The source mask (retained for ConstantState copy). */
    private val sourceMask: Bitmap = mask
    private val sourceColor: Int = color
    private val sourcePlateColor: Int = plateColor

    @Volatile private var currentColor: Int = color
    @Volatile private var currentPlateColor: Int = plateColor
    private var alphaValue: Int = 255
    private var composite: AdaptiveIconDrawable

    init {
        composite = buildComposite(color, plateColor)
        composite.alpha = alphaValue
        composite.bounds = Rect(0, 0, mask.width, mask.height)
    }

    override fun draw(canvas: Canvas) {
        if (sourceMask.isRecycled) return
        refreshDynamicColors()
        val target = bounds
        if (target.width() <= 0 || target.height() <= 0) return
        composite.bounds = target
        composite.draw(canvas)
    }

    private fun refreshDynamicColors() {
        if (sourceColor == 0 || sourcePlateColor == 0) return
        val colors = try { PixelMonetColorEngine.getIconColors() } catch (_: Throwable) { return }
        if (colors.foreground == currentColor && colors.background == currentPlateColor) return
        synchronized(this) {
            if (colors.foreground == currentColor && colors.background == currentPlateColor) return
            currentColor = colors.foreground
            currentPlateColor = colors.background
            composite = buildComposite(currentColor, currentPlateColor).also {
                it.alpha = alphaValue
                it.bounds = bounds
            }
        }
    }

    private fun buildComposite(foregroundColor: Int, backgroundColor: Int): AdaptiveIconDrawable {
        // Pixel applies SRC_IN to the AdaptiveIcon background as well.  The
        // outer adaptive shape therefore participates in the same glyph tint
        // pipeline as the two monochrome foreground layers.
        val plate = ColorDrawable(backgroundColor).apply {
            colorFilter = BlendModeColorFilter(foregroundColor, BlendMode.SRC_IN)
        }
        val plateMask = ScaledMonoDrawable(sourceMask).apply {
            colorFilter = BlendModeColorFilter(backgroundColor, BlendMode.SRC)
        }
        val glyphMask = ScaledMonoDrawable(sourceMask).apply {
            colorFilter = BlendModeColorFilter(foregroundColor, BlendMode.SRC_IN)
        }
        return AdaptiveIconDrawable(plate, LayerDrawable(arrayOf(plateMask, glyphMask)))
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        composite.bounds = bounds
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha.coerceIn(0, 255)
        composite.alpha = alphaValue
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

    override fun getIntrinsicWidth(): Int = sourceMask.width

    override fun getIntrinsicHeight(): Int = sourceMask.height

    override fun getConstantState(): ConstantState =
        ColoredMonoState(sourceMask, sourceColor, sourcePlateColor)

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** Equivalent to Pixel Launcher's ScaledMonoDrawable. */
    private class ScaledMonoDrawable(private val bitmap: Bitmap) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val scale = 1.0f / ((AdaptiveIconDrawable.getExtraInsetFraction() * 2f) + 1f)

        override fun draw(canvas: Canvas) {
            if (bitmap.isRecycled) return
            val target = bounds
            if (target.width() <= 0 || target.height() <= 0) return
            val save = canvas.save()
            canvas.scale(scale, scale, target.exactCenterX(), target.exactCenterY())
            canvas.drawBitmap(bitmap, null, target, paint)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        override fun getColorFilter(): ColorFilter? = paint.colorFilter
        override fun getAlpha(): Int = paint.alpha
        override fun getIntrinsicWidth(): Int = bitmap.width
        override fun getIntrinsicHeight(): Int = bitmap.height
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * [ConstantState] for [ColoredMonochromeDrawable].
     * [newDrawable] creates a fresh pre-tinted bitmap copy so each
     * cloned drawable is independent (same behavior as BitmapDrawable).
     */
    private class ColoredMonoState(
        private val mask: Bitmap,
        private val color: Int,
        private val plateColor: Int,
    ) : ConstantState() {
        override fun newDrawable(): Drawable = ColoredMonochromeDrawable(mask, color, plateColor)
        override fun getChangingConfigurations(): Int = 0
    }
}
