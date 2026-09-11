package com.jerrey.monoicon.theme.render

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
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
 */
object CircleIconShape {

    /** AOSP-style circle path in the 0..100 mask space (full-bleed). */
    const val MASK_PATH_DATA =
        "M50,0 C77.61,0 100,22.39 100,50 C100,77.61 77.61,100 50,100 " +
            "C22.39,100 0,77.61 0,50 C0,22.39 22.39,0 50,0 Z"

    private const val MASK_SIZE = 100f

    @Volatile
    private var unitPath: Path? = null

    @Volatile
    private var unitPathResolved = false

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
}
