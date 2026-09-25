package com.jerrey.monoicon.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleLogsTest {

    @Test(timeout = 10_000)
    fun successfulProcessFiltersLogsAndKeepsTheirOrder() = runBlocking {
        val process = FakeProcess(
            TrackingInputStream("noise\n\nI MonoIcon.First\n   \nother tag\nW MonoIcon.Second\n"),
        )

        val result = ModuleLogs.readLogProcess { process }

        assertTrue(result.rootAvailable)
        assertEquals(listOf("I MonoIcon.First", "W MonoIcon.Second"), result.lines)
        assertReleased(process)
    }

    @Test(timeout = 10_000)
    fun nonzeroExitDiscardsOutputAndReleasesProcess() = runBlocking {
        val process = FakeProcess(TrackingInputStream("MonoIcon rejected\n"), exitCode = 1)

        val result = ModuleLogs.readLogProcess { process }

        assertFalse(result.rootAvailable)
        assertTrue(result.lines.isEmpty())
        assertReleased(process)
    }

    @Test(timeout = 10_000)
    fun timeoutDestroysProcessAndClosesBlockedReader() = runBlocking {
        val input = BlockingInputStream()
        val process = FakeProcess(input, waitForDestroy = true)
        val read = async(Dispatchers.Default) {
            ModuleLogs.readLogProcess(timeoutMillis = 500) { process }
        }
        try {
            assertLatch("stdout reader started", input.readStarted)
            assertLatch("process waiter started", process.waitStarted)

            val result = withTimeout(3_000) { read.await() }

            assertFalse(result.rootAvailable)
            assertTrue(result.lines.isEmpty())
            assertReleased(process)
            assertLatch("blocked stdout reader finished", input.readFinished)
        } finally {
            // Also release the fake when an assertion fails, so a regression
            // cannot leave a blocking IO worker behind in the test process.
            input.close()
            process.destroy()
            read.cancel()
        }
    }

    @Test(timeout = 10_000)
    fun parentCancellationPropagatesAndReleasesBlockedReader() = runBlocking {
        val input = BlockingInputStream()
        val process = FakeProcess(input, waitForDestroy = true)
        val returnedNormally = AtomicBoolean(false)
        val cancellationObserved = CountDownLatch(1)
        val parent = launch(Dispatchers.Default) {
            try {
                ModuleLogs.readLogProcess { process }
                returnedNormally.set(true)
            } catch (cancelled: CancellationException) {
                cancellationObserved.countDown()
                throw cancelled
            }
        }
        try {
            assertLatch("stdout reader started", input.readStarted)
            assertLatch("process waiter started", process.waitStarted)

            parent.cancel()
            withTimeout(3_000) { parent.join() }

            assertTrue(parent.isCancelled)
            assertFalse("readLogProcess must not swallow cancellation", returnedNormally.get())
            assertLatch("caller observed cancellation", cancellationObserved)
            assertReleased(process)
            assertLatch("blocked stdout reader finished", input.readFinished)
        } finally {
            input.close()
            process.destroy()
            parent.cancel()
        }
    }

    @Test
    fun inAppLogsReusesSnapshotUntilAppendAndPreservesOlderSnapshots() {
        val before = ModuleLogs.inAppLogs()
        val originalEntries = before.toList()
        assertSame(before, ModuleLogs.inAppLogs())

        ModuleLogs.append("ModuleLogsTest", "new revision")
        val after = ModuleLogs.inAppLogs()

        assertNotSame(before, after)
        assertSame(after, ModuleLogs.inAppLogs())
        assertEquals(originalEntries, before)
        assertTrue(after.last().endsWith("ModuleLogsTest: new revision"))
    }

    @Test
    fun inAppLogsKeepsOnlyLatestThreeHundredEntriesInOrder() {
        val before = ModuleLogs.inAppLogs()
        val originalEntries = before.toList()
        repeat(325) { ModuleLogs.append("ModuleLogsTest.Buffer", "entry-$it") }

        val snapshot = ModuleLogs.inAppLogs()

        assertEquals(300, snapshot.size)
        assertEquals(
            (25 until 325).map { "entry-$it" },
            snapshot.map { it.substringAfter("ModuleLogsTest.Buffer: ") },
        )
        assertEquals(originalEntries, before)
        assertSame(snapshot, ModuleLogs.inAppLogs())
    }

    private fun assertReleased(process: FakeProcess) {
        assertTrue("process destroyed", process.destroyCalls.get() > 0)
        assertTrue("stdout closed", process.stdout.closed.get())
        assertTrue("stderr closed", process.stderr.closed.get())
        assertTrue("stdin closed", process.stdin.closed.get())
    }

    private fun assertLatch(message: String, latch: CountDownLatch) {
        assertTrue(message, latch.await(2, TimeUnit.SECONDS))
    }

    private open class TrackingInputStream(text: String = "") :
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) {
        val closed = AtomicBoolean(false)

        override fun close() {
            closed.set(true)
            super.close()
        }
    }

    private class BlockingInputStream : TrackingInputStream() {
        val readStarted = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        private val closeSignal = CountDownLatch(1)

        override fun read(): Int {
            readStarted.countDown()
            try {
                while (!closed.get()) {
                    try {
                        closeSignal.await()
                    } catch (_: InterruptedException) {
                        // A normal stream read is not coroutine-cancellable:
                        // only the owner's close operation releases this fake.
                    }
                }
                return -1
            } finally {
                readFinished.countDown()
            }
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            if (length == 0) 0 else read()

        override fun close() {
            super.close()
            closeSignal.countDown()
        }
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        val closed = AtomicBoolean(false)

        override fun close() {
            closed.set(true)
            super.close()
        }
    }

    private class FakeProcess(
        val stdout: TrackingInputStream,
        private val exitCode: Int = 0,
        waitForDestroy: Boolean = false,
    ) : Process() {
        val stderr = TrackingInputStream()
        val stdin = TrackingOutputStream()
        val waitStarted = CountDownLatch(1)
        val destroyCalls = AtomicInteger(0)
        private val exitSignal = CountDownLatch(if (waitForDestroy) 1 else 0)

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun getOutputStream() = stdin

        override fun waitFor(): Int {
            waitStarted.countDown()
            exitSignal.await()
            return exitCode
        }

        override fun exitValue(): Int {
            if (exitSignal.count != 0L) throw IllegalThreadStateException("still running")
            return exitCode
        }

        override fun destroy() {
            destroyCalls.incrementAndGet()
            exitSignal.countDown()
        }
    }
}
