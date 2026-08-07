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
) {
    companion object {
        /** Default configuration (fresh install: enabled). */
        val DEFAULT = MonoIconConfig(enabled = true)
    }
}
