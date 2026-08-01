package com.jerrey.monoicon.cache

import android.graphics.Bitmap

/**
 * Thread-safe in-memory cache for processed monochrome icon bitmaps.
 *
 * Implementations must be safe to call from any thread, as cache access
 * may occur on the main thread (quick lookups) or background threads
 * (after processing).
 */
interface BitmapCache {

    /** Current number of cached entries. */
    val size: Int

    /** Maximum number of entries this cache can hold. */
    val maxSize: Int

    /**
     * Returns a snapshot of cache performance statistics.
     */
    fun stats(): CacheStats

    /**
     * Retrieves the [Bitmap] associated with [key], or `null`
     * if the key is not present or the entry has been evicted.
     */
    fun get(key: String): Bitmap?

    /**
     * Stores [bitmap] in the cache associated with [key].
     * If an entry already exists for [key], it is replaced.
     * May trigger eviction of older entries if the cache is at capacity.
     */
    fun put(key: String, bitmap: Bitmap)

    /**
     * Removes the entry for [key] if it exists. Idempotent.
     */
    fun remove(key: String)

    /**
     * Removes all entries from the cache.
     */
    fun clear()

    /**
     * Invalidates the entire cache. Equivalent to [clear] but signals
     * an external reason (e.g., theme change, wallpaper change) that
     * makes all cached entries stale.
     *
     * Future implementations may track invalidation reasons for logging.
     */
    fun invalidate() {
        clear()
    }
}
