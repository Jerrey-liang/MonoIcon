package com.jerrey.monoicon.theme.render

import com.jerrey.monoicon.mask.GenerateResult

/**
 * Combined mask + color render result (Phase 6.0, 6.6).
 *
 * Packs the mask generation outcome together with the Pixel Launcher
 * color pair so the hook layer can construct the tinted drawable in
 * one step.
 *
 * @param maskResult  The original mask generation result (mask bitmap,
 *                    source category, raw-usage flag, cache-hit flag).
 * @param color       The glyph tint (`themed_icon_color`), or 0 when
 *                    extraction was not performed.
 * @param plate       The background plate (`themed_icon_background_color`),
 *                    0 = transparent → no plate.
 */
data class IconRenderResult(
    val maskResult: GenerateResult,
    val color: Int,
    val plate: Int = 0,
) {
    /** Convenience: the mask bitmap (may be null). */
    val mask: android.graphics.Bitmap? get() = maskResult.mask

    /** Convenience: mask source category. */
    val source: Int get() = maskResult.source

    /** Convenience: whether the raw APK drawable was used. */
    val rawUsed: Boolean get() = maskResult.rawUsed

    /** Convenience: whether the cache was hit. */
    val cacheHit: Boolean get() = maskResult.cacheHit
}
