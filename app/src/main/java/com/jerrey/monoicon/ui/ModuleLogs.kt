package com.jerrey.monoicon.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Log source for the "Logs" page (Phase 13).
 *
 * Two layers:
 *  - [append] keeps a small ring buffer of settings-process events (works
 *    without root and is always shown);
 *  - [readSystemLogs] pulls the module's hook logs from logcat through `su`,
 *    because the launcher/SystemUI hooks run in other processes and an app
 *    cannot read logcat without `READ_LOGS`.
 */
object ModuleLogs {

    private const val MAX_ENTRIES = 300

    /** Log tags emitted by the module (hook + settings processes). */
    private val TAGS = listOf(
        "MonoIcon.Hook",
        "MonoIcon.Config",
        "MonoIcon.Shape",
        "MonoIcon.SystemUI",
        "MonoIcon.Mask",
        "MonoIcon.AospMask",
        "MonoIcon.Monet",
        "MonoIcon.MonoCache",
        "MonoIcon.Identity",
        "MonoIcon.Theme",
        "MonoIcon.FolderLifecycle",
        "MonoIcon.Convert",
        "MonoIcon.Color",
    )

    private val buffer = ArrayDeque<String>()
    private var bufferSnapshot: List<String>? = emptyList()
    private val sequence = AtomicInteger(0)
    private val tagArgs = TAGS.joinToString(" ")

    /** Result of a system-log read: whether `su` worked plus the matching lines. */
    data class LogResult(val rootAvailable: Boolean, val lines: List<String>)

    /** Records one settings-process event. */
    fun append(tag: String, message: String) {
        synchronized(buffer) {
            buffer.addLast("[${sequence.incrementAndGet()}] $tag: $message")
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            bufferSnapshot = null
        }
    }

    /** In-process events, oldest first. */
    fun inAppLogs(): List<String> = synchronized(buffer) {
        bufferSnapshot ?: buffer.toList().also { bufferSnapshot = it }
    }

    /**
     * Module logs from logcat, or `rootAvailable = false` when `su` is missing or
     * denied. Reads are bounded by a timeout and destroy `su` on cancellation.
     */
    suspend fun readSystemLogs(lines: Int = 300): LogResult {
        val command = "logcat -d -t $lines -s $tagArgs"
        return try {
            readLogProcess {
                ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            append("Logs", "su logcat failed: ${t.message}")
            LogResult(rootAvailable = false, lines = emptyList())
        }
    }

    /** Owns both the reader and process; no worker survives timeout or page exit. */
    internal suspend fun readLogProcess(
        timeoutMillis: Long = 8_000L,
        startProcess: () -> Process,
    ): LogResult = withTimeoutOrNull(timeoutMillis) {
        withContext(Dispatchers.IO) {
            val process = startProcess()
            try {
                // Drain while waiting so a full stdout/stderr pipe cannot stall
                // the process. Filter during reading instead of copying twice.
                val output = async {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.filter { it.isNotBlank() && it.contains("MonoIcon") }.toList()
                    }
                }
                val exitCode = runInterruptible { process.waitFor() }
                if (exitCode == 0) {
                    LogResult(rootAvailable = true, lines = output.await())
                } else {
                    LogResult(rootAvailable = false, lines = emptyList())
                }
            } finally {
                process.destroy()
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
            }
        }
    } ?: LogResult(rootAvailable = false, lines = emptyList())
}
