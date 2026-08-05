package com.jerrey.monoicon.color

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.util.SparseArray

/**
 * Extracts the dominant icon color using the exact algorithm found in
 * Google Pixel Launcher's [ColorExtractor.findDominantColorByHue].
 *
 * ## Algorithm (confirmed from decompiled Pixel Launcher source)
 *
 * 1. **Sparse sampling**: step = ⌊√(w × h / 20)⌋, minimum 1
 * 2. **Alpha filter**: skip pixels with alpha < 128
 * 3. **HSV hue histogram** (360 bins): weight = saturation × value
 *    - Black pixels (V ≈ 0) → contribution ≈ 0
 *    - White pixels (S ≈ 0) → contribution ≈ 0
 *    - Saturated colors → high contribution
 * 4. **Dominant hue**: index of maximum accumulated weight
 * 5. **Candidate scoring** (within dominant hue): score by S × V,
 *    grouped by `⌊100×S⌋ + ⌊10000×V⌋` key (Pixel Launcher grouping)
 * 6. **Return**: ARGB pixel with highest accumulated score, or [IconColorExtractor.FALLBACK_COLOR]
 *
 * ## AdaptiveIcon Handling
 *
 * For [AdaptiveIconDrawable], use [renderForColorExtraction] to obtain
 * a full-color rendering before calling [extractDominantColor].
 * This helper renders foreground over transparent background using
 * a temporary [AdaptiveIconDrawable] so the system manages coordinate layout
 * (inset, viewport, density scaling). The resulting bitmap preserves ALL
 * RGB color information — no alpha/luminance mask is applied.
 *
 * ## Thread Safety
 *
 * Stateless. A single instance can be shared across all threads.
 * All allocations are stack-local (primitive arrays).
 */
class PixelStyleColorExtractor : IconColorExtractor {

    // ═══════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extracts the dominant color from [bitmap] using Pixel Launcher's
     * HSV hue-dominant algorithm.
     *
     * @param bitmap Raw icon bitmap. For AdaptiveIconDrawable icons,
     *               pass the output of [renderForColorExtraction] instead.
     * @return ARGB color int, or [IconColorExtractor.FALLBACK_COLOR] on failure.
     */
    override fun extractDominantColor(bitmap: Bitmap): Int {
        // Step 1: validation
        if (bitmap.isRecycled) {
            Log.w(TAG, "extractDominantColor: bitmap recycled, returning fallback")
            return IconColorExtractor.FALLBACK_COLOR
        }
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "extractDominantColor: invalid dimensions ${width}x$height, returning fallback")
            return IconColorExtractor.FALLBACK_COLOR
        }

        // Step 2: compute sampling step (~20 effective samples)
        val step = maxOf(1, Math.sqrt((width * height).toDouble() / 20.0).toInt())

        // Step 3: HSV hue histogram + candidate collection
        val hueHistogram = FloatArray(HUE_BINS)
        val topCandidates = IntArray(MAX_CANDIDATES)
        var candidateCount = 0

        val hsv = FloatArray(3)
        var dominantHue = -1
        var maxWeight = -1.0f
        var totalWeight = 0.0f
        var validPixelCount = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = (pixel shr 24) and 0xFF

                // Step 3a: skip transparent pixels (Pixel Launcher threshold)
                if (alpha < ALPHA_THRESHOLD) {
                    x += step
                    continue
                }

                validPixelCount++

                // Force fully opaque for HSV conversion
                val opaquePixel = pixel or 0xFF000000.toInt()
                Color.colorToHSV(opaquePixel, hsv)

                val hue = hsv[0].toInt()
                val saturation = hsv[1]
                val value = hsv[2]

                // Accumulate weighted hue histogram
                if (hue in 0 until HUE_BINS) {
                    val weight = saturation * value
                    hueHistogram[hue] += weight
                    totalWeight += weight

                    if (hueHistogram[hue] > maxWeight) {
                        dominantHue = hue
                        maxWeight = hueHistogram[hue]
                    }
                }

                // Step 4: collect up to MAX_CANDIDATES opaque pixels
                if (candidateCount < MAX_CANDIDATES) {
                    topCandidates[candidateCount] = opaquePixel
                    candidateCount++
                }

                x += step
            }
            y += step
        }

        // Step 5: no dominant hue found → fallback
        if (dominantHue < 0 || candidateCount == 0) {
            if (DEBUG_LOG) {
                Log.d(TAG, "[extractDominantColor] no valid pixel found, fallback")
            }
            return IconColorExtractor.FALLBACK_COLOR
        }

        // Step 6: score candidates within dominant hue
        // Pixel Launcher uses SparseArray<Float> with key = 100*s + 10000*v
        val scoreMap = SparseArray<Float>(MAX_CANDIDATES)
        var bestPixel = IconColorExtractor.FALLBACK_COLOR
        var bestScore = -1.0f

        for (i in 0 until candidateCount) {
            val pixel = topCandidates[i]
            Color.colorToHSV(pixel, hsv)
            val candidateHue = hsv[0].toInt()

            if (candidateHue != dominantHue) continue

            val saturation = hsv[1]
            val value = hsv[2]
            val score = saturation * value

            // Pixel Launcher grouping key
            val key = (100.0f * saturation).toInt() + (10000.0f * value).toInt()

            val existing = scoreMap[key]
            val accumulated = if (existing != null) existing + score else score
            scoreMap.put(key, accumulated)

            if (accumulated > bestScore) {
                bestPixel = pixel
                bestScore = accumulated
            }
        }

        val confidence = if (totalWeight > 0.0f) maxWeight / totalWeight else 0.0f

        if (DEBUG_LOG) {
            Log.d(TAG, "[extractDominantColor] w=$width h=$height " +
                "samples=${validPixelCount} dominantHue=$dominantHue " +
                "color=0x${Integer.toHexString(bestPixel)} " +
                "confidence=${"%.2f".format(confidence)}")
        }

        return bestPixel
    }

    // ═══════════════════════════════════════════════════════════════
    // AdaptiveIcon Helper
    // ═══════════════════════════════════════════════════════════════

    /**
     * Renders an [AdaptiveIconDrawable] foreground onto a transparent
     * background, producing a full-color bitmap suitable for color extraction.
     *
     * ## Why this exists
     *
     * The monochrome pipeline intentionally destroys RGB color information
     * (via [com.jerrey.monoicon.image.DrawableConverter.toBitmap]
     * and luminance masks). Color extraction needs the ORIGINAL colors.
     *
     * Using a temporary [AdaptiveIconDrawable] with a transparent background
     * ensures the system manages foreground layout (inset, viewport,
     * density scaling) — manual [setBounds] on the foreground would bypass
     * [AdaptiveIconDrawable]'s internal coordinate logic.
     *
     * ## Contract
     *
     * - Returns a raw ARGB_8888 bitmap with NO alpha/luminance mask applied.
     * - The bitmap is ONLY for color extraction — do NOT feed it back into
     *   [com.jerrey.monoicon.image.DrawableConverter.toBitmap] or any mask
     *   generation path.
     * - Caller is responsible for recycling the bitmap when done.
     *
     * @param drawable The AdaptiveIconDrawable whose foreground to render.
     * @return A full-color ARGB_8888 bitmap, or null if rendering fails.
     */
    fun renderForColorExtraction(drawable: AdaptiveIconDrawable): Bitmap? {
        val foreground = drawable.foreground ?: return null

        // Use a temporary AdaptiveIconDrawable so the system lays out
        // the foreground correctly (inset, viewport, density scaling).
        val temp = AdaptiveIconDrawable(
            ColorDrawable(Color.TRANSPARENT),
            foreground
        )

        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        temp.setBounds(0, 0, width, height)
        temp.draw(canvas)

        if (DEBUG_LOG) {
            Log.d(TAG, "[renderForColorExtraction] ${width}x${height} " +
                "fg=${foreground.javaClass.simpleName}")
        }

        return bitmap
    }

    companion object {
        private const val TAG = "MonoIcon.ColorExtract"

        /** Number of hue bins (0–359 degrees). */
        private const val HUE_BINS = 360

        /** Maximum number of candidate pixels collected during sampling. */
        private const val MAX_CANDIDATES = 20

        /** Minimum alpha value for a pixel to be considered opaque. */
        private const val ALPHA_THRESHOLD = 128

        /** Set to false for release builds to suppress per-invocation debug logs. */
        var DEBUG_LOG: Boolean = false
    }
}
