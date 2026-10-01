package com.jerrey.monoicon.theme.render

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.jerrey.monoicon.cache.BoundedSingleFlightCache
import com.jerrey.monoicon.color.IconDrawableCache

/**
 * Draw-time cache for launcher icon generation.
 *
 * `ShortcutIcon.setIconDrawable` runs for every icon on every workspace bind
 * and every app-drawer scroll frame. [ThemedIconBuilder.build] renders a mask
 * on each of those calls, so scrolling the drawer re-rendered the same
 * components continuously — the mask pipeline itself only caches *after*
 * rendering, because its fingerprint is taken from the finished bitmap.
 *
 * This object sits in front of that funnel and keys the finished result by
 * [LauncherIconRequest], turning a repeated bind of one icon into a lookup.
 *
 * Both layers are [BoundedSingleFlightCache]es, so a burst of identical
 * requests (workspace bind plus drawer bind, or two launcher threads racing)
 * renders once and every caller receives the same instance.
 *
 * Invalidation is driven entirely by [com.jerrey.monoicon.cache.CacheManager];
 * this object never decides on its own that an entry is stale.
 */
object LauncherIconRenderer {

    /** Fraction of the heap the generated icons may occupy (mirrors MonochromeCache). */
    private const val MAX_WEIGHT_DIVISOR = 16

    /** Bounds for both tiers: a launcher shows far fewer icons than this at once. */
    private const val MAX_ENTRIES = 512

    /**
     * Cache key: the icon being rendered, plus every property of the drawable
     * HyperOS passed the display hook that the mask pipeline can read.
     *
     * `AospMonochromeMaskStrategy` falls back to that drawable for its target
     * size whenever the raw APK icon reports no intrinsic size, so the rendered
     * mask size is only guaranteed to be a function of the identity. Tracking
     * the source's bounds and intrinsic size costs a few ints and stops a mask
     * rendered for one size from being handed to a display that wants another.
     */
    private data class Key(
        val request: LauncherIconRequest,
        /** `Drawable.bounds` — `getBounds()` returns the internal rect, no copy. */
        val boundsWidth: Int,
        val boundsHeight: Int,
        val sourceSize: Int,
    )

    /**
     * Generated masks plus their colour pair, bounded by entry count and by
     * mask bitmap bytes so one oversized mask cannot evict every desktop entry.
     *
     * Only the identity-stable half of a generated icon is retained — the
     * per-bind [ThemedIconBuilder.Result] is rebuilt from it, so no drawable
     * is ever shared between two views.
     */
    private val results = BoundedSingleFlightCache<Key, ThemedIconBuilder.Recipe>(
        maxEntries = MAX_ENTRIES,
        maxWeight = Runtime.getRuntime().maxMemory() / MAX_WEIGHT_DIVISOR,
        weigher = { recipe -> maskBytes(recipe.mask) },
    )

    /**
     * Raw APK drawables captured by Hook 7 during icon load.
     *
     * These are only present so that a burst of `getActivityIcon` calls for the
     * same component performs the capture — and therefore `extractEarlyIconColor`'s
     * bitmap render — once instead of once per call. Entries are counted, not
     * sized: the drawables themselves stay owned by
     * [IconDrawableCache], which is what the mask pipeline reads.
     */
    private val captures = BoundedSingleFlightCache<LauncherIconRequest, Drawable>(
        maxEntries = MAX_ENTRIES,
        maxWeight = MAX_ENTRIES.toLong(),
        weigher = { 1L },
    )

    /**
     * Returns the themed icon for [request], generating it once per identity.
     *
     * [source] is the drawable HyperOS handed the display hook. It is only used
     * to render entries that are not identity-stable:
     *
     * - With a Hook 7 capture present, the mask derives from the isolated raw
     *   APK drawable, so it depends on [LauncherIconRequest.identity] alone and
     *   the result is reusable across binds.
     * - Without one, the mask would be derived from the passed-in drawable,
     *   which is a different object on each bind. That path is rendered
     *   uncached so its output stays byte-identical to the previous behavior.
     *
     * @return the themed icon, or null when no mask could be generated — the
     *  caller must then keep the original drawable (fail-open).
     */
    fun build(request: LauncherIconRequest, source: Drawable): ThemedIconBuilder.Result? {
        if (IconDrawableCache.get(request.identity) == null) {
            return ThemedIconBuilder.build(source, request.identity)
        }
        val recipe = results.getOrCreate(keyOf(request, source)) {
            ThemedIconBuilder.recipe(source, request.identity)
        } ?: return null
        return ThemedIconBuilder.drawable(recipe)
    }

    /**
     * Runs [capture] at most once for [request] while a capture is in flight.
     *
     * A null result is never retained, so a component whose icon failed to load
     * is retried on the next request rather than being remembered as absent.
     */
    fun capture(request: LauncherIconRequest, capture: () -> Drawable?): Drawable? =
        captures.getOrCreate(request, capture)

    /** Drops every entry belonging to [packageName] (package update / removal). */
    fun invalidatePackage(packageName: String) {
        if (packageName.isBlank()) return
        val prefix = "$packageName/"
        val belongsToPackage = { request: LauncherIconRequest ->
            request.identity == packageName || request.identity.startsWith(prefix)
        }
        results.invalidate { key -> belongsToPackage(key.request) }
        captures.invalidate(belongsToPackage)
    }

    /** Drops every entry, including any in-flight generation result. */
    fun clear() {
        results.clear()
        captures.clear()
    }

    private fun keyOf(request: LauncherIconRequest, source: Drawable): Key = Key(
        request = request,
        boundsWidth = source.bounds.width(),
        boundsHeight = source.bounds.height(),
        sourceSize = maxOf(source.intrinsicWidth, source.intrinsicHeight),
    )

    private fun maskBytes(mask: Bitmap): Long =
        if (mask.isRecycled) 0L else mask.rowBytes.toLong() * mask.height
}
