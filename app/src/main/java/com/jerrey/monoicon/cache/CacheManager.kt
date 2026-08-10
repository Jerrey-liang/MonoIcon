package com.jerrey.monoicon.cache

import com.jerrey.monoicon.color.IconColorCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.identity.IdentityResolver
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine

/**
 * Cache lifecycle management (Phase 4.0-A).
 *
 * Owns the invalidation policy for every in-process MonoIcon cache:
 * - [IconDrawableCache] — raw APK drawables, keyed `pkg/cls`
 * - [IconColorCache] — extracted ARGB colors, keyed `pkg/cls`
 * - [MonoMaskCache] — mask bitmaps, keyed `pkg/cls|WxH@fp|src`
 * - IdentityResolver view bridge — view-hash → identity timing map
 *
 * ## Lifecycle rules
 * - [invalidatePackage] drops all entries for one package (app update /
 *   uninstall / icon resource change). Prefix-based: `"com.pkg/"` covers
 *   both `pkg/cls` identities and bare-`pkg` fallbacks.
 * - [clearAll] drops everything (theme switch / launcher restart / disable).
 * - Every operation is idempotent, cheap, and never throws.
 *
 * Thread-safe: delegates to the underlying concurrent caches.
 */
object CacheManager {

    /** Snapshot of cache occupancy for diagnostics. */
    data class CacheStats(
        val drawableEntries: Int,
        val colorEntries: Int,
        val maskBytes: Int,
        val viewIdentityEntries: Int,
    ) {
        override fun toString(): String =
            "CacheStats(drawables=$drawableEntries colors=$colorEntries " +
            "maskBytes=$maskBytes views=$viewIdentityEntries)"
    }

    /**
     * Invalidates every cache entry belonging to [packageName].
     *
     * Prefix `"$packageName/"` matches `pkg/cls` identity keys; the
     * bare-`pkg` fallback identity is also matched because mask keys are
     * `"identity|..."` and a package-prefix entry covers both forms.
     */
    fun invalidatePackage(packageName: String) {
        if (packageName.isBlank()) return
        val prefix = packageName + "/"
        IconDrawableCache.removeByPrefix(prefix)
        IconColorCache.removeByPrefix(prefix)
        MonochromeCache.shared.removeByPrefix(prefix)
    }

    /** Drops all cached content across every cache. */
    fun clearAll() {
        IconDrawableCache.clear()
        IconColorCache.clear()
        MonochromeCache.shared.clear()
        IdentityResolver.clearViews()
        invalidateDynamicColor()
    }

    /**
     * Phase 6.1: forces the dynamic color provider to re-read the system
     * palette on the next call. Called on theme switch or configuration
     * change (light/dark toggle) so the icon color updates immediately
     * without waiting for the internal 60s TTL.
     */
    fun invalidateDynamicColor() {
        PixelMonetColorEngine.invalidate()
    }

    /** Current cache occupancy. */
    fun statistics(): CacheStats = CacheStats(
        drawableEntries = IconDrawableCache.size,
        colorEntries = IconColorCache.size,
        maskBytes = MonochromeCache.shared.size,
        viewIdentityEntries = IdentityResolver.viewCount(),
    )
}
