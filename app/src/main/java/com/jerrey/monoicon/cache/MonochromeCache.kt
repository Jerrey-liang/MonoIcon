package com.jerrey.monoicon.cache

import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import com.jerrey.monoicon.logging.logd

private const val TAG = "MonoIcon.MonoCache"

/**
 * In-memory LRU cache for generated monochrome [BitmapDrawable]s.
 *
 * Keyed by a stable identifier derived from the app's **package name**
 * plus the icon size (`packageName|widthxheight`). The package name is
 * resolved at hook time via `ShortcutIcon.getShortcutInfo().getPackageName()`
 * and is stable across icon redraws for the same app.
 *
 * This deliberately avoids unstable keys:
 * - `Drawable.hashCode()` — instance identity changes on every redraw
 * - width/height alone — collides across different apps at the same size
 *
 * Backed by [android.util.LruCache] (thread-safe). The launcher's
 * icon redraws occur on the main thread, so access is single-threaded
 * in practice, but the underlying LruCache provides safety regardless.
 *
 * @param maxSize Maximum number of cached entries.
 */
class MonochromeCache(private val maxSize: Int) {

    private val cache = object : LruCache<String, BitmapDrawable>(maxSize) {
        override fun sizeOf(key: String, value: BitmapDrawable): Int = 1
    }

    /** Current number of cached entries. */
    val size: Int get() = cache.size()

    /**
     * Builds a stable cache key from [packageName] and icon dimensions.
     *
     * @param packageName The app package name (may be null → no cache).
     * @param width Icon width in px.
     * @param height Icon height in px.
     * @return Cache key, or `null` if [packageName] is null/blank.
     */
    fun buildKey(packageName: String?, width: Int, height: Int): String? {
        if (packageName.isNullOrBlank()) return null
        return "$packageName|${width}x${height}"
    }

    /**
     * Returns the cached monochrome drawable for [key], or `null`.
     */
    fun get(key: String): BitmapDrawable? {
        val drawable = cache.get(key)
        if (drawable != null) {
            logd(TAG, "Cache HIT: $key (size=${cache.size()}/${cache.maxSize()})")
        } else {
            logd(TAG, "Cache MISS: $key")
        }
        return drawable
    }

    /**
     * Stores [drawable] under [key]. Replaces any existing entry.
     */
    fun put(key: String, drawable: BitmapDrawable) {
        cache.put(key, drawable)
        logd(TAG, "Cache PUT: $key (size=${cache.size()}/${cache.maxSize()})")
    }

    /** Removes all entries (e.g., on theme change). */
    fun clear() {
        cache.evictAll()
        logd(TAG, "Cache CLEARED")
    }
}
