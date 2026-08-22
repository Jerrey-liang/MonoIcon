package com.jerrey.monoicon.theme.mask

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.mask.GenerateResult

/**
 * Pluggable mask generation strategy (Phase 5).
 *
 * Implementations define how a monochrome alpha mask is produced from
 * a raw drawable. [GenerateResult] carries the mask bitmap plus metadata
 * (source category, raw usage flag, cache hit) consumed by the hook layer
 * for logging and diagnostics.
 *
 * Phase 6.6: only the Pixel Launcher pipeline remains —
 * [PixelMonochromeMaskStrategy] implements this interface and every mask
 * source goes through [LabMonochromeExtractor].
 *
 * ## Cache contract
 * Strategies own their cache lookup/put — the [GenerateResult]
 * includes a [cacheHit] flag. The [MonochromeCache] key format must
 * include the theme ID and icon context to prevent cross-theme
 * collisions.
 */
interface MaskStrategy {

    /**
     * Generates (or retrieves from cache) the monochrome mask for [d].
     *
     * @param d        The source drawable.
     * @param identity Resolved "pkg/cls" identity; null → generate without cache.
     * @return A [GenerateResult] with the mask, or null on failure.
     */
    fun generate(d: Drawable, identity: String?): GenerateResult?

    /**
     * Configures the shared [MonochromeCache] and theme-scoped key prefix.
     *
     * Called once by [com.jerrey.monoicon.theme.ThemeManager] after theme
     * selection. The [themeId] and [context] are prepended to every cache
     * key produced by this strategy.
     */
    fun configureCache(themeId: String, context: String)

    companion object {
        // ── Source constants (synced with MonochromeCache / IconThemeHook) ──
        const val SOURCE_NATIVE = 1
        const val SOURCE_FOREGROUND = 2
        const val SOURCE_LUMINANCE = 3
    }
}
