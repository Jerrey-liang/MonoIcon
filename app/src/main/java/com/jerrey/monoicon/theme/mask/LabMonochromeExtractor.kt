package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Pixel Launcher compatible monochrome mask extractor (Phase 6.3).
 *
 * ## Mask pixel values
 * Equi-weight grayscale matching Pixel Launcher `MonochromeIconFactory`
 * ColorMatrix (lines 150-158): alpha = (R + G + B) / 3, scaled to [0, 100].
 *
 * ## Luminance measurement
 * [computeLuminanceDelta] and [averageLabL] use CIELAB L* (D65, CIE 1931
 * 2°), matching Pixel Launcher `LuminanceComputer.computeLuminance()`
 * which calls `n2.a.e()` → RGB→XYZ→LAB.
 *
 * ## Pipeline
 * 1. Render full AdaptiveIconDrawable → ARGB_8888
 * 2. Equi-weight grayscale per pixel → [0, 100]
 * 3. Contrast stretch + mid-tone boost (Pixel Launcher formula)
 * Pixel stores [luminanceDelta] as metadata but does not alter the cached
 * alpha mask with it. The mask itself is always the contrast-stretched
 * grayscale render.
 */
object LabMonochromeExtractor {

    // ── Reference white (D65) in XYZ ──────────────────────────────────
    private const val REF_X = 95.047
    private const val REF_Y = 100.000
    private const val REF_Z = 108.883

    // ── sRGB gamma threshold ──────────────────────────────────────────
    private const val GAMMA_THRESHOLD = 0.04045

    // ── Luminance sampling resolution (matches Pixel Launcher) ────────
    private const val SAMPLE_SIZE = 64

    // ── Background bins for polarity estimation (Phase 6.6) ────────────
    private const val BG_DARK = 10
    private const val BG_MID = 11
    private const val BG_LIGHT = 12

    /**
     * Generates an alpha mask from the full AdaptiveIconDrawable.
     * Uses default polarity (darker pixels → opaque). Call
     * [extract] with [luminanceDelta] for Pixel-polarity-aware output.
     */
    fun extract(drawable: AdaptiveIconDrawable): Bitmap? {
        return extract(drawable, luminanceDelta = null)
    }

    /**
     * Generates an alpha mask from separately rendered adaptive-icon layers.
     * [luminanceDelta] is accepted for call-site compatibility and metadata,
     * but Pixel does not invert the generated alpha bitmap from this value.
     */
    fun extract(drawable: AdaptiveIconDrawable, luminanceDelta: Double?): Bitmap? {
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)

        // Pixel flattens the background and foreground layers directly rather
        // than drawing the outer AdaptiveIconDrawable (which would clip them).
        val render = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(render)
        canvas.drawColor(Color.BLACK)
        drawable.background?.let {
            it.setBounds(0, 0, w, h)
            it.draw(canvas)
        }
        drawable.foreground?.let {
            it.setBounds(0, 0, w, h)
            it.draw(canvas)
        }

        return try {
            extractFromBitmap(render, luminanceDelta)
        } finally {
            render.recycle()
        }
    }

    /**
     * Phase 6.6: the Pixel Launcher mask pipeline for an already-rendered
     * bitmap — used by every fallback tier so ALL mask sources share the
     * exact same algorithm:
     * 1. Equi-weight grayscale `(R+G+B)/3 → [0,100]`
     * 2. min-max contrast stretch
     * 3. mid-tone boost
     * 4. optional inversion when [luminanceDelta] < 0
     *
     * The input [bitmap] is never recycled (the caller may still need it,
     * e.g. as the cache fingerprint source).
     */
    fun extractFromBitmap(bitmap: Bitmap, luminanceDelta: Double?): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Step 2: equi-weight grayscale → [0, 100] range
        // Matches Pixel Launcher ColorMatrix: [R×0.3333 + G×0.3333 + B×0.3333]
        // (MonochromeIconFactory lines 150-158, 186)
        val lValues = FloatArray(pixels.size)
        var validCount = 0
        var lMin = Float.MAX_VALUE
        var lMax = Float.MIN_VALUE

        for (i in pixels.indices) {
            val a = (pixels[i] shr 24) and 0xFF
            if (a < 32) { lValues[i] = -1f; continue }
            val r = ((pixels[i] shr 16) and 0xFF) / 255f
            val g = ((pixels[i] shr 8) and 0xFF) / 255f
            val b = (pixels[i] and 0xFF) / 255f
            val l = ((r + g + b) / 3.0f) * 100f
            lValues[i] = l
            lMin = minOf(lMin, l)
            lMax = maxOf(lMax, l)
            validCount++
        }

        if (validCount < 4 || lMax - lMin < 1f) return null

        // Step 3: contrast stretch + Pixel's mid-tone boost.
        val range = lMax - lMin
        val maskPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            if (lValues[i] < 0f) { maskPixels[i] = 0; continue }
            var alpha = ((lValues[i] - lMin) * 255f / range).toInt()
            alpha = midToneBoost(alpha)
            alpha = alpha.coerceIn(0, 255)
            maskPixels[i] = (alpha shl 24)
        }

        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mask.setPixels(maskPixels, 0, w, 0, 0, w, h)
        return mask
    }

    /**
     * Phase 6.6: estimates the inversion polarity for sources WITHOUT
     * separate background/foreground layers (bitmaps rendered from
     * BitmapDrawable / VectorDrawable / generic drawables).
     *
     * Reuses the proven 3-bin background-luminance estimation, but ONLY to
     * decide the direction — the mask pixels themselves always go through
     * [extractFromBitmap] (Pixel Launcher's formula):
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

    /**
     * Pixel Launcher luminance delta (Phase 6.3).
     *
     * Matches `MonoIconThemeController.createThemedBitmap()` lines 172-184:
     * 1. Render foreground alone → `averageLabL()` → fg_L*_avg
     * 2. Clear canvas to black → render background → `averageLabL()` → bg_L*_avg
     * 3. delta = fg_L*_avg - bg_L*_avg
     *
     * @return CIELAB L* mean difference (positive = foreground brighter).
     *         NaN if the drawable has no bg/fg or rendering fails.
     */
    fun computeLuminanceDelta(drawable: AdaptiveIconDrawable): Double {
        val fg = drawable.foreground ?: return Double.NaN
        val bg = drawable.background ?: return Double.NaN
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bmp)

            // Foreground alone (on transparent → black canvas)
            fg.setBounds(0, 0, w, h)
            fg.draw(canvas)
            val fgL = averageLabL(bmp)

            // Pixel compares foreground-only against background+foreground.
            canvas.drawColor(0xFF000000.toInt())
            bg.setBounds(0, 0, w, h)
            bg.draw(canvas)
            fg.draw(canvas)
            val combinedL = averageLabL(bmp)

            fgL - combinedL
        } catch (_: Throwable) {
            Double.NaN
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Pixel Launcher AVERAGE luminance across a 64×64 scaled sample.
     * Matches `LuminanceComputer.computeLuminance()` with `ComputationType.AVERAGE`.
     */
    fun averageLabL(bitmap: Bitmap): Double {
        val scaled = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        val w = scaled.width; val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        var sum = 0.0; var count = 0
        for (p in pixels) {
            if (((p shr 24) and 0xFF) < 32) continue
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val bl = (p and 0xFF) / 255f
            sum += rgbToLabL(r, g, bl) / 100.0
            count++
        }
        return if (count > 0) sum / count else Double.NaN
    }

    // ── Pixel Launcher mid-tone boost (MonochromeIconFactory) ──────────

    private fun midToneBoost(p: Int): Int {
        if (p > 128) return (255 - ((1.0 - (p - 128) / 128.0) * (255 - p))).toInt()
        return ((1.0 - (128.0 - p) / 128.0) * p).toInt()
    }

    // ── sRGB → CIELAB L* (D65, CIE 1931 2°) ──────────────────────────

    private fun rgbToLabL(r: Float, g: Float, b: Float): Float {
        val rl = if (r <= GAMMA_THRESHOLD) r / 12.92 else ((r + 0.055) / 1.055).pow(2.4)
        val gl = if (g <= GAMMA_THRESHOLD) g / 12.92 else ((g + 0.055) / 1.055).pow(2.4)
        val bl = if (b <= GAMMA_THRESHOLD) b / 12.92 else ((b + 0.055) / 1.055).pow(2.4)
        val y = 0.2126 * rl + 0.7152 * gl + 0.0722 * bl
        val fy = if (y / REF_Y > 0.008856) cbrt(y / REF_Y) else (7.787 * y / REF_Y) + (16.0 / 116.0)
        return (116.0 * fy - 16.0).toFloat()
    }
}
