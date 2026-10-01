package com.jerrey.monoicon.cache

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedSingleFlightCacheTest {

    @Test
    fun entryLimitEvictsLeastRecentlyUsedAndHitsSkipLoader() {
        val cache = BoundedSingleFlightCache<String, String>(2, 100) { it.length.toLong() }
        cache.getOrCreate("a") { "first" }
        cache.getOrCreate("b") { "second" }
        assertEquals("first", cache.getOrCreate("a") { error("cached value must be reused") })

        cache.getOrCreate("c") { "third" }

        assertNull(cache.getIfPresent("b"))
        assertEquals("first", cache.getIfPresent("a"))
        assertEquals("third", cache.getIfPresent("c"))
    }

    @Test
    fun weightLimitUsesAccessOrderAndCanEvictMultipleEntries() {
        val cache = BoundedSingleFlightCache<String, String>(10, 5) { it.length.toLong() }
        cache.getOrCreate("a") { "aaa" }
        cache.getOrCreate("b") { "bb" }
        cache.getIfPresent("a")

        cache.getOrCreate("c") { "cc" }
        assertNull(cache.getIfPresent("b"))
        assertEquals("aaa", cache.getIfPresent("a"))
        assertEquals("cc", cache.getIfPresent("c"))

        cache.getOrCreate("d") { "dddd" }
        assertNull(cache.getIfPresent("a"))
        assertNull(cache.getIfPresent("c"))
        assertEquals("dddd", cache.getIfPresent("d"))
    }

    @Test
    fun oversizedAndNullResultsAreNotCachedOrAllowedToEvictUsefulValues() {
        val cache = BoundedSingleFlightCache<String, String>(2, 5) { it.length.toLong() }
        cache.getOrCreate("keep") { "small" }
        var oversizedLoads = 0
        var nullLoads = 0

        repeat(2) {
            assertEquals("too large", cache.getOrCreate("large") {
                oversizedLoads++
                "too large"
            })
            assertNull(cache.getOrCreate("missing") {
                nullLoads++
                null
            })
        }

        assertEquals(2, oversizedLoads)
        assertEquals(2, nullLoads)
        assertNull(cache.getIfPresent("large"))
        assertNull(cache.getIfPresent("missing"))
        assertEquals("small", cache.getIfPresent("keep"))
    }

    @Test
    fun weightAccountingDoesNotOverflowLong() {
        val cache = BoundedSingleFlightCache<String, Long>(3, Long.MAX_VALUE) { it }
        cache.getOrCreate("large") { Long.MAX_VALUE - 1 }

        cache.getOrCreate("small") { 2L }

        assertNull(cache.getIfPresent("large"))
        assertEquals(2L, cache.getIfPresent("small"))
    }

    @Test
    fun invalidatingAndClearingCompletedEntriesReleaseTheirWeight() {
        val cache = BoundedSingleFlightCache<String, String>(3, 3) { it.length.toLong() }
        cache.getOrCreate("remove") { "aa" }
        cache.getOrCreate("keep") { "b" }

        cache.invalidate { it == "remove" }
        cache.getOrCreate("new") { "cc" }
        assertNull(cache.getIfPresent("remove"))
        assertEquals("b", cache.getIfPresent("keep"))
        assertEquals("cc", cache.getIfPresent("new"))

        cache.clear()
        assertNull(cache.getIfPresent("keep"))
        assertNull(cache.getIfPresent("new"))
        assertEquals("ddd", cache.getOrCreate("after") { "ddd" })
        assertEquals("ddd", cache.getIfPresent("after"))
    }

    @Test(timeout = 10_000)
    fun sameKeySharesOneFlightWhileOtherKeysCanFinishIndependently() {
        val cache = BoundedSingleFlightCache<String, Any>(4, 4) { 1L }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loads = AtomicInteger()
        val value = Any()
        val owner = Worker {
            cache.getOrCreate("shared") {
                loads.incrementAndGet()
                started.countDown()
                release.await()
                value
            }
        }
        var follower: Worker<Any?>? = null
        var independent: Worker<Any?>? = null
        try {
            await(started)
            follower = Worker {
                cache.getOrCreate("shared") {
                    loads.incrementAndGet()
                    Any()
                }
            }
            follower.awaitBlocked()
            val independentValue = Any()
            independent = Worker { cache.getOrCreate("other") { independentValue } }
            assertSame(independentValue, independent.get())
            assertFalse(owner.isDone)

            release.countDown()
            assertSame(value, owner.get())
            assertSame(value, follower.get())
            assertEquals(1, loads.get())
        } finally {
            release.countDown()
            owner.close()
            follower?.close()
            independent?.close()
        }
    }

    @Test(timeout = 10_000)
    fun loaderFailureReachesAllWaitersAndNextRequestCanRetry() {
        val cache = BoundedSingleFlightCache<String, String>(2, 10) { it.length.toLong() }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = IllegalStateException("loader failed")
        val owner = Worker {
            cache.getOrCreate("key") {
                started.countDown()
                release.await()
                throw failure
            }
        }
        var follower: Worker<String?>? = null
        try {
            await(started)
            follower = Worker { cache.getOrCreate("key") { error("duplicate load") } }
            follower.awaitBlocked()

            release.countDown()
            assertSame(failure, owner.failure())
            assertSame(failure, follower.failure())
            assertNull(cache.getIfPresent("key"))
            assertEquals("retry", cache.getOrCreate("key") { "retry" })
        } finally {
            release.countDown()
            owner.close()
            follower?.close()
        }
    }

    @Test(timeout = 10_000)
    fun clearDetachesOldFlightWithoutLettingItsCompletionRemoveTheNewFlight() {
        val cache = BoundedSingleFlightCache<String, String>(2, 10) { it.length.toLong() }
        val oldStarted = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val freshStarted = CountDownLatch(1)
        val freshRelease = CountDownLatch(1)
        val old = Worker {
            cache.getOrCreate("key") {
                oldStarted.countDown()
                oldRelease.await()
                "stale"
            }
        }
        var fresh: Worker<String?>? = null
        var follower: Worker<String?>? = null
        try {
            await(oldStarted)
            cache.clear()
            fresh = Worker {
                cache.getOrCreate("key") {
                    freshStarted.countDown()
                    freshRelease.await()
                    "fresh"
                }
            }
            await(freshStarted)
            oldRelease.countDown()
            assertEquals("stale", old.get())
            assertNull(cache.getIfPresent("key"))

            follower = Worker { cache.getOrCreate("key") { error("new flight was removed") } }
            follower.awaitBlocked()
            freshRelease.countDown()
            assertEquals("fresh", fresh.get())
            assertEquals("fresh", follower.get())
            assertEquals("fresh", cache.getIfPresent("key"))
        } finally {
            oldRelease.countDown()
            freshRelease.countDown()
            old.close()
            fresh?.close()
            follower?.close()
        }
    }

    @Test(timeout = 10_000)
    fun invalidateDetachesOnlyMatchingFlightsAndRejectsTheirStaleResults() {
        val cache = BoundedSingleFlightCache<String, String>(4, 20) { it.length.toLong() }
        val invalidatedStarted = CountDownLatch(1)
        val keptStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidated = Worker {
            cache.getOrCreate("app:old") {
                invalidatedStarted.countDown()
                release.await()
                "stale"
            }
        }
        val kept = Worker {
            cache.getOrCreate("other") {
                keptStarted.countDown()
                release.await()
                "kept"
            }
        }
        var keptFollower: Worker<String?>? = null
        try {
            await(invalidatedStarted)
            await(keptStarted)
            cache.invalidate { it.startsWith("app:") }
            assertEquals("fresh", cache.getOrCreate("app:old") { "fresh" })
            keptFollower = Worker { cache.getOrCreate("other") { error("unrelated flight removed") } }
            keptFollower.awaitBlocked()

            release.countDown()
            assertEquals("stale", invalidated.get())
            assertEquals("kept", kept.get())
            assertEquals("kept", keptFollower.get())
            assertEquals("fresh", cache.getIfPresent("app:old"))
            assertEquals("kept", cache.getIfPresent("other"))
        } finally {
            release.countDown()
            invalidated.close()
            kept.close()
            keptFollower?.close()
        }
    }

    @Test(timeout = 10_000)
    fun recursiveSameKeyLoadFailsFastAndDoesNotPoisonLaterLoads() {
        val cache = BoundedSingleFlightCache<String, String>(2, 10) { it.length.toLong() }
        val recursive = Worker {
            cache.getOrCreate("key") {
                cache.getOrCreate("key") { "unreachable" }
            }
        }
        try {
            assertTrue(recursive.failure() is IllegalStateException)
            assertNull(cache.getIfPresent("key"))
            assertEquals("retry", cache.getOrCreate("key") { "retry" })
        } finally {
            recursive.close()
        }
    }

    @Test
    fun invalidBudgetsAndNegativeWeightsFailWithoutCaching() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedSingleFlightCache<String, String>(-1, 10) { 1L }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedSingleFlightCache<String, String>(1, -1) { 1L }
        }
        val cache = BoundedSingleFlightCache<String, String>(1, 10) {
            if (it == "invalid") -1L else 1L
        }
        assertThrows(IllegalArgumentException::class.java) {
            cache.getOrCreate("key") { "invalid" }
        }
        assertNull(cache.getIfPresent("key"))
        assertEquals("valid", cache.getOrCreate("key") { "valid" })
    }

    @Test
    fun zeroEntryBudgetAndZeroWeightValuesRemainBounded() {
        val disabled = BoundedSingleFlightCache<String, String>(0, 10) { 1L }
        assertEquals("value", disabled.getOrCreate("key") { "value" })
        assertNull(disabled.getIfPresent("key"))

        val zeroWeight = BoundedSingleFlightCache<String, String>(1, 0) { 0L }
        zeroWeight.getOrCreate("a") { "first" }
        zeroWeight.getOrCreate("b") { "second" }
        assertNull(zeroWeight.getIfPresent("a"))
        assertEquals("second", zeroWeight.getIfPresent("b"))
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("worker reached its checkpoint", latch.await(2, TimeUnit.SECONDS))
    }

    private class Worker<T>(block: () -> T) {
        private val task = FutureTask(Callable(block))
        private val thread = Thread(task, "single-flight-test").apply {
            isDaemon = true
            start()
        }

        val isDone: Boolean get() = task.isDone

        fun get(): T = task.get(2, TimeUnit.SECONDS)

        fun failure(): Throwable? = assertThrows(ExecutionException::class.java) { get() }.cause

        fun awaitBlocked() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (thread.state != Thread.State.WAITING) {
                assertFalse("caller should wait for the active loader", task.isDone)
                assertTrue("caller did not reach the active flight", System.nanoTime() < deadline)
                Thread.sleep(1)
            }
        }

        fun close() {
            thread.interrupt()
            thread.join(2_000)
            assertFalse("test worker leaked", thread.isAlive)
        }
    }
}
