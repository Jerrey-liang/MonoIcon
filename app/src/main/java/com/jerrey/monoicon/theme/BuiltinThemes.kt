package com.jerrey.monoicon.theme

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.mask.GenerateResult
import com.jerrey.monoicon.theme.color.PixelColorStrategy
import com.jerrey.monoicon.theme.color.ThemeColors
import com.jerrey.monoicon.theme.mask.MaskStrategy
import com.jerrey.monoicon.theme.mask.PixelMonochromeMaskStrategy

/**
 * Built-in theme definitions (Phase 5, 6.6).
 *
 * Phase 6.6: only the Pixel Default theme remains — mask generation is
 * the single Pixel Launcher pipeline ([PixelMonochromeMaskStrategy]),
 * color is the Pixel Launcher dynamic tint ([PixelColorStrategy]).
 * High Contrast / Pure Mono and the legacy Rec.601 mask strategies have
 * been removed.
 */
object BuiltinThemes {

    // ── Strategy instances ─────────────────────────────────────────────

    private val pixelColor = PixelColorStrategy()

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
     * Mask: unified PixelMonochromeMaskStrategy (NATIVE > CIELAB luminance,
     * same for desktop and folder previews — same as Pixel Launcher).
     * Color: Pixel Launcher dynamic tint (system accent).
     */
    val PIXEL_DEFAULT = ThemeDefinition(
        id = "pixel_default",
        name = "Pixel Default",
        desktopMask = pixelMonoDesktopMask,
        folderMask = pixelMonoFolderMask,
        color = pixelColor,
    )

    // ── Registry ───────────────────────────────────────────────────────

    /** All built-in themes in display order. */
    val ALL: List<ThemeDefinition> = listOf(PIXEL_DEFAULT)

    /** Lookup by ID (always resolves to the single Pixel Default theme). */
    fun byId(id: String): ThemeDefinition = PIXEL_DEFAULT

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
        ): GenerateResult {
            val strategy = when (context) {
                IconContext.DESKTOP -> def.desktopMask
                IconContext.FOLDER_PREVIEW -> def.folderMask
            }
            return strategy.generate(drawable, identity)
                ?: GenerateResult(null, 3, false, false)
            // SOURCE_LUMINANCE = 3
        }

        override fun extractColors(drawable: Drawable, identity: String): ThemeColors =
            def.color.extract(drawable, identity)
    }

    /** Creates an [IconTheme] from a [ThemeDefinition]. */
    fun themeFor(definition: ThemeDefinition): IconTheme = DefinitionTheme(definition)
}
