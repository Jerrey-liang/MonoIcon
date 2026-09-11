package com.jerrey.monoicon.hook

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe invocation statistics for hook verification.
 *
 * Tracks call count, total execution time, and min/max time
 * for each hooked method. Prints summary every [printInterval]
 * invocations.
 *
 * All public methods are safe to call from any thread.
 */
class HookStats(
    private val tag: String,
    private val printInterval: Long = 100
) {

    // ── Per-method counters ──────────────────────────────────────

    private data class MethodStats(
        val count: AtomicLong = AtomicLong(0),
        val totalTimeNanos: AtomicLong = AtomicLong(0),
        val maxTimeNanos: AtomicLong = AtomicLong(Long.MIN_VALUE),
        val minTimeNanos: AtomicLong = AtomicLong(Long.MAX_VALUE)
    )

    private val statsMap = ConcurrentHashMap<String, MethodStats>()

    // ── Public API ────────────────────────────────────────────────

    /**
     * Records one invocation of [methodName] with the given
     * execution time in milliseconds.
     */
    fun record(methodName: String, elapsedMs: Long) {
        val elapsedNanos = elapsedMs * 1_000_000L
        val ms = statsMap.computeIfAbsent(methodName) { MethodStats() }
        val newCount = ms.count.incrementAndGet()
        ms.totalTimeNanos.addAndGet(elapsedNanos)
        ms.maxTimeNanos.updateAndGet { maxOf(it, elapsedNanos) }
        ms.minTimeNanos.updateAndGet { minOf(it, elapsedNanos) }

        // Print summary every [printInterval] calls
        if (newCount % printInterval == 0L) {
            printSummary(methodName, ms)
        }
    }

    // ── Internal ──────────────────────────────────────────────────

    private fun printSummary(methodName: String, ms: MethodStats) {
        val count = ms.count.get()
        val totalMs = ms.totalTimeNanos.get() / 1_000_000L
        val avgMs = if (count > 0) totalMs / count else 0L
        val maxMs = ms.maxTimeNanos.get() / 1_000_000L
        val minMs = if (ms.minTimeNanos.get() == Long.MAX_VALUE) 0L
                     else ms.minTimeNanos.get() / 1_000_000L

        com.jerrey.monoicon.logging.logi(tag,
            "[$methodName] count=$count | avg=${avgMs}ms | min=${minMs}ms | max=${maxMs}ms | total=${totalMs}ms"
        )
    }
}
