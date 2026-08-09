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
) {
    companion object {
        /** Default configuration (fresh install: enabled, Pixel Default theme). */
        val DEFAULT = MonoIconConfig(enabled = true, themeId = "pixel_default")
    }
}
