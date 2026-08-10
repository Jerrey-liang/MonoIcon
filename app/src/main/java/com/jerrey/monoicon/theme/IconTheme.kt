package com.jerrey.monoicon.theme

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.mask.MaskGenerator
import com.jerrey.monoicon.theme.render.IconRenderResult

/**
 * Pluggable icon theme (Phase 5 / Phase 6.1).
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
     * Phase 6.1: combined mask + color generation.
     *
     * Default implementation calls [generateMask] followed by
     * [extractColor]. In Phase 6.1 the color is no longer sourced
     * from [com.jerrey.monoicon.color.IconColorCache] — the default
     * Pixel Default theme uses [SystemMaterialColorProvider] to read
     * the system Material You palette, which is globally uniform
     * across all icons (matching Pixel Launcher behavior).
     *
     * Themes that need per-app colors (future / custom themes) can
     * override this method and re-add IconColorCache lookup.
     *
     * Pure Mono / High Contrast work identically: [extractColor]
     * delegates to their configured [ColorStrategy].
     */
    fun generateIcon(
        drawable: Drawable,
        identity: String?,
        context: IconContext,
    ): IconRenderResult {
        val maskResult = generateMask(drawable, identity, context)
        val color = if (identity != null) extractColor(drawable, identity) else 0
        return IconRenderResult(maskResult, color)
    }
}
