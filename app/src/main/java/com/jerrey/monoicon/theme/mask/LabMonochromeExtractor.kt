package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable

/** Native alpha rendering and source-aware finishing for generic HyperOS bitmaps. */
object LabMonochromeExtractor {

    // ── Background bins for polarity estimation ────────────────────────
    private const val BG_DARK = 10
    private const val BG_MID = 11
    private const val BG_LIGHT = 12

    /**
     * Native-monochrome path (AOSP / Pixel identical).
     *
     * Native monochrome layers are already alpha masks. Only the inverse
     * AdaptiveIcon inset is applied while rasterizing them; no luminance,
     * polarity or edge-plate processing is involved.
     */
    fun extractPixelNativeMonochrome(drawable: Drawable, targetSize: Int): Bitmap? {
        val previous = Rect(drawable.bounds)
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
        } finally {
            drawable.bounds = previous
        }
    }

    /** Coverage and tone remain independent for generic HyperOS bitmaps as well. */
    @Suppress("UNUSED_PARAMETER")
    fun extractFromBitmap(
        bitmap: Bitmap,
        luminanceDelta: Double?,
        borderRatio: Float = 1f,
    ): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        if (pixels.none { (it ushr 24) >= 32 }) return null
        return if (GeneratedMaskProcessor.isSingleTone(pixels)) {
            MaskBitmapIO.create(ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() }, width, height)
        } else Bitmap.createBitmap(GeneratedMaskProcessor.tonal(pixels, width, height),
            width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Estimates the inversion polarity for sources WITHOUT separate
     * background/foreground layers (bitmaps rendered from BitmapDrawable /
     * VectorDrawable / generic drawables).
     *
     * 3-bin background-luminance estimation decides the exterior plate's
     * baseline polarity — artwork itself always goes through
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

    // Retained as a reference curve for old baseline regression fixtures.

    internal fun midToneBoost(p: Int): Int {
        if (p > 128) return (255 - ((1.0 - (p - 128) / 128.0) * (255 - p))).toInt()
        return ((1.0 - (128.0 - p) / 128.0) * p).toInt()
    }
}
