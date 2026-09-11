package com.jerrey.monoicon.color

import android.graphics.drawable.Drawable
import java.util.LinkedHashMap

/**
 * Thread-safe LRU cache storing raw APK [Drawable] references
 * for monochrome mask generation.
 *
 * Populated at [com.miui.home.icon.IconProvider.getActivityIcon] hook time
 * (Phase 3.15), before HyperOS replaces the original colored
 * [android.graphics.drawable.AdaptiveIconDrawable] with a
 * [com.miui.home.common.drawable.LayerAdaptiveIconDrawable]
 * containing a monochrome mask.
 *
 * Consumed in [com.jerrey.monoicon.theme.mask.AospMonochromeMaskStrategy]
 * (raw-APK tier) to feed the original drawable into the AOSP mask pipeline.
 *
 * ## Key format
 * `"pkg/full.ClassName"` — matches [IconColorCache] and IdentityResolver.
 *
 * ## Drawable lifecycle
 * Stored drawables are NOT cloned. They are references to the original
 * AdaptiveIconDrawable obtained from [android.content.pm.LauncherActivityInfo.getIcon],
 * isolated via constantState.newDrawable().mutate() at capture time.
 * The launcher retains ownership; we only reference them for rendering.
 *
 * ## Capacity (Phase 3.18-D)
 * LRU eviction at [MAX_ENTRIES] (access-order [LinkedHashMap], all access
 * synchronized). Evicted entries are re-captured on the next app icon
 * load (Hook 7) — no correctness impact.
 */
object IconDrawableCache {

    /** Maximum retained raw drawables (LRU eviction beyond this). */
    private const val MAX_ENTRIES = 256

    private val lock = Any()

    private val store = object : LinkedHashMap<String, Drawable>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    /** Number of cached entries. */
    val size: Int get() = synchronized(lock) { store.size }

    fun put(identity: String, drawable: Drawable) {
        synchronized(lock) {
            store[identity] = drawable
        }
    }

    fun get(identity: String): Drawable? = synchronized(lock) {
        store[identity]
    }

    fun remove(identity: String) {
        synchronized(lock) {
            store.remove(identity)
        }
    }

    /**
     * Phase 4.0-A: removes every entry whose key starts with [prefix]
     * (e.g. `"com.pkg/"` invalidates all `com.pkg/`-prefixed identities).
     */
    fun removeByPrefix(prefix: String) {
        synchronized(lock) {
            val stale = store.keys.filter { it.startsWith(prefix) }
            stale.forEach { store.remove(it) }
        }
    }

    /**
     * Phase 10: first cached raw drawable whose key starts with
     * `"<packageName>/"`, or null.
     *
     * Recents hands us the *task's* top component, which is frequently not the
     * launcher activity captured by the RawIconProvider hook — this
     * package-level fallback keeps the raw-APK mask tier working there.
     */
    fun getByPackage(packageName: String): Drawable? {
        if (packageName.isEmpty()) return null
        val prefix = packageName + "/"
        return synchronized(lock) {
            store.entries.firstOrNull { it.key.startsWith(prefix) }?.value
        }
    }

    fun clear() {
        synchronized(lock) {
            store.clear()
        }
    }
}
