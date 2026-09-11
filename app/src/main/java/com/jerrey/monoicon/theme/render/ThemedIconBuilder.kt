package com.jerrey.monoicon.theme.render

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.theme.IconContext
import com.jerrey.monoicon.theme.ThemeManager

/**
 * The single place where a themed icon drawable is produced (Phase 10).
 *
 * Desktop icons (Hook 5) and HyperOS recents task icons (Hook 11) both call
 * [build], so both surfaces share the mask/colour caches and render
 * identically — including the Phase 9 shape chosen by
 * [ConfigManager.iconShape] (circle with the AOSP normalization, or the
 * previous full-bleed behaviour).
 */
object ThemedIconBuilder {

    /** A generated icon: the drawable handed to the launcher plus its mask. */
    class Result(
        val drawable: ColoredMonochromeDrawable,
        val mask: Bitmap,
        /** Mask source tier (`MaskStrategy.SOURCE_*`) — diagnostics only. */
        val source: Int,
    )

    /**
     * Generates the themed drawable for [source] ([identity] is the
     * `"pkg/cls"` cache/lookup key; null is allowed for unknown icons).
     *
     * @return the themed icon, or null when no mask could be generated — the
     *  caller must then keep the original drawable (fail-open).
     */
    fun build(source: Drawable, identity: String?): Result? {
        val iconResult = ThemeManager.currentTheme.generateIcon(source, identity, IconContext.DESKTOP)
        val mask = iconResult.mask ?: return null
        // Phase 3.18-D: hand the launcher a private copy (cache bitmap never shared)
        val safeMask = mask.copy(Bitmap.Config.ARGB_8888, false) ?: mask
        return Result(
            drawable = ColoredMonochromeDrawable(
                safeMask,
                iconResult.color,
                iconResult.plate,
                ConfigManager.iconShape(),
            ),
            mask = safeMask,
            source = iconResult.source,
        )
    }
}
