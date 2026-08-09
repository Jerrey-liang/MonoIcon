package com.jerrey.monoicon.theme.render

import com.jerrey.monoicon.mask.MaskGenerator

/**
 * Combined mask + color render result (Phase 6.0).
 *
 * Packs the mask generation outcome together with the extracted icon
 * color so the hook layer can construct a tinted drawable in one step.
 *
 * @param maskResult  The original mask generation result (mask bitmap,
 *                    source category, raw-usage flag, cache-hit flag).
 * @param color       The extracted ARGB icon color, or 0 if extraction
 *                    was not performed (e.g. mask generation failed).
 */
data class IconRenderResult(
    val maskResult: MaskGenerator.GenerateResult,
    val color: Int,
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
