package com.jerrey.monoicon.theme

/**
 * A set of colors extracted from the system theme (Material You / Monet)
 * or configured by the user, used to tint monochrome icons.
 *
 * All color values are ARGB [Int] in the sRGB color space,
 * as returned by [android.graphics.Color].
 *
 * @param primaryColor The dominant theme color (accent).
 * @param surfaceColor Background color for surfaces (used for icon background).
 * @param onSurfaceColor Foreground color for surfaces (used for icon foreground).
 * @param isDarkMode Whether the current system theme is in dark mode.
 * @param adaptiveColors Ordered list of tonal palette colors from lightest to darkest.
 *                       Used for multi-tone icon coloring in future versions.
 */
data class MonoTheme(
    val primaryColor: Int,
    val surfaceColor: Int,
    val onSurfaceColor: Int,
    val isDarkMode: Boolean = false,
    val adaptiveColors: List<Int> = emptyList()
) {
    /**
     * Convenience: the color to apply to the monochrome icon foreground.
     * Defaults to [onSurfaceColor] but may be overridden in future
     * multi-tone themes.
     */
    val foregroundColor: Int get() = onSurfaceColor

    companion object {
        /**
         * A safe neutral fallback theme used when system color extraction fails.
         * Light theme with Material baseline colors.
         */
        val FALLBACK = MonoTheme(
            primaryColor = 0xFF6750A4.toInt(),
            surfaceColor = 0xFFFFFBFE.toInt(),
            onSurfaceColor = 0xFF1C1B1F.toInt(),
            isDarkMode = false
        )

        /**
         * Dark-mode fallback theme.
         */
        val FALLBACK_DARK = MonoTheme(
            primaryColor = 0xFFD0BCFF.toInt(),
            surfaceColor = 0xFF1C1B1F.toInt(),
            onSurfaceColor = 0xFFE6E1E5.toInt(),
            isDarkMode = true
        )
    }
}
