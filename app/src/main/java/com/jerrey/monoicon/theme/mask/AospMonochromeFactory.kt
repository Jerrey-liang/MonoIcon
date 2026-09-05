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

    /** Flattens background + foreground onto a black square canvas (AOSP wrap). */
    fun renderFlat(drawable: AdaptiveIconDrawable, size: Int): Bitmap? {
        return try {
            val flat = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flat)
            canvas.drawColor(Color.BLACK)
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
     * AOSP `MonochromeIconFactory.generateMono()`:
     * grayscale → min/max stretch → edge-strip polarity flip.
     */
    fun generateMono(flat: Bitmap, targetSize: Int): Bitmap? {
        val size = flat.width
        val pixels = IntArray(size * size)
        flat.getPixels(pixels, 0, size, 0, 0, size, size)

        val gray = ByteArray(pixels.size)
        var min = 255
        var max = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val g = (((p ushr 16) and 0xFF) + ((p ushr 8) and 0xFF) + (p and 0xFF)) / 3
            gray[i] = g.toByte()
            min = minOf(min, g)
            max = maxOf(max, g)
        }

        if (min < max) {
            val range = (max - min).toFloat()

            // Edge strip: the top+bottom band outside the visible viewport.
            // mEdgePixelLength = mBitmapSize * (mBitmapSize - iconBitmapSize) / 2
            // → band rows on both sides, every column included.
            val bandRows = ((size - targetSize.coerceIn(1, size)) / 2)
            var edgeSum = 0L
            var edgeCount = 0
            for (y in 0 until bandRows) {
                val topRow = y * size
                val bottomRow = (size - 1 - y) * size
                for (x in 0 until size) {
                    edgeSum += (gray[topRow + x].toInt() and 0xFF)
                    edgeSum += (gray[bottomRow + x].toInt() and 0xFF)
                    edgeCount += 2
                }
            }
            val flip = if (edgeCount > 0) {
                val edgeAverage = edgeSum.toDouble() / edgeCount
                edgeMapped(edgeAverage, min, max) > 0.5
            } else {
                false
            }

            for (i in gray.indices) {
                val p = gray[i].toInt() and 0xFF
                val stretched = round(((p - min) * 255f) / range).toInt().coerceIn(0, 255)
                gray[i] = (if (flip) 255 - stretched else stretched).toByte()
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
