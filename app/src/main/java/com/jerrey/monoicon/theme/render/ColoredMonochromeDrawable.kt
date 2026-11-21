package com.jerrey.monoicon.theme.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.content.res.ColorStateList
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import com.jerrey.monoicon.theme.mask.MaskBitmapIO

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
 * Explicit appearance controls draw the plate and glyph independently so the
 * framework's own adaptive mask cannot override the requested corner radius.
 * This also preserves glyph proportions in rectangular tiles. The legacy
 * square-shape constructor retains its AdaptiveIconDrawable composite.
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
class ColoredMonochromeDrawable private constructor(
    mask: Bitmap,
    color: Int,
    plateColor: Int,
    shape: IconShape,
    private val largeIconWidth: Int,
    private val largeIconHeight: Int,
    private var appearance: IconAppearance?,
    private val dynamicColors: Boolean,
    generatedBorderRatio: Float,
    private val adjustGeneratedBorder: Boolean,
    private val tonal: Boolean,
    private val identity: String?,
    generatedArtworkRadiusFraction: Float,
) : Drawable() {

    constructor(
        mask: Bitmap,
        color: Int,
        plateColor: Int = 0,
        shape: IconShape = IconShape.SQUIRCLE,
    ) : this(mask, color, plateColor, shape, 0, 0, null, true, 1f, false, false, null, 0f)

    constructor(
        mask: Bitmap,
        color: Int,
        plateColor: Int = 0,
        roundnessPercent: Int,
        sizePercent: Int,
        dynamicColors: Boolean = true,
        generatedBorderRatio: Float = 1f,
        adjustGeneratedBorder: Boolean = false,
        tonal: Boolean = false,
        identity: String? = null,
        generatedArtworkRadiusFraction: Float = 0f,
    ) : this(mask, color, plateColor,
        if (roundnessPercent >= 100) IconShape.CIRCLE else IconShape.SQUIRCLE,
        0, 0, IconAppearance(roundnessPercent, sizePercent), dynamicColors,
        generatedBorderRatio, adjustGeneratedBorder, tonal, identity, generatedArtworkRadiusFraction)

    /** The source mask (retained for ConstantState copy). */
    private val sourceMask: Bitmap = mask
    private val sourceColor: Int = color
    private val sourcePlateColor: Int = plateColor
    private val sourceShape: IconShape = shape

    @Volatile private var currentColor: Int = color
    @Volatile private var currentPlateColor: Int = plateColor
    @Volatile private var paletteObserved = false
    private var alphaValue: Int = 255
    private var composite: AdaptiveIconDrawable
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val plateRect = RectF()
    private val glyphRect = RectF()
    private val adjustedGlyphRect = RectF()
    private var generatedGlyphScale = 1f
    private val largeClip = Path()
    private var largeGeometryBounds: Rect? = null
    private var largeRadius = 0f
    private var glyphTint: Int? = null
    private var glyphPlateTint: Int? = null
    private var borderRatio = GeneratedIconBorder.normalizeRatio(generatedBorderRatio)
    private var artworkRadiusFraction = GeneratedArtworkShape.normalizeRadiusFraction(generatedArtworkRadiusFraction)
    private val artworkShape by lazy(LazyThreadSafetyMode.NONE) {
        if (adjustGeneratedBorder) GeneratedArtworkShape.fromAlpha(
            MaskBitmapIO.read(sourceMask), sourceMask.width, sourceMask.height,
        ) else null
    }
    private var roundedArtworkMask: Bitmap? = null
    private var roundedArtworkFootprint: GeneratedIconBorder? = null
    private var appliedArtworkRadiusFraction = 0f
    private val borderFootprint by lazy(LazyThreadSafetyMode.NONE) {
        if (adjustGeneratedBorder) {
            GeneratedIconBorder.fromAlpha(MaskBitmapIO.read(sourceMask), sourceMask.width, sourceMask.height)
        } else null
    }

    /** Cached circle clip for the current bounds (null = no clipping). */
    @Volatile private var clipPath: Path? = null
    @Volatile private var clipBounds: Rect? = null
    @Volatile private var contentBounds: Rect? = null

    init {
        composite = buildComposite(color, plateColor)
        composite.alpha = alphaValue
        composite.bounds = Rect(0, 0, mask.width, mask.height)
    }

    override fun draw(canvas: Canvas) {
        if (sourceMask.isRecycled) return
        refreshAppearance()
        refreshArtworkShape()
        refreshDynamicColors()
        val target = bounds
        if (target.width() <= 0 || target.height() <= 0) return
        if (appearance != null || largeIconWidth > 0 || target.width() != target.height()) {
            drawPlateAndGlyph(canvas, target)
            return
        }
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
     * Draw a centered, uniformly scaled glyph into the requested silhouette.
     * The adaptive composite's foreground expansion and reciprocal compensation
     * cancel to this same glyph size. Rendering directly also avoids imposing
     * the framework mask when a preview asks for a different corner radius.
     */
    private fun drawPlateAndGlyph(canvas: Canvas, target: Rect) {
        if (largeGeometryBounds != target) {
            val content = appearance?.contentBounds(target)
                ?: if (sourceShape == IconShape.CIRCLE) CircleIconShape.insetBounds(target) else target
            if (content.isEmpty) return
            plateRect.set(content)
            val shortSide = minOf(plateRect.width(), plateRect.height())
            largeRadius = appearance?.cornerRadius(plateRect.width(), plateRect.height())
                ?: shortSide * if (sourceShape == IconShape.CIRCLE) 0.5f else 0.25f
            val scale = minOf(plateRect.width() / sourceMask.width, plateRect.height() / sourceMask.height)
            val halfWidth = sourceMask.width * scale / 2f
            val halfHeight = sourceMask.height * scale / 2f
            glyphRect.set(
                plateRect.centerX() - halfWidth, plateRect.centerY() - halfHeight,
                plateRect.centerX() + halfWidth, plateRect.centerY() + halfHeight,
            )
            largeClip.rewind()
            largeClip.addRoundRect(plateRect, largeRadius, largeRadius, Path.Direction.CW)
            generatedGlyphScale = if (adjustGeneratedBorder) {
                (roundedArtworkFootprint ?: borderFootprint)?.glyphScale(
                    plateRect.width(), plateRect.height(), largeRadius, borderRatio,
                ) ?: 1f
            } else 1f
            adjustedGlyphRect.set(
                plateRect.centerX() - halfWidth * generatedGlyphScale,
                plateRect.centerY() - halfHeight * generatedGlyphScale,
                plateRect.centerX() + halfWidth * generatedGlyphScale,
                plateRect.centerY() + halfHeight * generatedGlyphScale,
            )
            largeGeometryBounds = Rect(target)
        }
        platePaint.color = currentPlateColor
        if (glyphTint != currentColor || glyphPlateTint != currentPlateColor) {
            glyphPaint.colorFilter = artworkFilter(currentColor, currentPlateColor)
            glyphTint = currentColor
            glyphPlateTint = currentPlateColor
        }
        glyphPaint.alpha = 255
        val adjustGlyph = generatedGlyphScale != 1f && Color.alpha(currentPlateColor) > 0
        val opaquePlate = Color.alpha(currentPlateColor) == 255
        // SRC_ATOP confines scaled artwork to the plate's existing alpha,
        // including its antialiased edge. No extra edge pixels can enlarge the
        // icon. The layer isolates this operation from the wallpaper underneath.
        // Drawable alpha fades the completed icon once. Applying it to both
        // source layers changes their overlap and makes border zoom affect
        // opacity during launcher/folder animations.
        val layer = when {
            alphaValue < 255 -> canvas.saveLayerAlpha(plateRect, alphaValue)
            opaquePlate || adjustGlyph -> canvas.saveLayer(plateRect, null)
            else -> -1
        }
        canvas.drawRoundRect(plateRect, largeRadius, largeRadius, platePaint)
        // Use the same layer and plate alpha at every border setting. Drawing
        // 100% with SRC_OVER could add glyph coverage to the antialiased edge,
        // while scaled artwork retained only the plate's coverage.
        glyphPaint.blendMode = if (opaquePlate || adjustGlyph) BlendMode.SRC_ATOP else BlendMode.SRC_OVER
        val save = canvas.save()
        try {
            if (!adjustGlyph && !opaquePlate) canvas.clipPath(largeClip)
            canvas.drawBitmap(roundedArtworkMask ?: sourceMask, null,
                if (adjustGlyph) adjustedGlyphRect else glyphRect, glyphPaint)
        } finally {
            canvas.restoreToCount(save)
            glyphPaint.blendMode = BlendMode.SRC_OVER
            if (layer >= 0) canvas.restoreToCount(layer)
        }
    }

    /** Preserve the tile's aspect ratio through launcher bitmap conversion and clones. */
    fun forLargeIcon(width: Int, height: Int): ColoredMonochromeDrawable {
        require(width > 0 && height > 0)
        return ColoredMonochromeDrawable(
            sourceMask, sourceColor, sourcePlateColor, sourceShape, width, height, appearance, dynamicColors,
            borderRatio, adjustGeneratedBorder, tonal, identity, artworkRadiusFraction,
        ).also {
            it.setBounds(0, 0, width, height)
            it.alpha = alphaValue
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
    private fun contentBoundsFor(target: Rect): Rect {
        if (appearance == null && sourceShape != IconShape.CIRCLE) return target
        if (clipBounds == target) contentBounds?.let { return it }
        return (appearance?.contentBounds(target) ?: CircleIconShape.insetBounds(target))
            .also { contentBounds = it }
    }

    /**
     * Circle clip for the circle shape (applied to the already inset
     * [content] rect, so the visible circle matches the AOSP diameter) or null
     * for [IconShape.SQUIRCLE]. Recomputed only when the bounds change.
     */
    private fun clipPathFor(target: Rect, content: Rect): Path? {
        val cachedBounds = clipBounds
        if (cachedBounds != null && cachedBounds == target) return clipPath
        val computed = appearance?.clipPath(content) ?: CircleIconShape.clipPath(sourceShape, content)
        clipPath = computed
        clipBounds = Rect(target)
        return computed
    }

    private fun refreshDynamicColors() {
        if (!dynamicColors) return
        if (sourceColor == 0 || sourcePlateColor == 0) return
        if (!paletteObserved) paletteObserved = LauncherPaletteSnapshot.observe(this)
        val colors = try {
            LauncherPaletteSnapshot.currentOrNull() ?: PixelMonetColorEngine.getIconColors()
        } catch (_: Throwable) { return }
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

    /** Production icons follow remote preferences; fixed previews keep their supplied values. */
    private fun refreshAppearance() {
        if (appearance == null || !dynamicColors) return
        val preferences = com.jerrey.monoicon.config.ConfigManager.getEffectiveIconStyle(identity)
        val updated = IconAppearance(preferences.roundnessPercent, preferences.sizePercent)
        val updatedBorder = if (adjustGeneratedBorder) GeneratedIconBorder.normalizeRatio(
            preferences.generatedBorderRatio,
        ) else borderRatio
        val updatedArtworkRadius = if (adjustGeneratedBorder) GeneratedArtworkShape.normalizeRadiusFraction(
            preferences.generatedArtworkRadiusFraction,
        ) else artworkRadiusFraction
        if (appearance == updated && borderRatio == updatedBorder && artworkRadiusFraction == updatedArtworkRadius) return
        appearance = updated
        borderRatio = updatedBorder
        artworkRadiusFraction = updatedArtworkRadius
        clipBounds = null
        contentBounds = null
        largeGeometryBounds = null
    }

    /** Shape copies belong to this drawable; cached source masks remain untouched. */
    private fun refreshArtworkShape() {
        if (!adjustGeneratedBorder || artworkRadiusFraction == appliedArtworkRadiusFraction) return
        val alpha = if (artworkRadiusFraction > 0f) artworkShape?.roundedAlpha(artworkRadiusFraction) else null
        val previous = roundedArtworkMask
        roundedArtworkMask = if (alpha == null) null else if (sourceMask.config == Bitmap.Config.ALPHA_8) {
            MaskBitmapIO.create(alpha, sourceMask.width, sourceMask.height)
        } else {
            val pixels = IntArray(alpha.size)
            sourceMask.getPixels(pixels, 0, sourceMask.width, 0, 0, sourceMask.width, sourceMask.height)
            for (i in pixels.indices) pixels[i] = (pixels[i] and 0x00ffffff) or ((alpha[i].toInt() and 255) shl 24)
            Bitmap.createBitmap(pixels, sourceMask.width, sourceMask.height, Bitmap.Config.ARGB_8888)
        }
        roundedArtworkFootprint = alpha?.let { GeneratedIconBorder.fromAlpha(it, sourceMask.width, sourceMask.height) }
        appliedArtworkRadiusFraction = artworkRadiusFraction
        largeGeometryBounds = null
        previous?.recycle()
    }

    private fun buildComposite(foregroundColor: Int, backgroundColor: Int): AdaptiveIconDrawable {
        // AOSP ThemedIconDrawable.drawInternal(): the background plate is
        // drawn first and the mono mask is tinted with the glyph color via
        // SRC_IN on top of it. No SRC plate-mask layer, no center scaling.
        val plate = ColorDrawable(backgroundColor)
        val glyph = MonoGlyphDrawable(sourceMask).apply {
            colorFilter = artworkFilter(foregroundColor, backgroundColor)
        }
        return AdaptiveIconDrawable(plate, glyph)
    }

    private fun artworkFilter(foreground: Int, background: Int): ColorFilter =
        if (tonal) ColorMatrixColorFilter(GeneratedTonePalette.colorMatrix(foreground, background))
        else BlendModeColorFilter(foreground, BlendMode.SRC_IN)

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

    override fun getIntrinsicWidth(): Int = largeIconWidth.takeIf { it > 0 } ?: sourceMask.width

    override fun getIntrinsicHeight(): Int = largeIconHeight.takeIf { it > 0 } ?: sourceMask.height

    override fun getConstantState(): ConstantState =
        ColoredMonoState(sourceMask, sourceColor, sourcePlateColor, sourceShape, largeIconWidth, largeIconHeight,
            appearance, dynamicColors, borderRatio, adjustGeneratedBorder, tonal, identity, artworkRadiusFraction)

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
        private val width: Int,
        private val height: Int,
        private val appearance: IconAppearance?,
        private val dynamicColors: Boolean,
        private val borderRatio: Float,
        private val adjustGeneratedBorder: Boolean,
        private val tonal: Boolean,
        private val identity: String?,
        private val artworkRadiusFraction: Float,
    ) : ConstantState() {
        override fun newDrawable(): Drawable =
            ColoredMonochromeDrawable(mask, color, plateColor, shape, width, height, appearance, dynamicColors,
                borderRatio, adjustGeneratedBorder, tonal, identity, artworkRadiusFraction)
        override fun getChangingConfigurations(): Int = 0
    }
}
