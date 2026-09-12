package com.jerrey.monoicon.ui

import java.util.concurrent.TimeUnit
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
    private val sequence = AtomicInteger(0)

    /** Result of a system-log read: whether `su` worked plus the matching lines. */
    data class LogResult(val rootAvailable: Boolean, val lines: List<String>)

    /** Records one settings-process event. */
    fun append(tag: String, message: String) {
        synchronized(buffer) {
            buffer.addLast("[${sequence.incrementAndGet()}] $tag: $message")
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
    }

    /** In-process events, oldest first. */
    fun inAppLogs(): List<String> = synchronized(buffer) { buffer.toList() }

    /**
     * Module logs from logcat, or `rootAvailable = false` when `su` is missing or
     * denied. Runs a blocking `su` call — use from a background dispatcher.
     */
    fun readSystemLogs(lines: Int = 300): LogResult {
        val tagArgs = TAGS.joinToString(" ")
        val command = "logcat -d -t $lines -s $tagArgs"
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readLines() }
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            val exitCode = if (finished) process.exitValue() else -1
            process.destroy()
            if (!finished || exitCode != 0) {
                LogResult(rootAvailable = false, lines = emptyList())
            } else {
                LogResult(
                    rootAvailable = true,
                    lines = output.filter { it.isNotBlank() && it.contains("MonoIcon") },
                )
            }
        } catch (t: Throwable) {
            append("Logs", "su logcat failed: ${t.message}")
            LogResult(rootAvailable = false, lines = emptyList())
        }
    }
}
