package com.jerrey.monoicon.theme.color

import android.graphics.drawable.Drawable

/**
 * Static color extraction strategy (Phase 5).
 *
 * Returns a fixed ARGB color for every drawable — used by themes that
 * don't need per-icon color extraction (e.g. Pure Mono uses black).
 * No rendering or pixel sampling is performed.
 */
class StaticColorStrategy(private val color: Int) : ColorStrategy {

    override fun extract(drawable: Drawable, identity: String): Int = color
}
