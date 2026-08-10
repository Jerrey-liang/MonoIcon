package com.jerrey.monoicon.theme.color.dynamic

/**
 * Pixel-style tone selection (Phase 6.1).
 *
 * Maps the current light/dark mode to the appropriate tonal step
 * for icon rendering. Matches the behavior observed in Pixel
 * Launcher monochrome icons:
 *
 * - Light wallpaper → darker icon (better contrast on light bg)
 * - Dark wallpaper → lighter icon (better contrast on dark bg)
 *
 * Tone indices: 0=0, 1=10, 2=20, ..., 9=90, 10=95, 11=99, 12=100.
 * For Pixel Default: accent1 tone 40 (index 4) in light mode,
 * tone 80 (index 8) in dark mode.
 */
object TonalMapper {

    /**
     * Returns the tonal step index for icon tinting.
     *
     * @param isDarkMode Current dark-mode state (from system config).
     * @return Tone index into [MaterialYouPalette.accent1], or a
     *         fixed fallback.
     */
    fun iconTone(isDarkMode: Boolean): Int = if (isDarkMode) 8 else 4
}
