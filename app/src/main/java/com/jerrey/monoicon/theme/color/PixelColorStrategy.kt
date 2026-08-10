package com.jerrey.monoicon.theme.color

import android.graphics.drawable.Drawable
import com.jerrey.monoicon.theme.IconContext
import com.jerrey.monoicon.theme.color.dynamic.SystemMaterialColorProvider

/**
 * Pixel Launcher dynamic color strategy (Phase 6.2 material — stable baseline).
 *
 * Delegates to [SystemMaterialColorProvider] for the globally uniform
 * Material You icon tint from framework resources. This is the Phase 6.1/6.2
 * baseline proven stable on-device.
 *
 * HCT palette generation ([PixelMonetColorEngine]) is available for themes
 * that need the full 13-tone palette but is not yet integrated into the
 * default strategy pending stability verification.
 */
class PixelColorStrategy : ColorStrategy {

    override fun extract(drawable: Drawable, identity: String): Int =
        SystemMaterialColorProvider.getIconTint(identity, IconContext.DESKTOP)
}
