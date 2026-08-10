package com.jerrey.monoicon.theme.color

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.theme.IconContext
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine

/**
 * Pixel Launcher dynamic color strategy (Phase 6.2).
 *
 * Delegates to [PixelMonetColorEngine] for the globally uniform
 * Material You icon foreground/background color pair derived from
 * the system wallpaper palette.
 *
 * The [drawable] and [identity] parameters are accepted for interface
 * compatibility but unused — the Monet palette is icon-agnostic
 * (matches Pixel Launcher behavior where all themed icons share
 * the same `themed_icon_color`).
 */
class PixelColorStrategy : ColorStrategy {

    override fun extract(drawable: Drawable, identity: String): Int =
        PixelMonetColorEngine.getIconColors().foreground
}
