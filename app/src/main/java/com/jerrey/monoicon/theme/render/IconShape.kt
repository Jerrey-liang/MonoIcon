package com.jerrey.monoicon.theme.render

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import androidx.core.graphics.PathParser

/**
 * Icon silhouette used by MonoIcon-rendered icons (Phase 9).
 *
 * - [SQUIRCLE]: no clipping — the framework/launcher mask decides the shape
 *   (HyperOS ships a **square** `config_icon_mask`, so today's output is
 *   whatever MIUI composed around our drawable).
 * - [CIRCLE]: the module clips its own drawable to a circle, which is the
 *   only way to obtain an AOSP-style circular icon on HyperOS (see
 *   [CircleIconShape]).
 */
enum class IconShape { SQUIRCLE, CIRCLE }

/**
 * The single source of truth for the circular icon geometry (Phase 9).
 *
 * [MASK_PATH_DATA] is an AOSP-style full-bleed circle in the 0..100 mask
 * space (four cubic arcs). The very same string is handed to MIUI's
 * `IconCustomizer` as `IconConfig.mConfigIconMask`, and is parsed here with
 * `androidx.core.graphics.PathParser` for the module's own clip path — so the
 * MIUI-composed icons and MonoIcon's own drawables share one geometry.
 *
 * Geometry contract (matches MIUI `composeIconForDefaultIcon()`'s
 * `matrix.setScale(size / 100f, size / 100f)` and AOSP
 * `AdaptiveIconDrawable.updateMaskBoundsInternal()`'s
 * `mMaskMatrix.setScale(b.width() / MASK_SIZE, b.height() / MASK_SIZE)`):
 * the mask is stretched **non-uniformly** to the drawable bounds.
 *
 * Being full-bleed (bounds exactly `0,0,100,100`) keeps MIUI's
 * `mIsFullMask` predicate (`bounds.contains(50f, 0f)`) true, so icons keep
 * flowing through the default-icon composer instead of the theme bitmap
 * (`icon_mask.png`) branch.
 *
 * ## AOSP whole-icon normalization
 * AOSP does not only clip to the mask: for adaptive icons
 * `IconNormalizer.normalizeAdaptiveIcon()` derives a scale from the **current
 * mask area** (`hullByRect == 1` → target `MAX_SQUARE_AREA_FACTOR = 375/576`)
 * and `BaseIconFactory.drawIconBitmap()` then shrinks the whole icon by that
 * factor inside the icon bitmap, leaving a transparent ring:
 *
 * ```
 * scale  = sqrt(MAX_SQUARE_AREA_FACTOR / maskAreaFraction)   // ≈0.913 for this circle
 * offset = max(ceil(BLUR_FACTOR * size), round(size * (1 - scale) / 2))
 * ```
 *
 * [aospScale] / [insetBounds] reproduce that math so a MonoIcon circle is
 * inset (and its content scaled down) exactly like a Pixel icon, instead of
 * being cropped full-bleed.
 */
object CircleIconShape {

    /** AOSP-style circle path in the 0..100 mask space (full-bleed). */
    const val MASK_PATH_DATA =
        "M50,0 C77.61,0 100,22.39 100,50 C100,77.61 77.61,100 50,100 " +
            "C22.39,100 0,77.61 0,50 C0,22.39 22.39,0 50,0 Z"

    private const val MASK_SIZE = 100f

    /** `IconNormalizer.MAX_SQUARE_AREA_FACTOR` (375f / 576). */
    private const val MAX_SQUARE_AREA_FACTOR = 375f / 576f

    /**
     * `ShadowGenerator.BLUR_FACTOR` (1.68f / 48): the minimum room AOSP
     * reserves on every side for the path shadow drawn behind the icon.
     */
    private const val SHADOW_BLUR_FACTOR = 1.68f / 48f

    /** Measured circle area of [MASK_PATH_DATA] (used when the Region query fails). */
    private const val FALLBACK_AREA_FRACTION = 0.7808f

    @Volatile
    private var unitPath: Path? = null

    @Volatile
    private var unitPathResolved = false

    @Volatile
    private var areaFractionCache = -1f

    @Volatile
    private var scaleCache = -1f

    /**
     * Parsed unit-space (0..100) circle path, or null when the platform
     * parser rejects the path data. Parsed at most once; callers must not
     * mutate the returned path.
     */
    fun unitPath(): Path? {
        unitPath?.let { return it }
        if (unitPathResolved) return null
        synchronized(this) {
            unitPath?.let { return it }
            val parsed = try {
                PathParser.createPathFromPathData(MASK_PATH_DATA)
            } catch (_: Throwable) {
                null
            }
            unitPath = parsed
            unitPathResolved = true
            return parsed
        }
    }

    /**
     * Clip path for [bounds], or null when no clipping is required
     * ([IconShape.SQUIRCLE], empty bounds, or an unparsable mask).
     *
     * The returned path is a fresh instance on every call: cheap (one matrix
     * transform) and safe to cache per drawable.
     */
    fun clipPath(shape: IconShape, bounds: Rect): Path? {
        if (shape != IconShape.CIRCLE) return null
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        val unit = unitPath() ?: return null
        return try {
            val matrix = Matrix()
            matrix.setRectToRect(
                RectF(0f, 0f, MASK_SIZE, MASK_SIZE),
                RectF(
                    bounds.left.toFloat(),
                    bounds.top.toFloat(),
                    bounds.right.toFloat(),
                    bounds.bottom.toFloat(),
                ),
                Matrix.ScaleToFit.FILL,
            )
            Path().also { unit.transform(matrix, it) }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Fraction of the 0..100 tile covered by the circle, measured exactly like
     * AOSP (`GraphicsUtils.getArea(maskRegion) / size²`, i.e. summing the
     * region's rectangles — not the analytic π/4). For [MASK_PATH_DATA] the
     * four cubic arcs plus integer rasterization give ≈ **0.781**
     * (ideal circle: 0.7854).
     */
    fun areaFraction(): Float {
        areaFractionCache.takeIf { it > 0f }?.let { return it }
        synchronized(this) {
            areaFractionCache.takeIf { it > 0f }?.let { return it }
            val measured = try {
                val path = unitPath()
                if (path == null) {
                    FALLBACK_AREA_FRACTION
                } else {
                    val region = Region()
                    region.setPath(path, Region(0, 0, MASK_SIZE.toInt(), MASK_SIZE.toInt()))
                    var area = 0L
                    val iterator = android.graphics.RegionIterator(region)
                    val rect = Rect()
                    while (iterator.next(rect)) {
                        area += rect.width().toLong() * rect.height().toLong()
                    }
                    val fraction = area.toFloat() / (MASK_SIZE * MASK_SIZE)
                    if (fraction > 0f) fraction else FALLBACK_AREA_FRACTION
                }
            } catch (_: Throwable) {
                FALLBACK_AREA_FRACTION
            }
            areaFractionCache = measured
            return measured
        }
    }

    /**
     * AOSP `IconNormalizer.normalizeAdaptiveIcon()` scale for this mask:
     * `sqrt(MAX_SQUARE_AREA_FACTOR / areaFraction)`.
     *
     * The adaptive branch passes `hullArea == boundingArea`, so the target is
     * always `MAX_SQUARE_AREA_FACTOR` (375/576) regardless of how round the
     * mask is; the result only ever shrinks (≤ 1). For this circle that is
     * ≈ **0.913** (Region-measured area ≈ 0.781; the analytic π/4 would give
     * 0.910) — the whole icon is drawn at ~91% and the remaining ring stays
     * transparent, exactly like a Pixel icon.
     */
    fun aospScale(): Float {
        scaleCache.takeIf { it > 0f }?.let { return it }
        synchronized(this) {
            scaleCache.takeIf { it > 0f }?.let { return it }
            val fraction = areaFraction()
            val scale = if (fraction <= MAX_SQUARE_AREA_FACTOR) {
                1f
            } else {
                kotlin.math.sqrt(MAX_SQUARE_AREA_FACTOR / fraction)
            }
            val clamped = scale.coerceIn(0f, 1f)
            scaleCache = clamped
            return clamped
        }
    }

    /**
     * AOSP `BaseIconFactory.drawIconBitmap()` inset for [bounds]:
     * `offset = max(ceil(BLUR_FACTOR * size), round(size * (1 - scale) / 2))`
     * on every side, i.e. the icon is shrunk around its centre and the ring
     * left unpainted (transparent).
     */
    fun insetBounds(bounds: Rect): Rect {
        if (bounds.width() <= 0 || bounds.height() <= 0) return Rect(bounds)
        val size = minOf(bounds.width(), bounds.height())
        val scaleOffset = Math.round(size * (1f - aospScale()) / 2f)
        val blurOffset = kotlin.math.ceil(SHADOW_BLUR_FACTOR * size).toInt()
        val offset = maxOf(scaleOffset, blurOffset)
        return Rect(
            bounds.left + offset,
            bounds.top + offset,
            bounds.right - offset,
            bounds.bottom - offset,
        )
    }
}
