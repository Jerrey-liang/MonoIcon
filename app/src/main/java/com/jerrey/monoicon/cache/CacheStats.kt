package com.jerrey.monoicon.cache

/**
 * Immutable snapshot of cache performance metrics.
 *
 * @param hits Number of successful cache lookups.
 * @param misses Number of cache lookups where the key was not found.
 * @param evictions Number of entries evicted due to capacity constraints.
 * @param hitRate Ratio of `hits / (hits + misses)`, or `0f` if no lookups.
 * @param currentSize Current number of entries in the cache.
 * @param maxSize Maximum number of entries the cache can hold.
 */
data class CacheStats(
    val hits: Long = 0,
    val misses: Long = 0,
    val evictions: Long = 0,
    val hitRate: Float = 0f,
    val currentSize: Int = 0,
    val maxSize: Int = 0
)
