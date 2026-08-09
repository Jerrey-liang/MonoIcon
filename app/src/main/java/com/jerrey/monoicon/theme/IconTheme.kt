package com.jerrey.monoicon.theme

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.color.IconColorCache
import com.jerrey.monoicon.mask.MaskGenerator
import com.jerrey.monoicon.theme.render.IconRenderResult

/**
 * Pluggable icon theme (Phase 5 / Phase 6.0).
 *
 * Each theme defines how monochrome masks are generated and how
 * dominant colors are extracted. Themes are data-driven and do not
 * carry mutable state — [ThemeManager] owns the current selection.
 *
 * ## Contract
 * - [generateMask] returns null when the drawable cannot be processed
 *   (unsupported type, rendering failure) — the caller passes through
 *   the original drawable unchanged.
 * - [extractColor] returns an ARGB color int; failures return
 *   [IconColorExtractor.FALLBACK_COLOR].
 */
interface IconTheme {

    /** Stable identifier for config persistence and cache-key prefixing. */
    val id: String

    /** Human-readable name shown in the settings UI. */
    val displayName: String

    /**
     * Generates a monochrome alpha mask for [drawable].
     *
     * @param drawable  The source drawable (may be LayerAdaptiveIconDrawable,
     *                  raw AdaptiveIconDrawable, BitmapDrawable, or FancyDrawable).
     * @param identity  Resolved "pkg/cls" component identity, or null when
     *                  unresolved (folder fallback — mask still generated but
     *                  not cached, preserving Phase 3.17 behavior).
     * @param context   Rendering context — desktop vs folder preview.
     * @return A [MaskGenerator.GenerateResult] with the mask bitmap and
     *         metadata; [MaskGenerator.GenerateResult.mask] is null if
     *         processing failed — callers check mask separately (same
     *         pattern as the Phase 4 call sites).
     */
    fun generateMask(
        drawable: Drawable,
        identity: String?,
        context: IconContext,
    ): MaskGenerator.GenerateResult

    /**
     * Extracts the dominant icon color.
     *
     * @param drawable  Full-color drawable (raw APK AdaptiveIconDrawable or
     *                  LayerAdaptiveIconDrawable with intact background).
     * @param identity  Resolved "pkg/cls" component identity.
     * @return ARGB color int.
     */
    fun extractColor(drawable: Drawable, identity: String): Int

    /**
     * Phase 6.0: combined mask + color generation.
     *
     * Default implementation calls [generateMask] followed by color
     * lookup. The color is read from [IconColorCache] (populated by
     * the early extraction pipeline — Hook 7 / extractOriginalIconColor)
     * to avoid re-extracting from a HyperOS-processed drawable whose
     * colors may already be destroyed. Falls back to [extractColor] on
     * cache miss.
     *
     * Pure Mono theme (static black) naturally works through this path:
     * its [extractColor] always returns 0xFF000000, producing a
     * black-tinted result identical to Phase 5.
     */
    fun generateIcon(
        drawable: Drawable,
        identity: String?,
        context: IconContext,
    ): IconRenderResult {
        val maskResult = generateMask(drawable, identity, context)
        val cachedColor = if (identity != null) IconColorCache.get(identity) else null
        val color = when {
            cachedColor != null -> cachedColor
            identity != null -> extractColor(drawable, identity)
            else -> 0
        }
        return IconRenderResult(maskResult, color)
    }
}
