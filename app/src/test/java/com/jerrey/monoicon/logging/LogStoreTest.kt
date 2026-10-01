package com.jerrey.monoicon.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogStore] without [LogStore.install]: this is the launcher/SystemUI
 * configuration, where only the in-memory ring exists. The file layer needs a
 * real Context and is exercised by the app, not here.
 */
class LogStoreTest {

    @Test
    fun recordKeepsLevelTagMessageAndThrowable() {
        val failure = IllegalStateException("boom")
        LogStore.record(LogStore.LEVEL_ERROR, "Test.Tag", "something broke", failure)

        val entry = LogStore.snapshot().last()
        assertEquals(LogStore.LEVEL_ERROR, entry.level)
        assertEquals("Test.Tag", entry.tag)
        assertEquals("something broke", entry.message)
        assertEquals(failure, entry.throwable)
    }

    @Test
    fun revisionAdvancesOnEveryAppend() {
        val before = LogStore.revision()
        LogStore.record(LogStore.LEVEL_INFO, "Test.Tag", "one")
        val afterFirst = LogStore.revision()
        LogStore.record(LogStore.LEVEL_INFO, "Test.Tag", "two")

        assertTrue("revision must move forward", afterFirst > before)
        assertTrue(LogStore.revision() > afterFirst)
    }

    @Test
    fun snapshotIsOldestFirst() {
        LogStore.record(LogStore.LEVEL_INFO, "Test.Order", "first")
        LogStore.record(LogStore.LEVEL_INFO, "Test.Order", "second")

        val ordered = LogStore.snapshot().filter { it.tag == "Test.Order" }
        assertEquals(listOf("first", "second"), ordered.map { it.message })
    }

    @Test
    fun memoryRingIsBoundedAndKeepsTheNewestEntries() {
        repeat(LogStore.MAX_ENTRIES + 50) {
            LogStore.record(LogStore.LEVEL_DEBUG, "Test.Ring", "entry-$it")
        }

        val ring = LogStore.snapshot()
        assertEquals(LogStore.MAX_ENTRIES, ring.size)
        assertEquals("entry-${LogStore.MAX_ENTRIES + 49}", ring.last().message)
    }

    @Test
    fun formatIncludesTimeLevelTagAndMessage() {
        LogStore.record(LogStore.LEVEL_WARN, "Test.Format", "watch out")

        val line = LogStore.format(LogStore.snapshot().last())
        assertTrue(line.contains(" W "))
        assertTrue(line.contains("Test.Format: watch out"))
        // HH:mm:ss.SSS prefix
        assertTrue(Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} ").containsMatchIn(line))
    }

    @Test
    fun formatAppendsStackOnlyWhenRequested() {
        val failure = RuntimeException("with stack")
        LogStore.record(LogStore.LEVEL_ERROR, "Test.Stack", "failed", failure)
        val entry = LogStore.snapshot().last()

        assertFalse(LogStore.format(entry).contains("RuntimeException: with stack"))
        assertTrue(LogStore.format(entry, withStack = true).contains("RuntimeException: with stack"))
    }

    @Test
    fun exportTextFallsBackToMemoryWhenNoFileIsOpen() {
        LogStore.record(LogStore.LEVEL_INFO, "Test.Export", "exported line")

        val text = LogStore.exportText()
        assertTrue(text.contains("Test.Export: exported line"))
        assertTrue(text.contains("memory buffer"))
    }

    @Test
    fun exportFileNameIsATimestampedTxt() {
        val name = LogStore.exportFileName()
        assertTrue(name.startsWith("monoicon-"))
        assertTrue(name.endsWith(".txt"))
        assertNotNull(Regex("monoicon-\\d{8}-\\d{6}\\.txt").find(name))
    }
}
