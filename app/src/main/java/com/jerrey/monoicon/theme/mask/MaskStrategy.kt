package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.mask.MaskGenerator

/**
 * Pluggable mask generation strategy (Phase 5).
 *
 * Implementations define how a monochrome alpha mask is produced from
 * a raw drawable. [MaskGenerator.GenerateResult] carries the mask
 * bitmap plus metadata (source category, raw usage flag, cache hit)
 * consumed by the hook layer for logging and diagnostics.
 *
 * ## Cache contract
 * Strategies own their cache lookup/put — the [MaskGenerator.GenerateResult]
 * includes a [cacheHit] flag. The [MonochromeCache] key format must
 * include the theme ID and icon context to prevent cross-theme
 * collisions.
 */
interface MaskStrategy {

    /**
     * Generates (or retrieves from cache) the monochrome mask for [d].
     *
     * @param d        The source drawable.
     * @param identity Resolved "pkg/cls" identity; null → generate without cache.
     * @return A [MaskGenerator.GenerateResult] with the mask, or null on failure.
     */
    fun generate(d: Drawable, identity: String?): MaskGenerator.GenerateResult?

    /**
     * Configures the shared [MonochromeCache] and theme-scoped key prefix.
     *
     * Called once by [com.jerrey.monoicon.theme.ThemeManager] after theme
     * selection. The [themeId] and [context] are prepended to every cache
     * key produced by this strategy.
     */
    fun configureCache(themeId: String, context: String)

    companion object {
        // ── Source constants (synced with MonochromeCache / IconThemeHook) ──
        const val SOURCE_NATIVE = 1
        const val SOURCE_FOREGROUND = 2
        const val SOURCE_LUMINANCE = 3

        /**
         * Shared fallback renderer — Canvas draw + toLuminanceMask for
         * drawable types not handled by [DrawableConverter.toBitmap].
         */
        fun renderGenericToMask(drawable: Drawable): Bitmap? {
            return try {
                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
                DrawableConverter.toLuminanceMask(bmp)
            } catch (_: Throwable) { null }
        }
    }
}
