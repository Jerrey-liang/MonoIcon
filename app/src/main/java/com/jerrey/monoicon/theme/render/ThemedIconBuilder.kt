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
 *
 * Generation is split in two so callers that see the same component
 * repeatedly (the launcher rebinds every icon on each drawer scroll) can cache
 * [Recipe] — the expensive, identity-stable part — while still handing each
 * bind its own [ColoredMonochromeDrawable]. Drawables carry mutable bounds,
 * callback and alpha, so they are never shared between views.
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
     * The reusable half of a generated icon: the mask bitmap and the colour
     * pair it is tinted with.
     *
     * [mask] is already the private copy described in [recipe], so it is
     * safe to draw from more than one [ColoredMonochromeDrawable].
     * The Phase 9 shape is deliberately *not* baked in — [drawable] reads it
     * fresh, so changing the shape setting does not require invalidation.
     */
    class Recipe(
        val mask: Bitmap,
        val color: Int,
        val plate: Int,
        /** Mask source tier (`MaskStrategy.SOURCE_*`) — diagnostics only. */
        val source: Int,
    )

    /**
     * Generates the themed drawable for [source] ([identity] is the
     * `"pkg/cls"` cache/lookup key; null is allowed for unknown icons).
     *
     * Equivalent to `recipe(source, identity)?.let(::drawable)`.
     *
     * @return the themed icon, or null when no mask could be generated — the
     *  caller must then keep the original drawable (fail-open).
     */
    fun build(source: Drawable, identity: String?): Result? =
        recipe(source, identity)?.let(::drawable)

    /**
     * Runs the mask + colour pipeline for [source].
     *
     * @return the reusable recipe, or null when no mask could be generated.
     */
    fun recipe(source: Drawable, identity: String?): Recipe? {
        val iconResult = ThemeManager.currentTheme.generateIcon(source, identity, IconContext.DESKTOP)
        val mask = iconResult.mask ?: return null
        // Phase 3.18-D: hand the launcher a private copy (cache bitmap never shared)
        val safeMask = mask.copy(Bitmap.Config.ARGB_8888, false) ?: mask
        return Recipe(safeMask, iconResult.color, iconResult.plate, iconResult.source)
    }

    /**
     * Wraps [recipe] in a fresh drawable for one bind.
     *
     * [ConfigManager.iconShape] is read here rather than cached in the recipe
     * so a shape change takes effect on the next bind without invalidation.
     */
    fun drawable(recipe: Recipe): Result = Result(
        drawable = ColoredMonochromeDrawable(
            recipe.mask,
            recipe.color,
            recipe.plate,
            ConfigManager.iconShape(),
        ),
        mask = recipe.mask,
        source = recipe.source,
    )
}
