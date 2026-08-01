package com.jerrey.monoicon.launcher

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.core.Result

/**
 * Abstracts launcher-specific icon handling so that the hook layer
 * ([com.jerrey.monoicon.hook.IconThemeHook]) does not need to know
 * the internal details of each launcher's icon representation.
 *
 * ## Responsibilities
 * - **Extraction**: Convert the launcher's icon [Drawable] into a standard
 *   [Bitmap] that the image pipeline can process.
 * - **Wrapping**: Convert the processed [Bitmap] back into a [Drawable]
 *   that the launcher can render.
 *
 * ## Implementations
 * Each supported launcher provides one implementation:
 * - [HyperOsLauncherAdapter] for Xiaomi HyperOS (`com.miui.home`)
 * - Future: PixelLauncherAdapter, AospLauncherAdapter, etc.
 */
interface LauncherAdapter {

    /**
     * The package name of the launcher this adapter supports.
     * Used to select the correct adapter at hook time.
     */
    val supportedPackage: String

    /**
     * Extracts a standard [Bitmap] from the launcher's icon [Drawable].
     *
     * Different launchers wrap icons differently (AdaptiveIconDrawable,
     * BitmapDrawable, custom wrappers, etc.). This method handles
     * the launcher-specific unwrapping logic.
     *
     * @param drawable The launcher's original icon Drawable.
     * @return A bitmap suitable for image processing, or an error.
     */
    fun extractIcon(drawable: Drawable): Result<Bitmap>

    /**
     * Wraps a processed [Bitmap] back into a [Drawable] the launcher
     * expects. This is the inverse of [extractIcon].
     *
     * @param bitmap The processed monochrome/theme-colored bitmap.
     * @return A Drawable the launcher can render, or an error.
     */
    fun wrapResult(bitmap: Bitmap): Result<Drawable>
}
