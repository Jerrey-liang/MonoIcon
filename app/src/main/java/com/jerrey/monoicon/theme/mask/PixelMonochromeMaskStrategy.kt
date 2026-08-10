package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.mask.MaskGenerator

/**
 * Pixel Launcher compatible monochrome mask strategy (Phase 6.3).
 *
 * ## Priority
 * 1. **Native monochrome layer** — `AdaptiveIconDrawable.getMonochrome()`
 * 2. **LAB luminance extraction** — CIELAB L* from full icon render
 * 3. **Raw APK drawable** — IconDrawableCache hit → full render → toLuminanceMask
 * 4. **Foreground extraction** — foreground → toBitmap
 * 5. **Whole drawable** — toBitmap (Rec.601 Y' fallback)
 * 6. **null** → caller passes through (FancyDrawable/unrenderable types)
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

    override fun generate(d: Drawable, identity: String?): MaskGenerator.GenerateResult? {
        var source = MaskStrategy.SOURCE_LUMINANCE
        var rawUsed = false
        var keyBitmap: Bitmap? = null
        var mask: Bitmap? = null

        // Tiers 1-4: may throw from LabMonochromeExtractor or reflection —
        // catch inside this block so Tier 5 (LUMA) always runs as a fallback.
        try {
            var luminanceDelta: Double? = null
            if (d is AdaptiveIconDrawable) {
                // ① Native monochrome layer
                val mono = DrawableConverter.getMonochromeLayer(d)
                if (mono != null) {
                    source = MaskStrategy.SOURCE_NATIVE
                    keyBitmap = DrawableConverter.toRawBitmap(mono)
                    mask = keyBitmap?.let { DrawableConverter.normalizeNativeMonochrome(it) }
                    logd(TAG, "[PixelMask] source=NATIVE")
                }
                // ② LAB luminance extraction
                if (mask == null) {
                    luminanceDelta = LabMonochromeExtractor.computeLuminanceDelta(d)
                    val labMask = LabMonochromeExtractor.extract(d, luminanceDelta)
                    if (labMask != null) {
                        source = MaskStrategy.SOURCE_LUMINANCE; mask = labMask; keyBitmap = labMask
                        logd(TAG, "[PixelMask] source=LAB_LUMINANCE delta=$luminanceDelta")
                    }
                }
                // ③ Raw APK drawable → LAB
                if (mask == null) {
                    val rawCached = if (identity != null) IconDrawableCache.get(identity) else null
                    if (rawCached is AdaptiveIconDrawable) {
                        val delta = luminanceDelta ?: LabMonochromeExtractor.computeLuminanceDelta(rawCached)
                        val labMask = LabMonochromeExtractor.extract(rawCached, delta)
                        if (labMask != null) {
                            source = MaskStrategy.SOURCE_LUMINANCE; rawUsed = true; mask = labMask; keyBitmap = labMask
                            logd(TAG, "[PixelMask] source=LAB_VIA_CACHE identity=$identity")
                        }
                    }
                    if (mask == null && rawCached != null) {
                        keyBitmap = DrawableConverter.toRawBitmap(rawCached)
                        mask = keyBitmap?.let { DrawableConverter.toLuminanceMask(it) }
                        if (mask != null) { source = MaskStrategy.SOURCE_FOREGROUND; rawUsed = true }
                    }
                }
                // ④ Foreground extraction
                if (mask == null) {
                    val fg = d.foreground
                    if (fg != null) {
                        val srcBounds = d.bounds
                        val w = if (srcBounds.width() > 0) srcBounds.width() else d.intrinsicWidth.coerceAtLeast(1)
                        val h = if (srcBounds.height() > 0) srcBounds.height() else d.intrinsicHeight.coerceAtLeast(1)
                        fg.setBounds(0, 0, w, h)
                        source = MaskStrategy.SOURCE_FOREGROUND
                        mask = DrawableConverter.toBitmap(fg); keyBitmap = mask
                    }
                }
            }
        } catch (_: Throwable) {
            // Any exception in tiers 1-4 → fall through to tier 5
            mask = null
        }
        // ⑤ Whole drawable (non-adaptive or adaptive with no foreground)
        if (mask == null) {
            mask = DrawableConverter.toBitmap(d) ?: MaskStrategy.renderGenericToMask(d)
            keyBitmap = mask
            logd(TAG, "[PixelMask] source=LUMA")
        }

        if (mask == null) return null

        val cacheKey = MonochromeCache.shared.buildKey(
            "pixel_$themeId", contextStr, identity, keyBitmap ?: mask, source)
        if (cacheKey != null) {
            val cached = MonochromeCache.shared.get(cacheKey)
            if (cached != null) {
                return MaskGenerator.GenerateResult(cached, source, rawUsed, cacheHit = true)
            }
            MonochromeCache.shared.put(cacheKey, mask)
        }
        return MaskGenerator.GenerateResult(mask, source, rawUsed, cacheHit = false)
    }

    companion object {
        private const val TAG = "MonoIcon.PixelMask"
    }
}
