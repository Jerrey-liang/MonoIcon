package com.jerrey.monoicon.theme.color.dynamic

/**
 * Paired icon theme colors (Phase 6.3).
 *
 * @param foreground  Monochrome icon tint (themed_icon_color).
 * @param background  AdaptiveIconDrawable background fill (themed_icon_background_color).
 * @param palette     Full Monet tonal palette for advanced rendering (Phase 6.4+).
 */
data class IconThemeColors(
    val foreground: Int,
    val background: Int,
    val palette: MonetPalette,
)
