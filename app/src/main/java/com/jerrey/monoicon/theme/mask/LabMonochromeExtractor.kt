package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import kotlin.math.sqrt

/**
 * HyperOS compatibility mask extraction (Phase 7).
 *
 * The strict mask branches now follow AOSP android15-release
 * ([AospMonochromeFactory] + [IconNormalizerCompat]); this extractor keeps
 * the two pieces that AOSP itself does not provide:
 *
 * - [extractPixelNativeMonochrome]: the native `<monochrome>` ALPHA_8 path
 *   (identical in AOSP and Pixel Launcher).
 * - [extractFromBitmap] / [estimateLuminanceDelta]: the last-resort
 *   compatibility path for HyperOS Drawables that are neither raw legacy
 *   inputs nor AdaptiveIcons — edge-connected plate exclusion plus the
 *   Pixel-style grayscale stretch/mid-tone/polarity pipeline.
 */
object LabMonochromeExtractor {

    // ── Background bins for polarity estimation ────────────────────────
    private const val BG_DARK = 10
    private const val BG_MID = 11
    private const val BG_LIGHT = 12

    // Conservative thresholds for legacy bitmap icons that contain a plate
    // but do not expose an Android monochrome layer.
    private const val EDGE_COLOR_DISTANCE = 32f
    private const val MIN_BACKGROUND_RATIO = 0.35
    private const val MIN_FOREGROUND_RATIO = 0.02

    /**
     * Native-monochrome path (AOSP / Pixel identical).
     *
     * Native monochrome layers are already alpha masks. Only the inverse
     * AdaptiveIcon inset is applied while rasterizing them; no luminance,
     * polarity or edge-plate processing is involved.
     */
    fun extractPixelNativeMonochrome(drawable: Drawable, targetSize: Int): Bitmap? {
        return try {
            val size = targetSize.coerceAtLeast(1)
            val inset = InsetDrawable(
                drawable,
                -AdaptiveIconDrawable.getExtraInsetFraction(),
            )
            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
            inset.setBounds(0, 0, size, size)
            inset.draw(Canvas(result))
            result
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The compatibility mask pipeline for an already-rendered bitmap —
     * used by the HyperOS fallback tier:
     * 1. Edge-connected plate detection and exclusion.
     * 2. Equi-weight grayscale `(R+G+B)/3 → [0,100]`
     * 3. min-max contrast stretch
     * 4. mid-tone boost
     * 5. optional inversion when [luminanceDelta] < 0
     *
     * The input [bitmap] is never recycled (the caller may still need it,
     * e.g. as the cache fingerprint source).
     */
    fun extractFromBitmap(bitmap: Bitmap, luminanceDelta: Double?): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Legacy PNG icons often contain a white/light plate but no native
        // monochrome layer. If that plate participates in min-max stretching,
        // it becomes an opaque glyph and covers the whole themed icon. Detect
        // a conservative edge-connected plate before extracting luminance.
        val edgeBackground = detectEdgeBackground(pixels, w, h)

        // Step 2: equi-weight grayscale → [0, 100] range
        val lValues = FloatArray(pixels.size)
        var validCount = 0
        var lMin = Float.MAX_VALUE
        var lMax = Float.MIN_VALUE

        for (i in pixels.indices) {
            val a = (pixels[i] shr 24) and 0xFF
            if (a < 32 || edgeBackground?.get(i) == true) {
                lValues[i] = -1f
                continue
            }
            val r = ((pixels[i] shr 16) and 0xFF) / 255f
            val g = ((pixels[i] shr 8) and 0xFF) / 255f
            val b = (pixels[i] and 0xFF) / 255f
            val l = ((r + g + b) / 3.0f) * 100f
            lValues[i] = l
            lMin = minOf(lMin, l)
            lMax = maxOf(lMax, l)
            validCount++
        }

        if (validCount < 4) return null
        if (lMax - lMin < 1f) {
            // A flat foreground still has a useful silhouette after a plate
            // was removed. Preserve the old null result for truly flat images.
            if (edgeBackground == null) return null
            val silhouettePixels = IntArray(pixels.size)
            for (i in pixels.indices) {
                val a = (pixels[i] shr 24) and 0xFF
                if (a >= 32 && edgeBackground[i] != true) silhouettePixels[i] = a shl 24
            }
            val silhouette = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            silhouette.setPixels(silhouettePixels, 0, w, 0, 0, w, h)
            return silhouette
        }

        // Step 3: contrast stretch + mid-tone boost.
        val range = lMax - lMin
        val maskPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            if (lValues[i] < 0f) { maskPixels[i] = 0; continue }
            var alpha = ((lValues[i] - lMin) * 255f / range).toInt()
            alpha = midToneBoost(alpha)
            if (luminanceDelta != null && luminanceDelta < 0.0) alpha = 255 - alpha
            alpha = alpha.coerceIn(0, 255)
            maskPixels[i] = (alpha shl 24)
        }

        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mask.setPixels(maskPixels, 0, w, 0, 0, w, h)
        return mask
    }

    /** Finds a dominant plate connected to the bitmap edge. */
    private fun detectEdgeBackground(pixels: IntArray, w: Int, h: Int): BooleanArray? {
        if (w < 3 || h < 3) return null
        val edgeSamples = ArrayList<Int>(2 * (w + h))
        for (y in 0 until h) {
            edgeSamples += pixels[y * w]
            edgeSamples += pixels[y * w + w - 1]
        }
        for (x in 0 until w) {
            edgeSamples += pixels[x]
            edgeSamples += pixels[(h - 1) * w + x]
        }
        val opaqueEdge = edgeSamples.filter { ((it ushr 24) and 0xFF) >= 32 }
        if (opaqueEdge.size < edgeSamples.size / 4) return null

        val meanR = opaqueEdge.sumOf { (it ushr 16) and 0xFF }.toDouble() / opaqueEdge.size
        val meanG = opaqueEdge.sumOf { (it ushr 8) and 0xFF }.toDouble() / opaqueEdge.size
        val meanB = opaqueEdge.sumOf { it and 0xFF }.toDouble() / opaqueEdge.size
        val edgeVariance = opaqueEdge.map {
            val dr = ((it ushr 16) and 0xFF) - meanR
            val dg = ((it ushr 8) and 0xFF) - meanG
            val db = (it and 0xFF) - meanB
            dr * dr + dg * dg + db * db
        }.average()
        val tolerance = maxOf(EDGE_COLOR_DISTANCE, sqrt(edgeVariance).toFloat() * 2.0f)

        val background = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0
        fun enqueue(index: Int) {
            if (!background[index]) {
                background[index] = true
                queue[tail++] = index
            }
        }
        fun nearEdgeColor(color: Int): Boolean {
            val dr = ((color ushr 16) and 0xFF) - meanR
            val dg = ((color ushr 8) and 0xFF) - meanG
            val db = (color and 0xFF) - meanB
            return sqrt((dr * dr + dg * dg + db * db).toFloat()) <= tolerance
        }
        for (y in 0 until h) {
            if (nearEdgeColor(pixels[y * w])) enqueue(y * w)
            if (nearEdgeColor(pixels[y * w + w - 1])) enqueue(y * w + w - 1)
        }
        for (x in 0 until w) {
            if (nearEdgeColor(pixels[x])) enqueue(x)
            val bottom = (h - 1) * w + x
            if (nearEdgeColor(pixels[bottom])) enqueue(bottom)
        }
        while (head < tail) {
            val index = queue[head++]
            val x = index % w
            val y = index / w
            if (x > 0) {
                val next = index - 1
                if (!background[next] && nearEdgeColor(pixels[next])) enqueue(next)
            }
            if (x + 1 < w) {
                val next = index + 1
                if (!background[next] && nearEdgeColor(pixels[next])) enqueue(next)
            }
            if (y > 0) {
                val next = index - w
                if (!background[next] && nearEdgeColor(pixels[next])) enqueue(next)
            }
            if (y + 1 < h) {
                val next = index + w
                if (!background[next] && nearEdgeColor(pixels[next])) enqueue(next)
            }
        }

        var opaque = 0
        var edgeOpaque = 0
        var foregroundOpaque = 0
        for (i in pixels.indices) {
            if (((pixels[i] ushr 24) and 0xFF) < 32) continue
            opaque++
            if (background[i]) edgeOpaque++ else foregroundOpaque++
        }
        if (opaque == 0) return null
        val backgroundRatio = edgeOpaque.toDouble() / opaque.toDouble()
        val foregroundRatio = foregroundOpaque.toDouble() / opaque.toDouble()
        return if (backgroundRatio >= MIN_BACKGROUND_RATIO && foregroundRatio >= MIN_FOREGROUND_RATIO) background else null
    }

    /**
     * Estimates the inversion polarity for sources WITHOUT separate
     * background/foreground layers (bitmaps rendered from BitmapDrawable /
     * VectorDrawable / generic drawables).
     *
     * 3-bin background-luminance estimation used ONLY to decide the
     * direction — the mask pixels themselves always go through
     * [extractFromBitmap]:
     * - light background + darker foreground → negative delta (invert)
     * - dark background + lighter foreground → positive delta (standard)
     * - ambiguous → null (standard mapping, no inversion)
     */
    fun estimateLuminanceDelta(bitmap: Bitmap): Double? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var darkCount = 0
        var midCount = 0
        var lightCount = 0
        var midLuminanceSum = 0.0

        for (i in pixels.indices) {
            val color = pixels[i]
            val a = (color ushr 24) and 0xFF
            if (a <= 32) continue
            val y = rec601Luminance(color)
            when {
                y <= 85 -> darkCount++
                y <= 170 -> { midCount++; midLuminanceSum += y }
                else -> lightCount++
            }
        }

        val total = darkCount + midCount + lightCount
        if (total == 0) return null

        val backgroundBin = when (maxOf(darkCount, midCount, lightCount)) {
            darkCount -> BG_DARK
            lightCount -> BG_LIGHT
            else -> BG_MID
        }
        val backgroundCount = maxOf(darkCount, midCount, lightCount)
        val secondLargest = when (backgroundBin) {
            BG_DARK -> maxOf(midCount, lightCount)
            BG_MID -> maxOf(darkCount, lightCount)
            else -> maxOf(darkCount, midCount)
        }
        val backgroundRatio = backgroundCount.toDouble() / total.toDouble()
        val dominance = backgroundCount.toDouble() / maxOf(secondLargest, 1).toDouble()
        if (backgroundRatio < 0.35 || dominance < 1.25) return null

        val midMean = if (midCount > 0) midLuminanceSum / midCount else 128.0
        val fgReference = when (backgroundBin) {
            BG_LIGHT -> 170.0
            BG_DARK -> 85.0
            else -> midMean
        }

        var darkerThanBg = 0
        var lighterThanBg = 0
        for (i in pixels.indices) {
            val color = pixels[i]
            val a = (color ushr 24) and 0xFF
            if (a <= 32) continue
            val y = rec601Luminance(color)
            val isBackground = when (backgroundBin) {
                BG_DARK -> y <= 85
                BG_LIGHT -> y > 170
                else -> Math.abs(y - midMean) <= 32.0
            }
            if (isBackground) continue
            if (y < fgReference) darkerThanBg++ else lighterThanBg++
        }

        val foregroundCount = darkerThanBg + lighterThanBg
        if (foregroundCount == 0 || foregroundCount.toDouble() / total.toDouble() < 0.08) return null

        return when {
            darkerThanBg.toDouble() / foregroundCount >= 0.6 -> -0.5 // darker fg → invert
            lighterThanBg.toDouble() / foregroundCount >= 0.6 -> 0.5 // lighter fg → standard
            else -> null
        }
    }

    /** Rec.601 luminance — used only for polarity estimation. */
    private fun rec601Luminance(color: Int): Int {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    // ── Mid-tone boost (used by the compatibility fallback only) ───────

    internal fun midToneBoost(p: Int): Int {
        if (p > 128) return (255 - ((1.0 - (p - 128) / 128.0) * (255 - p))).toInt()
        return ((1.0 - (128.0 - p) / 128.0) * p).toInt()
    }
}
