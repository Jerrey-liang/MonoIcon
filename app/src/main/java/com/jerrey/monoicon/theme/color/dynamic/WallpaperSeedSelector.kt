package com.jerrey.monoicon.theme.color.dynamic

import android.app.WallpaperColors
import android.graphics.Color

/**
 * Wallpaper seed color selector (Phase 6.3).
 *
 * Wraps the system [WallpaperColors.getPrimaryColor] with a chroma
 * floor to reject near-gray seeds. The system's internal seed selection
 * (most saturated, most populous color) is already applied by Android;
 * this wrapper adds a safety net for wallpaper images where the system
 * selects a chroma-deficient seed (e.g. purely grayscale wallpapers).
 *
 * Falls back to a neutral blue-gray seed when no usable color is found.
 * Deterministic, never throws.
 */
object WallpaperSeedSelector {

    /** Minimum chroma (normalized 0–1) to accept a seed. */
    private const val MIN_CHROMA = 0.03f

    /** Neutral blue-gray fallback when wallpaper provides no usable seed. */
    private const val FALLBACK_SEED = 0xFF4A6D8C.toInt()

    /**
     * Returns an ARGB seed color from [wallpaperColors].
     *
     * Priority:
     * 1. System primary color, if chroma >= [MIN_CHROMA]
     * 2. System secondary color, if chroma >= [MIN_CHROMA]
     * 3. System tertiary color, if chroma >= [MIN_CHROMA]
     * 4. FALLBACK_SEED
     */
    fun select(wallpaperColors: WallpaperColors): Int {
        val candidates = listOf(
            wallpaperColors.primaryColor,
            wallpaperColors.secondaryColor,
            wallpaperColors.tertiaryColor
        )
        for (c in candidates) {
            val argb = try { c?.toArgb() } catch (_: Throwable) { null } ?: continue
            if (chroma(argb) >= MIN_CHROMA) return argb
        }
        return FALLBACK_SEED
    }

    /** Normalized chroma (0–1) as max(R,G,B)-min(R,G,B) / 255. */
    private fun chroma(argb: Int): Float {
        val r = Color.red(argb); val g = Color.green(argb); val b = Color.blue(argb)
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        return (mx - mn) / 255f
    }
}
