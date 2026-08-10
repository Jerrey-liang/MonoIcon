package com.jerrey.monoicon.theme.color

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.theme.IconContext
import com.jerrey.monoicon.theme.color.dynamic.SystemMaterialColorProvider

/**
 * Pixel Launcher dynamic color strategy (Phase 6.1).
 *
 * Delegates to [SystemMaterialColorProvider] for the globally uniform
 * Material You icon tint derived from the system wallpaper palette.
 * This matches Pixel Launcher's monochrome icon behavior: all icons
 * share the same accent color, light/dark-adaptive via [TonalMapper].
 *
 * The drawable and identity parameters are accepted for interface
 * compatibility but are unused — the system palette is icon-agnostic.
 *
 * ## Fallback
 * If the system provider returns a fallback, logs at debug level.
 * Provider itself is fail-safe (never throws, always returns ARGB).
 */
class PixelColorStrategy : ColorStrategy {

    override fun extract(drawable: Drawable, identity: String): Int {
        // Phase 6.1: delegate to system Material You palette.
        // IconContext is always DESKTOP here since extractColor() is called
        // without context in the ColorStrategy interface. The system palette
        // is uniform across contexts — same color for desktop and folders.
        return SystemMaterialColorProvider.getIconTint(identity, IconContext.DESKTOP)
    }
}
