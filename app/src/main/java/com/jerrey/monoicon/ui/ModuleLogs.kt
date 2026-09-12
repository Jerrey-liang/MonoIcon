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
     * Module logs from logcat (newest last), or null when root is unavailable.
     * Runs a blocking `su` call — use from a background dispatcher.
     */
    fun readSystemLogs(lines: Int = 300): List<String>? {
        val tagArgs = TAGS.joinToString(" ")
        val command = "logcat -d -t $lines -s $tagArgs"
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readLines() }
            val finished = process.waitFor(6, TimeUnit.SECONDS)
            process.destroy()
            if (!finished && output.isEmpty()) return null
            if (output.size == 1 && output[0].contains("not found")) null else output
        } catch (_: Throwable) {
            null
        }
    }
}
