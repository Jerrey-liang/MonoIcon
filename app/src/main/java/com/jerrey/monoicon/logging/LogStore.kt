package com.jerrey.monoicon.logging

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Central log sink for the process that owns the settings UI (Phase 14).
 *
 * Before this, the "Logs" page read the module's hook output back out of
 * logcat through `su`. That is the wrong direction for a normal app: it needs
 * root, and it shows another process's lines rather than MonoIcon's own. This
 * store is the opposite — every log MonoIcon emits is recorded here as it is
 * produced, so the page needs no permission at all.
 *
 * ## Layers
 * - **Memory**: a bounded ring buffer, so a page that is left and re-entered
 *   (or a recomposition, or an Activity recreation) still finds its history.
 * - **File**: `filesDir/monoicon.log`, appended to as entries arrive and
 *   flushed on a short interval, so history survives process death.
 *
 * The launcher/SystemUI hook processes never call [install] — they get the
 * memory layer only, which is all that is useful to them: their entries could
 * not be surfaced in this process's UI anyway, and writing to another app's
 * private directory is not possible.
 *
 * Nothing here ever throws. Logging is a side channel; a failure to log must
 * never become a failure of the caller.
 */
object LogStore {

    const val LEVEL_DEBUG = "D"
    const val LEVEL_INFO = "I"
    const val LEVEL_WARN = "W"
    const val LEVEL_ERROR = "E"

    /** Entries kept in memory for the log page. */
    const val MAX_ENTRIES = 1000

    /** Entries rendered by the page; the full history is still exported. */
    const val MAX_VISIBLE_ENTRIES = 400

    private const val FILE_NAME = "monoicon.log"
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024
    private const val KEEP_BYTES = 512L * 1024

    /** Entries are buffered until this much time passes, or a warning lands. */
    private const val FLUSH_INTERVAL_MS = 1500L

    /** One recorded line, with the throwable kept structured for the exporter. */
    data class Entry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    /**
     * Bumped on every append. The log page polls this instead of holding a
     * reference to the list, so it can detect changes without the store having
     * to know anything about Compose.
     */
    private val revisions = AtomicLong(0)

    private var logFile: File? = null
    private var writer: BufferedWriter? = null
    private var lastFlushAt = 0L

    @Volatile
    private var installed = false

    /**
     * Whether this process opened the store.
     *
     * Only the settings UI does, through [MonoIconApplication]. The launcher and
     * SystemUI hook processes never call [install], because their entries could
     * not be shown here anyway — which also means they can skip the lock on the
     * per-icon hot path entirely.
     */
    val isInstalled: Boolean get() = installed

    /** `SimpleDateFormat` is not thread-safe; every use is under [lock]. */
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Current revision; changes whenever an entry is appended. */
    fun revision(): Long = revisions.get()

    /**
     * Opens the persistent log under the app's private directory and writes a
     * session header. Safe to call more than once — only the first call opens
     * the file. Call from [com.jerrey.monoicon.MonoIconApplication].
     *
     * @return true when entries are reaching the file, false when only the
     *  memory layer is available. Callers log the outcome; nothing else
     *  depends on it.
     */
    fun install(context: Context): Boolean {
        // Set before anything can fail: the memory ring works regardless, and
        // this is what marks the process as one whose entries are worth keeping.
        installed = true
        try {
            val target = File(context.applicationContext.filesDir, FILE_NAME)
            synchronized(lock) {
                if (writer != null) return true
                trimIfOversized(target)
                val out = BufferedWriter(
                    OutputStreamWriter(FileOutputStream(target, true), Charsets.UTF_8)
                )
                logFile = target
                writer = out
                appendLocked(
                    Entry(
                        System.currentTimeMillis(),
                        LEVEL_INFO,
                        TAG,
                        "── session start (pid ${android.os.Process.myPid()}) ──",
                    )
                )
                flushLocked()
                return true
            }
        } catch (t: Throwable) {
            // Storage unavailable: the memory layer still works, so the page
            // keeps functioning for this process's lifetime.
            android.util.Log.w(TAG, "file log unavailable: ${t.message}")
            return false
        }
    }

    /**
     * Forces buffered entries to disk.
     *
     * Used before the process is torn down — [com.jerrey.monoicon.MonoIconApplication]
     * calls it from the crash handler — because the periodic flush would never
     * get the chance to run.
     */
    fun flushNow() {
        runCatching { synchronized(lock) { flushLocked() } }
    }

    /** Records one entry. Never throws. */
    fun record(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message, throwable)
        try {
            synchronized(lock) { appendLocked(entry) }
        } catch (_: Throwable) {
            // Never let logging break the caller.
        }
    }

    /** In-memory entries, oldest first. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    /**
     * Everything currently available: the persistent file when it exists, and
     * otherwise the in-memory ring. The file is the fuller record — it also
     * holds earlier sessions — so it wins when present.
     */
    fun exportText(): String {
        val (file, memory) = synchronized(lock) { logFile to entries.toList() }
        val body = file?.takeIf { it.isFile && it.length() > 0 }?.let { stored ->
            runCatching { stored.readText() }.getOrNull()
        }
        return buildString {
            appendLine(HEADER)
            appendLine("exported  : ${fileTimeFormat.format(Date())}")
            appendLine("entries   : ${memory.size} in memory")
            appendLine("source    : ${if (body != null) FILE_NAME else "memory buffer"}")
            appendLine(SEPARATOR)
            if (body != null) {
                append(body)
            } else {
                memory.forEach { appendLine(format(it, withStack = true)) }
            }
        }
    }

    /** Timestamped `.txt` name handed to the Storage Access Framework. */
    fun exportFileName(): String =
        "monoicon-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"

    /**
     * One rendered line, used by both the page and the fallback export path.
     *
     * Takes the lock because it reads the shared [SimpleDateFormat], which is
     * not thread-safe: the page renders while the launcher-side hooks may still
     * be appending.
     */
    fun format(entry: Entry, withStack: Boolean = false): String = synchronized(lock) {
        buildString {
            append(timeFormat.format(Date(entry.timestamp)))
            append(' ').append(entry.level)
            append(' ').append(entry.tag)
            append(": ").append(entry.message)
            if (withStack) {
                entry.throwable?.let { append('\n').append(stackTraceOf(it)) }
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────

    /** Called with [lock] held. */
    private fun appendLocked(entry: Entry) {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        revisions.incrementAndGet()

        val out = writer ?: return
        try {
            out.write(fileTimeFormat.format(Date(entry.timestamp)))
            out.write(" ")
            out.write(entry.level)
            out.write(" ")
            out.write(entry.tag)
            out.write(": ")
            out.write(entry.message)
            entry.throwable?.let {
                out.write("\n")
                out.write(stackTraceOf(it))
            }
            out.newLine()
            // Warnings and errors are the lines that matter most after a
            // crash, so they are not left sitting in the buffer.
            val urgent = entry.level == LEVEL_WARN || entry.level == LEVEL_ERROR
            val now = System.currentTimeMillis()
            if (urgent || now - lastFlushAt >= FLUSH_INTERVAL_MS) flushLocked()
        } catch (t: Throwable) {
            // A full or revoked file must not stop in-memory logging.
            writer = null
            runCatching { out.close() }
        }
    }

    /** Called with [lock] held. */
    private fun flushLocked() {
        val out = writer ?: return
        runCatching { out.flush() }
        lastFlushAt = System.currentTimeMillis()
    }

    /**
     * Keeps the file bounded. Runs once, at [install] time, rather than from
     * the append path: trimming means read-then-rewrite, which is not something
     * to do on whichever thread happened to log.
     */
    private fun trimIfOversized(target: File) {
        if (!target.isFile || target.length() <= MAX_FILE_BYTES) return
        runCatching {
            val tail = target.readBytes()
                .let { it.copyOfRange(maxOf(0, it.size - KEEP_BYTES.toInt()), it.size) }
            target.writeBytes(tail)
        }
    }

    /** Pure-JVM stack rendering, so this class stays unit-testable. */
    private fun stackTraceOf(throwable: Throwable): String {
        val buffer = StringWriter()
        PrintWriter(buffer).use { throwable.printStackTrace(it) }
        return buffer.toString().trimEnd()
    }

    private const val TAG = "MonoIcon.Log"
    private const val HEADER = "MonoIcon runtime log"
    private const val SEPARATOR = "────────────────────────────────────────"
}
