package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import com.jerrey.monoicon.theme.render.CircleIconShape

/**
 * MIUI `IconCustomizer` bridge for the circular icon shape (Phase 9).
 *
 * HyperOS derives the icon silhouette from
 * `ThemeResources.getSystem().getIconStream("transform_config.xml")`, parsed
 * by `IconCustomizer.loadIconConfig()` into `IconConfig.mConfigIconMask`, and
 * finally applied by `composeIconForDefaultIcon()`. Installing an MTZ theme is
 * therefore **not** required to change the shape: mutating the parsed config
 * in-process is enough (see [injectMask]).
 *
 * Every entry point here is failure-tolerant: a missing/renamed MIUI member,
 * an unexpected type or any other `Throwable` degrades to "no shape change"
 * and never propagates into MIUI's icon pipeline.
 */
object MiuiIconShapeCompat {

    private const val TAG = "MonoIcon.Shape"

    private const val CLASS_ICON_CUSTOMIZER = "miui.content.res.IconCustomizer"
    private const val FIELD_CONFIG_ICON_MASK = "mConfigIconMask"
    private const val METHOD_CLEAR_CACHE = "clearCache"

    /** Theme/mod-icon asset name whose bitmap is used as a `DST_IN` mask. */
    private const val NAME_THEME_MASK_PNG = "icon_mask.png"

    /** Fallback mask edge when the replaced bitmap size is unknown. */
    private const val FALLBACK_MASK_SIZE = 192

    private const val MIN_MASK_SIZE = 32
    private const val MAX_MASK_SIZE = 512
    private const val MAX_CACHED_SIZES = 4

    /** Rendered circle masks keyed by edge length. Source bitmaps are never shared. */
    private val maskCache = HashMap<Int, Bitmap>()

    @Volatile
    private var selfChecked = false

    @Volatile
    private var bypassed = false

    @Volatile
    private var fieldMissingLogged = false

    /**
     * Geometry self-check, executed at most once: the circle path must parse,
     * fill the whole 0..100 tile (so MIUI keeps `mIsFullMask == true`) and be
     * round rather than square. A failure bypasses this feature for the whole
     * process — icons are never left blank by a bad mask.
     */
    fun selfCheck(): Boolean {
        if (selfChecked) return !bypassed
        synchronized(this) {
            if (selfChecked) return !bypassed
            val ok = try {
                val path = CircleIconShape.unitPath()
                if (path == null) {
                    false
                } else {
                    val bounds = RectF()
                    path.computeBounds(bounds, true)
                    val fullBleed = bounds.left <= 0.5f && bounds.top <= 0.5f &&
                        bounds.right >= 99.5f && bounds.bottom >= 99.5f &&
                        bounds.contains(50f, 0f)
                    val region = Region()
                    region.setPath(path, Region(0, 0, 100, 100))
                    val rounded = region.contains(50, 50) && region.contains(50, 1) &&
                        region.contains(1, 50) && !region.contains(3, 3) &&
                        !region.contains(96, 96)
                    fullBleed && rounded
                }
            } catch (_: Throwable) {
                false
            }
            bypassed = !ok
            selfChecked = true
            if (ok) {
                android.util.Log.i(TAG, "circle shape self-check OK")
            } else {
                android.util.Log.e(TAG, "circle shape self-check FAILED — feature bypassed")
            }
            return ok
        }
    }

    /** True when [selfCheck] failed; callers must then pass through unchanged. */
    fun isBypassed(): Boolean = bypassed

    /**
     * Writes the circular mask into an `IconConfig` instance
     * (`IconConfig.mConfigIconMask`), in place.
     *
     * Idempotent: returns true when the target already carries the circle.
     * Never throws; a missing field is logged once and then short-circuits.
     */
    fun injectMask(target: Any?): Boolean {
        if (target == null || bypassed) return false
        return try {
            val field = target.javaClass.getDeclaredField(FIELD_CONFIG_ICON_MASK)
            field.isAccessible = true
            if ((field.get(target) as? String) == CircleIconShape.MASK_PATH_DATA) {
                true
            } else {
                field.set(target, CircleIconShape.MASK_PATH_DATA)
                true
            }
        } catch (t: NoSuchFieldException) {
            if (!fieldMissingLogged) {
                fieldMissingLogged = true
                android.util.Log.w(
                    TAG,
                    "IconConfig.$FIELD_CONFIG_ICON_MASK missing — circle shape disabled",
                )
            }
            false
        } catch (t: Throwable) {
            if (!fieldMissingLogged) {
                fieldMissingLogged = true
                android.util.Log.w(TAG, "mask injection failed: ${t.message}")
            }
            false
        }
    }

    /** True for the theme/mod-icon mask asset used by `composeIcon()`. */
    fun isThemeMaskName(name: String?): Boolean = name == NAME_THEME_MASK_PNG

    /**
     * A fresh circular mask bitmap of [size] px (ARGB_8888, white inside /
     * transparent outside), or null when the shape is bypassed.
     *
     * A copy is returned on purpose: MIUI may recycle or re-densify the bitmap
     * it receives, so the cached source is never handed out directly.
     */
    fun circleMaskCopy(size: Int): Bitmap? {
        if (bypassed) return null
        val edge = size.coerceIn(MIN_MASK_SIZE, MAX_MASK_SIZE)
        val source = synchronized(maskCache) {
            maskCache[edge] ?: renderCircleMask(edge)?.also { rendered ->
                if (maskCache.size >= MAX_CACHED_SIZES) maskCache.clear()
                maskCache[edge] = rendered
            }
        } ?: return null
        return try {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) {
            null
        }
    }

    private fun renderCircleMask(size: Int): Bitmap? = try {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val path: Path? = CircleIconShape.unitPath()
        if (path == null) {
            bitmap.recycle()
            null
        } else {
            val matrix = android.graphics.Matrix()
            matrix.setRectToRect(
                RectF(0f, 0f, 100f, 100f),
                RectF(0f, 0f, size.toFloat(), size.toFloat()),
                android.graphics.Matrix.ScaleToFit.FILL,
            )
            val scaled = Path()
            path.transform(matrix, scaled)
            Canvas(bitmap).drawPath(
                scaled,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE },
            )
            bitmap
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Clears MIUI's own icon caches (`IconCustomizer.clearCache()`), which also
     * resets the parsed `IconConfig`, so a toggle change is picked up by the
     * next icon composition. Best effort — returns false when unavailable.
     */
    fun invalidateMiuiCaches(): Boolean = try {
        val method = Class.forName(CLASS_ICON_CUSTOMIZER)
            .getDeclaredMethod(METHOD_CLEAR_CACHE)
        method.isAccessible = true
        method.invoke(null)
        android.util.Log.i(TAG, "MIUI icon caches cleared")
        true
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "MIUI icon cache clear failed: ${t.message}")
        false
    }
}
