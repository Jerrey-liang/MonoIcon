package com.jerrey.monoicon.theme.color.dynamic

import com.jerrey.monoicon.theme.IconContext

/**
 * Provides the current Material You icon tint color (Phase 6.1).
 *
 * The color is globally uniform for all icons — derived from the
 * system wallpaper / monet palette, not extracted from each app's
 * original icon. This matches Pixel Launcher's monochrome icon
 * behavior.
 *
 * ## Contract
 * - Thread-safe: may be called from any hook thread.
 * - Never throws: failures return a safe fallback.
 * - Hot-path: called per icon bind — implementations should cache
 *   aggressively (the color changes only on wallpaper / config
 *   change, not per-icon).
 */
interface DynamicColorProvider {

    /**
     * Returns the ARGB icon tint for the current system state.
     *
     * @param identity Resolved "pkg/cls" identity (unused by
     *                 system-palette providers; available for
     *                 future per-app color mapping).
     * @param context  Rendering context (desktop / folder preview).
     * @return ARGB color int. Never returns transparent (alpha == 0).
     */
    fun getIconTint(identity: String?, context: IconContext): Int
}
