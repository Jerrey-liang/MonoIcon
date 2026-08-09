package com.jerrey.monoicon.theme

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.mask.MaskGenerator

/**
 * Pluggable icon theme (Phase 5).
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
}
