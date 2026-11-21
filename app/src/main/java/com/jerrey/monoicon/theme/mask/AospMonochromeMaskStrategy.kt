package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.mask.GenerateResult

/**
 * Author masks first, then foreground-aware generation using AOSP geometry.
 *
 * ## Priority
 * 1. Native style uses native monochrome; Lawnicons style uses Lawnicons,
 *    then native monochrome. Generated style skips both author sources.
 * 2. Adaptive Icon without mono → source coverage and tonal detail extraction.
 * 3. Raw/display legacy Drawable → AOSP `BaseIconFactory.wrapToAdaptiveIcon`
 *    (white plate + `IconNormalizer.getScale() × 0.4667`) followed by the
 *    same foreground-aware finishing.
 * 4. Other HyperOS Drawable → existing compatibility fallback.
 *
 * The generated outer plate is adjusted by the renderer. Mask extraction is
 * independent of its width and never erodes source artwork for that setting.
 *
 * ## Desktop / folder unification
 * AOSP uses a single mask pipeline — this strategy replaces the Phase 5
 * desktop/folder split.
 */
class AospMonochromeMaskStrategy(
    private val generatedBorderRatio: Float? = null,
    private val sourceStyle: IconSourceStyle? = null,
    private val allowLawniconsFallback: Boolean? = null,
) : MaskStrategy {

    private var themeId: String = ""
    private var contextStr: String = ""

    override fun configureCache(themeId: String, context: String) {
        this.themeId = themeId
        this.contextStr = context
    }

    override fun generate(d: Drawable, identity: String?): GenerateResult? {
        val preferences = ConfigManager.getEffectiveIconStyle(identity)
        val borderRatio = generatedBorderRatio ?: preferences.generatedBorderRatio
        val style = sourceStyle ?: preferences.style
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
            val size = aospTargetSize(preferred, d)
            fun nativeMask(): Bitmap? = adaptiveSource?.let { this.nativeMask(it, size) }
            fun packMask(): Bitmap? = if (style.permitsLawniconsFallback &&
                (allowLawniconsFallback ?: (sourceStyle != null || ConfigManager.permitsLawniconsFallback(identity))))
                tryLawnicons(identity, size) else null
            // Keep both native-first chains: the default may use Lawnicons,
            // while native-generated never consults the bundle.
            val authored = when (style) {
                IconSourceStyle.NATIVE -> nativeMask()?.let { it to MaskStrategy.SOURCE_NATIVE }
                    ?: packMask()?.let { it to MaskStrategy.SOURCE_LAWNICONS }
                IconSourceStyle.NATIVE_GENERATED -> nativeMask()?.let { it to MaskStrategy.SOURCE_NATIVE }
                IconSourceStyle.LAWNICONS -> packMask()?.let { it to MaskStrategy.SOURCE_LAWNICONS }
                    ?: nativeMask()?.let { it to MaskStrategy.SOURCE_NATIVE }
                IconSourceStyle.GENERATED -> null
            }
            if (authored != null) {
                mask = authored.first
                source = authored.second
                keyBitmap = mask
                logd(TAG, "[AospMask] source=$source raw=$rawUsed identity=$identity")
            } else if (adaptiveSource != null) {
                // Near-flat single-color glyphs may use their alpha
                // silhouette only when the background adds no artwork.
                // Otherwise retain the composed layers in the tonal core.
                val structure = adaptiveSource.foreground?.let {
                    AospMonochromeFactory.foregroundStructure(it, size)
                }
                if (structure?.useSilhouette == true &&
                    !AospMonochromeFactory.hasBackgroundArtwork(adaptiveSource, size)
                ) {
                    source = MaskStrategy.SOURCE_AOSP_ADAPTIVE_SILHOUETTE
                    mask = AospMonochromeFactory.renderForegroundSilhouette(
                        adaptiveSource.foreground!!, size,
                    )
                    keyBitmap = mask
                    logd(TAG, "[AospMask] source=AOSP_ADAPTIVE_SILHOUETTE raw=$rawUsed")
                } else {
                    source = MaskStrategy.SOURCE_AOSP_ADAPTIVE
                    mask = AospMonochromeFactory.wrap(adaptiveSource, size, borderRatio)
                    keyBitmap = mask
                    logd(TAG, "[AospMask] source=AOSP_ADAPTIVE raw=$rawUsed")
                }
            } else if (
                rawCached != null ||
                    d is BitmapDrawable ||
                    d is VectorDrawable
            ) {
                val size = aospTargetSize(preferred, d)
                val wrapped = DrawableConverter.wrapAospLegacyIcon(preferred, size)
                if (wrapped != null) {
                    source = MaskStrategy.SOURCE_AOSP_LEGACY
                    mask = AospMonochromeFactory.wrap(wrapped, size, borderRatio)
                    keyBitmap = (preferred as? BitmapDrawable)?.bitmap ?: mask
                    logd(TAG, "[AospMask] source=AOSP_LEGACY raw=$rawUsed")
                }
            }
        } catch (_: Throwable) {
            // Any exception in the strict AOSP path → compatibility fallback.
            mask = null
        }

        // Preserve the existing HyperOS compatibility path for Drawables that
        // are neither raw legacy inputs nor Adaptive Icons.
        if (mask == null) {
            source = MaskStrategy.SOURCE_LUMINANCE
            val render = DrawableConverter.renderToBitmap(d)
            mask = render?.let {
                LabMonochromeExtractor.extractFromBitmap(
                    it, LabMonochromeExtractor.estimateLuminanceDelta(it), borderRatio)
            }
            keyBitmap = mask ?: render
            logd(TAG, "[AospMask] source=LUMA")
        }

        if (mask == null) return null

        val tonal = source != MaskStrategy.SOURCE_NATIVE && source != MaskStrategy.SOURCE_LAWNICONS &&
            mask.config == Bitmap.Config.ARGB_8888
        val cacheKey = MonochromeCache.shared.buildKey(
            "generated_v7_visible_plate_${borderRatio.toBits()}_$themeId", contextStr, identity, keyBitmap ?: mask, source)
        if (cacheKey != null) {
            val cached = MonochromeCache.shared.get(cacheKey)
            if (cached != null) {
                return GenerateResult(cached, source, rawUsed, cacheHit = true, tonal = tonal)
            }
            MonochromeCache.shared.put(cacheKey, mask)
        }
        return GenerateResult(mask, source, rawUsed, cacheHit = false, tonal = tonal)
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
        if (identity != null) {
            LawniconsAssetSource.lookupMask(identity, size)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    private fun nativeMask(source: AdaptiveIconDrawable, size: Int): Bitmap? =
        DrawableConverter.getMonochromeLayer(source)?.let {
            LabMonochromeExtractor.extractPixelNativeMonochrome(it, size)
        }?.let { candidate ->
            // Empty and solid placeholder layers do not count as themed artwork.
            if (hasUsableGlyph(candidate)) candidate else {
                candidate.recycle()
                null
            }
        }

    companion object {
        private const val TAG = "MonoIcon.AospMask"

        internal fun hasUsableGlyph(mask: Bitmap): Boolean {
            val pixels = IntArray(mask.width * mask.height)
            mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
            var min = 255
            var max = 0
            for (pixel in pixels) {
                val alpha = pixel ushr 24
                min = minOf(min, alpha)
                max = maxOf(max, alpha)
            }
            return max >= 32 && max - min >= 16
        }
    }
}
