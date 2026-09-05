package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Faithful port of AOSP android15-release `IconNormalizer.getScale()` for
 * non-adaptive drawables (the branch used by
 * `BaseIconFactory.wrapToAdaptiveIcon`):
 *
 * 1. Rasterize the drawable at intrinsic size (capped at 2× the launcher
 *    icon size), alpha channel only.
 * 2. Scan each row for the left/right-most pixel with alpha > 40.
 * 3. Convexify both borders (`convertToConvexArray`, transcribed verbatim).
 * 4. `hullByRect = hullArea / rectArea`; when the hull is rounder than a
 *    circle use `MAX_CIRCLE_AREA_FACTOR`, otherwise linearly interpolate
 *    between the square and circle area targets.
 * 5. `scale = sqrt(scaleRequired / areaScale)` when the visible area exceeds
 *    the target, otherwise 1 — the normalizer only ever SHRINKS.
 */
object IconNormalizerCompat {

    private const val MAX_SQUARE_AREA_FACTOR = 375.0f / 576f
    private const val MAX_CIRCLE_AREA_FACTOR = 380.0f / 576f
    private const val CIRCLE_AREA_BY_RECT = (PI / 4).toFloat()
    private const val LINEAR_SCALE_SLOPE =
        (MAX_CIRCLE_AREA_FACTOR - MAX_SQUARE_AREA_FACTOR) / (1f - CIRCLE_AREA_BY_RECT)

    private const val MIN_VISIBLE_ALPHA = 40

    /** @param iconBitmapSize the launcher icon bitmap size (AOSP mMaxSize = 2× it). */
    fun getScale(drawable: Drawable, iconBitmapSize: Int): Float {
        val maxSize = (iconBitmapSize.coerceAtLeast(1) * 2)
        var width = drawable.intrinsicWidth
        var height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0) {
            width = if (width <= 0 || width > maxSize) maxSize else width
            height = if (height <= 0 || height > maxSize) maxSize else height
        } else if (width > maxSize || height > maxSize) {
            val max = maxOf(width, height)
            width = maxSize * width / max
            height = maxSize * height / max
        }
        if (width <= 0 || height <= 0) return 1f

        val alpha = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        return try {
            val oldBounds = android.graphics.Rect(drawable.bounds)
            val canvas = Canvas(alpha)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            drawable.bounds = oldBounds

            val pixels = IntArray(width * height)
            alpha.getPixels(pixels, 0, width, 0, 0, width, height)
            val a = ByteArray(pixels.size)
            for (i in pixels.indices) a[i] = ((pixels[i] ushr 24) and 0xFF).toByte()

            val leftBorder = FloatArray(height) { -1f }
            val rightBorder = FloatArray(height) { -1f }
            var topY = -1
            var bottomY = -1
            var leftX = width + 1
            var rightX = -1
            for (y in 0 until height) {
                var firstX = -1
                var lastX = -1
                for (x in 0 until width) {
                    if ((a[y * width + x].toInt() and 0xFF) > MIN_VISIBLE_ALPHA) {
                        if (firstX == -1) firstX = x
                        lastX = x
                    }
                }
                leftBorder[y] = firstX.toFloat()
                rightBorder[y] = lastX.toFloat()
                if (firstX != -1) {
                    bottomY = y
                    if (topY == -1) topY = y
                    leftX = minOf(leftX, firstX)
                    rightX = maxOf(rightX, lastX)
                }
            }
            if (topY == -1 || rightX == -1) return 1f

            convertToConvexArray(leftBorder, 1, topY, bottomY, height)
            convertToConvexArray(rightBorder, -1, topY, bottomY, height)

            var area = 0f
            for (y in 0 until height) {
                if (leftBorder[y] <= -1f) continue
                area += rightBorder[y] - leftBorder[y] + 1f
            }
            val rectArea = (bottomY + 1 - topY) * (rightX + 1 - leftX).toFloat()
            val fullArea = (width * height).toFloat()

            val hullByRect = area / rectArea
            val scaleRequired = if (hullByRect < CIRCLE_AREA_BY_RECT) {
                MAX_CIRCLE_AREA_FACTOR
            } else {
                MAX_SQUARE_AREA_FACTOR + LINEAR_SCALE_SLOPE * (1f - hullByRect)
            }
            val areaScale = area / fullArea
            if (areaScale > scaleRequired) sqrt(scaleRequired / areaScale) else 1f
        } catch (_: Throwable) {
            1f
        } finally {
            alpha.recycle()
        }
    }

    /**
     * AOSP `IconNormalizer.convertToConvexArray`, transcribed verbatim:
     * fills missing rows and removes concave notches from the per-row
     * border, keeping the tangent angle convex for [direction].
     */
    internal fun convertToConvexArray(
        xCoordinates: FloatArray,
        direction: Int,
        topY: Int,
        bottomY: Int,
        height: Int,
    ) {
        val angles = FloatArray(height)
        val first = topY
        var last = -1
        var lastAngle = Float.MAX_VALUE
        for (i in topY + 1..bottomY) {
            if (xCoordinates[i] <= -1f) continue
            var start: Int
            if (lastAngle == Float.MAX_VALUE) {
                start = first
            } else {
                var currentAngle = (xCoordinates[i] - xCoordinates[last]) / (i - last)
                start = last
                if ((currentAngle - lastAngle) * direction < 0) {
                    while (start > first) {
                        start--
                        currentAngle = (xCoordinates[i] - xCoordinates[start]) / (i - start)
                        if ((currentAngle - angles[start]) * direction >= 0) {
                            break
                        }
                    }
                }
            }
            lastAngle = (xCoordinates[i] - xCoordinates[start]) / (i - start)
            for (j in start until i) {
                angles[j] = lastAngle
                xCoordinates[j] = xCoordinates[start] + lastAngle * (j - start)
            }
            last = i
        }
    }
}
