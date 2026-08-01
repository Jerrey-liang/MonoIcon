package com.jerrey.monoicon.config

/**
 * Defines the visual style for monochrome icon conversion.
 */
enum class IconStyle {
    /**
     * Monochrome icons that adapt to light/dark theme automatically.
     * Uses the system's wallpaper-based color extraction for tinting.
     */
    MONOCHROME_ADAPTIVE,

    /**
     * Monochrome icons with a fixed light/dark color scheme.
     * Does not follow wallpaper color changes.
     */
    MONOCHROME_FIXED,

    /**
     * Monochrome icons tinted with user-selected custom colors.
     * The user picks specific colors that override Material You extraction.
     */
    CUSTOM_COLOR
}
