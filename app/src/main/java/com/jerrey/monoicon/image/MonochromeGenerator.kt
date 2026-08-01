package com.jerrey.monoicon.image

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log

/**
 * Produces monochrome [android.graphics.drawable.Drawable]s from
 * alpha-mask bitmaps.
 *
 * ## Input contract
 * [bitmap] must already be the output of [DrawableConverter.toBitmap]:
 * ARGB_8888, RGB = white, alpha = original icon shape. The launcher will
 * tint this drawable black
 * ([android.graphics.drawable.Drawable.setTint] 0xFF000000), so the
 * RGB value is overwritten and only alpha defines the rendered shape.
 *
 * ## Design notes
 * - Wrapping a [Bitmap] in a [BitmapDrawable] is the most compatible
 *   baseline. More exotic drawable types are intentionally NOT used yet —
 *   Phase 2.3a will verify the launcher accepts this type at runtime.
 * - `Bitmap.extractAlpha()` (ALPHA_8) is deliberately deferred: its
 *   compatibility with the launcher's monochrome path is unverified.
 *   It will be evaluated only as a performance optimization after
 *   correctness is proven.
 */
object MonochromeGenerator {

    private const val TAG = "MonoIcon.Monochrome"

    /**
     * Wraps [bitmap] into a [BitmapDrawable] ready for the launcher's
     * monochrome path.
     *
     * @param bitmap Alpha-mask bitmap (ARGB_8888, RGB=white, alpha=shape).
     * @return A [BitmapDrawable] wrapping [bitmap], or `null` if [bitmap]
     *         is null, recycled, or has invalid dimensions.
     */
    fun create(bitmap: Bitmap?): BitmapDrawable? {
        if (bitmap == null) return null
        if (bitmap.isRecycled) {
            Log.w(TAG, "create: bitmap already recycled, aborting")
            return null
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            Log.w(TAG, "create: invalid dimensions ${bitmap.width}x${bitmap.height}, aborting")
            return null
        }

        return BitmapDrawable(null, bitmap)
    }
}
