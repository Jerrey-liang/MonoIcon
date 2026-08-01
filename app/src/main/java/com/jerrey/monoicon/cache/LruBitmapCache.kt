package com.jerrey.monoicon.cache

import android.graphics.Bitmap
import android.util.LruCache
import com.jerrey.monoicon.logging.logd

private const val TAG = "MonoIcon.Cache"

/**
 * Thread-safe LRU (Least Recently Used) in-memory [Bitmap] cache.
 *
 * Backed by [android.util.LruCache], which is already thread-safe.
 * Eviction order is based on access recency — the least recently used
 * entry is evicted first when the cache exceeds [maxSize].
 *
 * @param maxSize Maximum number of entries to hold.
 */
class LruBitmapCache(override val maxSize: Int) : BitmapCache {

    // Track statistics manually since LruCache doesn't expose them
    private var hits: Long = 0
    private var misses: Long = 0
    private var evictions: Long = 0

    private val cache = object : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            // Each entry counts as 1 regardless of bitmap byte size.
            // For a size-based cache, override this to return value.byteCount.
            return 1
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            if (evicted) {
                evictions++
                // Recycle the evicted bitmap if it's not also stored elsewhere
                if (!oldValue.isRecycled && newValue == null) {
                    oldValue.recycle()
                }
            }
        }
    }

    override val size: Int get() = cache.size()

    override fun stats(): CacheStats {
        val total = hits + misses
        val rate = if (total > 0) hits.toFloat() / total else 0f
        return CacheStats(
            hits = hits,
            misses = misses,
            evictions = evictions,
            hitRate = rate,
            currentSize = cache.size(),
            maxSize = cache.maxSize()
        )
    }

    override fun get(key: String): Bitmap? {
        val bitmap = cache.get(key)
        if (bitmap != null) {
            hits++
            logd(TAG, "Cache HIT: $key (${stats().hitRate})")
        } else {
            misses++
            logd(TAG, "Cache MISS: $key")
        }
        return bitmap
    }

    override fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
        logd(TAG, "Cache PUT: $key (size=${cache.size()}/${cache.maxSize()})")
    }

    override fun remove(key: String) {
        cache.remove(key)
        logd(TAG, "Cache REMOVE: $key")
    }

    override fun clear() {
        cache.evictAll()
        logd(TAG, "Cache CLEARED")
    }

    override fun invalidate() {
        clear()
        logd(TAG, "Cache INVALIDATED (theme/wallpaper change)")
    }
}
