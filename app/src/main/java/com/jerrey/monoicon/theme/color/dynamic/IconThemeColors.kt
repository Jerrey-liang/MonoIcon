package com.jerrey.monoicon.theme.color.dynamic

/**
 * Paired icon theme colors matching Pixel Launcher's
 * `themed_icon_background_color` + `themed_icon_color` (Phase 6.2).
 *
 * @param background  AdaptiveIconDrawable background fill (ColorDrawable color).
 * @param foreground  Monochrome icon tint.
 */
data class IconThemeColors(
    val background: Int,
    val foreground: Int,
)
