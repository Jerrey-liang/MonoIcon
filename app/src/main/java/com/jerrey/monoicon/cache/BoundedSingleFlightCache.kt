package com.jerrey.monoicon.cache

import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch

/**
 * Access-ordered cache bounded by both entry count and completed-value weight.
 * Loaders for different keys run independently, outside the cache lock.
 *
 * Invalidating a running load detaches it: its existing callers can still receive
 * its result, but it cannot populate the cache or serve later requests.
 */
internal class BoundedSingleFlightCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val maxWeight: Long,
    private val weigher: (V) -> Long,
) {
    init {
        require(maxEntries >= 0) { "maxEntries must be non-negative" }
        require(maxWeight >= 0) { "maxWeight must be non-negative" }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val flights = HashMap<K, Flight<V>>()
    private var totalWeight = 0L

    fun getIfPresent(key: K): V? = synchronized(lock) { entries[key]?.value }

    fun getOrCreate(key: K, loader: () -> V?): V? {
        var ownsFlight = false
        val flight = synchronized(lock) {
            entries[key]?.let { return it.value }
            flights[key]?.also {
                check(it.owner !== Thread.currentThread()) {
                    "Recursive loading of the same cache key is not supported"
                }
            } ?: Flight<V>(Thread.currentThread()).also {
                flights[key] = it
                ownsFlight = true
            }
        }
        if (!ownsFlight) return flight.await()

        try {
            val value = loader()
            val entry = value?.let {
                val weight = weigher(it)
                require(weight >= 0) { "Value weight must be non-negative" }
                Entry(it, weight)
            }
            synchronized(lock) {
                if (flights[key] === flight && entry != null) {
                    put(key, entry)
                }
            }
            flight.value = value
            return value
        } catch (failure: Throwable) {
            flight.failure = failure
            throw failure
        } finally {
            synchronized(lock) {
                if (flights[key] === flight) flights.remove(key)
            }
            // CountDownLatch publishes value/failure to every waiting caller.
            flight.completed.countDown()
        }
    }

    /** The predicate should only inspect the key, without reentering the cache. */
    fun invalidate(predicate: (K) -> Boolean) {
        synchronized(lock) {
            val cached = entries.entries.iterator()
            while (cached.hasNext()) {
                val entry = cached.next()
                if (predicate(entry.key)) {
                    totalWeight -= entry.value.weight
                    cached.remove()
                }
            }
            val pending = flights.keys.iterator()
            while (pending.hasNext()) {
                if (predicate(pending.next())) pending.remove()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            totalWeight = 0L
            flights.clear()
        }
    }

    /** Called with [lock] held; oversize results do not evict useful entries. */
    private fun put(key: K, entry: Entry<V>) {
        if (maxEntries == 0 || entry.weight > maxWeight) return
        entries.remove(key)?.let { totalWeight -= it.weight }
        val oldest = entries.entries.iterator()
        // Subtract first so a near-Long.MAX_VALUE budget cannot overflow.
        while (entries.size >= maxEntries || totalWeight > maxWeight - entry.weight) {
            totalWeight -= oldest.next().value.weight
            oldest.remove()
        }
        entries[key] = entry
        totalWeight += entry.weight
    }

    private data class Entry<V>(val value: V, val weight: Long)

    private class Flight<V>(val owner: Thread) {
        val completed = CountDownLatch(1)
        var value: V? = null
        var failure: Throwable? = null

        fun await(): V? {
            completed.await()
            failure?.let { throw it }
            return value
        }
    }
}
