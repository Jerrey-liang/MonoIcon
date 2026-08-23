package com.jerrey.monoicon.theme.color

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine

/**
 * HyperOS Launcher overlay color strategy.
 *
 * Reads both colors from the final `com.miui.home:material_dynamic_primary*`
 * resources already resolved by SystemUI's Monet/RRO pipeline:
 * - glyph = primary40 (light) / primary80 (dark)
 * - plate = primary95 (light) / primary10 (dark)
 *
 * Resource IDs are cached, but RGB values are read for every invocation so
 * overlay changes are visible on the next Drawable draw.
 */
class PixelColorStrategy : ColorStrategy {

    override fun extract(drawable: Drawable, identity: String): ThemeColors {
        val colors = PixelMonetColorEngine.getIconColors()
        return ThemeColors(foreground = colors.foreground, background = colors.background)
    }
}
