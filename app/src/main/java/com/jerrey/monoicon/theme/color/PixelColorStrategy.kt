package com.jerrey.monoicon.theme.color

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.color.IconColorExtractor
import com.jerrey.monoicon.color.PixelStyleColorExtractor
import com.jerrey.monoicon.logging.logw

/**
 * Pixel Launcher HSV dominant hue color extraction (Phase 5).
 *
 * Fully delegates to [PixelStyleColorExtractor] (the existing Phase 3.11
 * implementation). The drawable is rendered to a full-color bitmap if
 * needed — this is the same rendering logic that Phase 4's
 * `extractEarlyIconColor` and `extractOriginalIconColor` use.
 *
 * No algorithm copy: the sole responsibility of this strategy is to
 * prepare the drawable for the extractor.
 */
class PixelColorStrategy : ColorStrategy {

    private val extractor = PixelStyleColorExtractor()

    override fun extract(drawable: Drawable, identity: String): Int {
        return try {
            val colorBitmap = when (drawable) {
                is AdaptiveIconDrawable -> renderAdaptive(drawable)
                is BitmapDrawable -> drawable.bitmap
                else -> renderGeneric(drawable)
            }

            if (colorBitmap != null && !colorBitmap.isRecycled) {
                val color = extractor.extractDominantColor(colorBitmap)
                if (drawable !is BitmapDrawable) {
                    colorBitmap.recycle()
                }
                color
            } else {
                IconColorExtractor.FALLBACK_COLOR
            }
        } catch (t: Throwable) {
            logw(TAG, "PixelColorStrategy failed for $identity: ${t.message}")
            IconColorExtractor.FALLBACK_COLOR
        }
    }

    private fun renderAdaptive(d: AdaptiveIconDrawable): Bitmap {
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return bmp
    }

    private fun renderGeneric(d: Drawable): Bitmap {
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return bmp
    }

    companion object {
        private const val TAG = "MonoIcon.ColorStrategy"
    }
}
