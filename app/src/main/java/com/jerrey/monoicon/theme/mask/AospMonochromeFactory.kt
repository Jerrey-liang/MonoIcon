package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import java.nio.ByteBuffer
import kotlin.math.round

/**
 * Faithful port of the AOSP android15-release monochrome mask core:
 *
 * 1. `MonochromeIconFactory` (iconloaderlib): the wrapped AdaptiveIcon is
 *    flattened into a square intermediate bitmap
 *    (`mBitmapSize = round(iconBitmapSize * 2 * viewportScale)`), starting
 *    from a BLACK canvas, then background and foreground are drawn on top.
 *    Equal-weight grayscale `(R+G+B)/3` is copied into an ALPHA_8 bitmap,
 *    contrast-stretched, and then POLARITY-FLIPPED when the average gray of
 *    the top+bottom edge strips (the overscan band that will be clipped
 *    away) maps above the mid-point of the stretched range:
 *    `flip = (edgeAverage - min) / range > 0.5` → `alpha = 255 - stretched`.
 *    There is NO mid-tone boost and NO luminance-delta step.
 *
 * 2. `BaseIconFactory.ClippedMonoDrawable` (iconloaderlib): the mono bitmap
 *    is drawn through `InsetDrawable(base, -extraInsetFraction)` into the
 *    output size, clipped to the platform adaptive-icon mask path (reflected
 *    `AdaptiveIconDrawable.getIconMask()`, with the documented cubic
 *    squircle as a fallback).
 */
object AospMonochromeFactory {

    // Thresholds validated by the plan replay (plan_silhouette_b_replay).
    private const val SILHOUETTE_MIN_TRANSPARENT_FRACTION = 0.05f
    private const val SILHOUETTE_MAX_FG_GRAY_STD = 20f
    private const val HAZE_THRESHOLD = 64
    private const val MIN_VISIBLE_ALPHA = 32

    /** Intermediate square size for a requested output size (AOSP mBitmapSize). */
    fun flatSize(targetSize: Int): Int {
        val size = targetSize.coerceAtLeast(1)
        val extraInset = AdaptiveIconDrawable.getExtraInsetFraction()
        val viewportScale = 1.0 / ((extraInset * 2.0) + 1.0)
        return round(size * 2.0 * viewportScale).toInt().coerceAtLeast(1)
    }

    /** Full pipeline: flatten → grayscale/stretch/edge-flip → clipped output. */
    fun wrap(drawable: AdaptiveIconDrawable, targetSize: Int): Bitmap? {
        val flatSize = flatSize(targetSize)
        val flat = renderFlat(drawable, flatSize) ?: return null
        var mono: Bitmap? = null
        return try {
            mono = generateMono(flat, targetSize) ?: return null
            rasterizeClipped(mono, targetSize)
        } finally {
            mono?.takeUnless { it.isRecycled }?.recycle()
            flat.takeUnless { it.isRecycled }?.recycle()
        }
    }

    /** Flattens background + foreground (transparent prefill; alpha = coverage). */
    fun renderFlat(drawable: AdaptiveIconDrawable, size: Int): Bitmap? {
        return try {
            // A fresh ARGB_8888 bitmap is transparent black, so pixels not
            // covered by any layer keep RGB=0 (the same grayscale value as
            // AOSP's opaque black prefill) while the alpha channel records
            // coverage — used to exclude prefill from the stretch statistics.
            val flat = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flat)
            drawable.background?.let {
                it.setBounds(0, 0, size, size)
                it.draw(canvas)
            }
            drawable.foreground?.let {
                it.setBounds(0, 0, size, size)
                it.draw(canvas)
            }
            flat
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * AOSP `MonochromeIconFactory.generateMono()` with two robustness fixes
     * validated by the plan replay:
     * - prefill pixels (no layer coverage) are excluded from min/max and
     *   forced to alpha 0;
     * - the edge strip samples the OUTER RING OF THE VISIBLE VIEWPORT
     *   instead of the clipped overscan band (an inset background layer
     *   would otherwise pollute the band with prefill);
     * - after a flip, mask values below [HAZE_THRESHOLD] are zeroed so the
     *   bright plate-adjacent content does not haze over the whole icon.
     */
    fun generateMono(flat: Bitmap, targetSize: Int): Bitmap? {
        val size = flat.width
        val pixels = IntArray(size * size)
        flat.getPixels(pixels, 0, size, 0, 0, size, size)

        val gray = ByteArray(pixels.size)
        val prefill = BooleanArray(pixels.size)
        var min = 255
        var max = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val g = (((p ushr 16) and 0xFF) + ((p ushr 8) and 0xFF) + (p and 0xFF)) / 3
            gray[i] = g.toByte()
            if (((p ushr 24) and 0xFF) == 0) {
                prefill[i] = true
            } else {
                min = minOf(min, g)
                max = maxOf(max, g)
            }
        }

        if (min < max) {
            val range = (max - min).toFloat()

            // Visible-viewport outer ring: the viewport is the center 2/3 of
            // the flat; sample its outermost `bandRows` rows on both sides
            // (the AOSP band size, relocated to where the plate really is).
            val bandRows = (size - targetSize.coerceIn(1, size)) / 2
            val viewportTop = size / 6
            var edgeSum = 0L
            var edgeCount = 0
            val firstBand = viewportTop until minOf(viewportTop + bandRows, size - viewportTop)
            val secondBand = (size - viewportTop - bandRows).coerceAtLeast(viewportTop) until (size - viewportTop)
            for (y in firstBand) {
                val row = y * size
                for (x in 0 until size) {
                    edgeSum += (gray[row + x].toInt() and 0xFF)
                    edgeCount++
                }
            }
            for (y in secondBand) {
                val row = y * size
                for (x in 0 until size) {
                    edgeSum += (gray[row + x].toInt() and 0xFF)
                    edgeCount++
                }
            }
            val flip = if (edgeCount > 0) {
                val edgeAverage = edgeSum.toDouble() / edgeCount
                edgeMapped(edgeAverage, min, max) > 0.5
            } else {
                false
            }

            for (i in gray.indices) {
                if (prefill[i]) {
                    gray[i] = 0
                    continue
                }
                val p = gray[i].toInt() and 0xFF
                var value = round(((p - min) * 255f) / range).toInt().coerceIn(0, 255)
                if (flip) {
                    value = 255 - value
                    if (value < HAZE_THRESHOLD) value = 0
                }
                gray[i] = value.toByte()
            }
        }
        // min == max: AOSP leaves the bytes unchanged (flat grayscale pass-through).

        val mono = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        mono.copyPixelsFromBuffer(ByteBuffer.wrap(gray))
        return mono
    }

    /** AOSP flip predicate: (edgeAverage - min) / range > 0.5. */
    internal fun edgeMapped(edgeAverage: Double, min: Int, max: Int): Double =
        if (min < max) (edgeAverage - min) / (max - min) else 0.0

    /**
     * `ClippedMonoDrawable.draw()`: InsetDrawable(-extraInset) + clip to the
     * adaptive-icon mask path.
     */
    fun rasterizeClipped(mono: Bitmap, targetSize: Int): Bitmap? {
        return try {
            val size = targetSize.coerceAtLeast(1)
            val inset = InsetDrawable(
                MonoBitmapDrawable(mono),
                -AdaptiveIconDrawable.getExtraInsetFraction(),
            )
            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
            val canvas = Canvas(result)
            val maskPath = iconMaskPath(size)
            val save = if (maskPath != null) {
                canvas.save()
                canvas.clipPath(maskPath)
                canvas.saveCount - 1
            } else {
                -1
            }
            inset.setBounds(0, 0, size, size)
            inset.draw(canvas)
            if (save >= 0) canvas.restoreToCount(save)
            result
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Foreground alpha-coverage silhouette (plan "silhouette" branch):
     * render the foreground layer alone, keep alpha >= 32 as the glyph and
     * rasterize with the ClippedMonoDrawable geometry.
     */
    fun renderForegroundSilhouette(drawable: Drawable, targetSize: Int): Bitmap? {
        val flatSize = flatSize(targetSize)
        val raw: Bitmap = try {
            val bmp = Bitmap.createBitmap(flatSize, flatSize, Bitmap.Config.ALPHA_8)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, flatSize, flatSize)
            drawable.draw(canvas)
            bmp
        } catch (_: Throwable) {
            null
        } ?: return null
        var mono: Bitmap? = null
        return try {
            val pixels = IntArray(flatSize * flatSize)
            raw.getPixels(pixels, 0, flatSize, 0, 0, flatSize, flatSize)
            val mapped = ByteArray(pixels.size)
            for (i in pixels.indices) {
                mapped[i] = if (((pixels[i] ushr 24) and 0xFF) >= MIN_VISIBLE_ALPHA) 255.toByte() else 0
            }
            mono = Bitmap.createBitmap(flatSize, flatSize, Bitmap.Config.ALPHA_8)
            mono.copyPixelsFromBuffer(ByteBuffer.wrap(mapped))
            rasterizeClipped(mono, targetSize)
        } catch (_: Throwable) {
            null
        } finally {
            mono?.takeUnless { it.isRecycled }?.recycle()
            raw.takeUnless { it.isRecycled }?.recycle()
        }
    }

    /**
     * Foreground-layer structure used by the silhouette/B decision:
     * transparency fractions measured in flat space and inside the fg's own
     * bounding box, plus the grayscale standard deviation of its opaque
     * content. Full-bleed artwork badges (high std) must not collapse into a
     * flat silhouette block.
     */
    fun foregroundStructure(drawable: Drawable, targetSize: Int): ForegroundStructure? {
        val flatSize = flatSize(targetSize)
        val bmp: Bitmap = try {
            val bitmap = Bitmap.createBitmap(flatSize, flatSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, flatSize, flatSize)
            drawable.draw(canvas)
            bitmap
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            val pixels = IntArray(flatSize * flatSize)
            bmp.getPixels(pixels, 0, flatSize, 0, 0, flatSize, flatSize)

            val opaque = BooleanArray(pixels.size)
            var left = flatSize
            var right = -1
            var top = flatSize
            var bottom = -1
            var opaqueCount = 0
            for (i in pixels.indices) {
                if (((pixels[i] ushr 24) and 0xFF) >= MIN_VISIBLE_ALPHA) {
                    opaque[i] = true
                    opaqueCount++
                    val x = i % flatSize
                    val y = i / flatSize
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
            val flatTransparentFraction = 1f - opaqueCount.toFloat() / pixels.size
            if (opaqueCount == 0) {
                return ForegroundStructure(
                    flatTransparentFraction = flatTransparentFraction,
                    bboxTransparentFraction = 0f,
                    grayStd = 0f,
                )
            }

            var bboxOpaque = 0
            var sum = 0.0
            var sumSq = 0.0
            for (y in top..bottom) {
                for (x in left..right) {
                    val i = y * flatSize + x
                    if (opaque[i]) {
                        bboxOpaque++
                        val p = pixels[i]
                        val g = (((p ushr 16) and 0xFF) + ((p ushr 8) and 0xFF) + (p and 0xFF)) / 3.0
                        sum += g
                        sumSq += g * g
                    }
                }
            }
            val bboxArea = (bottom - top + 1) * (right - left + 1)
            val bboxTransparentFraction = 1f - bboxOpaque.toFloat() / bboxArea
            val mean = sum / bboxOpaque
            val variance = (sumSq / bboxOpaque) - mean * mean
            val grayStd = kotlin.math.sqrt(variance.coerceAtLeast(0.0)).toFloat()
            ForegroundStructure(
                flatTransparentFraction = flatTransparentFraction,
                bboxTransparentFraction = bboxTransparentFraction,
                grayStd = grayStd,
            )
        } catch (_: Throwable) {
            null
        } finally {
            bmp.takeUnless { it.isRecycled }?.recycle()
        }
    }

    /** Foreground structure metrics + the silhouette branch decision. */
    data class ForegroundStructure(
        val flatTransparentFraction: Float,
        val bboxTransparentFraction: Float,
        val grayStd: Float,
    ) {
        val useSilhouette: Boolean
            get() = flatTransparentFraction >= SILHOUETTE_MIN_TRANSPARENT_FRACTION &&
                bboxTransparentFraction >= SILHOUETTE_MIN_TRANSPARENT_FRACTION &&
                grayStd < SILHOUETTE_MAX_FG_GRAY_STD
    }

    /**
     * Platform adaptive-icon mask path at the requested size. AOSP obtains it
     * from a scratch AdaptiveIconDrawable; `getIconMask()` is hidden API, so
     * it is reflected with the documented cubic squircle as fallback. Null
     * means "skip clipping" — the plate corners are transparent anyway.
     */
    private fun iconMaskPath(size: Int): Path? {
        val reflected = try {
            val crop = AdaptiveIconDrawable(ColorDrawable(Color.BLACK), null)
            crop.setBounds(0, 0, size, size)
            val method = AdaptiveIconDrawable::class.java.getDeclaredMethod("getIconMask")
            method.isAccessible = true
            method.invoke(crop) as? Path
        } catch (_: Throwable) {
            null
        }
        return reflected ?: defaultSquirclePath(size)
    }

    /** The documented default AdaptiveIconDrawable mask (100×100 cubic squircle). */
    private fun defaultSquirclePath(size: Int): Path? {
        return try {
            val s = size / 100f
            Path().apply {
                moveTo(50f * s, 0f)
                cubicTo(10f * s, 0f, 0f, 10f * s, 0f, 50f * s)
                cubicTo(0f, 90f * s, 10f * s, 100f * s, 50f * s, 100f * s)
                cubicTo(90f * s, 100f * s, 100f * s, 90f * s, 100f * s, 50f * s)
                cubicTo(100f * s, 10f * s, 90f * s, 0f, 50f * s, 0f)
                close()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Minimal bitmap-backed drawable used by the inset raster step. */
    private class MonoBitmapDrawable(private val bitmap: Bitmap) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            if (!bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, null, bounds, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
