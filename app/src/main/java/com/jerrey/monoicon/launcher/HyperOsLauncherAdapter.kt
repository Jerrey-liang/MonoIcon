package com.jerrey.monoicon.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.core.Result
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.logging.logw

private const val TAG = "MonoIcon.HyperOS"

/**
 * [LauncherAdapter] for Xiaomi HyperOS Launcher (`com.miui.home`).
 *
 * HyperOS Launcher uses standard Android [AdaptiveIconDrawable] for
 * apps targeting API 26+, and [BitmapDrawable] for legacy apps.
 * This adapter handles both cases.
 *
 * ## Future Work
 * HyperOS may apply additional transformations (e.g., rounded corners,
 * custom shadows) on top of the standard icon. These will need to be
 * handled in the extraction step once reverse-engineered.
 */
internal class HyperOsLauncherAdapter : LauncherAdapter {

    override val supportedPackage: String = "com.miui.home"

    override fun extractIcon(drawable: Drawable): Result<Bitmap> {
        return Result.of {
            logd(TAG, "Extracting icon from: ${drawable.javaClass.simpleName}")

            val bitmap = when (drawable) {
                is AdaptiveIconDrawable -> {
                    // Extract the foreground layer only.
                    // The background layer is launcher-specific and
                    // should not affect monochrome conversion.
                    extractFromAdaptiveIcon(drawable)
                }
                is BitmapDrawable -> {
                    val inner = drawable.bitmap
                    if (inner != null) {
                        inner.copy(inner.config ?: Bitmap.Config.ARGB_8888, inner.isMutable)
                    } else {
                        // BitmapDrawable with null bitmap — unlikely but handle gracefully
                        logw(TAG, "BitmapDrawable has null bitmap, falling back to canvas render")
                        renderDrawableToBitmap(drawable)
                    }
                }
                else -> {
                    // Unknown Drawable type — render to a bitmap via Canvas.
                    // This is the safest generic fallback.
                    logw(TAG, "Unknown drawable type: ${drawable.javaClass.name}, using canvas fallback")
                    renderDrawableToBitmap(drawable)
                }
            }

            bitmap
        }
    }

    override fun wrapResult(bitmap: Bitmap): Result<Drawable> {
        return Result.of {
            BitmapDrawable(null, bitmap).also {
                logd(TAG, "Wrapped result bitmap: ${bitmap.width}x${bitmap.height}")
            }
        }
    }

    /**
     * Extracts the foreground layer from an [AdaptiveIconDrawable].
     *
     * We only process the foreground because:
     * 1. The background is typically a solid color or simple shape
     *    that the launcher controls, not the app.
     * 2. The foreground contains the actual app icon artwork.
     * 3. Monochrome conversion of the background would produce
     *    a solid block, which is undesirable.
     */
    private fun extractFromAdaptiveIcon(adaptive: AdaptiveIconDrawable): Bitmap {
        val foreground = adaptive.foreground ?: return renderDrawableToBitmap(adaptive)

        return renderDrawableToBitmap(foreground)
    }

    /**
     * Renders any [Drawable] to a [Bitmap] by drawing it onto a [Canvas].
     * This is the most general extraction method and works for any [Drawable] subclass.
     */
    private fun renderDrawableToBitmap(drawable: Drawable): Bitmap {
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)

        return bitmap
    }
}
