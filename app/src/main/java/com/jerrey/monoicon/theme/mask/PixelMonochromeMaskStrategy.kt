package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.mask.GenerateResult

/**
 * Pixel Launcher mask strategy with a HyperOS compatibility fallback.
 *
 * ## Priority
 * 1. Native monochrome layer → Pixel ALPHA_8 path.
 * 2. Adaptive Icon → Pixel forced grayscale path.
 * 3. Raw/display legacy Drawable → Pixel legacy wrapper, then the same
 *    Adaptive Icon grayscale path.
 * 4. Other HyperOS Drawable → existing compatibility fallback.
 *
 * Strict Pixel branches intentionally bypass MonoIcon-specific plate and
 * polarity heuristics. Only unknown HyperOS Drawable types use the legacy
 * compatibility fallback below the strict dispatch.
 *
 * ## Desktop / folder unification
 * Pixel Launcher uses a single mask pipeline — this strategy replaces
 * the Phase 5 desktop/folder split.
 */
class PixelMonochromeMaskStrategy : MaskStrategy {

    private var themeId: String = ""
    private var contextStr: String = ""

    override fun configureCache(themeId: String, context: String) {
        this.themeId = themeId
        this.contextStr = context
    }

    override fun generate(d: Drawable, identity: String?): GenerateResult? {
        var source = MaskStrategy.SOURCE_LUMINANCE
        var rawUsed = false
        var keyBitmap: Bitmap? = null
        var mask: Bitmap? = null

        // Pixel receives the raw APK AdaptiveIconDrawable before launcher
        // theming. HyperOS hands the display hook a LayerAdaptiveIconDrawable,
        // so Hook 7's isolated raw copy is the authoritative Pixel input when
        // available. The generic HyperOS drawable remains the compatibility
        // fallback when identity/raw capture is unavailable.
        try {
            val rawCached = if (identity != null) IconDrawableCache.get(identity) else null
            val preferred = rawCached ?: d
            rawUsed = rawCached != null && rawCached !== d

            val adaptiveSource = preferred as? AdaptiveIconDrawable
            if (adaptiveSource != null) {
                val size = pixelTargetSize(adaptiveSource, d)
                val mono = DrawableConverter.getMonochromeLayer(adaptiveSource)
                if (mono != null) {
                    source = MaskStrategy.SOURCE_NATIVE
                    mask = LabMonochromeExtractor.extractPixelNativeMonochrome(mono, size)
                    keyBitmap = mask
                    logd(TAG, "[PixelMask] source=PIXEL_NATIVE raw=$rawUsed")
                } else {
                    source = MaskStrategy.SOURCE_LUMINANCE
                    mask = LabMonochromeExtractor.extractPixelAdaptiveIcon(adaptiveSource, size)
                    keyBitmap = mask
                    logd(TAG, "[PixelMask] source=PIXEL_ADAPTIVE raw=$rawUsed")
                }
            } else if (
                rawCached != null ||
                    d is BitmapDrawable ||
                    d is VectorDrawable
            ) {
                val wrapped = DrawableConverter.wrapPixelLegacyIcon(preferred)
                if (wrapped != null) {
                    source = MaskStrategy.SOURCE_LUMINANCE
                    val size = pixelTargetSize(preferred, d)
                    mask = LabMonochromeExtractor.extractPixelAdaptiveIcon(wrapped, size)
                    keyBitmap = (preferred as? BitmapDrawable)?.bitmap ?: mask
                    logd(TAG, "[PixelMask] source=PIXEL_LEGACY raw=$rawUsed")
                }
            }
        } catch (_: Throwable) {
            // Any exception in the strict Pixel path → compatibility fallback.
            mask = null
        }

        // Preserve the existing HyperOS compatibility path for Drawables that
        // are neither raw legacy inputs nor Adaptive Icons.
        if (mask == null) {
            val render = DrawableConverter.renderToBitmap(d)
            mask = render?.let {
                LabMonochromeExtractor.extractFromBitmap(
                    it, LabMonochromeExtractor.estimateLuminanceDelta(it))
            }
            keyBitmap = mask ?: render
            logd(TAG, "[PixelMask] source=LUMA")
        }

        if (mask == null) return null

        val cacheKey = MonochromeCache.shared.buildKey(
            "pixel_$themeId", contextStr, identity, keyBitmap ?: mask, source)
        if (cacheKey != null) {
            val cached = MonochromeCache.shared.get(cacheKey)
            if (cached != null) {
                return GenerateResult(cached, source, rawUsed, cacheHit = true)
            }
            MonochromeCache.shared.put(cacheKey, mask)
        }
        return GenerateResult(mask, source, rawUsed, cacheHit = false)
    }

    /** Closest available equivalent to Pixel's BaseIconFactory.iconBitmapSize. */
    private fun pixelTargetSize(source: Drawable, display: Drawable): Int {
        val bitmap = (source as? BitmapDrawable)?.bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            return maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        }
        val sourceSize = maxOf(source.intrinsicWidth, source.intrinsicHeight)
        if (sourceSize > 0) return sourceSize
        return maxOf(display.intrinsicWidth, display.intrinsicHeight).coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "MonoIcon.PixelMask"
    }
}
