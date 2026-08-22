package com.jerrey.monoicon.theme.color

import android.graphics.drawable.Drawable

/**
 * Pixel Launcher icon color pair (Phase 6.6).
 *
 * @param foreground Glyph tint — Pixel's `themed_icon_color`.
 * @param background Background plate — Pixel's `themed_icon_background_color`
 *                   (alpha 0 = no plate, e.g. engine unavailable).
 */
data class ThemeColors(
    val foreground: Int,
    val background: Int,
) {
    companion object {
        /** Transparent pair — used when identity is unknown or extraction fails. */
        val TRANSPARENT = ThemeColors(0, 0)
    }
}

/**
 * Pluggable color strategy (Phase 5, 6.6).
 *
 * Implementations return the Pixel Launcher color pair (glyph tint +
 * background plate). [PixelColorStrategy] derives both from the Monet
 * wallpaper palette via [com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine].
 */
interface ColorStrategy {

    /**
     * Returns the icon colors for [identity].
     *
     * @param drawable Full-color drawable (unused by the dynamic strategy;
     *                 available for future per-app colors).
     * @param identity Resolved "pkg/cls" component identity (for logging).
     * @return The glyph/plate color pair.
     */
    fun extract(drawable: Drawable, identity: String): ThemeColors
}
