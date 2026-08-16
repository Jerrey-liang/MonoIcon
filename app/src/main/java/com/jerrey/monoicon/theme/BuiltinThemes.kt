package com.jerrey.monoicon.theme

import android.graphics.Color
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.theme.color.PixelColorStrategy
import com.jerrey.monoicon.theme.color.StaticColorStrategy
import com.jerrey.monoicon.theme.mask.NativeFirstMaskStrategy
import com.jerrey.monoicon.theme.mask.PixelMonochromeMaskStrategy
import com.jerrey.monoicon.theme.mask.RawFirstMaskStrategy
import com.jerrey.monoicon.mask.MaskGenerator

/**
 * Built-in theme definitions (Phase 5).
 *
 * Each entry is a [ThemeDefinition] that configures desktop mask,
 * folder mask, and color extraction strategies. New themes are added
 * by creating a definition here and registering it in [ALL].
 */
object BuiltinThemes {

    // ── Strategy instances ─────────────────────────────────────────────

    private val pixelColor = PixelColorStrategy()

    // ── Theme 1: Pixel Default (Phase 6.3: unified Pixel strategy) ─────

    /**
     * Unified Pixel Launcher monochrome mask strategy. Desktop and folder
     * use SEPARATE instances because [MaskStrategy.configureCache] bakes
     * the context label into each strategy's cache-key prefix — sharing one
     * instance across contexts would make desktop/folder cache entries
     * collide.
     */
    private val pixelMonoDesktopMask = PixelMonochromeMaskStrategy()
    private val pixelMonoFolderMask = PixelMonochromeMaskStrategy()

    /**
     * Pixel Default — matches Pixel Launcher's monochrome icon pipeline.
     *
     * Mask: unified PixelMonochromeMaskStrategy (NATIVE > LAB luminance,
     * no desktop/folder distinction — same as Pixel Launcher).
     * Color: Pixel Launcher dynamic color (Phase 6.2).
     */
    val PIXEL_DEFAULT = ThemeDefinition(
        id = "pixel_default",
        name = "Pixel Default",
        desktopMask = pixelMonoDesktopMask,
        folderMask = pixelMonoFolderMask,
        color = pixelColor,
    )

    // ── Theme 2: Pure Mono — all icons black/white ─────────────────────

    /**
     * Pure Mono — all icons as pure silhouettes, color forced to black.
     *
     * Mask: RAW_FIRST everywhere (same source priority as folder previews).
     * Color: static black (no per-icon color extraction).
     */
    val PURE_MONO = ThemeDefinition(
        id = "pure_mono",
        name = "Pure Mono",
        desktopMask = RawFirstMaskStrategy(),
        folderMask = RawFirstMaskStrategy(),
        color = StaticColorStrategy(Color.BLACK),
    )

    // ── Theme 3: High Contrast — placeholder for enhancement ────────────

    /**
     * High Contrast — placeholder for enhancement.
     *
     * Mask: the Phase 5 split (desktop NATIVE_FIRST, folder RAW_FIRST),
     * kept as an alternative to the unified Pixel pipeline.
     * Color: same dynamic color strategy as Pixel Default.
     */
    val HIGH_CONTRAST = ThemeDefinition(
        id = "high_contrast",
        name = "High Contrast",
        desktopMask = NativeFirstMaskStrategy(),
        folderMask = RawFirstMaskStrategy(),
        color = PixelColorStrategy(),
    )

    // ── Registry ───────────────────────────────────────────────────────

    /** All built-in themes in display order. */
    val ALL: List<ThemeDefinition> = listOf(PIXEL_DEFAULT, PURE_MONO, HIGH_CONTRAST)

    /** Lookup by ID (falls back to PIXEL_DEFAULT). */
    fun byId(id: String): ThemeDefinition = ALL.find { it.id == id } ?: PIXEL_DEFAULT

    /**
     * Thin [IconTheme] wrapper that delegates to a [ThemeDefinition].
     * The theme ID + display name come from the definition; mask and color
     * are dispatched to the definition's strategies.
     */
    private class DefinitionTheme(private val def: ThemeDefinition) : IconTheme {
        override val id = def.id
        override val displayName = def.name

        override fun generateMask(
            drawable: Drawable,
            identity: String?,
            context: IconContext,
        ): MaskGenerator.GenerateResult {
            val strategy = when (context) {
                IconContext.DESKTOP -> def.desktopMask
                IconContext.FOLDER_PREVIEW -> def.folderMask
            }
            return strategy.generate(drawable, identity)
                ?: MaskGenerator.GenerateResult(null, 3, false, false)
            // SOURCE_LUMINANCE = 3
        }

        override fun extractColor(drawable: Drawable, identity: String): Int =
            def.color.extract(drawable, identity)
    }

    /** Creates an [IconTheme] from a [ThemeDefinition]. */
    fun themeFor(definition: ThemeDefinition): IconTheme = DefinitionTheme(definition)
}
