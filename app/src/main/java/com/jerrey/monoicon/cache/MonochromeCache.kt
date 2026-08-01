package com.jerrey.monoicon.cache

import android.graphics.Bitmap
import android.util.LruCache
import com.jerrey.monoicon.logging.logd

private const val TAG = "MonoIcon.MonoCache"

/**
 * In-memory LRU cache for generated monochrome mask [Bitmap]s.
 *
 * Key format (Phase 2.9):
 * `identity + "|" + width + "x" + height + "@" + fingerprint`
 * Example: `com.pkg/.MainActivity|108x108@A3F29E4D`
 *
 * **Stores raw [Bitmap] masks**, not [android.graphics.drawable.Drawable]
 * instances. Drawable objects may carry state and should not be blindly
 * shared; the caller wraps the cached bitmap each time.
 *
 * Identity resolution (package/component name) is done in the hook layer
 * ([com.jerrey.monoicon.hook.IconThemeHook]) and passed in as [identity].
 *
 * Fingerprint is computed from the bitmap content via **FNV-1a 32-bit**
 * to distinguish different visuals from the same component.
 *
 * @param maxSize Maximum number of cached entries.
 */
class MonochromeCache(private val maxSize: Int) {

    /** Stores [Bitmap] masks, keyed by identity + size + fingerprint. */
    private val cache = object : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** Current number of cached entries. */
    val size: Int get() = cache.size()

    // ── Key construction ──────────────────────────────────────────────

    /**
     * Builds a stable cache key from component identity and bitmap content.
     *
     * Format: `"$identity|${width}x${height}@$fingerprint"`
     *
     * @param identity Resolved component identity (e.g. "com.pkg/.MainActivity"
     *                 or a fallback such as "com.pkg").
     * @param bitmap The monochrome mask bitmap whose content fingerprint
     *               is used to distinguish different visual states of the
     *               same component.
     * @return Cache key, or `null` if [identity] is blank or [bitmap] is recycled.
     */
    /**
     * Builds a stable cache key from component identity, bitmap content,
     * and icon source type.
     *
     * Format: `"$identity|${w}x${h}@$fp|$src"`
     *
     * Source suffix prevents cache collisions between different icon sources
     * (e.g. NATIVE monochrome vs LUMINANCE fallback for the same component).
     *
     * @param identity Resolved component identity.
     * @param bitmap The monochrome mask bitmap (for fingerprint).
     * @param source Source type: [SOURCE_NATIVE], [SOURCE_FOREGROUND], or [SOURCE_LUMINANCE].
     * @return Cache key, or `null` if identity/bitmap invalid.
     */
    fun buildKey(identity: String?, bitmap: Bitmap?, source: Int): String? {
        if (identity.isNullOrBlank()) return null
        if (bitmap == null || bitmap.isRecycled) return null
        val w = bitmap.width
        val h = bitmap.height
        val fp = computeFingerprint(bitmap)
        val src = when (source) {
            SOURCE_NATIVE -> "NATIVE"
            SOURCE_FOREGROUND -> "FG"
            else -> "LUMA"
        }
        return "$identity|${w}x${h}@$fp|$src"
    }

    // ── Source constants (synced with IconThemeHook) ──────────────────
    companion object {
        const val SOURCE_NATIVE = 1
        const val SOURCE_FOREGROUND = 2
        const val SOURCE_LUMINANCE = 3
    }

    // ── Cache operations ──────────────────────────────────────────────

    /**
     * Returns the cached monochrome mask [Bitmap] for [key], or `null`.
     */
    fun get(key: String): Bitmap? {
        val bmp = cache.get(key)
        if (bmp != null) {
            logd(TAG, "Cache HIT: $key (size=${cache.size()}/${cache.maxSize()})")
        } else {
            logd(TAG, "Cache MISS: $key")
        }
        return bmp
    }

    /** Stores [maskBitmap] under [key]. Replaces any existing entry. */
    fun put(key: String, maskBitmap: Bitmap) {
        cache.put(key, maskBitmap)
        logd(TAG, "Cache PUT: $key (size=${cache.size()}/${cache.maxSize()})")
    }

    /** Removes all entries (e.g., on theme change). */
    fun clear() {
        cache.evictAll()
        logd(TAG, "Cache CLEARED")
    }

    // ── Fingerprint ───────────────────────────────────────────────────

    /**
     * Computes a deterministic 32-bit content fingerprint of [bitmap]
     * using the **FNV-1a** hash algorithm.
     *
     * ## Inclusion
     * - Bitmap width and height
     * - Sampled ARGB pixel values (step = max(1, min(w,h) / 16))
     *
     * ## Not included
     * - [Bitmap.hashCode()] — object identity, not content
     * - [android.graphics.drawable.Drawable.hashCode()] — same reason
     *
     * ## Collision resistance
     * 32-bit space is sufficient for icon-scale datasets (hundreds of
     * entries per process). The same visual input always produces the
     * same hash; different inputs are very likely to produce different
     * hashes.
     *
     * Internal operations use [Long] and an explicit 32-bit mask
     * (`and 0xFFFFFFFFL`) to avoid Kotlin UInt dependency.
     */
    fun computeFingerprint(bitmap: Bitmap): String {
        var hash = 0x811c9dc5L

        // Include dimensions
        hash = ((hash xor bitmap.width.toLong()) * 0x01000193L) and 0xFFFFFFFFL
        hash = ((hash xor bitmap.height.toLong()) * 0x01000193L) and 0xFFFFFFFFL

        val w = bitmap.width
        val h = bitmap.height
        val step = maxOf(1, minOf(w, h) / 16)

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                hash = ((hash xor bitmap.getPixel(x, y).toLong()) * 0x01000193L) and 0xFFFFFFFFL
                x += step
            }
            y += step
        }

        return hash.toString(16).padStart(8, '0')
    }
}
