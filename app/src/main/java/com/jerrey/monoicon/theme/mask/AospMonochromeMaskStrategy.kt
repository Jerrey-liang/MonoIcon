package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.mask.GenerateResult

/**
 * AOSP android15-release monochrome mask strategy with a HyperOS
 * compatibility fallback.
 *
 * ## Priority
 * 1. Native monochrome layer → AOSP/Pixel ALPHA_8 path (unchanged).
 * 2. Adaptive Icon without mono → AOSP `MonochromeIconFactory.wrap`:
 *    black-prefill flat, equal-weight grayscale, min/max stretch and the
 *    edge-strip polarity flip, no mid-tone boost.
 * 3. Raw/display legacy Drawable → AOSP `BaseIconFactory.wrapToAdaptiveIcon`
 *    (white plate + `IconNormalizer.getScale() × 0.4667`) followed by the
 *    same AOSP mono core.
 * 4. Other HyperOS Drawable → existing compatibility fallback.
 *
 * The strict AOSP branches intentionally do NOT run MonoIcon plate
 * exclusion or polarity heuristics. Known AOSP limitation accepted by
 * design: icons with a colored plate and a WHITE motif (fenbi-class) get
 * their white motif flipped to transparency by the edge-strip flip — the
 * same behavior as open-source Pixel.
 *
 * ## Desktop / folder unification
 * AOSP uses a single mask pipeline — this strategy replaces the Phase 5
 * desktop/folder split.
 */
class AospMonochromeMaskStrategy : MaskStrategy {

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

        // AOSP receives the raw APK AdaptiveIconDrawable before launcher
        // theming. HyperOS hands the display hook a LayerAdaptiveIconDrawable,
        // so Hook 7's isolated raw copy is the authoritative AOSP input when
        // available. The generic HyperOS drawable remains the compatibility
        // fallback when identity/raw capture is unavailable.
        try {
            val rawCached = if (identity != null) IconDrawableCache.get(identity) else null
            val preferred = rawCached ?: d
            rawUsed = rawCached != null && rawCached !== d

            val adaptiveSource = preferred as? AdaptiveIconDrawable
            if (adaptiveSource != null) {
                val size = aospTargetSize(adaptiveSource, d)
                val mono = DrawableConverter.getMonochromeLayer(adaptiveSource)
                if (mono != null) {
                    source = MaskStrategy.SOURCE_NATIVE
                    mask = LabMonochromeExtractor.extractPixelNativeMonochrome(mono, size)
                    keyBitmap = mask
                    logd(TAG, "[AospMask] source=AOSP_NATIVE raw=$rawUsed")
                } else {
                    // Phase 8 tier: author-drawn Lawnicons mask (skips every
                    // grayscale heuristic). Falls through to AOSP when the
                    // bundle has no entry or the tier is disabled.
                    val pack = tryLawnicons(identity, size)
                    if (pack != null) {
                        source = MaskStrategy.SOURCE_LAWNICONS
                        mask = pack
                        keyBitmap = pack
                        logd(
                            TAG,
                            "[AospMask] source=LAWNICONS bundle=${LawniconsAssetSource.version()} " +
                                "raw=$rawUsed identity=$identity",
                        )
                    } else {
                        // Plan replay decision: near-flat single-color glyph layers
                        // use the foreground alpha silhouette; everything else
                        // (opaque art, artwork badges) takes the B core.
                        val structure = adaptiveSource.foreground?.let {
                            AospMonochromeFactory.foregroundStructure(it, size)
                        }
                        if (structure?.useSilhouette == true) {
                            source = MaskStrategy.SOURCE_AOSP_ADAPTIVE_SILHOUETTE
                            mask = AospMonochromeFactory.renderForegroundSilhouette(
                                adaptiveSource.foreground!!, size,
                            )
                            keyBitmap = mask
                            logd(TAG, "[AospMask] source=AOSP_ADAPTIVE_SILHOUETTE raw=$rawUsed")
                        } else {
                            source = MaskStrategy.SOURCE_AOSP_ADAPTIVE
                            mask = AospMonochromeFactory.wrap(adaptiveSource, size)
                            keyBitmap = mask
                            logd(TAG, "[AospMask] source=AOSP_ADAPTIVE raw=$rawUsed")
                        }
                    }
                }
            } else if (
                rawCached != null ||
                    d is BitmapDrawable ||
                    d is VectorDrawable
            ) {
                val size = aospTargetSize(preferred, d)
                val pack = tryLawnicons(identity, size)
                if (pack != null) {
                    source = MaskStrategy.SOURCE_LAWNICONS
                    mask = pack
                    keyBitmap = pack
                    logd(
                        TAG,
                        "[AospMask] source=LAWNICONS bundle=${LawniconsAssetSource.version()} " +
                            "raw=$rawUsed identity=$identity",
                    )
                } else {
                    val wrapped = DrawableConverter.wrapAospLegacyIcon(preferred, size)
                    if (wrapped != null) {
                        source = MaskStrategy.SOURCE_AOSP_LEGACY
                        mask = AospMonochromeFactory.wrap(wrapped, size)
                        keyBitmap = (preferred as? BitmapDrawable)?.bitmap ?: mask
                        logd(TAG, "[AospMask] source=AOSP_LEGACY raw=$rawUsed")
                    }
                }
            }
        } catch (_: Throwable) {
            // Any exception in the strict AOSP path → compatibility fallback.
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
            logd(TAG, "[AospMask] source=LUMA")
        }

        if (mask == null) return null

        val cacheKey = MonochromeCache.shared.buildKey(
            "aosp_$themeId", contextStr, identity, keyBitmap ?: mask, source)
        if (cacheKey != null) {
            val cached = MonochromeCache.shared.get(cacheKey)
            if (cached != null) {
                return GenerateResult(cached, source, rawUsed, cacheHit = true)
            }
            MonochromeCache.shared.put(cacheKey, mask)
        }
        return GenerateResult(mask, source, rawUsed, cacheHit = false)
    }

    /**
     * Closest available equivalent to AOSP's BaseIconFactory.iconBitmapSize.
     */
    private fun aospTargetSize(source: Drawable, display: Drawable): Int {
        val bitmap = (source as? BitmapDrawable)?.bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            return maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        }
        val sourceSize = maxOf(source.intrinsicWidth, source.intrinsicHeight)
        if (sourceSize > 0) return sourceSize
        return maxOf(display.intrinsicWidth, display.intrinsicHeight).coerceAtLeast(1)
    }

    /**
     * Phase 8: Lawnicons bundle lookup. Never throws — a missing/disabled
     * bundle simply returns null so the AOSP branches stay in charge.
     */
    private fun tryLawnicons(identity: String?, size: Int): Bitmap? = try {
        if (ConfigManager.isLawniconsEnabled()) {
            LawniconsAssetSource.lookupMask(identity, size)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val TAG = "MonoIcon.AospMask"
    }
}
