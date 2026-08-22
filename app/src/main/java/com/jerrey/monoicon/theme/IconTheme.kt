package com.jerrey.monoicon.theme

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.mask.GenerateResult
import com.jerrey.monoicon.theme.color.ThemeColors
import com.jerrey.monoicon.theme.render.IconRenderResult

/**
 * Pluggable icon theme (Phase 5 / Phase 6.1 / 6.6).
 *
 * Each theme defines how monochrome masks are generated and how the
 * Pixel Launcher color pair (glyph tint + background plate) is derived.
 * Themes are data-driven and do not carry mutable state — [ThemeManager]
 * owns the current selection.
 *
 * ## Contract
 * - [generateMask] returns null when the drawable cannot be processed
 *   (unsupported type, rendering failure) — the caller passes through
 *   the original drawable unchanged.
 * - [extractColors] returns the Pixel color pair; failures return
 *   [ThemeColors.TRANSPARENT].
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
     * @return A [GenerateResult] with the mask bitmap and
     *         metadata; [GenerateResult.mask] is null if
     *         processing failed — callers check mask separately (same
     *         pattern as the Phase 4 call sites).
     */
    fun generateMask(
        drawable: Drawable,
        identity: String?,
        context: IconContext,
    ): GenerateResult

    /**
     * Returns the Pixel Launcher color pair for [identity].
     *
     * @param drawable  Full-color drawable (raw APK AdaptiveIconDrawable or
     *                  LayerAdaptiveIconDrawable with intact background).
     * @param identity  Resolved "pkg/cls" component identity.
     * @return Glyph tint + background plate colors.
     */
    fun extractColors(drawable: Drawable, identity: String): ThemeColors

    /**
     * Phase 6.1/6.6: combined mask + color generation.
     *
     * Default implementation calls [generateMask] followed by
     * [extractColors]. The color pair comes from the Monet wallpaper
     * palette via [PixelMonetColorEngine] — globally uniform across all
     * icons (matching Pixel Launcher behavior). Themes that need per-app
     * colors (future / custom themes) can override this method and
     * re-add IconColorCache lookup.
     */
    fun generateIcon(
        drawable: Drawable,
        identity: String?,
        context: IconContext,
    ): IconRenderResult {
        return try {
            val maskResult = generateMask(drawable, identity, context)
            // Pixel's themed icon colors are global wallpaper-derived values,
            // not component-specific. Keep them even when folder identity
            // resolution is temporarily unavailable; only mask caching needs
            // a stable identity.
            val colors = try {
                extractColors(drawable, identity ?: "unknown")
            } catch (_: Throwable) {
                ThemeColors.TRANSPARENT
            }
            IconRenderResult(maskResult, colors.foreground, colors.background)
        } catch (_: Throwable) {
            // Any exception in mask generation or color extraction must not
            // propagate to the hook layer — return null mask → hook passes
            // through the original drawable gracefully.
            IconRenderResult(GenerateResult(null, 3, false, false), 0, 0)
        }
    }
}
