package com.jerrey.monoicon.config

/**
 * MonoIcon runtime configuration model (Phase 4.1).
 *
 * Minimal by design: a single toggle controls whether the hook runtime
 * processes icons. No style / color / mask options are exposed.
 */
data class MonoIconConfig(
    /** Master switch: false bypasses all icon processing in the launcher. */
    val enabled: Boolean = true,

    /** Active theme ID (Phase 5). Defaults to pixel_default = Phase 4 behavior. */
    val themeId: String = "pixel_default",

    /**
     * Phase 8: use the built-in Lawnicons mask bundle for icons WITHOUT a
     * native monochrome layer (annotator-drawn glyphs, no heuristics).
     */
    val lawniconsEnabled: Boolean = true,

    /**
     * Phase 9: render icons with the circular silhouette (MIUI
     * `IconCustomizer` config hijack + MonoIcon drawable clip) without
     * installing an MTZ theme. Opt-in — default off.
     */
    val circleIconsEnabled: Boolean = false,

    /**
     * Phase 11: notification app icons in SystemUI use MonoIcon's themed
     * drawable (`AppIconsManager` hooks). Default on; the switch is an escape
     * hatch for the SystemUI process.
     */
    val notificationIconsEnabled: Boolean = true,
) {
    companion object {
        /** Default configuration (fresh install: enabled, Pixel Default theme). */
        val DEFAULT = MonoIconConfig(
            enabled = true,
            themeId = "pixel_default",
            lawniconsEnabled = true,
            circleIconsEnabled = false,
            notificationIconsEnabled = true,
        )
    }
}
