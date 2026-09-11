package com.jerrey.monoicon.theme.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.content.res.ColorStateList
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine

/**
 * A [Drawable] that renders an alpha-mask [Bitmap] with the AOSP
 * `ThemedIconDrawable` structure (Phase 7):
 *
 * - **Background plate** ([plateColor] = AOSP
 *   `themed_icon_background_color`): a solid AdaptiveIcon background
 *   filling the whole icon shape.
 * - **Glyph** ([color] = AOSP `themed_icon_color`): the monochrome mask
 *   drawn on top with `SRC_IN`, i.e. `fg·mask + bg·(1−mask)`. The mask is
 *   pre-scaled by `1/(1+2·extraInsetFraction)` around the center to cancel
 *   the AdaptiveIconDrawable foreground viewport expansion (AOSP's
 *   ThemedIconDrawable has no such expansion because it is a plain
 *   FastBitmapDrawable).
 *
 * AOSP draws the plate from a white shadow layer; this module has no
 * shadow generator, so a `ColorDrawable` is the equivalent flat plate.
 * The Pixel-style 2/3 `ScaledMonoDrawable` layers are intentionally gone.
 *
 * When [plateColor] is transparent (engine unavailable), only the glyph
 * is drawn at full bounds (the pre-6.6 behavior).
 *
 * The drawable is built as `AdaptiveIconDrawable(ColorDrawable(bg), glyph)`
 * so the HyperOS hooks and animation compatibility paths still receive
 * an AdaptiveIconDrawable outer type.
 *
 * ## [ConstantState] support
 * HyperOS [FolderPreviewIconView.refreshIconDrawable] copies the
 * drawable via [getConstantState] → [newDrawable]. This drawable
 * provides a full [ConstantState] so folder icon copies are
 * correctly cloned rather than producing null → invisible icons.
 *
 * ## Launcher tint resistance
 * [setTintList] is intentionally a no-op.
 *
 * ## Shape (Phase 9)
 * HyperOS ships a **square** framework `config_icon_mask`
 * (`M50,0L100,0 100,100 0,100 0,0z`), so the shape of our composite is not
 * decided by MIUI: with [IconShape.CIRCLE] the drawable clips itself to the
 * AOSP-equivalent circle ([CircleIconShape]) at draw time, **after** the AOSP
 * whole-icon normalization (scale ≈ 0.913 + transparent padding), matching
 * `BaseIconFactory.drawIconBitmap()` on a Pixel. The geometry is derived from
 * the current bounds, so it survives rescaling and the folder preview clones
 * created through [getConstantState].
 */
class ColoredMonochromeDrawable(
    mask: Bitmap,
    color: Int,
    plateColor: Int = 0,
    shape: IconShape = IconShape.SQUIRCLE,
) : Drawable() {

    /** The source mask (retained for ConstantState copy). */
    private val sourceMask: Bitmap = mask
    private val sourceColor: Int = color
    private val sourcePlateColor: Int = plateColor
    private val sourceShape: IconShape = shape

    @Volatile private var currentColor: Int = color
    @Volatile private var currentPlateColor: Int = plateColor
    private var alphaValue: Int = 255
    private var composite: AdaptiveIconDrawable

    /** Cached circle clip for the current bounds (null = no clipping). */
    @Volatile private var clipPath: Path? = null
    @Volatile private var clipBounds: Rect? = null

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
        val content = contentBoundsFor(target)
        composite.bounds = content
        val clip = clipPathFor(target, content)
        if (clip == null) {
            composite.draw(canvas)
        } else {
            val save = canvas.save()
            try {
                canvas.clipPath(clip)
                composite.draw(canvas)
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }

    /**
     * Bounds the composite is drawn into.
     *
     * With [IconShape.CIRCLE] this is the AOSP-normalized inset rect
     * (`BaseIconFactory.drawIconBitmap()`): the whole icon — plate included —
     * is scaled to `sqrt(375/576 / maskArea)` ≈ 0.913 and centred, so the ring
     * around it stays **transparent** (wallpaper shows through) exactly like a
     * Pixel icon. [IconShape.SQUIRCLE] keeps the previous full-bleed behavior.
     */
    private fun contentBoundsFor(target: Rect): Rect =
        if (sourceShape == IconShape.CIRCLE) CircleIconShape.insetBounds(target) else target

    /**
     * Circle clip for the circle shape (applied to the already inset
     * [content] rect, so the visible circle matches the AOSP diameter) or null
     * for [IconShape.SQUIRCLE]. Recomputed only when the bounds change.
     */
    private fun clipPathFor(target: Rect, content: Rect): Path? {
        val cachedBounds = clipBounds
        if (cachedBounds != null && cachedBounds == target) return clipPath
        val computed = CircleIconShape.clipPath(sourceShape, content)
        clipPath = computed
        clipBounds = Rect(target)
        return computed
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
        // AOSP ThemedIconDrawable.drawInternal(): the background plate is
        // drawn first and the mono mask is tinted with the glyph color via
        // SRC_IN on top of it. No SRC plate-mask layer, no center scaling.
        val plate = ColorDrawable(backgroundColor)
        val glyph = MonoGlyphDrawable(sourceMask).apply {
            colorFilter = BlendModeColorFilter(foregroundColor, BlendMode.SRC_IN)
        }
        return AdaptiveIconDrawable(plate, glyph)
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
        ColoredMonoState(sourceMask, sourceColor, sourcePlateColor, sourceShape)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /**
     * Mono glyph drawable: the mask bitmap drawn with the viewport
     * compensation scale.
     *
     * AOSP's ThemedIconDrawable is a FastBitmapDrawable drawn directly, so it
     * renders the mask at full bounds. Our container must stay an
     * AdaptiveIconDrawable for the HyperOS hooks, and AdaptiveIconDrawable
     * expands its foreground canvas by (1 + 2·extraInsetFraction) before
     * clipping back to the viewport. Drawing the mask scaled by
     * 1/(1 + 2·extraInsetFraction) around the center cancels that expansion
     * exactly, keeping the AOSP motif size.
     */
    private class MonoGlyphDrawable(private val bitmap: Bitmap) : Drawable() {
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

        @Suppress("OVERRIDE_DEPRECATION")
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
        private val shape: IconShape,
    ) : ConstantState() {
        override fun newDrawable(): Drawable = ColoredMonochromeDrawable(mask, color, plateColor, shape)
        override fun getChangingConfigurations(): Int = 0
    }
}
