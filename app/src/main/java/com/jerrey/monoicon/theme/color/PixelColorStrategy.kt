package com.jerrey.monoicon.theme.color

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine

/**
 * Pixel Launcher dynamic color strategy (Phase 6.6).
 *
 * Derives BOTH colors from the Monet wallpaper palette with the exact
 * Pixel Launcher algorithm (HCT/CAM16 tonal palette from the wallpaper
 * primary seed):
 * - glyph  (`themed_icon_color`)                = accent tone 40 (light) / 80 (dark)
 * - plate  (`themed_icon_background_color`)     = accent tone 95 (light) / 10 (dark)
 *
 * Cached by [PixelMonetColorEngine] with a 60-second TTL.
 */
class PixelColorStrategy : ColorStrategy {

    override fun extract(drawable: Drawable, identity: String): ThemeColors {
        val colors = PixelMonetColorEngine.getIconColors()
        return ThemeColors(foreground = colors.foreground, background = colors.background)
    }
}
