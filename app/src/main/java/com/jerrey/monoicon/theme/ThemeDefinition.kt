package com.jerrey.monoicon.theme

import com.jerrey.monoicon.theme.color.ColorStrategy
import com.jerrey.monoicon.theme.mask.MaskStrategy

/**
 * Declarative theme configuration (Phase 5).
 *
 * Describes which mask and color strategies a theme uses. The
 * [IconTheme] interface is the runtime contract; [ThemeDefinition]
 * is the configuration record used for the built-in registry and
 * the settings UI.
 */
data class ThemeDefinition(
    /** Stable identifier (matches [IconTheme.id]). */
    val id: String,

    /** Human-readable name for the settings UI. */
    val name: String,

    /** Desktop mask strategy. */
    val desktopMask: MaskStrategy,

    /** Folder preview mask strategy. */
    val folderMask: MaskStrategy,

    /** Color extraction strategy. */
    val color: ColorStrategy,
)
