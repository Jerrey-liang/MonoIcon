package com.jerrey.monoicon.theme

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.mask.GenerateResult
import com.jerrey.monoicon.theme.color.PixelColorStrategy
import com.jerrey.monoicon.theme.color.ThemeColors
import com.jerrey.monoicon.theme.mask.AospMonochromeMaskStrategy
import com.jerrey.monoicon.theme.mask.MaskStrategy

/**
 * Built-in theme definitions (Phase 5, 7).
 *
 * Phase 7: the built-in theme follows AOSP android15-release — mask
 * generation is the AOSP monochrome pipeline ([AospMonochromeMaskStrategy]),
 * color is the AOSP themed-icon tone mapping ([PixelColorStrategy]).
 */
object BuiltinThemes {

    // ── Strategy instances ─────────────────────────────────────────────

    private val pixelColor = PixelColorStrategy()

    /**
     * Unified AOSP monochrome mask strategy. Desktop and folder use
     * SEPARATE instances because [MaskStrategy.configureCache] bakes
     * the context label into each strategy's cache-key prefix — sharing one
     * instance across contexts would make desktop/folder cache entries
     * collide.
     */
    private val pixelMonoDesktopMask = AospMonochromeMaskStrategy()
    private val pixelMonoFolderMask = AospMonochromeMaskStrategy()

    /**
     * AOSP Default — matches AOSP android15-release's themed icon pipeline.
     *
     * Mask: unified AospMonochromeMaskStrategy (NATIVE > AOSP edge-flip,
     * same for desktop and folder previews — same as AOSP).
     * Color: AOSP themed-icon tone mapping (primary 30/90, secondary 20).
     */
    val PIXEL_DEFAULT = ThemeDefinition(
        id = "pixel_default",
        name = "AOSP Default",
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
