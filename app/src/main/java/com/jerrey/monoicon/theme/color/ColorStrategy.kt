package com.jerrey.monoicon.theme.color

import android.graphics.drawable.Drawable

/**
 * Pluggable color extraction strategy (Phase 5).
 *
 * Implementations define how a dominant ARGB color is extracted from
 * a full-color icon drawable. The result may be cached per identity
 * by the caller ([com.jerrey.monoicon.color.IconColorCache]) — this
 * interface does NOT own caching.
 */
interface ColorStrategy {

    /**
     * Extracts the dominant color from [drawable].
     *
     * @param drawable Full-color drawable (raw APK AdaptiveIconDrawable
     *                 or a rendered Bitmap).
     * @param identity Resolved "pkg/cls" component identity (for logging).
     * @return ARGB color int.
     */
    fun extract(drawable: Drawable, identity: String): Int
}
