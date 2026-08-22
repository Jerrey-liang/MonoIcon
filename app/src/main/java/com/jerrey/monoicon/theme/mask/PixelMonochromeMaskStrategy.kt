package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.mask.GenerateResult

/**
 * Pixel Launcher compatible monochrome mask strategy (Phase 6.3, 6.6).
 *
 * ## Priority
 * 1. **Native monochrome layer** — `AdaptiveIconDrawable.getMonochrome()`
 * 2. **LAB luminance extraction** — CIELAB L* from full icon render
 * 3. **Raw APK drawable** — IconDrawableCache hit → LAB, or full render
 *    → [LabMonochromeExtractor.extractFromBitmap]
 * 4. **Foreground extraction** — foreground render → extractFromBitmap
 * 5. **Whole drawable** — generic render → extractFromBitmap
 * 6. **null** → caller passes through
 *
 * ## Phase 6.6: single Pixel algorithm everywhere
 * Every tier that renders a bitmap now feeds it into
 * [LabMonochromeExtractor.extractFromBitmap] (equi-weight grayscale,
 * min-max stretch, mid-tone boost, polarity inversion) — the exact
 * Pixel Launcher mask formula. The old Rec.601 polarity pipeline
 * (DrawableConverter.toBitmap/toLuminanceMask) has been removed.
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
            val adaptiveSource = (rawCached as? AdaptiveIconDrawable)
                ?: (d as? AdaptiveIconDrawable)
            rawUsed = adaptiveSource != null && adaptiveSource !== d

            if (adaptiveSource != null) {
                // ① Native monochrome layer
                val mono = DrawableConverter.getMonochromeLayer(adaptiveSource)
                if (mono != null) {
                    source = MaskStrategy.SOURCE_NATIVE
                    val size = maxOf(
                        adaptiveSource.intrinsicWidth,
                        adaptiveSource.intrinsicHeight,
                    ).coerceAtLeast(1)
                    mask = DrawableConverter.renderPixelNativeMonochrome(mono, size)
                    keyBitmap = mask
                    logd(TAG, "[PixelMask] source=NATIVE raw=$rawUsed")
                }
                // ② LAB luminance extraction
                if (mask == null) {
                    val luminanceDelta = LabMonochromeExtractor.computeLuminanceDelta(adaptiveSource)
                    val labMask = LabMonochromeExtractor.extract(adaptiveSource, luminanceDelta)
                    if (labMask != null) {
                        source = MaskStrategy.SOURCE_LUMINANCE; mask = labMask; keyBitmap = labMask
                        logd(TAG, "[PixelMask] source=LAB_LUMINANCE delta=$luminanceDelta raw=$rawUsed")
                    }
                }
                // HyperOS compatibility fallback for malformed adaptive icons.
                if (mask == null) {
                    val fg = adaptiveSource.foreground
                    if (fg != null) {
                        val srcBounds = adaptiveSource.bounds
                        val w = if (srcBounds.width() > 0) srcBounds.width() else adaptiveSource.intrinsicWidth.coerceAtLeast(1)
                        val h = if (srcBounds.height() > 0) srcBounds.height() else adaptiveSource.intrinsicHeight.coerceAtLeast(1)
                        fg.setBounds(0, 0, w, h)
                        source = MaskStrategy.SOURCE_FOREGROUND
                        val render = DrawableConverter.renderToBitmap(fg)
                        mask = render?.let {
                            LabMonochromeExtractor.extractFromBitmap(
                                it, LabMonochromeExtractor.estimateLuminanceDelta(it))
                        }
                        keyBitmap = render ?: mask
                        logd(TAG, "[PixelMask] source=FOREGROUND")
                    }
                }
            }
        } catch (_: Throwable) {
            // Any exception in tiers 1-4 → fall through to tier 5
            mask = null
        }
        // Whole drawable: compatibility path for non-adaptive HyperOS themes.
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

    companion object {
        private const val TAG = "MonoIcon.PixelMask"
    }
}
