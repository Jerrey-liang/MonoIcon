package com.jerrey.monoicon.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOs4GeometryRetriesTest {
    @Test fun aSavedCommitCanRetryBeforeItsNativeAcknowledgement() {
        val retries = HyperOs4GeometryRetries()
        // No native ACK or lastPublished state is required after the root commit.
        assertTrue(retries.schedule(10, "saved-config:1", unavailable = true))
        assertEquals(10L, retries.pending)
        assertTrue(retries.consume(10, 10))
        assertEquals("restart waits until the retry has read geometry", 10L, retries.pending)
        retries.readCompleted()
        assertNull(retries.pending)
    }

    @Test fun repeatedReadsOfTheSameCommitStopAfterThreeRetries() {
        val retries = HyperOs4GeometryRetries()
        for (request in 1L..3L) {
            retries.readCompleted()
            assertTrue(retries.schedule(request, "same-config:1", unavailable = true))
            assertTrue(retries.consume(request, request))
        }
        retries.readCompleted()
        assertFalse(retries.schedule(4, "same-config:1", unavailable = true))
        assertEquals(3, retries.attempts)
        assertNull(retries.pending)
    }

    @Test fun aSupersededRequestCannotPublishOrCancelANewerRetry() {
        val retries = HyperOs4GeometryRetries()
        assertTrue(retries.schedule(1, "old-config:1", unavailable = true))
        assertFalse(retries.consume(1, 2))
        retries.readCompleted()
        assertTrue(retries.schedule(2, "new-config:1", unavailable = true))
        assertFalse(retries.consume(1, 2))
        assertEquals(2L, retries.pending)
        assertTrue(retries.consume(2, 2))
    }

    @Test fun recoveryAndExplicitRevisionChangesStartANewFiniteChain() {
        val retries = HyperOs4GeometryRetries()
        repeat(3) { retries.schedule(it.toLong(), "config:1", unavailable = true) }
        assertFalse(retries.schedule(3, "config:1", unavailable = true))
        assertTrue(retries.schedule(4, "config:2", unavailable = true))
        assertEquals(1, retries.attempts)
        assertFalse(retries.schedule(5, "config:2", unavailable = false))
        assertEquals(0, retries.attempts)
        assertNull(retries.pending)
        assertTrue(retries.schedule(6, "config:2", unavailable = true))
        assertEquals(1, retries.attempts)
    }
}
